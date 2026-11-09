// Talks to one Google Spreadsheet through the Sheets v4 REST API straight from the browser (free; the signed-in person's own quota).
// Layout (identical to the Android app): one ledger tab per year ("2026"), one "Contacts" tab, one "Users" tab.
import { parseRole } from './access'
import type { AppUser, Contact, Entry } from './model'
import { ROLES, ROLE_KEYS } from './model'
import { ContactRows, EntryRows, isBlankRow, type Cell } from './rows'
import { SheetsError, type SheetsApi } from './sheetsApi'

export const CONTACTS_TAB = 'Contacts'
export const USERS_TAB = 'Users'

type Json = Record<string, unknown>

/** An authenticated JSON call with one token refresh on 401 and a few retries on 429/5xx. Shared with the Drive client. */
export function makeHttp(token: (force: boolean) => Promise<string>, fetchImpl: typeof fetch = (...a) => fetch(...a)) {
  return async function call(method: string, url: string, body?: unknown): Promise<Json> {
    let refreshed = false
    for (let attempt = 0; ; attempt++) {
      const res = await fetchImpl(url, {
        method,
        // After a 401 the forced refresh below has stored a fresh token, so asking again without force returns it.
        headers: { Authorization: `Bearer ${await token(false)}`, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        body: body !== undefined ? JSON.stringify(body) : undefined,
      })
      if (res.status === 401 && !refreshed) {
        refreshed = true
        await token(true)
        continue
      }
      if ((res.status === 429 || res.status >= 500) && attempt < 3) {
        await new Promise((r) => setTimeout(r, 400 * 2 ** attempt))
        continue
      }
      const text = await res.text()
      let json: Json = {}
      try { json = text ? (JSON.parse(text) as Json) : {} } catch { /* not JSON */ }
      if (!res.ok) {
        const msg = ((json.error as Json | undefined)?.message as string | undefined) ?? (text.slice(0, 200) || `HTTP ${res.status}`)
        throw new SheetsError(res.status, msg)
      }
      return json
    }
  }
}

interface TabSpec {
  headers: string[]
  deletedColumn: string
  systemStart: number
  dateColumn: number | null
  amountColumn: number | null
  checkboxColumn: number | null
  widths: number[]
}

const ENTRY_SPEC: TabSpec = { headers: EntryRows.HEADERS, deletedColumn: 'O', systemStart: 8, dateColumn: 0, amountColumn: 5, checkboxColumn: null, widths: [100, 100, 120, 200, 220, 90, 130, 130] }
const CONTACT_SPEC: TabSpec = { headers: ContactRows.HEADERS, deletedColumn: 'M', systemStart: 6, dateColumn: null, amountColumn: null, checkboxColumn: 4, widths: [200, 120, 110, 60, 70, 240] }

const color = (red: number, green: number, blue: number) => ({ red, green, blue })
const range = (sheetId: number, r0: number, r1: number | null, c0: number, c1: number) =>
  ({ sheetId, startRowIndex: r0, ...(r1 !== null ? { endRowIndex: r1 } : {}), startColumnIndex: c0, endColumnIndex: c1 })
const repeatCell = (sheetId: number, r0: number, r1: number | null, c0: number, c1: number, format: Json, fields: string) =>
  ({ repeatCell: { range: range(sheetId, r0, r1, c0, c1), cell: { userEnteredFormat: format }, fields } })

function formatRequests(sheetId: number, spec: TabSpec): Json[] {
  const cols = spec.headers.length
  const req: Json[] = [
    repeatCell(sheetId, 0, 1, 0, cols, { backgroundColor: color(1, 0.86, 0.68), textFormat: { bold: true } }, 'userEnteredFormat(backgroundColor,textFormat)'),
  ]
  if (spec.dateColumn !== null) req.push(repeatCell(sheetId, 1, null, spec.dateColumn, spec.dateColumn + 1, { numberFormat: { type: 'DATE', pattern: 'yyyy-mm-dd' } }, 'userEnteredFormat.numberFormat'))
  if (spec.amountColumn !== null) req.push(repeatCell(sheetId, 1, null, spec.amountColumn, spec.amountColumn + 1, { numberFormat: { type: 'NUMBER', pattern: '#,##0.##' } }, 'userEnteredFormat.numberFormat'))
  if (spec.checkboxColumn !== null) {
    req.push({ setDataValidation: { range: range(sheetId, 1, 2000, spec.checkboxColumn, spec.checkboxColumn + 1), rule: { condition: { type: 'BOOLEAN' }, strict: true } } })
  }
  req.push(repeatCell(sheetId, 1, null, spec.systemStart, cols, { textFormat: { foregroundColor: color(0.55, 0.55, 0.55) } }, 'userEnteredFormat.textFormat'))
  req.push({ setBasicFilter: { filter: { range: range(sheetId, 0, null, 0, cols) } } })
  req.push({ addDimensionGroup: { range: { sheetId, dimension: 'COLUMNS', startIndex: spec.systemStart, endIndex: cols } } })
  // deleted rows: grey + strikethrough
  req.push({
    addConditionalFormatRule: {
      rule: {
        ranges: [range(sheetId, 1, 2000, 0, cols)],
        booleanRule: {
          condition: { type: 'CUSTOM_FORMULA', values: [{ userEnteredValue: `=$${spec.deletedColumn}2<>""` }] },
          format: { textFormat: { strikethrough: true, foregroundColor: color(0.6, 0.6, 0.6) } },
        },
      },
      index: 0,
    },
  })
  spec.widths.forEach((px, i) => req.push({
    updateDimensionProperties: { range: { sheetId, dimension: 'COLUMNS', startIndex: i, endIndex: i + 1 }, properties: { pixelSize: px }, fields: 'pixelSize' },
  }))
  return req
}

export class GoogleSheets implements SheetsApi {
  private base: string
  private call: ReturnType<typeof makeHttp>
  private tabIds: Map<string, number> | null = null
  private formatted = new Set<string>()

  constructor(spreadsheetId: string, token: (force: boolean) => Promise<string>, fetchImpl?: typeof fetch) {
    this.base = `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheetId}`
    this.call = makeHttp(token, fetchImpl)
  }

  private enc = (s: string) => encodeURIComponent(s)

  private async loadTabs(): Promise<Map<string, number>> {
    const res = await this.call('GET', `${this.base}?fields=sheets.properties(sheetId,title)`)
    const map = new Map<string, number>()
    for (const s of (res.sheets as { properties: { sheetId: number; title: string } }[] | undefined) ?? []) map.set(s.properties.title, s.properties.sheetId)
    this.tabIds = map
    return map
  }

  private async tabs() { return this.tabIds ?? (await this.loadTabs()) }

  async listYears() {
    return [...(await this.loadTabs()).keys()].filter((t) => /^\d{4}$/.test(t)).map(Number).sort((a, b) => a - b)
  }

  private async readValues(rangeA1: string): Promise<Cell[][]> {
    const res = await this.call('GET', `${this.base}/values/${this.enc(rangeA1)}?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER`)
    return (res.values as Cell[][] | undefined) ?? []
  }

  private async readRecords<T>(title: string, lastColumn: string, parse: (cells: Cell[], row: number) => T | null): Promise<{ rows: T[]; skipped: number }> {
    if (!(await this.tabs()).has(title)) return { rows: [], skipped: 0 }
    const rows: T[] = []
    let skipped = 0
    ;(await this.readValues(`'${title}'!A2:${lastColumn}`)).forEach((cells, i) => {
      if (isBlankRow(cells)) return
      const parsed = parse(cells, i + 2)
      if (parsed === null) skipped++
      else rows.push(parsed)
    })
    return { rows, skipped }
  }

  async readYear(year: number) {
    const r = await this.readRecords(`${year}`, EntryRows.LAST_COLUMN, (cells, row) => EntryRows.fromCells(cells, row, year))
    return { entries: r.rows, skipped: r.skipped }
  }

  async readContacts() {
    const r = await this.readRecords(CONTACTS_TAB, ContactRows.LAST_COLUMN, (cells, row) => ContactRows.fromCells(cells, row))
    return { contacts: r.rows, skipped: r.skipped }
  }

  // -------------------------------------------------------------------------------------------- write
  private async ensureTab(title: string, spec: TabSpec) {
    const existed = (await this.tabs()).has(title)
    if (!existed) {
      const res = await this.call('POST', `${this.base}:batchUpdate`, {
        requests: [{ addSheet: { properties: { title, gridProperties: { frozenRowCount: 1 } } } }],
      })
      const id = ((res.replies as { addSheet: { properties: { sheetId: number } } }[])[0]).addSheet.properties.sheetId
      ;(await this.tabs()).set(title, id)
      await this.call('POST', `${this.base}/values/${this.enc(`'${title}'!A1`)}:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS`, { values: [spec.headers] })
      await this.call('POST', `${this.base}:batchUpdate`, { requests: formatRequests(id, spec) })
      this.formatted.add(title)
    } else if (!this.formatted.has(title)) {
      // A tab made by hand (or by an older version) may have no date/number formats: without them dates show as serial numbers.
      this.formatted.add(title)
      const id = (await this.tabs()).get(title)!
      const requests: Json[] = []
      if (spec.dateColumn !== null) requests.push(repeatCell(id, 1, null, spec.dateColumn, spec.dateColumn + 1, { numberFormat: { type: 'DATE', pattern: 'yyyy-mm-dd' } }, 'userEnteredFormat.numberFormat'))
      if (spec.amountColumn !== null) requests.push(repeatCell(id, 1, null, spec.amountColumn, spec.amountColumn + 1, { numberFormat: { type: 'NUMBER', pattern: '#,##0.##' } }, 'userEnteredFormat.numberFormat'))
      if (requests.length) await this.call('POST', `${this.base}:batchUpdate`, { requests })
    }
  }

  private async appendRows(title: string, spec: TabSpec, rows: (string | number | boolean)[][]) {
    if (!rows.length) return
    await this.ensureTab(title, spec)
    await this.call('POST', `${this.base}/values/${this.enc(`'${title}'!A1`)}:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS`, { values: rows })
  }

  private async writeRow(title: string, lastColumn: string, row: number, cells: (string | number | boolean)[]) {
    await this.call('POST', `${this.base}/values:batchUpdate`, {
      valueInputOption: 'RAW',
      data: [{ range: `'${title}'!A${row}:${lastColumn}${row}`, values: [cells] }],
    })
  }

  appendEntries(year: number, entries: Entry[]) { return this.appendRows(`${year}`, ENTRY_SPEC, entries.map((e) => EntryRows.toCells(e))) }
  writeEntryRow(year: number, row: number, entry: Entry) { return this.writeRow(`${year}`, EntryRows.LAST_COLUMN, row, EntryRows.toCells(entry)) }
  appendContacts(contacts: Contact[]) { return this.appendRows(CONTACTS_TAB, CONTACT_SPEC, contacts.map((c) => ContactRows.toCells(c))) }
  writeContactRow(row: number, contact: Contact) { return this.writeRow(CONTACTS_TAB, ContactRows.LAST_COLUMN, row, ContactRows.toCells(contact)) }

  // -------------------------------------------------------------------------------------------- users
  /** Rows are `Name | Email | Role`. A row counts only if it has an email, so the header row is skipped naturally. */
  async readUsers(): Promise<AppUser[] | null> {
    if (!(await this.loadTabs()).has(USERS_TAB)) return null
    const out: AppUser[] = []
    for (const r of await this.readValues(`'${USERS_TAB}'!A1:C`)) {
      const email = String(r[1] ?? '').trim().toLowerCase()
      if (!email.includes('@')) continue
      out.push({ name: String(r[0] ?? '').trim(), email, role: parseRole(r[2] === undefined ? '' : String(r[2])) })
    }
    return out
  }

  private async ensureUsersTab() {
    if ((await this.tabs()).has(USERS_TAB)) return
    const res = await this.call('POST', `${this.base}:batchUpdate`, {
      requests: [{ addSheet: { properties: { title: USERS_TAB, gridProperties: { frozenRowCount: 1 } } } }],
    })
    const id = ((res.replies as { addSheet: { properties: { sheetId: number } } }[])[0]).addSheet.properties.sheetId
    ;(await this.tabs()).set(USERS_TAB, id)
    await this.call('POST', `${this.base}/values/${this.enc(`'${USERS_TAB}'!A1`)}:append?valueInputOption=RAW`, { values: [['Name', 'Email', 'Role']] })
    await this.call('POST', `${this.base}:batchUpdate`, {
      requests: [
        repeatCell(id, 0, 1, 0, 3, { backgroundColor: color(1, 0.86, 0.68), textFormat: { bold: true } }, 'userEnteredFormat(backgroundColor,textFormat)'),
        // A dropdown so a hand-typed role is always Viewer, Editor or Admin.
        {
          setDataValidation: {
            range: range(id, 1, 500, 2, 3),
            rule: { condition: { type: 'ONE_OF_LIST', values: ROLE_KEYS.map((k) => ({ userEnteredValue: ROLES[k].label })) }, showCustomUi: true, strict: true },
          },
        },
        ...[200, 280, 100].map((px, i) => ({
          updateDimensionProperties: { range: { sheetId: id, dimension: 'COLUMNS', startIndex: i, endIndex: i + 1 }, properties: { pixelSize: px }, fields: 'pixelSize' },
        })),
      ],
    })
  }

  async writeUsers(users: AppUser[]) {
    await this.ensureUsersTab()
    await this.call('POST', `${this.base}/values/${this.enc(`'${USERS_TAB}'!A2:C`)}:clear`, {})
    if (users.length) {
      await this.call('PUT', `${this.base}/values/${this.enc(`'${USERS_TAB}'!A2`)}?valueInputOption=RAW`, {
        values: users.map((u) => [u.name, u.email, ROLES[u.role].label]),
      })
    }
  }
}
