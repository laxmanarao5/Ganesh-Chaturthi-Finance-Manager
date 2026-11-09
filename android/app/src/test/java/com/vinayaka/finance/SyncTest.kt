package com.vinayaka.finance

import com.vinayaka.finance.data.EntryRepository
import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.sheets.FakeSheetGateway
import com.vinayaka.finance.data.sync.EntryStore
import com.vinayaka.finance.data.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryStore : EntryStore {
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

private var clockMs = 1_000L

private fun entry(id: String, amount: Long = 100, name: String = "item $id", year: Int = 2025, type: EntryType = EntryType.EXPENDITURE) =
    Entry(
        id = id, type = type, year = year, date = "$year-09-07", category = "Pooja", name = name, description = "", amount = amount,
        person = "Laxman", createdBy = "a@x.com", createdAt = clockMs, updatedBy = "a@x.com", updatedAt = clockMs,
        rev = 1, baseRev = 0, dirty = true,
    )

private fun contact(cid: String, year: Int, called: Boolean = false, name: String = "Contact $cid") = Contact(
    id = Contact.rowId(cid, year), contactId = cid, year = year, name = name, number = "9000000000", caller = "Bhanu",
    called = called, remarks = "", createdBy = "a@x.com", createdAt = clockMs, updatedBy = "a@x.com", updatedAt = clockMs,
)

private fun Entry.edit(amount: Long, at: Long) = copy(amount = amount, updatedAt = at, rev = rev + 1, dirty = true)

/** Two phones sharing one fake sheet. */
private class Phone(val sheet: FakeSheetGateway) {
    val store = MemoryStore()
    var now = 10_000L
    val engine = SyncEngine(store, sheet) { now }
    fun sync(allYears: Boolean = false, canWrite: Boolean = true) = runBlocking { engine.sync(2025, canWrite, allYears = allYears) }
    fun add(e: Entry) { store.data[e.id] = e }
    fun edit(id: String, amount: Long, at: Long) { store.data[id] = store.data.getValue(id).edit(amount, at) }
}

class SyncTest {
    @Test fun `first sync pushes new local rows and a second sync has nothing to do`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet)
        a.add(entry("1")); a.add(entry("2"))
        val r1 = a.sync()
        assertEquals(2, r1.pushed)
        assertEquals(2, sheet.rows(2025).size)
        assertTrue(a.store.data.values.none { it.dirty })
        val r2 = a.sync()
        assertTrue("second sync should be a no-op", r2.nothingToSync)
        assertEquals(1, sheet.writes)
    }

    @Test fun `second phone pulls what the first pushed`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1")); a.sync()
        val r = b.sync(allYears = true)
        assertEquals(1, r.pulled)
        assertEquals(100L, b.store.data.getValue("1").amount)
        assertEquals("Laxman", b.store.data.getValue("1").person)
        assertTrue(b.sync().nothingToSync)
    }

    @Test fun `edit on one phone reaches the other`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1")); a.sync(); b.sync(allYears = true)
        a.edit("1", 500, 20_000); a.sync()
        val r = b.sync()
        assertEquals(1, r.pulled)
        assertEquals(500L, b.store.data.getValue("1").amount)
    }

    @Test fun `marking a donation collected on one phone shows on the other`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1", type = EntryType.DONATION).copy(person = "")); a.sync(); b.sync(allYears = true)
        assertTrue(b.store.data.getValue("1").isPending)
        a.store.data["1"] = a.store.data.getValue("1").let { it.copy(person = "Bhanu", rev = it.rev + 1, updatedAt = 20_000, dirty = true) }
        a.sync(); b.sync()
        assertFalse(b.store.data.getValue("1").isPending)
        assertEquals("Bhanu", b.store.data.getValue("1").person)
    }

    @Test fun `conflict - newest edit wins and is reported`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1")); a.sync(); b.sync(allYears = true)
        a.edit("1", 111, 20_000)
        b.edit("1", 222, 30_000) // later
        a.sync()
        val r = b.sync()
        assertEquals(1, r.conflicts.size)
        assertTrue(r.conflicts[0].keptMine)
        assertEquals(222L, sheet.rows(2025).single().amount)
        a.sync()
        assertEquals(222L, a.store.data.getValue("1").amount)
    }

    @Test fun `conflict - older local edit loses to the sheet`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1")); a.sync(); b.sync(allYears = true)
        b.edit("1", 222, 15_000)
        a.edit("1", 111, 20_000); a.sync()
        val r = b.sync()
        assertFalse(r.conflicts.single().keptMine)
        assertEquals(111L, b.store.data.getValue("1").amount)
    }

    @Test fun `delete is a tombstone that reaches other phones`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1")); a.sync(); b.sync(allYears = true)
        a.store.data["1"] = a.store.data.getValue("1").copy(deletedAt = 20_000, rev = 2, dirty = true, updatedAt = 20_000)
        a.sync(); b.sync()
        assertTrue(b.store.data.getValue("1").isDeleted)
    }

    @Test fun `row edited by hand in the sheet is adopted with a new rev`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet)
        a.add(entry("1")); a.sync()
        sheet.handEdit(2025, "1") { it.copy(amount = 999) }
        val r = a.sync()
        assertEquals(1, r.pulled)
        assertEquals(999L, a.store.data.getValue("1").amount)
        assertEquals(2, a.store.data.getValue("1").rev)
        assertTrue(a.sync().nothingToSync) // sheet hash was repaired
    }

    @Test fun `row deleted by hand in the sheet is removed locally`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet)
        a.add(entry("1")); a.add(entry("2")); a.sync()
        sheet.handDelete(2025, "1")
        val r = a.sync()
        assertEquals(1, r.removed)
        assertEquals(setOf("2"), a.store.data.keys)
    }

    @Test fun `emptied tab restores local rows instead of wiping them`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet)
        a.add(entry("1")); a.add(entry("2")); a.sync()
        sheet.clearTab(2025)
        val r = a.sync()
        assertEquals(2, r.restored)
        assertEquals(2, sheet.rows(2025).size)
        assertEquals(2, a.store.data.size)
    }

    @Test fun `identical content on both sides is not a conflict or a push`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1")); a.sync()
        b.add(entry("1")) // same id and content, e.g. imported twice
        val r = b.sync()
        assertTrue(r.nothingToSync)
        assertEquals(1, sheet.writes)
    }

    @Test fun `read-only member pulls but never writes`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val viewer = Phone(sheet)
        a.add(entry("1")); a.sync()
        viewer.add(entry("9")) // stray unsent row
        val r = viewer.sync(allYears = true, canWrite = false)
        assertEquals(1, r.pulled)
        assertEquals(1, r.skippedWrites)
        assertEquals(1, sheet.rows(2025).size)
        assertTrue(viewer.store.data.getValue("9").dirty)
    }

    @Test fun `each year is its own tab and old years load only on request`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.add(entry("1", year = 2024)); a.add(entry("2", year = 2025)); a.sync(allYears = true)
        assertEquals(listOf(2024, 2025), runBlocking { sheet.listYears() })
        b.sync()
        assertEquals(setOf("2"), b.store.data.keys)
        b.sync(allYears = true)
        assertEquals(setOf("1", "2"), b.store.data.keys)
    }

    // ---------------------------------------------------------------- contacts
    @Test fun `contacts sync through one tab for every year`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.store.contacts["c1:2025"] = contact("c1", 2025, called = true)
        a.store.contacts["c1:2026"] = contact("c1", 2026)
        a.store.contacts["c2:2025"] = contact("c2", 2025)
        val r = a.sync()
        assertEquals(3, r.pushed)
        assertEquals(3, sheet.contactRows().size)
        b.sync()
        assertEquals(3, b.store.contacts.size)
        assertTrue(b.store.contacts.getValue("c1:2025").called)
        assertTrue(b.sync().nothingToSync)
    }

    @Test fun `marking a contact called on one phone reaches the other`() {
        val sheet = FakeSheetGateway()
        val a = Phone(sheet); val b = Phone(sheet)
        a.store.contacts["c1:2025"] = contact("c1", 2025)
        a.sync(); b.sync()
        a.store.contacts["c1:2025"] = a.store.contacts.getValue("c1:2025").let { it.copy(called = true, rev = it.rev + 1, updatedAt = 20_000, dirty = true) }
        a.sync(); b.sync()
        assertTrue(b.store.contacts.getValue("c1:2025").called)
    }

    @Test fun `a new year starts with every contact un-called`() {
        val rows = listOf(contact("c1", 2025, called = true), contact("c2", 2025, called = true), contact("c3", 2025))
        val in2025 = EntryRepository.viewsOf(rows, 2025)
        assertEquals(listOf(true, true, false), in2025.map { it.called })
        val in2026 = EntryRepository.viewsOf(rows, 2026)
        assertEquals(3, in2026.size)
        assertTrue(in2026.none { it.called })
    }

    @Test fun `marking called in a new year only affects that year`() {
        val rows = listOf(contact("c1", 2025, called = true), contact("c1", 2026, called = true), contact("c2", 2025, called = true))
        assertEquals(listOf(true, false), EntryRepository.viewsOf(rows, 2026).map { it.called })
        assertEquals(listOf(true, true), EntryRepository.viewsOf(rows, 2025).map { it.called })
    }
}
