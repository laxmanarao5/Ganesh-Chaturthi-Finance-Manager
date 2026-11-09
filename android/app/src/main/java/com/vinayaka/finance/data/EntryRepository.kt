package com.vinayaka.finance.data

import com.vinayaka.finance.data.local.ContactDao
import com.vinayaka.finance.data.local.EntryDao
import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.sync.EntryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomEntryStore(private val entries: EntryDao, private val contacts: ContactDao) : EntryStore {
    override suspend fun allForYear(year: Int) = entries.allForYear(year)
    override suspend fun years() = entries.years()
    override suspend fun apply(upserts: List<Entry>, deletes: List<String>) = entries.apply(upserts, deletes)
    override suspend fun allContacts() = contacts.all()
    override suspend fun applyContacts(upserts: List<Contact>, deletes: List<String>) = contacts.apply(upserts, deletes)
}

/** One person's contact details for display: the newest row wins, and `called` is for the chosen year. */
data class ContactView(
    val contactId: String,
    val name: String,
    val number: String,
    val caller: String,
    val remarks: String,
    val called: Boolean,
    val dirty: Boolean,
)

/** All writes land in the local database first; syncing to the Sheet is a separate, explicit step. */
class EntryRepository(
    private val dao: EntryDao,
    private val contactDao: ContactDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeYear(year: Int): Flow<List<Entry>> = dao.observeYear(year)
    fun observeYears(): Flow<List<Int>> = combine(dao.observeYears(), contactDao.observeYears()) { a, b -> (a + b).distinct().sortedDescending() }
    fun observeDirtyCount(): Flow<Int> = combine(dao.observeDirtyCount(), contactDao.observeDirtyCount()) { a, b -> a + b }

    /** Everyone ever named as a collector, payer or caller, for the pickers. */
    fun observePeople(): Flow<List<String>> =
        combine(dao.observePersons(), contactDao.observeCallers()) { a, b -> a + b }

    // ------------------------------------------------------------ ledger
    suspend fun add(
        type: EntryType, date: String, category: String, name: String, description: String,
        amount: Long, person: String, by: String,
    ): Entry {
        val now = clock()
        val e = Entry(
            id = UUID.randomUUID().toString().replace("-", ""),
            type = type, year = date.substring(0, 4).toInt(), date = date,
            category = category.trim(), name = name.trim(), description = description.trim(),
            amount = amount, person = person.trim(),
            createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
            rev = 1, baseRev = 0, dirty = true,
        )
        dao.upsert(e)
        return e
    }

    suspend fun update(
        old: Entry, date: String, category: String, name: String, description: String,
        amount: Long, person: String, by: String,
    ) {
        dao.upsert(
            old.copy(
                date = date, category = category.trim(), name = name.trim(), description = description.trim(),
                amount = amount, person = person.trim(),
                updatedBy = by, updatedAt = clock(), rev = old.rev + 1, dirty = true,
            ),
        )
    }

    suspend fun setPerson(old: Entry, person: String, by: String) =
        update(old, old.date, old.category, old.name, old.description, old.amount, person, by)

    /** Rows never sent to the Sheet are simply removed; others become tombstones so the delete syncs. */
    suspend fun delete(entry: Entry, by: String) {
        if (entry.baseRev == 0) dao.deleteById(entry.id)
        else {
            val now = clock()
            dao.upsert(entry.copy(deletedAt = now, updatedAt = now, updatedBy = by, rev = entry.rev + 1, dirty = true))
        }
    }

    suspend fun undoDelete(original: Entry) {
        dao.upsert(original.copy(rev = original.rev + 2, updatedAt = clock(), dirty = true))
    }

    suspend fun addMany(entries: List<Entry>) = dao.upsertAll(entries)
    suspend fun allForYear(year: Int) = dao.allForYear(year)

    // ------------------------------------------------------------ contacts
    fun observeContacts(year: Int): Flow<List<ContactView>> = contactDao.observeLive().map { viewsOf(it, year) }

    suspend fun markCalled(contactId: String, year: Int, called: Boolean, by: String) {
        val now = clock()
        val rows = contactDao.forContact(contactId).filter { !it.isDeleted }
        val existing = rows.firstOrNull { it.year == year }
        if (existing != null) {
            contactDao.upsert(existing.copy(called = called, updatedBy = by, updatedAt = now, rev = existing.rev + 1, dirty = true))
        } else {
            val master = rows.maxByOrNull { it.updatedAt } ?: return
            contactDao.upsert(
                master.copy(
                    id = Contact.rowId(contactId, year), year = year, called = called,
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                    deletedAt = null, rev = 1, baseRev = 0, dirty = true,
                ),
            )
        }
    }

    suspend fun addContact(name: String, number: String, caller: String, remarks: String, year: Int, by: String) {
        val now = clock()
        val cid = UUID.randomUUID().toString().replace("-", "")
        contactDao.upsert(
            Contact(
                id = Contact.rowId(cid, year), contactId = cid, year = year,
                name = name.trim(), number = number.trim(), caller = caller.trim(), called = false, remarks = remarks.trim(),
                createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
            ),
        )
    }

    /** Edits show everywhere, because the newest row supplies the name, number and caller. */
    suspend fun editContact(contactId: String, year: Int, name: String, number: String, caller: String, remarks: String, by: String) {
        val now = clock()
        val rows = contactDao.forContact(contactId).filter { !it.isDeleted }
        val existing = rows.firstOrNull { it.year == year }
        val base = existing ?: rows.maxByOrNull { it.updatedAt } ?: return
        val updated = base.copy(
            id = Contact.rowId(contactId, year), year = year,
            name = name.trim(), number = number.trim(), caller = caller.trim(), remarks = remarks.trim(),
            called = existing?.called ?: false,
            updatedBy = by, updatedAt = now,
            rev = if (existing != null) existing.rev + 1 else 1,
            baseRev = existing?.baseRev ?: 0, createdBy = existing?.createdBy ?: by, createdAt = existing?.createdAt ?: now,
            dirty = true,
        )
        contactDao.upsert(updated)
    }

    suspend fun deleteContact(contactId: String, by: String) {
        val now = clock()
        contactDao.forContact(contactId).forEach { c ->
            if (c.baseRev == 0) contactDao.deleteById(c.id)
            else contactDao.upsert(c.copy(deletedAt = now, updatedAt = now, updatedBy = by, rev = c.rev + 1, dirty = true))
        }
    }

    suspend fun addManyContacts(contacts: List<Contact>) = contactDao.upsertAll(contacts)
    suspend fun allContacts() = contactDao.all()

    suspend fun clear() { dao.clear(); contactDao.clear() }

    companion object {
        fun viewsOf(rows: List<Contact>, year: Int): List<ContactView> =
            rows.groupBy { it.contactId }.map { (cid, list) ->
                val master = list.maxBy { it.updatedAt }
                val mine = list.firstOrNull { it.year == year }
                ContactView(
                    contactId = cid, name = master.name, number = master.number, caller = master.caller,
                    remarks = mine?.remarks ?: master.remarks, called = mine?.called ?: false,
                    dirty = list.any { it.dirty },
                )
            }.sortedBy { it.name.lowercase() }
    }
}
