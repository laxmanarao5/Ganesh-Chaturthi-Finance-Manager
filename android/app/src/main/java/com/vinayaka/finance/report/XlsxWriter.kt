package com.vinayaka.finance.report

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * Writes the report as an Excel workbook (.xlsx) with nothing but the JDK: values only, no formulas, one tab per [ReportSheet].
 * Text is stored inline, amounts are real numbers (so they add up in Excel), and the tab with the ABSTRACT opens first.
 */
object XlsxWriter {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    // cellXfs indexes in styles.xml below
    private const val S_TITLE = 1
    private const val S_HEADER = 2
    private const val S_TEXT = 3
    private const val S_MONEY = 4
    private const val S_INT = 5
    private const val S_TOTAL_TEXT = 6
    private const val S_TOTAL_MONEY = 7
    private const val S_NOTE = 8
    private const val S_SECTION = 9

    /** Characters XML cannot carry are dropped; the rest is escaped. */
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(ch)
                ch.code < 0x20 || ch.code in 0xFFFE..0xFFFF -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun column(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }

    private fun rupees(paise: Long): String = BigDecimal(paise).movePointLeft(2).stripTrailingZeros().toPlainString()

    private fun styleFor(row: ReportRow, cell: Any?): Int = when (row.style) {
        RowStyle.TITLE -> S_TITLE
        RowStyle.HEADER -> S_HEADER
        RowStyle.SECTION -> S_SECTION
        RowStyle.NOTE -> S_NOTE
        RowStyle.TOTAL -> if (cell is Money) S_TOTAL_MONEY else S_TOTAL_TEXT
        RowStyle.BODY -> when (cell) { is Money -> S_MONEY; is Int -> S_INT; else -> S_TEXT }
    }

    private fun sheetXml(sheet: ReportSheet, selected: Boolean): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="$NS" xmlns:r="$REL">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"${if (selected) " tabSelected=\"1\"" else ""}><pane ySplit="2" topLeftCell="A3" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("""<sheetFormatPr defaultRowHeight="15"/>""")
        sb.append("<cols>")
        sheet.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        val covered = sheet.coveredByMerges()
        sheet.rows.forEachIndexed { r, row ->
            val cells = row.cells
            if (cells.all { it == null }) return@forEachIndexed
            sb.append("""<row r="${r + 1}">""")
            // A merged range needs its hidden cells styled too, or its border and fill break.
            val width = maxOf(cells.size, covered.filter { it.first == r }.maxOfOrNull { it.second + 1 } ?: 0)
            for (c in 0 until width) {
                val v = cells.getOrNull(c)
                val ref = "${column(c)}${r + 1}"
                val s = styleFor(row, v)
                when (v) {
                    null -> if (row.style != RowStyle.BODY && (r to c) in covered) sb.append("""<c r="$ref" s="$s"/>""")
                    is Money -> sb.append("""<c r="$ref" s="$s"><v>${rupees(v.paise)}</v></c>""")
                    is Int -> sb.append("""<c r="$ref" s="$s"><v>$v</v></c>""")
                    else -> sb.append("""<c r="$ref" s="$s" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (sheet.merges.isNotEmpty()) {
            sb.append("""<mergeCells count="${sheet.merges.size}">""")
            sheet.merges.forEach { sb.append("""<mergeCell ref="${column(it.col)}${it.row + 1}:${column(it.lastCol)}${it.lastRow + 1}"/>""") }
            sb.append("</mergeCells>")
        }
        sb.append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/>""")
        sb.append("""<pageSetup orientation="portrait" fitToHeight="0"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="$NS">
<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.##"/></numFmts>
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font></fonts>
<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFDCAE"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFC78A"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFBEBDF"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="10">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="0" fontId="1" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
<xf numFmtId="164" fontId="1" fillId="4" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    fun write(sheets: List<ReportSheet>, activeSheet: Int = 0): ByteArray {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet." }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$NS" xmlns:r="$REL">""")
                append("""<bookViews><workbookView activeTab="$activeSheet"/></bookViews><sheets>""")
                sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="$REL/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="$REL/styles" Target="styles.xml"/></Relationships>""")
            })
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s, i == activeSheet)) }
        }
        return bytes.toByteArray()
    }
}
