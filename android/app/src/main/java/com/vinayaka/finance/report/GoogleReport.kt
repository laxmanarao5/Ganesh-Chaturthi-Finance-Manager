package com.vinayaka.finance.report

import com.vinayaka.finance.data.sheets.GoogleHttp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal

data class CreatedReport(val id: String, val url: String)

/** What the report looks like as a brand-new Google Spreadsheet: the same tabs, values and look as the Excel file. */
object GoogleReportBody {
    private fun color(r: Double, g: Double, b: Double): JsonObject = buildJsonObject { put("red", r); put("green", g); put("blue", b) }
    private val HEADER = color(1.0, 0.863, 0.682)
    private val TITLE = color(1.0, 0.78, 0.541)
    private val TOTAL = color(0.984, 0.922, 0.875)

    private fun borders(): JsonObject = buildJsonObject {
        listOf("top", "bottom", "left", "right").forEach { side -> putJsonObject(side) { put("style", "SOLID") } }
    }

    private fun rupees(paise: Long) = BigDecimal(paise).movePointLeft(2).toDouble()

    /** The look of one cell, or null for no formatting. */
    private fun format(row: ReportRow, cell: Any?): JsonObject? = when (row.style) {
        RowStyle.TITLE -> buildJsonObject {
            put("backgroundColor", TITLE); putJsonObject("textFormat") { put("bold", true); put("fontSize", 14) }
            put("horizontalAlignment", "CENTER"); put("verticalAlignment", "MIDDLE"); put("wrapStrategy", "WRAP"); put("borders", borders())
        }
        RowStyle.HEADER -> buildJsonObject {
            put("backgroundColor", HEADER); putJsonObject("textFormat") { put("bold", true) }
            put("horizontalAlignment", "CENTER"); put("verticalAlignment", "MIDDLE"); put("wrapStrategy", "WRAP"); put("borders", borders())
        }
        RowStyle.SECTION -> buildJsonObject { putJsonObject("textFormat") { put("bold", true) } }
        RowStyle.NOTE -> buildJsonObject { put("wrapStrategy", "WRAP"); put("verticalAlignment", "TOP") }
        RowStyle.TOTAL -> buildJsonObject {
            put("backgroundColor", TOTAL); putJsonObject("textFormat") { put("bold", true) }; put("borders", borders()); put("verticalAlignment", "MIDDLE")
            if (cell is Money) { putJsonObject("numberFormat") { put("type", "NUMBER"); put("pattern", "#,##0.##") }; put("horizontalAlignment", "RIGHT") } else put("wrapStrategy", "WRAP")
        }
        RowStyle.BODY -> if (cell == null) null else buildJsonObject {
            put("borders", borders()); put("verticalAlignment", "MIDDLE")
            when (cell) {
                is Money -> { putJsonObject("numberFormat") { put("type", "NUMBER"); put("pattern", "#,##0.##") }; put("horizontalAlignment", "RIGHT") }
                is Int -> put("horizontalAlignment", "CENTER")
                else -> put("wrapStrategy", "WRAP")
            }
        }
    }

    private fun cellJson(row: ReportRow, cell: Any?, covered: Boolean): JsonObject = buildJsonObject {
        when (cell) {
            null -> Unit
            is Money -> putJsonObject("userEnteredValue") { put("numberValue", rupees(cell.paise)) }
            is Int -> putJsonObject("userEnteredValue") { put("numberValue", cell) }
            else -> putJsonObject("userEnteredValue") { put("stringValue", cell.toString()) }
        }
        val fmt = if (cell == null && !(covered && row.style != RowStyle.BODY)) null else format(row, cell)
        if (fmt != null) put("userEnteredFormat", fmt)
    }

    /** The body for `POST /v4/spreadsheets`. */
    fun create(report: Report): JsonObject = buildJsonObject {
        putJsonObject("properties") { put("title", report.googleTitle) }
        putJsonArray("sheets") {
            report.sheets.forEachIndexed { index, sheet ->
                val covered = sheet.coveredByMerges()
                val columns = maxOf(sheet.widths.size, sheet.rows.maxOfOrNull { it.cells.size } ?: 0)
                add(buildJsonObject {
                    putJsonObject("properties") {
                        put("sheetId", index); put("title", sheet.name); put("index", index)
                        putJsonObject("gridProperties") {
                            put("rowCount", maxOf(sheet.rows.size + 2, 20)); put("columnCount", maxOf(columns, 6)); put("frozenRowCount", 2)
                        }
                    }
                    putJsonArray("data") {
                        add(buildJsonObject {
                            put("startRow", 0); put("startColumn", 0)
                            putJsonArray("rowData") {
                                sheet.rows.forEachIndexed { r, row ->
                                    add(buildJsonObject {
                                        putJsonArray("values") {
                                            val width = maxOf(row.cells.size, covered.filter { it.first == r }.maxOfOrNull { it.second + 1 } ?: 0)
                                            for (c in 0 until width) add(cellJson(row, row.cells.getOrNull(c), (r to c) in covered))
                                        }
                                    })
                                }
                            }
                            putJsonArray("columnMetadata") { sheet.widths.forEach { w -> add(buildJsonObject { put("pixelSize", w * 8 + 12) }) } }
                        })
                    }
                    if (sheet.merges.isNotEmpty()) putJsonArray("merges") {
                        sheet.merges.forEach { m ->
                            add(buildJsonObject {
                                put("sheetId", index)
                                put("startRowIndex", m.row); put("endRowIndex", m.lastRow + 1)
                                put("startColumnIndex", m.col); put("endColumnIndex", m.lastCol + 1)
                            })
                        }
                    }
                })
            }
        }
    }
}

/** Where a finished report is published as a Google Sheet: Google itself, or a stand-in for demo mode and tests. */
interface ReportPublisher {
    suspend fun create(report: Report): CreatedReport
}

class FakeReportPublisher : ReportPublisher {
    val created = mutableListOf<Report>()
    override suspend fun create(report: Report): CreatedReport {
        created += report
        return CreatedReport("demo-report-${created.size}", "https://docs.google.com/spreadsheets/d/demo-report-${created.size}/edit")
    }
}

/** Creates the report as a new Google Spreadsheet owned by the signed-in account (needs a token with the Sheets scope). */
class GoogleReports(private val http: GoogleHttp) : ReportPublisher {
    override suspend fun create(report: Report): CreatedReport {
        val res = http.call("POST", "https://sheets.googleapis.com/v4/spreadsheets", GoogleReportBody.create(report))
        val id = res["spreadsheetId"]?.jsonPrimitive?.content ?: error("Google did not return the new Sheet's id.")
        val url = res["spreadsheetUrl"]?.jsonPrimitive?.content ?: "https://docs.google.com/spreadsheets/d/$id/edit"
        return CreatedReport(id, url)
    }
}
