package com.vinayaka.finance.tools

import com.sun.net.httpserver.HttpServer
import com.vinayaka.finance.BuildConfig
import com.vinayaka.finance.data.DEFAULT_ADMIN_EMAIL
import com.vinayaka.finance.data.sheets.FakeSheetGateway
import com.vinayaka.finance.data.sheets.GoogleSheetsGateway
import com.vinayaka.finance.data.sync.SheetGateway
import com.vinayaka.finance.importer.ImportMapper
import com.vinayaka.finance.importer.XlsxReader
import com.vinayaka.finance.util.formatRupees
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * NOT a normal test: it only runs when the WORKBOOK environment variable is set, and is driven by the `import-workbook` skill.
 * It reads the committee's Excel workbook with the app's own importer and puts the rows into the Google Sheet (or, in dry mode, into
 * an in-memory fake). Output goes to import-out/ (git-ignored). The summary holds only counts and totals, never names or numbers.
 *
 * Environment: WORKBOOK (path of the .xlsx, required), IMPORT_MODE = dry (default) | push | csv (writes CSVs for the app's Import screen), IMPORT_YEAR (default current year),
 * UNDATED_DATE (date for rows with no date; default the earliest dated row, else Sept 14 of IMPORT_YEAR), WHO (Created by text),
 * OUT_DIR, OAUTH_PORT (default 8765), GOOGLE_LOGIN_HINT. For push, git-ignored secrets.properties must hold
 * DESKTOP_CLIENT_ID and DESKTOP_CLIENT_SECRET (a Google Cloud OAuth client of type "Desktop app").
 */
class ImportWorkbookTool {
    private fun env(name: String) = System.getenv(name)?.trim().orEmpty()

    @Test fun importWorkbook() {
        val workbook = env("WORKBOOK")
        assumeTrue("Set WORKBOOK to run the importer tool", workbook.isNotEmpty())
        val mode = env("IMPORT_MODE").ifEmpty { "dry" }
        val year = env("IMPORT_YEAR").ifEmpty { java.time.Year.now().value.toString() }
        val out = File(env("OUT_DIR").ifEmpty { "../import-out" }).apply { mkdirs() }
        val who = env("WHO").ifEmpty { "Workbook import" }

        val sheets = File(workbook).inputStream().use { XlsxReader { KXmlParser() }.read(it) }
        val bundle = ImportMapper.parse(sheets)
        check(!bundle.isEmpty) { "No donations, expenditure or contacts were recognised in this workbook." }
        val undated = env("UNDATED_DATE").ifEmpty { bundle.earliestDate ?: "$year-09-14" }

        val summary = StringBuilder()
        fun line(s: String = "") { summary.appendLine(s); println(s) }
        line("Workbook: ${sheets.size} sheet(s) read, mode = $mode")
        bundle.sections.forEach { line("  ${it.label}: ${it.rows} rows${if (it.total != 0L) ", total ${formatRupees(it.total)}" else ""}") }
        line("Ignored sheets: ${bundle.ignoredSheets.joinToString().ifEmpty { "none" }}")
        line("Skipped rows (no valid amount/date): ${bundle.skippedRows}")
        line("Rows without a date: ${bundle.undated}, given the date $undated")

        val signIn = lazy { token(out) }   // one browser sign-in for the whole run
        // Date check: for every sheet with a Date column, how are the cells stored? Shapes are masked (9 = digit, a = letter) so no content is shown.
        line()
        line("Date check (per sheet with a Date column):")
        for (s in sheets) {
            val h = s.rows.take(5).indexOfFirst { r -> r.any { it.trim().equals("date", true) } }
            if (h < 0) continue
            val c = s.rows[h].indexOfFirst { it.trim().equals("date", true) }
            var empty = 0; var serial = 0; var text = 0
            val odd = sortedMapOf<String, Int>()
            for (r in s.rows.drop(h + 1)) {
                val t = r.getOrNull(c)?.trim().orEmpty()
                when {
                    t.isEmpty() -> empty++
                    t.toDoubleOrNull()?.let { it in 20000.0..80000.0 } == true -> serial++
                    com.vinayaka.finance.util.parseDateFlexible(t) != null -> text++
                    else -> odd.merge(t.map { if (it.isDigit()) '9' else if (it.isLetter()) 'a' else it }.joinToString(""), 1, Int::plus)
                }
            }
            line("  ${s.name}: ${serial} real dates, ${text} date text, ${empty} empty, ${odd.values.sum()} not dates")
            odd.forEach { (shape, n) -> line("      not a date, shape \"$shape\": $n") }
        }

        if (mode == "csv") {
            writeCsvFiles(bundle, undated, year, out, ::line)
            File(out, "summary.txt").writeText(summary.toString())
            return
        }

        val gateway: SheetGateway = if (mode == "push") GoogleSheetsGateway(BuildConfig.SPREADSHEET_ID, accessToken = { signIn.value }) else FakeSheetGateway()
        if (mode == "push") check(BuildConfig.SPREADSHEET_ID.isNotEmpty()) { "This build has no SPREADSHEET_URL. Set it in secrets.properties." }

        runBlocking {
            val result = WorkbookPipeline.run(bundle, undated, who, gateway)
            line()
            line("New rows ${if (mode == "push") "written to the Google Sheet" else "(dry run, nothing written)"}: " +
                "${result.plan.entries.size} entries, ${result.plan.contacts.size} contacts")
            line("Already there and skipped: ${result.plan.duplicateEntries} entries, ${result.plan.duplicateContacts} contacts")
            line("Sync: sent ${result.report.pushed}, received ${result.report.pulled}, rows ignored in Sheet ${result.report.skippedRows}")
            line()
            line("What the ${if (mode == "push") "Sheet" else "fake sheet"} holds now:")
            for (y in gateway.listYears()) {
                val live = gateway.readYear(y).rows.map { it.record }.filter { !it.isDeleted }
                line("  Tab $y: ${live.size} rows")
                live.groupBy { it.type.sheetName + (if (it.category.isNotEmpty()) " / ${it.category}" else "") }.toSortedMap().forEach { (k, v) ->
                    line("    $k: ${v.size} rows, total ${formatRupees(v.sumOf { it.amount })}")
                }
                val pending = live.count { it.isPending }
                if (pending > 0) line("    (not collected yet: $pending)")
            }
            val contacts = gateway.readContacts().rows.map { it.record }.filter { !it.isDeleted }
            line("  Contacts: ${contacts.size} rows (${contacts.map { it.year }.distinct().sorted().joinToString()}), called ${contacts.count { it.called }}")
        }
        File(out, "summary.txt").writeText(summary.toString())
    }

    private fun rupees(paise: Long): String = "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

    /**
     * IMPORT_MODE=csv: writes two files the app's own Import screen reads (no Google involved): `Ledger <year>.csv` (the same columns the app
     * exports: Date, Type, Category, Name, Description, Amount, Collected / Paid by; every row has a date, undated rows get [undated]) and
     * `Contacts.csv`. The file name is the sheet name the importer sees, so keep the names. Each file is then read back through the app's
     * own CSV reader and mapper and must give the same counts and totals as the workbook.
     */
    private fun writeCsvFiles(bundle: com.vinayaka.finance.importer.ImportBundle, undated: String, year: String, out: File, line: (String) -> Unit) {
        val ledger = com.vinayaka.finance.importer.Csv.write(
            listOf(listOf("Date", "Type", "Category", "Name", "Description", "Amount", "Collected / Paid by")) +
                bundle.entries.map { listOf(it.date ?: undated, it.type.sheetName, it.category, it.name, it.description, rupees(it.amount), it.person) },
        )
        val contacts = com.vinayaka.finance.importer.Csv.write(
            listOf(listOf("Name", "Number", "Who calls", "Called", "Remarks")) +
                bundle.contacts.map { listOf(it.name, it.number, it.caller, if (it.called) "Yes" else "", it.remarks) },
        )
        val bom = '﻿'
        val ledgerFile = File(out, "Ledger $year.csv").apply { writeText("$bom$ledger") }
        val contactsFile = File(out, "Contacts.csv").apply { writeText("$bom$contacts") }

        fun readBack(f: File) = com.vinayaka.finance.importer.ImportMapper.parse(
            listOf(com.vinayaka.finance.importer.SheetData(f.nameWithoutExtension, com.vinayaka.finance.importer.Csv.parse(f.readText()))),
        )
        val l = readBack(ledgerFile)
        val c = readBack(contactsFile)
        check(l.entries.size == bundle.entries.size && l.entries.sumOf { it.amount } == bundle.entries.sumOf { it.amount }) {
            "Ledger CSV does not read back to the same rows and total."
        }
        check(l.entries.groupingBy { it.type.name + "/" + it.category }.eachCount() == bundle.entries.groupingBy { it.type.name + "/" + it.category }.eachCount()) {
            "Ledger CSV categories do not read back the same."
        }
        check(l.undated == 0 && l.skippedRows == 0) { "Ledger CSV has rows without a date or with an unreadable amount." }
        check(c.contacts.size == bundle.contacts.size && c.contacts.count { it.called } == bundle.contacts.count { it.called }) {
            "Contacts CSV does not read back the same."
        }
        line("")
        line("CSV files for the app's Import screen (read back through the app's own reader and checked):")
        line("  ${ledgerFile.name}: ${l.entries.size} rows, total ${formatRupees(l.entries.sumOf { it.amount })}, all dated (undated rows use $undated)")
        line("  ${contactsFile.name}: ${c.contacts.size} contacts, ${c.contacts.count { it.called }} marked called")
    }

    /** Google sign-in for a command-line tool: opens a local page the browser redirects to (loopback flow with PKCE). */
    private fun token(out: File): String {
        val secrets = Properties().apply {
            listOf("../secrets.properties", "secrets.properties").map(::File).firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun setting(key: String) = env(key).ifEmpty { secrets.getProperty(key, "").trim() }
        // Either a Desktop-app client (needs no redirect setup), or the app's existing Web client when its secret is given and
        // http://localhost:<port> is listed under its authorized redirect URIs. These keys are read by this tool only, never built into the APK.
        val (clientId, clientSecret) = listOf(
            setting("DESKTOP_CLIENT_ID") to setting("DESKTOP_CLIENT_SECRET"),
            setting("GOOGLE_WEB_CLIENT_ID") to setting("GOOGLE_WEB_CLIENT_SECRET"),
        ).firstOrNull { (id, secret) -> id.isNotEmpty() && secret.isNotEmpty() }
            ?: error("Add DESKTOP_CLIENT_ID + DESKTOP_CLIENT_SECRET (Desktop app client), or GOOGLE_WEB_CLIENT_SECRET (for the existing Web client) to android/secrets.properties.")
        val port = env("OAUTH_PORT").ifEmpty { "8765" }.toInt()
        val redirect = "http://localhost:$port"
        val rnd = SecureRandom()
        fun random(n: Int) = ByteArray(n).also(rnd::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val verifier = random(48)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = random(16)
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        val reply = CompletableFuture<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/") { ex ->
            val q = (ex.requestURI.rawQuery ?: "").split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            val done = q.containsKey("code") || q.containsKey("error")
            val body = (if (done) "<h3>Signed in. You can close this tab and go back to Claude.</h3>" else "").toByteArray()
            ex.sendResponseHeaders(if (done) 200 else 404, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
            if (done) reply.complete(q)
        }
        server.start()
        val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=${enc(clientId)}&redirect_uri=${enc(redirect)}&response_type=code" +
            "&scope=${enc("https://www.googleapis.com/auth/spreadsheets")}&code_challenge=$challenge&code_challenge_method=S256&state=$state" +
            "&login_hint=${enc(env("GOOGLE_LOGIN_HINT").ifEmpty { DEFAULT_ADMIN_EMAIL })}"
        File(out, "auth-url.txt").writeText(url)
        println("OPEN THIS URL IN YOUR BROWSER AND APPROVE:\n$url")
        val q = try { reply.get(10, TimeUnit.MINUTES) } finally { server.stop(0) }
        check(q["state"] == state) { "Sign-in response did not match this request." }
        val code = q["code"] ?: error("Google sign-in failed: ${q["error"]}")
        val response = OkHttpClient().newCall(
            Request.Builder().url("https://oauth2.googleapis.com/token").post(
                FormBody.Builder().add("code", code).add("client_id", clientId).add("client_secret", clientSecret)
                    .add("redirect_uri", redirect).add("grant_type", "authorization_code").add("code_verifier", verifier).build(),
            ).build(),
        ).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "Google refused the sign-in code (HTTP ${r.code}): $text" }
            Json.parseToJsonElement(text).jsonObject
        }
        File(out, "auth-url.txt").delete()
        return response["access_token"]!!.jsonPrimitive.content
    }
}
