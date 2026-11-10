package com.vinayaka.finance.importer

import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.util.parseAmountToPaise
import com.vinayaka.finance.util.parseDateFlexible
import com.vinayaka.finance.util.parseSignedAmountToPaise
import com.vinayaka.finance.util.serialToIso
import java.util.UUID

data class ImportEntry(
    val type: EntryType,
    val category: String,
    /** Null for rows that have no date (donor lists); the importer asks for one date for all of them. */
    val date: String?,
    val name: String,
    val description: String,
    val amount: Long,
    val person: String,
)

data class ImportContact(val name: String, val number: String, val caller: String, val called: Boolean, val remarks: String)

data class SectionSummary(val label: String, val rows: Int, val total: Long)

data class ImportBundle(
    val entries: List<ImportEntry>,
    val contacts: List<ImportContact>,
    val sections: List<SectionSummary>,
    val ignoredSheets: List<String>,
    val skippedRows: Int,
) {
    val undated: Int get() = entries.count { it.date == null }
    val isEmpty: Boolean get() = entries.isEmpty() && contacts.isEmpty()
    /** The earliest dated row, or null: a sensible default for rows that have no date. */
    val earliestDate: String? get() = entries.mapNotNull { it.date }.minOrNull()
}

data class ImportPlan(val entries: List<Entry>, val contacts: List<Contact>, val duplicateEntries: Int, val duplicateContacts: Int)

/**
 * Reads the committee's Excel workbook (one sheet per donation list, expenditure list, other inputs and
 * contacts) or a CSV/xlsx exported from this app. Only the main table of each sheet is read, so the
 * little summary tables beside the data are ignored, and so are Total rows.
 */
object ImportMapper {
    private class SheetSpec(
        val label: String,
        val type: EntryType,
        val category: String,
        val nameHeaders: List<String>,
        val detailHeaders: List<String>,
        val personHeaders: List<String>,
        val hasDate: Boolean,
        val amountRequired: Boolean,
    )

    private val donorDetail = listOf("parent/guardian", "village", "item")
    private val collectors = listOf("collectedby")
    private val known: Map<String, SheetSpec> = listOf(
        SheetSpec("Youth Donations", EntryType.DONATION, "Youth", listOf("name"), donorDetail, collectors, false, false),
        SheetSpec("Villagers Donations", EntryType.DONATION, "Villagers", listOf("name"), donorDetail, collectors, false, false),
        SheetSpec("Children Donations", EntryType.DONATION, "Children", listOf("name"), donorDetail, collectors, false, false),
        SheetSpec("Outsiders Donations", EntryType.DONATION, "Other villagers", listOf("name"), donorDetail, collectors, false, false),
        SheetSpec("Annadhanam donations", EntryType.DONATION, "Annadhanam", listOf("name"), donorDetail, collectors, false, false),
        SheetSpec("Pooja Expenditure", EntryType.EXPENDITURE, "Pooja", listOf("item"), listOf("quantity"), listOf("paidby", "spentby"), true, true),
        SheetSpec("Annadhanam Expenditure", EntryType.EXPENDITURE, "Annadhanam", listOf("item"), listOf("quantity"), listOf("paidby", "spentby"), false, true),
        SheetSpec("Other inputs", EntryType.OTHER, "", listOf("purpose", "name"), listOf("description"), collectors, false, true),
        SheetSpec("Dhakshina", EntryType.OTHER, "", emptyList(), emptyList(), collectors, true, true),
    ).associateBy { norm(it.label) }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '/' }

    /** [allowNegative] is for Expenditure only: a purchase that was returned is written as -1610. */
    private fun amountOf(s: String, allowNegative: Boolean = false): Long? =
        s.replace("Rs.", "", true).let { if (allowNegative) parseSignedAmountToPaise(it) else parseAmountToPaise(it) }

    private fun dateOf(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { if (it in 20000.0..80000.0) return serialToIso(it) }
        return parseDateFlexible(t)
    }

    /** Column of each wanted header: the leftmost match, so side tables further right never win. */
    private fun columns(header: List<String>, wanted: List<String>): List<Int> =
        wanted.mapNotNull { w -> header.indexOfFirst { it == w }.takeIf { it >= 0 } }

    private fun headerRow(rows: List<List<String>>, mustHave: List<String>): Int =
        rows.take(5).indexOfFirst { r -> r.map { norm(it) }.any { it in mustHave } }

    fun parse(sheets: List<SheetData>): ImportBundle {
        val entries = mutableListOf<ImportEntry>()
        val contacts = mutableListOf<ImportContact>()
        val sections = mutableListOf<SectionSummary>()
        val ignored = mutableListOf<String>()
        var skipped = 0
        val people = mutableListOf<String>()
        fun person(raw: String): String {
            val p = Stats.canonicalPerson(raw, people)
            if (p.isNotEmpty() && p !in people) people += p
            return p
        }

        for (sheet in sheets) {
            val key = norm(sheet.name)
            val spec = known[key]
            when {
                spec != null -> {
                    val found = mutableListOf<ImportEntry>()
                    skipped += readKnown(sheet, spec, ::person, found)
                    entries += found
                    sections += SectionSummary("${sheet.name} → ${spec.type.label}${if (spec.category.isNotEmpty()) " / ${spec.category}" else ""}", found.size, found.sumOf { it.amount })
                }
                key == "contacts" -> {
                    val found = mutableListOf<ImportContact>()
                    skipped += readContacts(sheet, ::person, found)
                    contacts += found
                    sections += SectionSummary("${sheet.name} → Contacts", found.size, 0)
                }
                else -> {
                    val found = mutableListOf<ImportEntry>()
                    val n = readGeneric(sheet, ::person, found)
                    if (n == null) ignored += sheet.name
                    else { skipped += n; entries += found; sections += SectionSummary("${sheet.name} → by Type column", found.size, found.sumOf { it.amount }) }
                }
            }
        }
        return ImportBundle(entries, contacts, sections, ignored, skipped)
    }

    private fun readKnown(sheet: SheetData, spec: SheetSpec, person: (String) -> String, out: MutableList<ImportEntry>): Int {
        val h = headerRow(sheet.rows, listOf("amount"))
        if (h < 0) return 0
        val header = sheet.rows[h].map { norm(it) }
        val cAmount = columns(header, listOf("amount")).firstOrNull() ?: return 0
        val cName = columns(header, spec.nameHeaders).firstOrNull() ?: -1
        val cDetails = columns(header, spec.detailHeaders)
        val cPerson = columns(header, spec.personHeaders).firstOrNull() ?: -1
        val cDate = if (spec.hasDate) columns(header, listOf("date")).firstOrNull() ?: -1 else -1
        var skipped = 0
        for (r in sheet.rows.drop(h + 1)) {
            fun cell(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            val amountText = cell(cAmount)
            // Dhakshina has no name column: its name is the sheet's own name.
            val name = if (cName >= 0) cell(cName) else spec.label
            if (name.isEmpty() || name.equals("total", true)) continue
            val amount = if (amountText.isEmpty() && !spec.amountRequired) 0L else amountOf(amountText, spec.type == EntryType.EXPENDITURE)
            if (amount == null) { skipped++; continue }
            val dateText = cell(cDate)
            val date = dateOf(dateText)
            if (spec.hasDate && dateText.isNotEmpty() && date == null) { skipped++; continue }
            out += ImportEntry(
                type = spec.type, category = spec.category, date = date, name = name,
                description = cDetails.map { cell(it) }.filter { it.isNotEmpty() }.joinToString(" · "),
                amount = amount, person = person(cell(cPerson)),
            )
        }
        return skipped
    }

    private fun readContacts(sheet: SheetData, person: (String) -> String, out: MutableList<ImportContact>): Int {
        val h = headerRow(sheet.rows, listOf("number", "name"))
        if (h < 0) return 0
        val header = sheet.rows[h].map { norm(it) }
        val cName = columns(header, listOf("name")).firstOrNull() ?: return 0
        val cNumber = columns(header, listOf("number", "phone", "mobile")).firstOrNull() ?: -1
        val cCaller = columns(header, listOf("whocalls", "caller")).firstOrNull() ?: -1
        val cStatus = columns(header, listOf("status", "called")).firstOrNull() ?: -1
        val cRemarks = columns(header, listOf("remarks", "notes")).firstOrNull() ?: -1
        for (r in sheet.rows.drop(h + 1)) {
            fun cell(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            val name = cell(cName)
            if (name.isEmpty()) continue
            out += ImportContact(
                name = name, number = cell(cNumber), caller = person(cell(cCaller)),
                called = cell(cStatus).lowercase() in setOf("1", "true", "yes", "y", "done", "called"),
                remarks = cell(cRemarks),
            )
        }
        return 0
    }

    /** A sheet or CSV with a Type column, like the files this app exports. Null when it is not that shape. */
    private fun readGeneric(sheet: SheetData, person: (String) -> String, out: MutableList<ImportEntry>): Int? {
        val h = headerRow(sheet.rows, listOf("amount"))
        if (h < 0) return null
        val header = sheet.rows[h].map { norm(it) }
        val cType = columns(header, listOf("type")).firstOrNull() ?: return null
        val cAmount = columns(header, listOf("amount")).firstOrNull() ?: return null
        val cDate = columns(header, listOf("date")).firstOrNull() ?: -1
        val cCategory = columns(header, listOf("category")).firstOrNull() ?: -1
        val cName = columns(header, listOf("name", "item", "purpose")).firstOrNull() ?: -1
        val cDesc = columns(header, listOf("description", "details")).firstOrNull() ?: -1
        val cPerson = columns(header, listOf("collected/paidby", "collectedby", "paidby", "person")).firstOrNull() ?: -1
        var skipped = 0
        for (r in sheet.rows.drop(h + 1)) {
            fun cell(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            if (r.all { it.isBlank() }) continue
            val type = EntryType.fromSheet(cell(cType))
            val amount = amountOf(cell(cAmount), type == EntryType.EXPENDITURE)
            if (type == null || amount == null) { skipped++; continue }
            out += ImportEntry(type, cell(cCategory), dateOf(cell(cDate)), cell(cName), cell(cDesc), amount, person(cell(cPerson)))
        }
        return skipped
    }

    private fun key(type: EntryType, date: String, category: String, name: String, desc: String, amount: Long, person: String) =
        listOf(type.name, date, category.trim().lowercase(), name.trim().lowercase(), desc.trim().lowercase(), amount, person.trim().lowercase()).joinToString("|")

    /**
     * Rows already present (same everything) are skipped, counting repeats, so importing the same file twice
     * adds nothing while two genuinely identical rows both survive. [undatedDate] is used for rows without a date.
     */
    fun plan(
        bundle: ImportBundle, undatedDate: String, contactYear: Int,
        existingEntries: List<Entry>, existingContacts: List<Contact>, who: String, now: Long,
    ): ImportPlan {
        val counts = HashMap<String, Int>()
        existingEntries.filter { !it.isDeleted }.forEach {
            counts.merge(key(it.type, it.date, it.category, it.name, it.description, it.amount, it.person), 1, Int::plus)
        }
        val created = mutableListOf<Entry>()
        var dupEntries = 0
        for (r in bundle.entries) {
            val date = r.date ?: undatedDate
            val k = key(r.type, date, r.category, r.name, r.description, r.amount, r.person)
            val left = counts[k] ?: 0
            if (left > 0) { counts[k] = left - 1; dupEntries++; continue }
            created += Entry(
                id = UUID.randomUUID().toString().replace("-", ""),
                type = r.type, year = date.substring(0, 4).toInt(), date = date,
                category = r.category, name = r.name, description = r.description, amount = r.amount, person = r.person,
                createdBy = who, createdAt = now, updatedBy = who, updatedAt = now,
                rev = 1, baseRev = 0, dirty = true,
            )
        }

        val newContacts = mutableListOf<Contact>()
        var dupContacts = 0
        val liveContacts = existingContacts.filter { !it.isDeleted }
        for (c in bundle.contacts) {
            val same = liveContacts.filter { it.name.equals(c.name, true) && it.number == c.number }
            if (same.any { it.year == contactYear }) { dupContacts++; continue }
            val cid = same.firstOrNull()?.contactId ?: UUID.randomUUID().toString().replace("-", "")
            newContacts += Contact(
                id = Contact.rowId(cid, contactYear), contactId = cid, year = contactYear,
                name = c.name, number = c.number, caller = c.caller, called = c.called, remarks = c.remarks,
                createdBy = who, createdAt = now, updatedBy = who, updatedAt = now,
            )
        }
        return ImportPlan(created, newContacts, dupEntries, dupContacts)
    }
}
