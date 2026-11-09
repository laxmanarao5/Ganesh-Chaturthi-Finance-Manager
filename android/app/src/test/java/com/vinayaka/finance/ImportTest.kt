package com.vinayaka.finance

import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.export.Exporter
import com.vinayaka.finance.importer.Csv
import com.vinayaka.finance.importer.ImportMapper
import com.vinayaka.finance.importer.SheetData
import com.vinayaka.finance.importer.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a workbook shaped like the committee's own file (fake names), then reads it back through the importer. */
class ImportTest {
    private fun col(i: Int) = ('A' + i).toString()

    private fun sheetXml(rows: List<List<String>>): String = "<worksheet><sheetData>" + rows.mapIndexed { r, cells ->
        "<row r=\"${r + 1}\">" + cells.mapIndexedNotNull { c, v ->
            if (v.isEmpty()) null else "<c r=\"${col(c)}${r + 1}\" t=\"inlineStr\"><is><t>${v.replace("&", "&amp;")}</t></is></c>"
        }.joinToString("") + "</row>"
    }.joinToString("") + "</sheetData></worksheet>"

    private fun workbook(vararg sheets: Pair<String, List<List<String>>>): List<SheetData> {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            put(
                "xl/workbook.xml",
                """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    sheets.mapIndexed { i, s -> """<sheet name="${s.first}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>",
            )
            put("xl/_rels/workbook.xml.rels", "<Relationships>" + sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Target="worksheets/sheet${it + 1}.xml"/>""" } + "</Relationships>")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s.second)) }
        }
        return XlsxReader { KXmlParser() }.read(ByteArrayInputStream(out.toByteArray()))
    }

    private val book = workbook(
        "Youth Donations" to listOf(
            listOf("Name", "Amount", "Collected By", "", "Collected By", "Total", "Collected Amount"),
            listOf("Donor One", "501", "Bhanu", "", "Laxman", "", "1000"),          // right-hand columns are a side table
            listOf("Donor Two", "1116", "bhanu"),
            listOf("Donor Three", "1000", "Laxman"),
            listOf("", "2617"),                                                      // total row: no name
        ),
        "Villagers Donations" to listOf(
            listOf("Name", "Amount", "Collected By"),
            listOf("Village A", "5000", "Bhanu"),
            listOf("Village B", "10000", ""),                                        // promised, not collected
            listOf("Total", "15000"),
        ),
        "Children Donations" to listOf(
            listOf("Name", "Parent/Guardian", "Village", "Amount", "Collected By", "Column 1", "Collected By", "Total Amount", "Amount"),
            listOf("Kid One", "s/o Ramesh", "Odhipadu", "100", "Bhanu", "", "Laxman", "", "400"),
        ),
        "Outsiders Donations" to listOf(
            listOf("Name", "Village", "Amount", "Collected By"),
            listOf("Visitor", "Polaki", "201.5", "Simhadri"),
        ),
        "Annadhanam donations" to listOf(
            listOf("Name", "Item", "Amount", "Collected By"),
            listOf("Gift One", "Aakulu", "", ""),                                    // in kind, no amount
            listOf("Gift Two", "Rice bag", "1600", "Laxman"),
        ),
        "Pooja Expenditure" to listOf(
            listOf("Date", "Item", "Quantity", "Amount", "Paid By", "", "Date", "Amount", "", "Spent By", "Spent Amount"),
            listOf("30-08-2026", "Clearing", "", "9850", "Simhadri", "", "30-08-2026", "9850", "", "Laxman", "24528.5"),
            listOf("14-09-2026", "Vim", "2", "14.5", "Laxman", "", "14-09-2026", "7918.5"),
            listOf("", "Total", "", "9864.5"),
        ),
        "Annadhanam Expenditure" to listOf(
            listOf("Item", "Quantity", "Amount", "Paid By"),
            listOf("Kirana", "", "4295", "Bhanu"),
        ),
        "Other inputs" to listOf(
            listOf("Purpose", "Amount", "CollectedBy"),
            listOf("Laddu", "10100", "Venky"),
            listOf("Stage support", "10000", ""),
        ),
        "contacts" to listOf(
            listOf("Name", "Number", "Who calls", "Status", "Remarks", "", "Name", "Total"),
            listOf("Person A", "8978713813", "Venky", "1", ""),
            listOf("Person B", "9966403683", "bhanu", "0", "busy"),
            listOf("Person C", "", "Laxman", "TRUE"),
        ),
        "Summary" to listOf(listOf("Category", "Amount", "Collected"), listOf("Youth", "14143", "14143")),
        "T-Shirts" to listOf(listOf("SL No", "Name", "Size"), listOf("1", "Someone", "xl")),
    )

    @Test fun `every known sheet is read and unrelated sheets are ignored`() {
        val b = ImportMapper.parse(book)
        assertEquals(listOf("Summary", "T-Shirts"), b.ignoredSheets)
        val byLabel = b.sections.associateBy { it.label.substringBefore(" →") }
        assertEquals(3, byLabel.getValue("Youth Donations").rows)
        assertEquals(2_617_00L, byLabel.getValue("Youth Donations").total)
        assertEquals(2, byLabel.getValue("Villagers Donations").rows)           // Total row skipped
        assertEquals(1_500_000L, byLabel.getValue("Villagers Donations").total)
        assertEquals(2, byLabel.getValue("Pooja Expenditure").rows)             // Total row skipped
        assertEquals(986_450L, byLabel.getValue("Pooja Expenditure").total)
        assertEquals(3, b.contacts.size)
        assertEquals(0, b.skippedRows)
    }

    @Test fun `donations map to categories with collector, detail and in-kind gifts`() {
        val e = ImportMapper.parse(book).entries
        val villager = e.first { it.name == "Village B" }
        assertEquals(EntryType.DONATION, villager.type); assertEquals("Villagers", villager.category); assertEquals("", villager.person)
        val kid = e.first { it.name == "Kid One" }
        assertEquals("Children", kid.category); assertEquals("s/o Ramesh · Odhipadu", kid.description); assertEquals("Bhanu", kid.person)
        assertEquals("Other villagers", e.first { it.name == "Visitor" }.category)
        assertEquals(20150L, e.first { it.name == "Visitor" }.amount)
        val inKind = e.first { it.name == "Gift One" }
        assertEquals(0L, inKind.amount); assertEquals("Aakulu", inKind.description)
        assertEquals("Annadhanam", inKind.category)
    }

    @Test fun `people names keep one spelling across sheets`() {
        val people = ImportMapper.parse(book).entries.map { it.person }.filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf("Bhanu", "Laxman", "Simhadri", "Venky"), people)
    }

    @Test fun `expenditure keeps dates, quantity and who paid, and ignores the side tables`() {
        val e = ImportMapper.parse(book).entries.filter { it.type == EntryType.EXPENDITURE }
        assertEquals(3, e.size)
        val vim = e.first { it.name == "Vim" }
        assertEquals("2026-09-14", vim.date); assertEquals("Pooja", vim.category); assertEquals(1450L, vim.amount); assertEquals("2", vim.description); assertEquals("Laxman", vim.person)
        assertEquals(null, e.first { it.name == "Kirana" }.date)               // no date column in that sheet
        assertEquals("Annadhanam", e.first { it.name == "Kirana" }.category)
    }

    @Test fun `contacts read name, phone, caller and called status`() {
        val c = ImportMapper.parse(book).contacts
        assertEquals(listOf("Person A", "Person B", "Person C"), c.map { it.name })
        assertEquals("8978713813", c[0].number); assertTrue(c[0].called); assertEquals("Venky", c[0].caller)
        assertEquals("Bhanu", c[1].caller); assertEquals(false, c[1].called); assertEquals("busy", c[1].remarks)
        assertTrue(c[2].called)
    }

    @Test fun `undated rows get the chosen date and a re-import adds nothing`() {
        val b = ImportMapper.parse(book)
        assertEquals("2026-08-30", b.earliestDate)
        val first = ImportMapper.plan(b, "2026-09-01", 2026, emptyList(), emptyList(), "me", 1L)
        assertEquals(b.entries.size, first.entries.size)
        assertTrue(first.entries.filter { it.name == "Donor One" }.all { it.date == "2026-09-01" && it.year == 2026 })
        assertEquals("2026-09-14", first.entries.first { it.name == "Vim" }.date)
        assertEquals(3, first.contacts.size)
        assertTrue(first.contacts.all { it.year == 2026 && it.id.endsWith(":2026") })
        val again = ImportMapper.plan(b, "2026-09-01", 2026, first.entries.map { it.synced() }, first.contacts.map { it.synced() }, "me", 2L)
        assertEquals(0, again.entries.size); assertEquals(0, again.contacts.size)
        assertEquals(b.entries.size, again.duplicateEntries); assertEquals(3, again.duplicateContacts)
    }

    @Test fun `a contact already known from an earlier year keeps one id for the new year`() {
        val b = ImportMapper.parse(book)
        val last = ImportMapper.plan(b, "2025-09-01", 2025, emptyList(), emptyList(), "me", 1L).contacts.map { it.synced() }
        val thisYear = ImportMapper.plan(b, "2026-09-01", 2026, emptyList(), last, "me", 2L).contacts
        assertEquals(3, thisYear.size)
        assertEquals(last.map { it.contactId }.toSet(), thisYear.map { it.contactId }.toSet())
    }

    @Test fun `identical rows in the file both survive`() {
        val twice = workbook("Youth Donations" to listOf(listOf("Name", "Amount", "Collected By"), listOf("Same", "100", "Bhanu"), listOf("Same", "100", "Bhanu")))
        val b = ImportMapper.parse(twice)
        assertEquals(2, ImportMapper.plan(b, "2026-09-01", 2026, emptyList(), emptyList(), "me", 1L).entries.size)
    }

    @Test fun `csv exported by the app imports back unchanged`() {
        val e = Entry(
            id = "1", type = EntryType.DONATION, year = 2026, date = "2026-09-14", category = "Youth", name = "Ravi, K", description = "Main \"street\"",
            amount = 150050, person = "Bhanu", createdBy = "x", createdAt = 1, updatedBy = "x", updatedAt = 1,
        )
        val csv = Exporter.entriesCsv(listOf(e))
        val back = ImportMapper.parse(listOf(SheetData("export", Csv.parse(csv)))).entries.single()
        assertEquals(EntryType.DONATION, back.type); assertEquals("2026-09-14", back.date); assertEquals("Youth", back.category)
        assertEquals("Ravi, K", back.name); assertEquals("Main \"street\"", back.description)
        assertEquals(150050L, back.amount); assertEquals("Bhanu", back.person)
    }

    @Test fun `csv parsing handles quotes semicolons and a byte order mark`() {
        val rows = Csv.parse("﻿a;b;c\r\n\"x;y\";\"he said \"\"hi\"\"\";3\r\n")
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("x;y", "he said \"hi\"", "3"), rows[1])
        assertEquals("a,\"b,c\"\r\n", Csv.write(listOf(listOf("a", "b,c"))))
    }
}
