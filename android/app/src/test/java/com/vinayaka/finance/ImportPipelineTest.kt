package com.vinayaka.finance

import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.sheets.FakeSheetGateway
import com.vinayaka.finance.importer.ImportBundle
import com.vinayaka.finance.importer.ImportContact
import com.vinayaka.finance.importer.ImportEntry
import com.vinayaka.finance.tools.WorkbookPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The workbook-to-Sheet steps used by the import-workbook skill, tested with made-up rows against the in-memory sheet. */
class ImportPipelineTest {
    private val bundle = ImportBundle(
        entries = listOf(
            ImportEntry(EntryType.DONATION, "Youth", null, "Donor One", "Village A", 50_000, "Collector A"),
            ImportEntry(EntryType.DONATION, "Villagers", null, "Donor Two", "", 25_000, ""),
            ImportEntry(EntryType.EXPENDITURE, "Pooja", "2026-09-12", "Flowers", "2 kg", 14_50, "Collector A"),
            ImportEntry(EntryType.OTHER, "", "2026-09-14", "Dhakshina", "", 10_000, "Collector B"),
        ),
        contacts = listOf(ImportContact("Contact One", "9000000001", "Collector A", true, "")),
        sections = emptyList(), ignoredSheets = emptyList(), skippedRows = 0,
    )

    @Test fun `rows land in the year tab and the contacts tab of the sheet`() = runBlocking {
        val sheet = FakeSheetGateway()
        val result = WorkbookPipeline.run(bundle, "2026-09-10", "Workbook import", sheet)
        assertEquals(4, result.plan.entries.size)
        assertEquals(listOf(2026), sheet.listYears())
        val rows = sheet.readYear(2026).rows.map { it.record }
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.date == "2026-09-10" })          // the two undated rows got the chosen date
        assertEquals(1, rows.count { it.isPending })                     // the donation with no collector stays "not collected"
        assertEquals(50_000 + 25_000 + 14_50 + 10_000L, rows.sumOf { it.amount })
        val contacts = sheet.readContacts().rows.map { it.record }
        assertEquals(1, contacts.size)
        assertEquals(2026, contacts[0].year)
        assertTrue(contacts[0].called)
    }

    @Test fun `running it twice adds nothing`() = runBlocking {
        val sheet = FakeSheetGateway()
        WorkbookPipeline.run(bundle, "2026-09-10", "Workbook import", sheet)
        val again = WorkbookPipeline.run(bundle, "2026-09-10", "Workbook import", sheet)
        assertEquals(0, again.plan.entries.size)
        assertEquals(4, again.plan.duplicateEntries)
        assertEquals(1, again.plan.duplicateContacts)
        assertEquals(4, sheet.readYear(2026).rows.size)
        assertEquals(1, sheet.readContacts().rows.size)
    }
}
