package com.vinayaka.finance.tools

import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.sync.EntryStore
import com.vinayaka.finance.data.sync.SheetGateway
import com.vinayaka.finance.data.sync.SyncEngine
import com.vinayaka.finance.data.sync.SyncReport
import com.vinayaka.finance.importer.ImportBundle
import com.vinayaka.finance.importer.ImportMapper
import com.vinayaka.finance.importer.ImportPlan

/** A throw-away in-memory database for the importer tool (the app itself uses Room). */
class ToolStore : EntryStore {
    val data = linkedMapOf<String, Entry>()
    val contacts = linkedMapOf<String, Contact>()
    override suspend fun allForYear(year: Int) = data.values.filter { it.year == year }
    override suspend fun years() = data.values.map { it.year }.distinct()
    override suspend fun apply(upserts: List<Entry>, deletes: List<String>) {
        deletes.forEach { data.remove(it) }
        upserts.forEach { data[it.id] = it }
    }
    override suspend fun allContacts() = contacts.values.toList()
    override suspend fun applyContacts(upserts: List<Contact>, deletes: List<String>) {
        deletes.forEach { contacts.remove(it) }
        upserts.forEach { contacts[it.id] = it }
    }
}

data class PipelineResult(val plan: ImportPlan, val report: SyncReport, val years: List<Int>)

/**
 * The same steps the app takes for "Import" followed by "Sync", against any [SheetGateway]:
 * 1. read what the Sheet already holds (never writes),
 * 2. plan the import, skipping rows that are already there (so running it twice adds nothing),
 * 3. push the new rows with the real [SyncEngine], which creates and formats the year tab and the Contacts tab.
 */
object WorkbookPipeline {
    suspend fun run(bundle: ImportBundle, undatedDate: String, who: String, gateway: SheetGateway, now: Long = System.currentTimeMillis()): PipelineResult {
        val store = ToolStore()
        val engine = SyncEngine(store, gateway)
        val contactYear = undatedDate.take(4).toInt()
        engine.sync(currentYear = contactYear, canWrite = false, allYears = true)
        val plan = ImportMapper.plan(bundle, undatedDate, contactYear, store.data.values.toList(), store.contacts.values.toList(), who, now)
        store.apply(plan.entries, emptyList())
        store.applyContacts(plan.contacts, emptyList())
        val years = plan.entries.map { it.year }.distinct().sorted()
        val report = engine.sync(currentYear = contactYear, canWrite = true, extraYears = years.toSet(), allYears = true)
        return PipelineResult(plan, report, years)
    }
}
