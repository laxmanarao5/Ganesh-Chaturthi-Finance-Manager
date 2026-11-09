package com.vinayaka.finance.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Anything that is synced with the Sheet, one row per record. */
interface SyncRecord<T : SyncRecord<T>> {
    val id: String
    val rev: Int
    val baseRev: Int
    val dirty: Boolean
    val updatedAt: Long
    val deletedAt: Long?

    /** Fingerprint of the user-visible fields; used to notice edits made by hand in the Sheet. */
    fun contentHash(): String
    fun synced(): T
    fun withRev(rev: Int): T
    /** A row that was edited or typed directly in the Sheet. */
    fun adopted(rev: Int, now: Long, editor: String): T
    fun describe(): String
}

/** The three kinds of money records the committee tracks. */
enum class EntryType(
    val label: String,
    val sheetName: String,
    val isIncome: Boolean,
    val categories: List<String>,
    val nameLabel: String,
    val detailLabel: String,
    val personLabel: String,
) {
    DONATION("Donations", "Donation", true, listOf("Youth", "Villagers", "Children", "Other villagers", "Annadhanam"), "Donor name", "Village / details", "Collected by"),
    EXPENDITURE("Expenditure", "Expenditure", false, listOf("Pooja", "Annadhanam"), "Item", "Quantity / notes", "Paid by"),
    OTHER("Other income", "Other", true, emptyList(), "Name", "Description", "Collected by");

    companion object {
        fun fromSheet(text: String?): EntryType? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            return entries.firstOrNull { it.sheetName.equals(t, true) || it.label.equals(t, true) || it.name.equals(t, true) }
        }
    }
}

/**
 * One money record. [amount] is in paise (1/100 rupee) so totals are exact. [person] is who collected
 * the money (income) or who paid (spending); blank on income means "not collected yet".
 * [rev] counts edits; [baseRev] is the rev this device last saw on the Sheet, which is how a
 * conflicting edit from another phone is detected.
 */
@Entity(tableName = "entries", indices = [Index("year"), Index("type")])
data class Entry(
    @PrimaryKey override val id: String,
    val type: EntryType,
    val year: Int,
    /** yyyy-MM-dd */
    val date: String,
    val category: String,
    val name: String,
    val description: String,
    val amount: Long,
    val person: String,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
    override val rev: Int = 1,
    override val baseRev: Int = 0,
    override val dirty: Boolean = true,
) : SyncRecord<Entry> {
    val isDeleted: Boolean get() = deletedAt != null
    /**
     * Money that has been promised but not yet collected by anyone: income with an amount and no collector.
     * A gift in kind (amount 0) is handed to the committee directly, so it is never "not collected".
     */
    val isPending: Boolean get() = type.isIncome && person.isBlank() && amount > 0

    override fun contentHash(): String = fnv(
        "$date|${type.sheetName}|${category.trim()}|${name.trim()}|${description.trim()}|$amount|${person.trim()}|${if (deletedAt != null) 1 else 0}",
    )

    override fun synced(): Entry = copy(dirty = false, baseRev = rev)
    override fun withRev(rev: Int): Entry = copy(rev = rev)
    override fun adopted(rev: Int, now: Long, editor: String): Entry = copy(
        rev = rev, updatedAt = now, updatedBy = editor,
        createdAt = if (createdAt == 0L) now else createdAt,
        createdBy = createdBy.ifBlank { editor },
    )
    override fun describe() = "${type.sheetName} $date ${name.take(24)}".trim()
}

/**
 * One phone-call target for one year. A person who is called every year has one row per year,
 * so a new year starts with everyone un-called. [contactId] is shared by all of a person's rows.
 */
@Entity(tableName = "contacts", indices = [Index("contactId"), Index("year")])
data class Contact(
    @PrimaryKey override val id: String,
    val contactId: String,
    val year: Int,
    val name: String,
    val number: String,
    val caller: String,
    val called: Boolean,
    val remarks: String,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
    override val rev: Int = 1,
    override val baseRev: Int = 0,
    override val dirty: Boolean = true,
) : SyncRecord<Contact> {
    val isDeleted: Boolean get() = deletedAt != null

    override fun contentHash(): String = fnv(
        "$year|${name.trim()}|${number.trim()}|${caller.trim()}|$called|${remarks.trim()}|${if (deletedAt != null) 1 else 0}",
    )

    override fun synced(): Contact = copy(dirty = false, baseRev = rev)
    override fun withRev(rev: Int): Contact = copy(rev = rev)
    override fun adopted(rev: Int, now: Long, editor: String): Contact = copy(
        rev = rev, updatedAt = now, updatedBy = editor,
        createdAt = if (createdAt == 0L) now else createdAt,
        createdBy = createdBy.ifBlank { editor },
    )
    override fun describe() = "Contact $year ${name.take(24)}".trim()

    companion object {
        fun rowId(contactId: String, year: Int) = "$contactId:$year"
    }
}

private fun fnv(text: String): String {
    var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (ch in text) {
        h = h xor ch.code.toLong()
        h *= 0x100000001b3L
    }
    return java.lang.Long.toHexString(h)
}

/** What a signed-in person may do: Viewers only look, Editors add and edit records, Admins also manage users. */
enum class Role(val label: String, val canEdit: Boolean, val canManageUsers: Boolean) {
    VIEWER("Viewer", false, false),
    EDITOR("Editor", true, false),
    ADMIN("Admin", true, true),
}

/** One row of the Sheet's Users tab. Only listed emails may use the app. */
data class AppUser(val name: String, val email: String, val role: Role)

/** A record as read from the Sheet, plus where it lives there. */
data class RemoteRow<T>(
    val record: T,
    val rowNumber: Int,
    /** False when the visible cells no longer match the stored hash (edited by hand). */
    val hashMatches: Boolean,
    /** False when the row had no id (added by hand). */
    val hadId: Boolean,
)

data class RowWrite<T>(val rowNumber: Int, val record: T)

data class RecordRead<T>(val rows: List<RemoteRow<T>>, val skippedRows: Int = 0)
