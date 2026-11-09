package com.vinayaka.finance.data.sheets

import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.RecordRead
import com.vinayaka.finance.data.model.RemoteRow
import com.vinayaka.finance.data.model.RowWrite
import com.vinayaka.finance.data.model.SyncRecord
import com.vinayaka.finance.data.sync.SheetGateway

/**
 * In-memory stand-in for a Google Sheet. Used by the demo mode (so the app can be tried without a
 * Google account) and by tests. Rows keep their row numbers like a real sheet.
 */
class FakeSheetGateway : SheetGateway {
    private class Row<T>(var record: T, var storedHash: String)

    private class Tab<T : SyncRecord<T>> {
        val rows = mutableListOf<Row<T>>()

        fun read(): RecordRead<T> = RecordRead(
            rows.mapIndexed { i, r ->
                RemoteRow(r.record.synced(), rowNumber = i + 2, hashMatches = r.storedHash == r.record.contentHash(), hadId = true)
            },
        )

        fun write(updates: List<RowWrite<T>>, appends: List<T>) {
            for (u in updates) rows[u.rowNumber - 2].apply { record = u.record; storedHash = u.record.contentHash() }
            for (a in appends) rows += Row(a, a.contentHash())
        }
    }

    private val years = linkedMapOf<Int, Tab<Entry>>()
    private val contacts = Tab<Contact>()
    private var users: List<AppUser>? = null
    var reads = 0
        private set
    var writes = 0
        private set

    override suspend fun listYears(): List<Int> = years.keys.sorted()

    override suspend fun readYear(year: Int): RecordRead<Entry> {
        reads++
        return years[year]?.read() ?: RecordRead(emptyList())
    }

    override suspend fun writeYear(year: Int, updates: List<RowWrite<Entry>>, appends: List<Entry>) {
        writes++
        years.getOrPut(year) { Tab() }.write(updates, appends)
    }

    override suspend fun readContacts(): RecordRead<Contact> { reads++; return contacts.read() }

    override suspend fun writeContacts(updates: List<RowWrite<Contact>>, appends: List<Contact>) {
        writes++
        contacts.write(updates, appends)
    }

    override suspend fun readUsers(): List<AppUser>? = users
    override suspend fun writeUsers(users: List<AppUser>) { this.users = users }

    // ---- test helpers: act like a person editing the sheet by hand ----
    fun handEdit(year: Int, id: String, change: (Entry) -> Entry) {
        val row = years.getValue(year).rows.first { it.record.id == id }
        row.record = change(row.record) // storedHash left stale on purpose
    }

    fun handDelete(year: Int, id: String) { years.getValue(year).rows.removeAll { it.record.id == id } }

    fun clearTab(year: Int) { years.getValue(year).rows.clear() }

    fun rows(year: Int): List<Entry> = years[year]?.rows.orEmpty().map { it.record }

    fun contactRows(): List<Contact> = contacts.rows.map { it.record }
}
