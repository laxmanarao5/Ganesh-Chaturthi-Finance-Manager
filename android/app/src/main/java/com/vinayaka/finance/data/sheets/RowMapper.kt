package com.vinayaka.finance.data.sheets

import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.model.RemoteRow
import com.vinayaka.finance.util.isoToSerial
import com.vinayaka.finance.util.paiseToSheetNumber
import com.vinayaka.finance.util.parseDateFlexible
import com.vinayaka.finance.util.serialToIso
import com.vinayaka.finance.util.sheetNumberToPaise
import java.time.Instant
import java.util.UUID

private fun isoMs(ms: Long) = Instant.ofEpochMilli(ms).toString()

private fun parseMs(v: Any?): Long =
    v?.toString()?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

private fun cell(cells: List<Any?>, i: Int): Any? = cells.getOrNull(i)?.takeIf { it.toString().isNotBlank() }

private fun text(cells: List<Any?>, i: Int): String = cell(cells, i)?.toString()?.trim().orEmpty()

private fun newId() = UUID.randomUUID().toString().replace("-", "")

/** A phone number may arrive as a number (8.97E9) or as text. */
private fun phoneText(v: Any?): String = when (v) {
    null -> ""
    is Number -> java.math.BigDecimal(v.toDouble()).toBigInteger().toString()
    else -> v.toString().trim()
}

/**
 * Ledger tab (one per year):
 * A Date | B Type | C Category | D Name | E Description | F Amount | G Collected / Paid by | H Created by   <- for humans
 * I id | J rev | K hash | L created_at | M updated_by | N updated_at | O deleted_at                           <- system
 */
object EntryRows {
    val HEADERS = listOf(
        "Date", "Type", "Category", "Name", "Description", "Amount", "Collected / Paid by", "Created by",
        "id", "rev", "hash", "created_at", "updated_by", "updated_at", "deleted_at",
    )
    const val COLUMNS = 15
    const val LAST_COLUMN = "O"
    const val DELETED_COLUMN = "O"

    fun toCells(e: Entry): List<Any> = listOf(
        isoToSerial(e.date), e.type.sheetName, e.category, e.name, e.description,
        paiseToSheetNumber(e.amount), e.person, e.createdBy,
        e.id, e.rev, e.contentHash(), isoMs(e.createdAt), e.updatedBy, isoMs(e.updatedAt),
        e.deletedAt?.let { isoMs(it) } ?: "",
    )

    private fun amountOf(v: Any?): Long? = when (v) {
        null -> 0L // blank amount: a gift in kind
        is Number -> sheetNumberToPaise(v.toDouble())
        else -> v.toString().replace(",", "").replace("₹", "").trim().toDoubleOrNull()?.let { sheetNumberToPaise(it) }
    }

    private fun dateOf(v: Any?): String? = when (v) {
        null -> null
        is Number -> serialToIso(v.toDouble())
        else -> parseDateFlexible(v.toString())
    }

    /** Null means the row is blank or unreadable (no valid date, type or amount). */
    fun fromCells(cells: List<Any?>, rowNumber: Int, year: Int): RemoteRow<Entry>? {
        val date = dateOf(cell(cells, 0)) ?: return null
        val type = EntryType.fromSheet(text(cells, 1)) ?: return null
        val amount = amountOf(cell(cells, 5)) ?: return null
        val storedId = text(cells, 8)
        val entry = Entry(
            id = storedId.ifEmpty { newId() },
            type = type, year = year, date = date,
            category = text(cells, 2), name = text(cells, 3), description = text(cells, 4),
            amount = amount, person = text(cells, 6), createdBy = text(cells, 7),
            createdAt = parseMs(cell(cells, 11)), updatedBy = text(cells, 12), updatedAt = parseMs(cell(cells, 13)),
            deletedAt = parseMs(cell(cells, 14)).takeIf { it != 0L },
            rev = (cell(cells, 9) as? Number)?.toInt() ?: text(cells, 9).toIntOrNull() ?: 1,
            baseRev = 0, dirty = false,
        )
        return RemoteRow(entry, rowNumber, hashMatches = text(cells, 10) == entry.contentHash(), hadId = storedId.isNotEmpty())
    }

    fun isBlankRow(cells: List<Any?>) = cells.all { it == null || it.toString().isBlank() }
}

/**
 * "Contacts" tab, all years in one tab (one row per contact per year):
 * A Name | B Number | C Who calls | D Year | E Called | F Remarks   <- for humans
 * G contact_id | H rev | I hash | J created_at | K updated_by | L updated_at | M deleted_at   <- system
 */
object ContactRows {
    val HEADERS = listOf(
        "Name", "Number", "Who calls", "Year", "Called", "Remarks",
        "contact_id", "rev", "hash", "created_at", "updated_by", "updated_at", "deleted_at",
    )
    const val COLUMNS = 13
    const val LAST_COLUMN = "M"
    const val DELETED_COLUMN = "M"

    fun toCells(c: Contact): List<Any> = listOf(
        c.name, c.number, c.caller, c.year, c.called, c.remarks,
        c.contactId, c.rev, c.contentHash(), isoMs(c.createdAt), c.updatedBy, isoMs(c.updatedAt),
        c.deletedAt?.let { isoMs(it) } ?: "",
    )

    private fun calledOf(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        else -> v.toString().trim().lowercase() in setOf("true", "yes", "y", "1", "done", "called", "✓", "x")
    }

    fun fromCells(cells: List<Any?>, rowNumber: Int): RemoteRow<Contact>? {
        val name = text(cells, 0)
        val year = (cell(cells, 3) as? Number)?.toInt() ?: text(cells, 3).toIntOrNull() ?: return null
        if (name.isEmpty() || year !in 2000..2100) return null
        val storedContactId = text(cells, 6)
        val contactId = storedContactId.ifEmpty { newId() }
        val contact = Contact(
            id = Contact.rowId(contactId, year), contactId = contactId, year = year,
            name = name, number = phoneText(cell(cells, 1)), caller = text(cells, 2),
            called = calledOf(cell(cells, 4)), remarks = text(cells, 5),
            createdBy = "", createdAt = parseMs(cell(cells, 9)),
            updatedBy = text(cells, 10), updatedAt = parseMs(cell(cells, 11)),
            deletedAt = parseMs(cell(cells, 12)).takeIf { it != 0L },
            rev = (cell(cells, 7) as? Number)?.toInt() ?: text(cells, 7).toIntOrNull() ?: 1,
            baseRev = 0, dirty = false,
        )
        return RemoteRow(contact, rowNumber, hashMatches = text(cells, 8) == contact.contentHash(), hadId = storedContactId.isNotEmpty())
    }

    fun isBlankRow(cells: List<Any?>) = cells.all { it == null || it.toString().isBlank() }
}
