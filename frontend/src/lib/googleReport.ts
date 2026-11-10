// Publishes the report as a brand-new Google Spreadsheet (same tabs, values and look as the Excel file). Port of the app's GoogleReport.kt.
import { makeHttp } from './googleSheets'
import { coveredByMerges, isMoney, reportGoogleTitle, type Report, type ReportCell, type ReportRow } from './report'

export interface CreatedReport { id: string; url: string }

/** Where a finished report is published: Google itself, or a stand-in for the tests. */
export interface ReportPublisher { create(report: Report): Promise<CreatedReport> }

type Json = Record<string, unknown>
const color = (red: number, green: number, blue: number) => ({ red, green, blue })
const HEADER = color(1, 0.863, 0.682)
const TITLE = color(1, 0.78, 0.541)
const TOTAL = color(0.984, 0.922, 0.875)
const side = { style: 'SOLID' }
const borders = { top: side, bottom: side, left: side, right: side }
const numberFormat = { type: 'NUMBER', pattern: '#,##0.##' }

/** The look of one cell, or null for no formatting. */
function format(row: ReportRow, cell: ReportCell | undefined): Json | null {
  switch (row.style) {
    case 'TITLE': return { backgroundColor: TITLE, textFormat: { bold: true, fontSize: 14 }, horizontalAlignment: 'CENTER', verticalAlignment: 'MIDDLE', wrapStrategy: 'WRAP', borders }
    case 'HEADER': return { backgroundColor: HEADER, textFormat: { bold: true }, horizontalAlignment: 'CENTER', verticalAlignment: 'MIDDLE', wrapStrategy: 'WRAP', borders }
    case 'SECTION': return { textFormat: { bold: true } }
    case 'NOTE': return { wrapStrategy: 'WRAP', verticalAlignment: 'TOP' }
    case 'TOTAL': return {
      backgroundColor: TOTAL, textFormat: { bold: true }, borders, verticalAlignment: 'MIDDLE',
      ...(isMoney(cell) ? { numberFormat, horizontalAlignment: 'RIGHT' } : { wrapStrategy: 'WRAP' }),
    }
    default:
      if (cell === null || cell === undefined) return null
      return { borders, verticalAlignment: 'MIDDLE', ...(isMoney(cell) ? { numberFormat, horizontalAlignment: 'RIGHT' } : typeof cell === 'number' ? { horizontalAlignment: 'CENTER' } : { wrapStrategy: 'WRAP' }) }
  }
}

function cellJson(row: ReportRow, cell: ReportCell | undefined, covered: boolean): Json {
  const out: Json = {}
  if (cell === null || cell === undefined) { /* empty */ }
  else if (isMoney(cell)) out.userEnteredValue = { numberValue: cell.paise / 100 }
  else if (typeof cell === 'number') out.userEnteredValue = { numberValue: cell }
  else out.userEnteredValue = { stringValue: cell }
  const fmt = (cell === null || cell === undefined) && !(covered && row.style !== 'BODY') ? null : format(row, cell)
  if (fmt) out.userEnteredFormat = fmt
  return out
}

/** The body for `POST /v4/spreadsheets`. */
export function googleReportBody(report: Report): Json {
  return {
    properties: { title: reportGoogleTitle(report) },
    sheets: report.sheets.map((sheet, index) => {
      const covered = coveredByMerges(sheet)
      const columns = Math.max(sheet.widths.length, ...sheet.rows.map((r) => r.cells.length))
      return {
        properties: {
          sheetId: index, title: sheet.name, index,
          gridProperties: { rowCount: Math.max(sheet.rows.length + 2, 20), columnCount: Math.max(columns, 6), frozenRowCount: 2 },
        },
        data: [{
          startRow: 0, startColumn: 0,
          rowData: sheet.rows.map((row, r) => {
            let coveredWidth = 0
            for (const key of covered) { const [cr, cc] = key.split(',').map(Number); if (cr === r) coveredWidth = Math.max(coveredWidth, cc + 1) }
            const width = Math.max(row.cells.length, coveredWidth)
            return { values: Array.from({ length: width }, (_, c) => cellJson(row, row.cells[c], covered.has(`${r},${c}`))) }
          }),
          columnMetadata: sheet.widths.map((w) => ({ pixelSize: w * 8 + 12 })),
        }],
        ...(sheet.merges.length ? {
          merges: sheet.merges.map((m) => ({ sheetId: index, startRowIndex: m.row, endRowIndex: m.lastRow + 1, startColumnIndex: m.col, endColumnIndex: m.lastCol + 1 })),
        } : {}),
      }
    }),
  }
}

/** Creates the report as a new Google Spreadsheet owned by the signed-in account (needs a token with the Sheets scope). */
export class GoogleReports implements ReportPublisher {
  private call: ReturnType<typeof makeHttp>
  constructor(token: (force: boolean) => Promise<string>, fetchImpl?: typeof fetch) { this.call = makeHttp(token, fetchImpl) }

  async create(report: Report): Promise<CreatedReport> {
    const res = await this.call('POST', 'https://sheets.googleapis.com/v4/spreadsheets', googleReportBody(report))
    const id = res.spreadsheetId as string | undefined
    if (!id) throw new Error("Google did not return the new Sheet's id.")
    return { id, url: (res.spreadsheetUrl as string | undefined) ?? `https://docs.google.com/spreadsheets/d/${id}/edit` }
  }
}

export class FakeReportPublisher implements ReportPublisher {
  created: Report[] = []
  async create(report: Report): Promise<CreatedReport> {
    this.created.push(report)
    return { id: `report-${this.created.length}`, url: `https://docs.google.com/spreadsheets/d/report-${this.created.length}/edit` }
  }
}
