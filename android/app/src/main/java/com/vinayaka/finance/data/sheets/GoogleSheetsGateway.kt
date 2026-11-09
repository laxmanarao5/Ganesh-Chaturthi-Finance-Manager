package com.vinayaka.finance.data.sheets

import com.vinayaka.finance.data.Access
import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.RecordRead
import com.vinayaka.finance.data.model.RemoteRow
import com.vinayaka.finance.data.model.Role
import com.vinayaka.finance.data.model.RowWrite
import com.vinayaka.finance.data.sync.SheetGateway
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.net.URLEncoder

class SheetsApiException(val code: Int, message: String) : Exception(message) {
    /** The signed-in account can see the sheet but may not change it. */
    val isForbidden get() = code == 403
    val isNotFound get() = code == 404
}

/** How a tab looks: header, which columns hold dates/amounts/checkboxes, and where the grey system columns start. */
private class TabSpec(
    val headers: List<String>,
    val deletedColumn: String,
    val systemStart: Int,
    val dateColumn: Int?,
    val amountColumn: Int?,
    val checkboxColumn: Int?,
    val widths: List<Int>,
) {
    val columns get() = headers.size
}

private val ENTRY_SPEC = TabSpec(EntryRows.HEADERS, EntryRows.DELETED_COLUMN, 8, 0, 5, null, listOf(100, 100, 120, 200, 220, 90, 130, 130))
private val CONTACT_SPEC = TabSpec(ContactRows.HEADERS, ContactRows.DELETED_COLUMN, 6, null, null, 4, listOf(200, 120, 110, 60, 70, 240))

/**
 * Talks to one Google Spreadsheet through the Sheets v4 REST API (free; limits are generous per user).
 * Layout: one ledger tab per year ("2026"), one "Contacts" tab, and an optional "Members" tab.
 */
class GoogleSheetsGateway(
    spreadsheetId: String,
    accessToken: suspend (forceRefresh: Boolean) -> String,
    client: OkHttpClient = OkHttpClient(),
) : SheetGateway {
    private val base = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId"
    private val http = GoogleHttp(accessToken, client)
    private var tabIds: MutableMap<String, Int>? = null
    private val formattedTabs = mutableSetOf<String>()

    // ---------------------------------------------------------------- http
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun call(method: String, url: String, body: JsonElement? = null): JsonObject = http.call(method, url, body)

    // ---------------------------------------------------------------- tabs
    private suspend fun loadTabs(): MutableMap<String, Int> {
        val res = call("GET", "$base?fields=sheets.properties(sheetId,title)")
        val map = mutableMapOf<String, Int>()
        res["sheets"]?.jsonArray?.forEach {
            val p = it.jsonObject["properties"]!!.jsonObject
            map[p["title"]!!.jsonPrimitive.content] = p["sheetId"]!!.jsonPrimitive.content.toInt()
        }
        tabIds = map
        return map
    }

    private suspend fun tabs() = tabIds ?: loadTabs()

    override suspend fun listYears(): List<Int> =
        loadTabs().keys.filter { it.matches(Regex("""\d{4}""")) }.map { it.toInt() }.sorted()

    // ---------------------------------------------------------------- read
    private fun JsonElement.toCell(): Any? = (this as? JsonPrimitive)?.let {
        if (it is JsonNull) null else if (it.isString) it.content else it.booleanOrNull ?: it.doubleOrNull
    }

    private suspend fun readValues(range: String): List<List<Any?>> {
        val url = "$base/values/${enc(range)}?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER"
        val res = call("GET", url)
        return res["values"]?.jsonArray?.map { row -> row.jsonArray.map { it.toCell() } } ?: emptyList()
    }

    private suspend fun <T> readRecords(
        title: String, lastColumn: String,
        parse: (cells: List<Any?>, rowNumber: Int) -> RemoteRow<T>?,
    ): RecordRead<T> {
        if (title !in tabs()) return RecordRead(emptyList())
        val out = mutableListOf<RemoteRow<T>>()
        var skipped = 0
        readValues("'$title'!A2:$lastColumn").forEachIndexed { i, cells ->
            if (cells.all { it == null || it.toString().isBlank() }) return@forEachIndexed
            val parsed = parse(cells, i + 2)
            if (parsed == null) skipped++ else out += parsed
        }
        return RecordRead(out, skipped)
    }

    override suspend fun readYear(year: Int): RecordRead<Entry> =
        readRecords("$year", EntryRows.LAST_COLUMN) { cells, row -> EntryRows.fromCells(cells, row, year) }

    override suspend fun readContacts(): RecordRead<Contact> =
        readRecords(CONTACTS_TAB, ContactRows.LAST_COLUMN) { cells, row -> ContactRows.fromCells(cells, row) }

    // ---------------------------------------------------------------- write
    private fun cellsJson(cells: List<Any>): JsonArray = buildJsonArray {
        cells.forEach { c ->
            when (c) {
                is Boolean -> add(JsonPrimitive(c))
                is Number -> add(JsonPrimitive(c))
                else -> add(JsonPrimitive(c.toString()))
            }
        }
    }

    private suspend fun writeRecords(
        title: String, spec: TabSpec, lastColumn: String,
        updates: List<Pair<Int, List<Any>>>, appends: List<List<Any>>,
    ) {
        if (updates.isEmpty() && appends.isEmpty()) return
        val existed = title in tabs()
        ensureTab(title, spec)
        // A tab that was made by hand (or by an older version) has no date/number formats: without them dates show as serial numbers.
        if (existed && formattedTabs.add(title)) fixNumberFormats(title, spec)
        if (updates.isNotEmpty()) {
            call("POST", "$base/values:batchUpdate", buildJsonObject {
                put("valueInputOption", "RAW")
                putJsonArray("data") {
                    updates.forEach { (row, cells) ->
                        add(buildJsonObject {
                            put("range", "'$title'!A$row:$lastColumn$row")
                            putJsonArray("values") { add(cellsJson(cells)) }
                        })
                    }
                }
            })
        }
        if (appends.isNotEmpty()) {
            call("POST", "$base/values/${enc("'$title'!A1")}:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", buildJsonObject {
                putJsonArray("values") { appends.forEach { add(cellsJson(it)) } }
            })
        }
    }

    override suspend fun writeYear(year: Int, updates: List<RowWrite<Entry>>, appends: List<Entry>) =
        writeRecords("$year", ENTRY_SPEC, EntryRows.LAST_COLUMN,
            updates.map { it.rowNumber to EntryRows.toCells(it.record) }, appends.map { EntryRows.toCells(it) })

    override suspend fun writeContacts(updates: List<RowWrite<Contact>>, appends: List<Contact>) =
        writeRecords(CONTACTS_TAB, CONTACT_SPEC, ContactRows.LAST_COLUMN,
            updates.map { it.rowNumber to ContactRows.toCells(it.record) }, appends.map { ContactRows.toCells(it) })

    private suspend fun addTab(title: String): Int {
        val res = call("POST", "$base:batchUpdate", buildJsonObject {
            putJsonArray("requests") {
                add(buildJsonObject {
                    putJsonObject("addSheet") {
                        putJsonObject("properties") {
                            put("title", title)
                            putJsonObject("gridProperties") { put("frozenRowCount", 1) }
                        }
                    }
                })
            }
        })
        val id = res["replies"]!!.jsonArray[0].jsonObject["addSheet"]!!.jsonObject["properties"]!!.jsonObject["sheetId"]!!.jsonPrimitive.content.toInt()
        tabs()[title] = id
        return id
    }

    /** Makes the Date and Amount columns of an existing tab show as dates and numbers (idempotent, one call per tab per session). */
    private suspend fun fixNumberFormats(title: String, spec: TabSpec) {
        val id = tabs()[title] ?: return
        val requests = buildList {
            spec.dateColumn?.let { c ->
                add(repeatCell(id, 1, null, c, c + 1,
                    buildJsonObject { putJsonObject("numberFormat") { put("type", "DATE"); put("pattern", "yyyy-mm-dd") } },
                    "userEnteredFormat.numberFormat"))
            }
            spec.amountColumn?.let { c ->
                add(repeatCell(id, 1, null, c, c + 1,
                    buildJsonObject { putJsonObject("numberFormat") { put("type", "NUMBER"); put("pattern", "#,##0.##") } },
                    "userEnteredFormat.numberFormat"))
            }
        }
        if (requests.isEmpty()) return
        call("POST", "$base:batchUpdate", buildJsonObject { putJsonArray("requests") { requests.forEach { add(it) } } })
    }

    private suspend fun ensureTab(title: String, spec: TabSpec) {
        if (title in tabs()) return
        val id = addTab(title)
        call("POST", "$base/values/${enc("'$title'!A1")}:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", buildJsonObject {
            putJsonArray("values") { add(buildJsonArray { spec.headers.forEach { add(JsonPrimitive(it)) } }) }
        })
        call("POST", "$base:batchUpdate", buildJsonObject { putJsonArray("requests") { formatRequests(id, spec).forEach { add(it) } } })
    }

    private fun range(sheetId: Int, r0: Int, r1: Int?, c0: Int, c1: Int) = buildJsonObject {
        put("sheetId", sheetId)
        put("startRowIndex", r0)
        if (r1 != null) put("endRowIndex", r1)
        put("startColumnIndex", c0)
        put("endColumnIndex", c1)
    }

    private fun repeatCell(sheetId: Int, r0: Int, r1: Int?, c0: Int, c1: Int, format: JsonObject, fields: String) = buildJsonObject {
        putJsonObject("repeatCell") {
            put("range", range(sheetId, r0, r1, c0, c1))
            putJsonObject("cell") { put("userEnteredFormat", format) }
            put("fields", fields)
        }
    }

    private fun color(r: Double, g: Double, b: Double) = buildJsonObject { put("red", r); put("green", g); put("blue", b) }

    private fun formatRequests(sheetId: Int, spec: TabSpec): List<JsonObject> = buildList {
        add(repeatCell(
            sheetId, 0, 1, 0, spec.columns,
            buildJsonObject {
                put("backgroundColor", color(1.0, 0.86, 0.68))
                putJsonObject("textFormat") { put("bold", true) }
            },
            "userEnteredFormat(backgroundColor,textFormat)",
        ))
        spec.dateColumn?.let { c ->
            add(repeatCell(sheetId, 1, null, c, c + 1,
                buildJsonObject { putJsonObject("numberFormat") { put("type", "DATE"); put("pattern", "yyyy-mm-dd") } },
                "userEnteredFormat.numberFormat"))
        }
        spec.amountColumn?.let { c ->
            add(repeatCell(sheetId, 1, null, c, c + 1,
                buildJsonObject { putJsonObject("numberFormat") { put("type", "NUMBER"); put("pattern", "#,##0.##") } },
                "userEnteredFormat.numberFormat"))
        }
        spec.checkboxColumn?.let { c ->
            add(buildJsonObject {
                putJsonObject("setDataValidation") {
                    put("range", range(sheetId, 1, 2000, c, c + 1))
                    putJsonObject("rule") {
                        putJsonObject("condition") { put("type", "BOOLEAN") }
                        put("strict", true)
                    }
                }
            })
        }
        add(repeatCell(sheetId, 1, null, spec.systemStart, spec.columns,
            buildJsonObject { putJsonObject("textFormat") { put("foregroundColor", color(0.55, 0.55, 0.55)) } },
            "userEnteredFormat.textFormat"))
        add(buildJsonObject { putJsonObject("setBasicFilter") { putJsonObject("filter") { put("range", range(sheetId, 0, null, 0, spec.columns)) } } })
        add(buildJsonObject {
            putJsonObject("addDimensionGroup") {
                putJsonObject("range") {
                    put("sheetId", sheetId); put("dimension", "COLUMNS"); put("startIndex", spec.systemStart); put("endIndex", spec.columns)
                }
            }
        })
        // deleted rows: grey + strikethrough
        add(buildJsonObject {
            putJsonObject("addConditionalFormatRule") {
                putJsonObject("rule") {
                    putJsonArray("ranges") { add(range(sheetId, 1, 2000, 0, spec.columns)) }
                    putJsonObject("booleanRule") {
                        putJsonObject("condition") {
                            put("type", "CUSTOM_FORMULA")
                            putJsonArray("values") { add(buildJsonObject { put("userEnteredValue", "=\$${spec.deletedColumn}2<>\"\"") }) }
                        }
                        putJsonObject("format") {
                            putJsonObject("textFormat") { put("strikethrough", true); put("foregroundColor", color(0.6, 0.6, 0.6)) }
                        }
                    }
                }
                put("index", 0)
            }
        })
        spec.widths.forEachIndexed { i, px ->
            add(buildJsonObject {
                putJsonObject("updateDimensionProperties") {
                    putJsonObject("range") { put("sheetId", sheetId); put("dimension", "COLUMNS"); put("startIndex", i); put("endIndex", i + 1) }
                    putJsonObject("properties") { put("pixelSize", px) }
                    put("fields", "pixelSize")
                }
            })
        }
    }

    // ---------------------------------------------------------------- users
    /** Rows are `Name | Email | Role`. A row counts only if it has an email, so the header row is skipped naturally. */
    override suspend fun readUsers(): List<AppUser>? {
        if (USERS_TAB !in loadTabs()) return null
        return readValues("$USERS_TAB!A1:C").mapNotNull { r ->
            val email = r.getOrNull(1)?.toString()?.trim()?.lowercase().orEmpty()
            if (!email.contains("@")) return@mapNotNull null
            AppUser(r.getOrNull(0)?.toString()?.trim().orEmpty(), email, Access.parseRole(r.getOrNull(2)?.toString()))
        }
    }

    private suspend fun ensureUsersTab() {
        if (USERS_TAB in tabs()) return
        val id = addTab(USERS_TAB)
        call("POST", "$base/values/${enc("$USERS_TAB!A1")}:append?valueInputOption=RAW", buildJsonObject {
            putJsonArray("values") { add(buildJsonArray { listOf("Name", "Email", "Role").forEach { add(JsonPrimitive(it)) } }) }
        })
        call("POST", "$base:batchUpdate", buildJsonObject {
            putJsonArray("requests") {
                add(repeatCell(
                    id, 0, 1, 0, 3,
                    buildJsonObject {
                        put("backgroundColor", color(1.0, 0.86, 0.68))
                        putJsonObject("textFormat") { put("bold", true) }
                    },
                    "userEnteredFormat(backgroundColor,textFormat)",
                ))
                // A dropdown so a hand-typed role is always Viewer, Editor or Admin.
                add(buildJsonObject {
                    putJsonObject("setDataValidation") {
                        put("range", range(id, 1, 500, 2, 3))
                        putJsonObject("rule") {
                            putJsonObject("condition") {
                                put("type", "ONE_OF_LIST")
                                putJsonArray("values") { Role.entries.forEach { r -> add(buildJsonObject { put("userEnteredValue", r.label) }) } }
                            }
                            put("showCustomUi", true)
                            put("strict", true)
                        }
                    }
                })
                listOf(200, 280, 100).forEachIndexed { i, px ->
                    add(buildJsonObject {
                        putJsonObject("updateDimensionProperties") {
                            putJsonObject("range") { put("sheetId", id); put("dimension", "COLUMNS"); put("startIndex", i); put("endIndex", i + 1) }
                            putJsonObject("properties") { put("pixelSize", px) }
                            put("fields", "pixelSize")
                        }
                    })
                }
            }
        })
    }

    override suspend fun writeUsers(users: List<AppUser>) {
        ensureUsersTab()
        call("POST", "$base/values/${enc("$USERS_TAB!A2:C")}:clear", buildJsonObject { })
        if (users.isNotEmpty()) {
            call("PUT", "$base/values/${enc("$USERS_TAB!A2")}?valueInputOption=RAW", buildJsonObject {
                putJsonArray("values") {
                    users.forEach { u ->
                        add(buildJsonArray {
                            add(JsonPrimitive(u.name)); add(JsonPrimitive(u.email)); add(JsonPrimitive(u.role.label))
                        })
                    }
                }
            })
        }
    }

    companion object {
        const val USERS_TAB = "Users"
        const val CONTACTS_TAB = "Contacts"

        /** Accepts a full Google Sheets URL or a bare spreadsheet id. */
        fun parseSpreadsheetId(input: String): String? {
            val t = input.trim()
            Regex("""/spreadsheets/d/([a-zA-Z0-9_-]+)""").find(t)?.let { return it.groupValues[1] }
            return t.takeIf { it.matches(Regex("""[a-zA-Z0-9_-]{20,}""")) }
        }
    }
}
