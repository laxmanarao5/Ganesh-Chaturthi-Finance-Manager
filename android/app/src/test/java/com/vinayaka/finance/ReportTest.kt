package com.vinayaka.finance

import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.report.GoogleReportBody
import com.vinayaka.finance.report.Money
import com.vinayaka.finance.report.Report
import com.vinayaka.finance.report.ReportBuilder
import com.vinayaka.finance.report.ReportLang
import com.vinayaka.finance.report.ReportSheet
import com.vinayaka.finance.report.RowStyle
import com.vinayaka.finance.report.XlsxWriter
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

private var seq = 0
private fun e(type: EntryType, category: String, name: String, amount: Long, person: String = "Bhanu", date: String = "2026-09-14", description: String = "") = Entry(
    id = "r${seq++}", type = type, year = 2026, date = date, category = category, name = name, description = description, amount = amount, person = person,
    createdBy = "x", createdAt = seq.toLong(), updatedBy = "x", updatedAt = 1,
)

/** Made-up committee data: the real names and numbers never go in this repository. */
class ReportTest {
    private val data = listOf(
        e(EntryType.DONATION, "Youth", "Donor A", 50_000), e(EntryType.DONATION, "Youth", "Donor B", 111_600),
        e(EntryType.DONATION, "Villagers", "Donor C", 500_000), e(EntryType.DONATION, "Villagers", "Donor D", 100_000, person = ""),
        e(EntryType.DONATION, "Children", "Kid A", 10_000, description = "s/o Parent, Village X"),
        e(EntryType.DONATION, "Other villagers", "Visitor", 20_150, description = "Village Y"),
        e(EntryType.DONATION, "Annadhanam", "Gift in kind", 0, person = "", description = "Rice bag"),
        e(EntryType.DONATION, "Annadhanam", "Donor E", 160_000),
        e(EntryType.OTHER, "", "Laddu auction", 1_010_000), e(EntryType.OTHER, "", "Dhakshina", 115_500, date = "2026-09-15"),
        e(EntryType.EXPENDITURE, "Pooja", "Flowers", 245_285), e(EntryType.EXPENDITURE, "Pooja", "Kirana returned", -161_000),
        e(EntryType.EXPENDITURE, "Annadhanam", "Vegetables", 100_000),
        e(EntryType.DONATION, "Youth", "Deleted donor", 999_999).copy(deletedAt = 5),
    )

    private val telugu = ReportBuilder.build(data, 2026, ReportLang.TELUGU)
    private fun Report.sheet(name: String) = sheets.first { it.name == name }
    private fun ReportSheet.texts() = rows.flatMap { it.cells }.filterIsInstance<String>()
    private fun ReportSheet.moneys() = rows.flatMap { it.cells }.filterIsInstance<Money>().map { it.paise }

    @Test fun `has the same tabs as the committee workbook, ABSTRACT opens first`() {
        assertEquals(
            listOf("Youth Donations", "Villagers Donations", "Children Donations", "OUTSIDERS DONATIONS", "Dakshina", "Annadanam Donation", "Annadanam Expenditure", "Expenditure", "ABSTRACT"),
            telugu.sheets.map { it.name },
        )
        assertEquals(8, telugu.abstractIndex)
    }

    @Test fun `donation tabs list donors with a numbered total and leave deleted rows out`() {
        val youth = telugu.sheet("Youth Donations")
        assertEquals("యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు - 2026", youth.rows[0].cells[0])
        assertEquals(listOf("క్రమ సంక్య", "ధాత వివరములు", "మొత్తము రూపాయలలో"), youth.rows[1].cells)
        assertEquals(listOf<Any?>(1, "Donor A", Money(50_000)), youth.rows[2].cells)
        assertEquals(listOf<Any?>(2, "Donor B", Money(111_600)), youth.rows[3].cells)
        val total = youth.rows.last()
        assertEquals(RowStyle.TOTAL, total.style)
        assertEquals(Money(161_600), total.cells.last())
        assertFalse(youth.texts().contains("Deleted donor"))
    }

    @Test fun `a gift in kind shows its item and no amount`() {
        val ann = telugu.sheet("Annadanam Donation")
        assertEquals(listOf<Any?>(1, "Gift in kind", "Rice bag", null), ann.rows[2].cells)
        assertEquals(Money(160_000), ann.rows.last().cells.last())
    }

    @Test fun `dakshina is its own tab and not an auction line`() {
        val d = telugu.sheet("Dakshina")
        assertEquals(listOf<Any?>(1, "15-09-2026", Money(115_500)), d.rows[2].cells)
        val abstract = telugu.sheet("ABSTRACT")
        assertTrue(abstract.texts().contains("Laddu auction"))
        assertFalse(abstract.texts().contains("Dhakshina"))
        assertTrue(abstract.texts().contains("దక్షిణ"))
    }

    @Test fun `other collections list dakshina first, then every other input, then the annadhanam surplus`() {
        val more = data + e(EntryType.OTHER, "", "Panche auction", 280_000, description = "cloth")
        val a = ReportBuilder.build(more, 2026, ReportLang.ENGLISH).sheet("ABSTRACT")
        val start = a.rows.indexOfFirst { it.cells.getOrNull(1) == "Other income" }
        val labels = a.rows.drop(start + 1).takeWhile { it.style == RowStyle.BODY }.map { it.cells[1] }
        assertEquals(listOf<Any?>("Dakshina", "Laddu auction", "Panche auction (cloth)", "Annadhanam donations minus Annadhanam expenses"), labels)
    }

    @Test fun `dakshina is also found by its Telugu name`() {
        val r = ReportBuilder.build(listOf(e(EntryType.OTHER, "", " దక్షిణ ", 5_000)), 2026, ReportLang.ENGLISH)
        assertEquals(listOf<Any?>(1, "14-09-2026", Money(5_000)), r.sheet("Dakshina").rows[2].cells)
    }

    @Test fun `a returned purchase is negative and reduces the expense`() {
        val pooja = telugu.sheet("Expenditure")
        assertEquals(Money(245_285 - 161_000), pooja.rows.last().cells.last())
        assertTrue(pooja.moneys().contains(-161_000L))
        assertEquals(245_285L - 161_000L, telugu.expenses)
    }

    @Test fun `abstract adds up like the workbook`() {
        val a = telugu.sheet("ABSTRACT")
        // block 1: youth, villagers (listed amounts count even without a collector), children, outsiders
        val block1 = 161_600L + 600_000L + 10_000L + 20_150L
        assertTrue(a.moneys().contains(block1))
        // annadhanam: donations 160,000 minus expenses 100,000 = surplus 60,000
        assertTrue(a.moneys().containsAll(listOf(160_000L, 100_000L, 60_000L)))
        // other collections: auction + dakshina + annadhanam surplus
        val block2 = 1_010_000L + 115_500L + 60_000L
        assertTrue(a.moneys().contains(block2))
        assertEquals(block1 + block2, telugu.collections)
        assertTrue(a.moneys().contains(block1 + block2))
        assertEquals(block1 + block2 - (245_285L - 161_000L), telugu.surplus)
        assertEquals(Money(telugu.surplus), a.rows.first { it.cells.getOrNull(1) == "ప్రస్తుత సంవత్సర మిగులు - 2026" }.cells[2])
    }

    @Test fun `a note says how much has no collector yet`() {
        val a = telugu.sheet("ABSTRACT")
        val note = a.rows.last()
        assertEquals(RowStyle.NOTE, note.style)
        assertTrue((note.cells[0] as String).contains("₹1,000"))
        val without = ReportBuilder.build(data.filter { it.person.isNotBlank() || it.amount == 0L }, 2026, ReportLang.TELUGU).sheet("ABSTRACT")
        assertTrue(without.rows.none { it.style == RowStyle.NOTE })
    }

    @Test fun `english report has english headings and the same numbers`() {
        val en = ReportBuilder.build(data, 2026, ReportLang.ENGLISH)
        assertEquals("Donations from youth association members - 2026", en.sheet("Youth Donations").rows[0].cells[0])
        assertEquals(telugu.collections, en.collections)
        assertEquals(telugu.expenses, en.expenses)
        assertTrue(en.sheet("ABSTRACT").texts().contains("Total of all collections"))
    }

    @Test fun `an empty year still produces every tab with zero totals`() {
        val empty = ReportBuilder.build(emptyList(), 2027, ReportLang.ENGLISH)
        assertEquals(9, empty.sheets.size)
        assertEquals(0L, empty.collections)
        assertEquals(0L, empty.surplus)
        assertEquals(Money(0), empty.sheet("Youth Donations").rows.last().cells.last())
    }

    @Test fun `other categories are not lost`() {
        val r = ReportBuilder.build(data + e(EntryType.DONATION, "Special", "Patron", 300_000) + e(EntryType.EXPENDITURE, "Tent", "Tent hire", 40_000), 2026, ReportLang.ENGLISH)
        val a = r.sheet("ABSTRACT")
        assertTrue(a.texts().contains("Special"))
        assertTrue(a.texts().contains("Tent"))
        assertEquals(telugu.collections + 300_000, r.collections)
        assertEquals(telugu.expenses + 40_000, r.expenses)
    }

    // ------------------------------------------------------------------------------------------------- Excel
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test fun `excel file is a valid package with every tab, Telugu text and real numbers`() {
        val files = unzip(XlsxWriter.write(telugu.sheets, telugu.abstractIndex))
        assertNotNull(files["[Content_Types].xml"]); assertNotNull(files["_rels/.rels"]); assertNotNull(files["xl/styles.xml"])
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, xml) -> parse(xml).also { assertNotNull(name, it) } } // every part is well-formed XML
        val workbook = files.getValue("xl/workbook.xml")
        telugu.sheets.forEach { assertTrue(it.name, workbook.contains("name=\"${it.name}\"")) }
        assertTrue(workbook.contains("activeTab=\"8\""))
        val youth = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(youth.contains("యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు - 2026"))
        assertTrue(youth.contains("<v>501</v>") || youth.contains("<v>500</v>")) // Donor A 500.00 as a number
        assertTrue(youth.contains("<mergeCell ref=\"A1:C1\"/>"))
        assertTrue(files.getValue("xl/worksheets/sheet8.xml").contains("<v>-1610</v>"))
        assertFalse(youth.contains("Deleted donor"))
    }

    @Test fun `excel text is escaped and control characters cannot corrupt the file`() {
        val sheet = ReportSheet("Tricky & <Sheet>", listOf(com.vinayaka.finance.report.ReportRow(listOf("a < b & \"c\" \u0001 end", Money(150))), com.vinayaka.finance.report.ReportRow(listOf(1, Money(-5)))), listOf(20, 10))
        val files = unzip(XlsxWriter.write(listOf(sheet)))
        parse(files.getValue("xl/worksheets/sheet1.xml"))
        parse(files.getValue("xl/workbook.xml"))
        assertTrue(files.getValue("xl/workbook.xml").contains("Tricky &amp; &lt;Sheet&gt;"))
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").contains("a &lt; b &amp; &quot;c&quot;  end"))
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").contains("<v>1.5</v>"))
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").contains("<v>-0.05</v>"))
    }

    // ------------------------------------------------------------------------------------------------- Google Sheet
    @Test fun `google sheet body mirrors the tabs, values, merges and widths`() {
        val body = GoogleReportBody.create(telugu)
        assertEquals("Vinayaka Finance report 2026", body["properties"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        val sheets = body["sheets"]!!.jsonArray
        assertEquals(9, sheets.size)
        val youth = sheets[0].jsonObject
        assertEquals("Youth Donations", youth["properties"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        val data = youth["data"]!!.jsonArray[0].jsonObject
        val rows = data["rowData"]!!.jsonArray
        assertEquals(telugu.sheet("Youth Donations").rows.size, rows.size)
        val firstDonor = rows[2].jsonObject["values"]!!.jsonArray
        assertEquals(1.0, firstDonor[0].jsonObject["userEnteredValue"]!!.jsonObject["numberValue"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("Donor A", firstDonor[1].jsonObject["userEnteredValue"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals(500.0, firstDonor[2].jsonObject["userEnteredValue"]!!.jsonObject["numberValue"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("NUMBER", firstDonor[2].jsonObject["userEnteredFormat"]!!.jsonObject["numberFormat"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(3, data["columnMetadata"]!!.jsonArray.size)
        val merge = youth["merges"]!!.jsonArray[0].jsonObject
        assertEquals("0", merge["startRowIndex"]!!.jsonPrimitive.content)
        assertEquals("3", merge["endColumnIndex"]!!.jsonPrimitive.content)
        assertEquals(0, youth["properties"]!!.jsonObject["sheetId"]!!.jsonPrimitive.content.toInt())
        val inKind = sheets[5].jsonObject["data"]!!.jsonArray[0].jsonObject["rowData"]!!.jsonArray[2].jsonObject["values"]!!.jsonArray
        assertNull(inKind[3].jsonObject["userEnteredValue"]) // no amount for a gift in kind
    }
}
