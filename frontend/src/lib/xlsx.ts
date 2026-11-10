// Writes the report as an Excel workbook (.xlsx) with no library: a tiny "stored" zip writer plus the SpreadsheetML parts.
// Values only (no formulas); text is stored inline and amounts are real numbers, so they add up in Excel. Port of the app's XlsxWriter.kt.
import { coveredByMerges, isMoney, type ReportCell, type ReportRow, type ReportSheet } from './report'

export const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

// ---------------------------------------------------------------------------------------------------------- zip (no compression)
const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

export function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

export function zipStore(files: { name: string; data: Uint8Array }[]): Uint8Array<ArrayBuffer> {
  const enc = new TextEncoder()
  const chunks: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const f of files) {
    const name = enc.encode(f.name)
    const crc = crc32(f.data)
    const local = new Uint8Array(30 + name.length)
    const lv = new DataView(local.buffer)
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, 0x0800, true) // UTF-8 names
    lv.setUint16(8, 0, true); lv.setUint16(10, 0, true); lv.setUint16(12, 0x21, true)            // stored, 1980-01-01 00:00
    lv.setUint32(14, crc, true); lv.setUint32(18, f.data.length, true); lv.setUint32(22, f.data.length, true)
    lv.setUint16(26, name.length, true); lv.setUint16(28, 0, true)
    local.set(name, 30)
    chunks.push(local, f.data)
    const cen = new Uint8Array(46 + name.length)
    const cv = new DataView(cen.buffer)
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint16(8, 0x0800, true)
    cv.setUint16(10, 0, true); cv.setUint16(12, 0, true); cv.setUint16(14, 0x21, true)
    cv.setUint32(16, crc, true); cv.setUint32(20, f.data.length, true); cv.setUint32(24, f.data.length, true)
    cv.setUint16(28, name.length, true); cv.setUint32(42, offset, true)
    cen.set(name, 46)
    central.push(cen)
    offset += local.length + f.data.length
  }
  const centralSize = central.reduce((a, c) => a + c.length, 0)
  const end = new Uint8Array(22)
  const ev = new DataView(end.buffer)
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true)
  ev.setUint32(12, centralSize, true); ev.setUint32(16, offset, true)
  const all = [...chunks, ...central, end]
  const out = new Uint8Array(new ArrayBuffer(all.reduce((a, c) => a + c.length, 0)))
  let p = 0
  for (const c of all) { out.set(c, p); p += c.length }
  return out
}

// ---------------------------------------------------------------------------------------------------------- SpreadsheetML
const NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

const S = { TITLE: 1, HEADER: 2, TEXT: 3, MONEY: 4, INT: 5, TOTAL_TEXT: 6, TOTAL_MONEY: 7, NOTE: 8, SECTION: 9 }

/** Characters XML cannot carry are dropped; the rest is escaped. */
export function xmlEscape(s: string): string {
  let out = ''
  for (const ch of s) {
    const code = ch.codePointAt(0)!
    if (ch === '&') out += '&amp;'
    else if (ch === '<') out += '&lt;'
    else if (ch === '>') out += '&gt;'
    else if (ch === '"') out += '&quot;'
    else if (ch === '\n' || ch === '\r' || ch === '\t') out += ch
    else if (code < 0x20 || code === 0xfffe || code === 0xffff) continue
    else out += ch
  }
  return out
}

export function columnName(index: number): string {
  let n = index
  let s = ''
  do { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1 } while (n >= 0)
  return s
}

/** Paise as the plain number written in a cell: 161000 -> "1610", -5 -> "-0.05". */
export const rupeesNumber = (paise: number): string => String(Number((paise / 100).toFixed(2)))

function styleFor(row: ReportRow, cell: ReportCell | undefined): number {
  switch (row.style) {
    case 'TITLE': return S.TITLE
    case 'HEADER': return S.HEADER
    case 'SECTION': return S.SECTION
    case 'NOTE': return S.NOTE
    case 'TOTAL': return isMoney(cell) ? S.TOTAL_MONEY : S.TOTAL_TEXT
    default: return isMoney(cell) ? S.MONEY : typeof cell === 'number' ? S.INT : S.TEXT
  }
}

function sheetXml(sheet: ReportSheet, selected: boolean): string {
  const covered = coveredByMerges(sheet)
  let xml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="${NS}" xmlns:r="${REL}">`
  xml += `<sheetViews><sheetView workbookViewId="0"${selected ? ' tabSelected="1"' : ''}><pane ySplit="2" topLeftCell="A3" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>`
  xml += '<sheetFormatPr defaultRowHeight="15"/><cols>'
  sheet.widths.forEach((w, i) => { xml += `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>` })
  xml += '</cols><sheetData>'
  sheet.rows.forEach((row, r) => {
    if (row.cells.every((c) => c === null)) return
    xml += `<row r="${r + 1}">`
    let coveredWidth = 0
    for (const key of covered) { const [cr, cc] = key.split(',').map(Number); if (cr === r) coveredWidth = Math.max(coveredWidth, cc + 1) }
    const width = Math.max(row.cells.length, coveredWidth)
    for (let c = 0; c < width; c++) {
      const v = row.cells[c] ?? null
      const ref = `${columnName(c)}${r + 1}`
      const s = styleFor(row, v)
      if (v === null) { if (row.style !== 'BODY' && covered.has(`${r},${c}`)) xml += `<c r="${ref}" s="${s}"/>` } // a merged range needs its hidden cells styled too
      else if (isMoney(v)) xml += `<c r="${ref}" s="${s}"><v>${rupeesNumber(v.paise)}</v></c>`
      else if (typeof v === 'number') xml += `<c r="${ref}" s="${s}"><v>${v}</v></c>`
      else xml += `<c r="${ref}" s="${s}" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(v)}</t></is></c>`
    }
    xml += '</row>'
  })
  xml += '</sheetData>'
  if (sheet.merges.length) {
    xml += `<mergeCells count="${sheet.merges.length}">`
    for (const m of sheet.merges) xml += `<mergeCell ref="${columnName(m.col)}${m.row + 1}:${columnName(m.lastCol)}${m.lastRow + 1}"/>`
    xml += '</mergeCells>'
  }
  xml += '<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/><pageSetup orientation="portrait" fitToHeight="0"/></worksheet>'
  return xml
}

const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="${NS}">
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
</styleSheet>`

/** The package parts of the workbook as text, by file name (also used by the tests). */
export function xlsxParts(sheets: ReportSheet[], activeSheet = 0): Record<string, string> {
  if (sheets.length === 0) throw new Error('A workbook needs at least one sheet.')
  const parts: Record<string, string> = {}
  parts['[Content_Types].xml'] = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('') +
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>'
  parts['_rels/.rels'] = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>`
  parts['xl/workbook.xml'] = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="${NS}" xmlns:r="${REL}"><bookViews><workbookView activeTab="${activeSheet}"/></bookViews><sheets>` +
    sheets.map((s, i) => `<sheet name="${xmlEscape(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('') + '</sheets></workbook>'
  parts['xl/_rels/workbook.xml.rels'] = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${REL}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('') +
    `<Relationship Id="rId${sheets.length + 1}" Type="${REL}/styles" Target="styles.xml"/></Relationships>`
  parts['xl/styles.xml'] = STYLES
  sheets.forEach((s, i) => { parts[`xl/worksheets/sheet${i + 1}.xml`] = sheetXml(s, i === activeSheet) })
  return parts
}

export function writeXlsx(sheets: ReportSheet[], activeSheet = 0): Uint8Array<ArrayBuffer> {
  const enc = new TextEncoder()
  return zipStore(Object.entries(xlsxParts(sheets, activeSheet)).map(([name, text]) => ({ name, data: enc.encode(text) })))
}
