package com.vinayaka.finance.data.sync

import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.RecordRead
import com.vinayaka.finance.data.model.RemoteRow
import com.vinayaka.finance.data.model.RowWrite
import com.vinayaka.finance.data.model.SyncRecord

/** Where the Sheet data lives. Implemented by the Google Sheets REST client and by an in-memory fake. */
interface SheetGateway {
    /** Year tabs that exist (tab titles that are four-digit years). */
    suspend fun listYears(): List<Int>
    suspend fun readYear(year: Int): RecordRead<Entry>
    /** Creates the year tab when missing, then applies row updates and appends. */
    suspend fun writeYear(year: Int, updates: List<RowWrite<Entry>>, appends: List<Entry>)

    /** The single "Contacts" tab, which holds every year (one row per contact per year). */
    suspend fun readContacts(): RecordRead<Contact>
    suspend fun writeContacts(updates: List<RowWrite<Contact>>, appends: List<Contact>)

    /** The Users tab: who may use the app and with which role. Null when the sheet has no Users tab. */
    suspend fun readUsers(): List<AppUser>?
    suspend fun writeUsers(users: List<AppUser>)
}

/** The slice of the local database the engine needs. */
interface EntryStore {
    suspend fun allForYear(year: Int): List<Entry>
    suspend fun years(): List<Int>
    suspend fun apply(upserts: List<Entry>, deletes: List<String>)

    suspend fun allContacts(): List<Contact>
    suspend fun applyContacts(upserts: List<Contact>, deletes: List<String>)
}

class SyncEngine(
    private val store: EntryStore,
    private val gateway: SheetGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param allYears also download every year tab, not just the ones already on this phone
     * @param canWrite false for read-only members: pull only, never write to the Sheet
     */
    suspend fun sync(
        currentYear: Int,
        canWrite: Boolean = true,
        extraYears: Set<Int> = emptySet(),
        allYears: Boolean = false,
    ): SyncReport {
        val remoteYears = gateway.listYears()
        val localYears = store.years()
        val targets = (if (allYears) remoteYears + localYears else localYears + currentYear + extraYears)
            .filter { it in remoteYears || it in localYears }
            .distinct().sorted()
        var total = SyncReport()
        for (y in targets) {
            total += syncScope(
                canWrite,
                read = { gateway.readYear(y) },
                loadLocal = { store.allForYear(y) },
                write = { u, a -> gateway.writeYear(y, u, a) },
                apply = { up, del -> store.apply(up, del) },
            )
        }
        total += syncScope(
            canWrite,
            read = { gateway.readContacts() },
            loadLocal = { store.allContacts() },
            write = { u, a -> gateway.writeContacts(u, a) },
            apply = { up, del -> store.applyContacts(up, del) },
        )
        return total.copy(years = targets, remoteYears = remoteYears)
    }

    private suspend fun <T : SyncRecord<T>> syncScope(
        canWrite: Boolean,
        read: suspend () -> RecordRead<T>,
        loadLocal: suspend () -> List<T>,
        write: suspend (List<RowWrite<T>>, List<T>) -> Unit,
        apply: suspend (List<T>, List<String>) -> Unit,
    ): SyncReport {
        var attempt = 0
        while (true) {
            attempt++
            val first = read()
            var local = loadLocal()
            // A read-only member must never "restore" rows into an emptied tab.
            if (!canWrite && first.rows.isEmpty()) return SyncReport(skippedRows = first.skippedRows)
            if (first.rows.isEmpty() && local.isEmpty()) return SyncReport(skippedRows = first.skippedRows)
            var remote = first.rows
            var blockedWrites = 0
            if (!canWrite) {
                // Never touch rows with unsent edits, and never write.
                val dirtyIds = local.filter { it.dirty }.map { it.id }.toSet()
                blockedWrites = dirtyIds.size
                local = local.filter { !it.dirty }
                remote = remote.filter { it.record.id !in dirtyIds }
            }
            val plan = Merger.merge(local, remote, clock())
            val writes = canWrite && plan.hasRemoteWrites
            if (writes && attempt == 1) {
                // Guard against someone else writing between our read and our write.
                if (fingerprint(read().rows) != fingerprint(first.rows)) continue
            }
            if (writes) write(plan.remoteUpdates, plan.remoteAppends)
            apply(plan.localUpserts, plan.localDeletes)
            return plan.report.copy(skippedRows = first.skippedRows, skippedWrites = blockedWrites)
        }
    }

    private fun <T : SyncRecord<T>> fingerprint(rows: List<RemoteRow<T>>): String =
        rows.joinToString(";") { "${it.rowNumber}:${it.record.id}:${it.record.rev}:${it.record.contentHash()}" }
}
