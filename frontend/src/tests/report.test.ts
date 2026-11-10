import { describe, expect, it } from 'vitest'
import { Core } from '../core/core'
import type { GoogleAuth, Profile } from '../lib/auth'
import { FakeSheets } from '../lib/fakeSheets'
import { amountLabel, paiseToInput, parseSignedAmountToPaise } from '../lib/format'
import { FakeReportPublisher, googleReportBody, GoogleReports } from '../lib/googleReport'
import { parseImport } from '../lib/importer'
import { parseCsv } from '../lib/csv'
import type { Entry, EntryType } from '../lib/model'
import { abstractIndex, buildReport, isMoney, type Report, type ReportSheet } from '../lib/report'
import { FakeSharing } from '../lib/sharing'
import { byPerson, totals } from '../lib/stats'
import { crc32, columnName, rupeesNumber, writeXlsx, xlsxParts, zipStore } from '../lib/xlsx'
import { seedSheet } from './fixtures'

let seq = 0
const e = (type: EntryType, category: string, name: string, amount: number, extra: Partial<Entry> = {}): Entry => ({
  id: `r${seq}`, type, year: 2026, date: '2026-09-14', category, name, description: '', amount, person: 'Bhanu',
  createdBy: 'x', createdAt: ++seq, updatedBy: 'x', updatedAt: 1, deletedAt: null, rev: 1, row: seq + 1, hashMatches: true, hadId: true, ...extra,
})

// Made-up committee data: the real names and numbers never go in this repository.
const data: Entry[] = [
  e('DONATION', 'Youth', 'Donor A', 50_000), e('DONATION', 'Youth', 'Donor B', 111_600),
  e('DONATION', 'Villagers', 'Donor C', 500_000), e('DONATION', 'Villagers', 'Donor D', 100_000, { person: '' }),
  e('DONATION', 'Children', 'Kid A', 10_000, { description: 's/o Parent, Village X' }),
  e('DONATION', 'Other villagers', 'Visitor', 20_150, { description: 'Village Y' }),
  e('DONATION', 'Annadhanam', 'Gift in kind', 0, { person: '', description: 'Rice bag' }),
  e('DONATION', 'Annadhanam', 'Donor E', 160_000),
  e('OTHER', '', 'Laddu auction', 1_010_000), e('OTHER', '', 'Dhakshina', 115_500, { date: '2026-09-15' }),
  e('EXPENDITURE', 'Pooja', 'Flowers', 245_285), e('EXPENDITURE', 'Pooja', 'Kirana returned', -161_000),
  e('EXPENDITURE', 'Annadhanam', 'Vegetables', 100_000),
  e('DONATION', 'Youth', 'Deleted donor', 999_999, { deletedAt: 5 }),
]

const telugu = buildReport(data, 2026, 'TELUGU')
const sheetOf = (r: Report, name: string): ReportSheet => r.sheets.find((s) => s.name === name)!
const texts = (s: ReportSheet) => s.rows.flatMap((r) => r.cells).filter((c): c is string => typeof c === 'string')
const moneys = (s: ReportSheet) => s.rows.flatMap((r) => r.cells).filter(isMoney).map((m) => m.paise)

describe('annual report (same layout as the committee workbook)', () => {
  it('has the same tabs, with the ABSTRACT opening first', () => {
    expect(telugu.sheets.map((s) => s.name)).toEqual(['Youth Donations', 'Villagers Donations', 'Children Donations', 'OUTSIDERS DONATIONS', 'Dakshina', 'Annadanam Donation', 'Annadanam Expenditure', 'Expenditure', 'ABSTRACT'])
    expect(abstractIndex(telugu)).toBe(8)
  })

  it('donation tabs list donors with a numbered total and leave deleted rows out', () => {
    const youth = sheetOf(telugu, 'Youth Donations')
    expect(youth.rows[0].cells[0]).toBe('యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు - 2026')
    expect(youth.rows[1].cells).toEqual(['క్రమ సంక్య', 'ధాత వివరములు', 'మొత్తము రూపాయలలో'])
    expect(youth.rows[2].cells).toEqual([1, 'Donor A', { paise: 50_000 }])
    expect(youth.rows[3].cells).toEqual([2, 'Donor B', { paise: 111_600 }])
    expect(youth.rows.at(-1)!.style).toBe('TOTAL')
    expect(youth.rows.at(-1)!.cells.at(-1)).toEqual({ paise: 161_600 })
    expect(texts(youth)).not.toContain('Deleted donor')
  })

  it('a gift in kind shows its item and no amount', () => {
    const ann = sheetOf(telugu, 'Annadanam Donation')
    expect(ann.rows[2].cells).toEqual([1, 'Gift in kind', 'Rice bag', null])
    expect(ann.rows.at(-1)!.cells.at(-1)).toEqual({ paise: 160_000 })
  })

  it('dakshina is its own tab, found by its Telugu or English name, and is not an auction line', () => {
    const d = sheetOf(telugu, 'Dakshina')
    expect(d.rows[2].cells).toEqual([1, '15-09-2026', { paise: 115_500 }])
    const abstract = sheetOf(telugu, 'ABSTRACT')
    expect(texts(abstract)).toContain('Laddu auction')
    expect(texts(abstract)).not.toContain('Dhakshina')
    expect(texts(abstract)).toContain('దక్షిణ')
    const viaTelugu = buildReport([e('OTHER', '', ' దక్షిణ ', 5_000)], 2026, 'ENGLISH')
    expect(sheetOf(viaTelugu, 'Dakshina').rows[2].cells).toEqual([1, '14-09-2026', { paise: 5_000 }])
  })

  it('other collections list dakshina first, then every other input, then the annadhanam surplus', () => {
    const en = buildReport([...data, e('OTHER', '', 'Panche auction', 280_000, { description: 'cloth' })], 2026, 'ENGLISH')
    const a = sheetOf(en, 'ABSTRACT')
    const start = a.rows.findIndex((r) => r.cells[1] === 'Other income')
    const labels: unknown[] = []
    for (const r of a.rows.slice(start + 1)) { if (r.style !== 'BODY' || r.cells.length === 0) break; labels.push(r.cells[1]) }
    expect(labels).toEqual(['Dakshina', 'Laddu auction', 'Panche auction (cloth)', 'Annadhanam donations minus Annadhanam expenses'])
  })

  it('a returned purchase is negative and reduces the expense', () => {
    const pooja = sheetOf(telugu, 'Expenditure')
    expect(pooja.rows.at(-1)!.cells.at(-1)).toEqual({ paise: 245_285 - 161_000 })
    expect(moneys(pooja)).toContain(-161_000)
    expect(telugu.expenses).toBe(245_285 - 161_000)
  })

  it('the abstract adds up like the workbook', () => {
    const a = sheetOf(telugu, 'ABSTRACT')
    const block1 = 161_600 + 600_000 + 10_000 + 20_150 // listed amounts count even without a collector
    expect(moneys(a)).toContain(block1)
    expect(moneys(a)).toEqual(expect.arrayContaining([160_000, 100_000, 60_000])) // annadhanam donations, expenses, surplus
    const block2 = 1_010_000 + 115_500 + 60_000 // auction + dakshina + annadhanam surplus
    expect(moneys(a)).toContain(block2)
    expect(telugu.collections).toBe(block1 + block2)
    expect(telugu.surplus).toBe(block1 + block2 - (245_285 - 161_000))
    expect(a.rows.find((r) => r.cells[1] === 'ప్రస్తుత సంవత్సర మిగులు - 2026')!.cells[2]).toEqual({ paise: telugu.surplus })
  })

  it('a note says how much has no collector yet', () => {
    const note = sheetOf(telugu, 'ABSTRACT').rows.at(-1)!
    expect(note.style).toBe('NOTE')
    expect(note.cells[0] as string).toContain('₹1,000')
    const without = buildReport(data.filter((x) => x.person !== '' || x.amount === 0), 2026, 'TELUGU')
    expect(sheetOf(without, 'ABSTRACT').rows.some((r) => r.style === 'NOTE')).toBe(false)
  })

  it('the English report has English headings and the same numbers', () => {
    const en = buildReport(data, 2026, 'ENGLISH')
    expect(sheetOf(en, 'Youth Donations').rows[0].cells[0]).toBe('Donations from youth association members - 2026')
    expect(en.collections).toBe(telugu.collections)
    expect(en.expenses).toBe(telugu.expenses)
    expect(texts(sheetOf(en, 'ABSTRACT'))).toContain('Total of all collections')
  })

  it('an empty year still has every tab with zero totals, and other categories are not lost', () => {
    const empty = buildReport([], 2027, 'ENGLISH')
    expect(empty.sheets).toHaveLength(9)
    expect(empty.collections).toBe(0)
    expect(sheetOf(empty, 'Youth Donations').rows.at(-1)!.cells.at(-1)).toEqual({ paise: 0 })
    const more = buildReport([...data, e('DONATION', 'Special', 'Patron', 300_000), e('EXPENDITURE', 'Tent', 'Tent hire', 40_000)], 2026, 'ENGLISH')
    expect(texts(sheetOf(more, 'ABSTRACT'))).toEqual(expect.arrayContaining(['Special', 'Tent']))
    expect(more.collections).toBe(telugu.collections + 300_000)
    expect(more.expenses).toBe(telugu.expenses + 40_000)
  })
})

// ------------------------------------------------------------------------------------------------------------ Excel file
/** Reads a "stored" zip back, checking each entry's CRC. */
function readZip(bytes: Uint8Array): Record<string, string> {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const out: Record<string, string> = {}
  let p = 0
  while (dv.getUint32(p, true) === 0x04034b50) {
    const crc = dv.getUint32(p + 14, true)
    const size = dv.getUint32(p + 18, true)
    const nameLen = dv.getUint16(p + 26, true)
    const name = new TextDecoder().decode(bytes.subarray(p + 30, p + 30 + nameLen))
    const data = bytes.subarray(p + 30 + nameLen, p + 30 + nameLen + size)
    expect(crc32(data), `crc of ${name}`).toBe(crc)
    out[name] = new TextDecoder().decode(data)
    p += 30 + nameLen + size
  }
  expect(dv.getUint32(p, true)).toBe(0x02014b50) // the central directory follows
  return out
}

/** A small well-formedness check: every tag is closed in order. */
function assertBalanced(xml: string, name: string) {
  const stack: string[] = []
  for (const m of xml.matchAll(/<(\/?)([A-Za-z_][\w:.-]*)[^>]*?(\/?)>/g)) {
    if (m[3] === '/') continue
    if (m[1] === '/') expect(stack.pop(), `${name} closes ${m[2]}`).toBe(m[2])
    else stack.push(m[2])
  }
  expect(stack, `${name} left open`).toEqual([])
}

describe('Excel file', () => {
  it('crc32 and the zip writer follow the standard', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926)
    const z = readZip(zipStore([{ name: 'a.txt', data: new TextEncoder().encode('hello') }, { name: 'b/ü.txt', data: new TextEncoder().encode('తెలుగు') }]))
    expect(z).toEqual({ 'a.txt': 'hello', 'b/ü.txt': 'తెలుగు' })
  })

  it('column names and amounts as numbers', () => {
    expect([0, 1, 25, 26, 27, 51, 52, 701, 702].map(columnName)).toEqual(['A', 'B', 'Z', 'AA', 'AB', 'AZ', 'BA', 'ZZ', 'AAA'])
    expect(rupeesNumber(161_000)).toBe('1610')
    expect(rupeesNumber(-161_000)).toBe('-1610')
    expect(rupeesNumber(-5)).toBe('-0.05')
    expect(rupeesNumber(150)).toBe('1.5')
  })

  it('is a valid package with every tab, Telugu text and real numbers', () => {
    const files = readZip(writeXlsx(telugu.sheets, abstractIndex(telugu)))
    for (const n of ['[Content_Types].xml', '_rels/.rels', 'xl/workbook.xml', 'xl/_rels/workbook.xml.rels', 'xl/styles.xml']) expect(files[n], n).toBeDefined()
    for (const [name, xml] of Object.entries(files)) assertBalanced(xml, name)
    for (const s of telugu.sheets) expect(files['xl/workbook.xml']).toContain(`name="${s.name}"`)
    expect(files['xl/workbook.xml']).toContain('activeTab="8"')
    const youth = files['xl/worksheets/sheet1.xml']
    expect(youth).toContain('యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు - 2026')
    expect(youth).toContain('<v>500</v>') // Donor A, Rs 500 as a number
    expect(youth).toContain('<mergeCell ref="A1:C1"/>')
    expect(youth).not.toContain('Deleted donor')
    expect(files['xl/worksheets/sheet8.xml']).toContain('<v>-1610</v>')
    expect(files['xl/worksheets/sheet9.xml']).toContain('tabSelected="1"')
  })

  it('escapes text and cannot be corrupted by control characters', () => {
    const sheet: ReportSheet = {
      name: 'Tricky & <Sheet>', widths: [20, 10], merges: [],
      rows: [{ cells: ['a < b & "c" \u0001 end', { paise: 150 }], style: 'BODY' }, { cells: [1, { paise: -5 }], style: 'BODY' }],
    }
    const parts = xlsxParts([sheet])
    for (const [name, xml] of Object.entries(parts)) assertBalanced(xml, name)
    expect(parts['xl/workbook.xml']).toContain('Tricky &amp; &lt;Sheet&gt;')
    expect(parts['xl/worksheets/sheet1.xml']).toContain('a &lt; b &amp; &quot;c&quot;  end')
    expect(parts['xl/worksheets/sheet1.xml']).toContain('<v>1.5</v>')
    expect(parts['xl/worksheets/sheet1.xml']).toContain('<v>-0.05</v>')
    expect(() => writeXlsx([])).toThrow()
  })
})

// ------------------------------------------------------------------------------------------------------------ Google Sheet
describe('Google Sheet body', () => {
  it('mirrors the tabs, values, merges and widths', () => {
    const body = googleReportBody(telugu) as { properties: { title: string }; sheets: any[] }
    expect(body.properties.title).toBe('Vinayaka Finance report 2026')
    expect(body.sheets).toHaveLength(9)
    const youth = body.sheets[0]
    expect(youth.properties).toMatchObject({ sheetId: 0, title: 'Youth Donations', index: 0 })
    const rows = youth.data[0].rowData
    expect(rows).toHaveLength(sheetOf(telugu, 'Youth Donations').rows.length)
    expect(rows[2].values[0].userEnteredValue).toEqual({ numberValue: 1 })
    expect(rows[2].values[1].userEnteredValue).toEqual({ stringValue: 'Donor A' })
    expect(rows[2].values[2].userEnteredValue).toEqual({ numberValue: 500 })
    expect(rows[2].values[2].userEnteredFormat.numberFormat.type).toBe('NUMBER')
    expect(youth.data[0].columnMetadata).toHaveLength(3)
    expect(youth.merges[0]).toMatchObject({ sheetId: 0, startRowIndex: 0, endRowIndex: 1, startColumnIndex: 0, endColumnIndex: 3 })
    expect(body.sheets[5].data[0].rowData[2].values[3].userEnteredValue).toBeUndefined() // no amount for a gift in kind
  })

  it('creates the sheet with one POST and returns its id and link', async () => {
    const calls: { url: string; body: any }[] = []
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      calls.push({ url, body: JSON.parse(String(init?.body)) })
      return new Response(JSON.stringify({ spreadsheetId: 'NEWID', spreadsheetUrl: 'https://docs.google.com/spreadsheets/d/NEWID/edit' }), { status: 200 })
    }) as typeof fetch
    const created = await new GoogleReports(async () => 't', fetchImpl).create(telugu)
    expect(created).toEqual({ id: 'NEWID', url: 'https://docs.google.com/spreadsheets/d/NEWID/edit' })
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('https://sheets.googleapis.com/v4/spreadsheets')
    expect(calls[0].body.sheets).toHaveLength(9)
    const none = (async () => new Response('{}', { status: 200 })) as unknown as typeof fetch
    await expect(new GoogleReports(async () => 't', none).create(telugu)).rejects.toThrow()
  })
})

// ------------------------------------------------------------------------------------------------------------ negative expenditure
describe('negative expenditure (a return)', () => {
  it('accepts a leading minus for signed amounts', () => {
    expect(parseSignedAmountToPaise('-1610')).toBe(-161_000)
    expect(parseSignedAmountToPaise('−1,610.50')).toBe(-161_050)
    expect(parseSignedAmountToPaise('1610')).toBe(161_000)
    expect(parseSignedAmountToPaise('-0')).toBe(0)
    for (const bad of ['-', '--5', 'abc', '']) expect(parseSignedAmountToPaise(bad), bad).toBeNull()
    expect(paiseToInput(-161_000)).toBe('-1610')
    expect(paiseToInput(-1_610)).toBe('-16.1')
    expect(parseSignedAmountToPaise(paiseToInput(-1_610))).toBe(-1_610)
  })

  it('a return shows as plus in green and a normal expense as minus in red', () => {
    expect(amountLabel(false, -161_000)).toEqual({ text: '+₹1,610', moneyIn: true })
    expect(amountLabel(false, 25_000)).toEqual({ text: '−₹250', moneyIn: false })
    expect(amountLabel(true, 50_000)).toEqual({ text: '+₹500', moneyIn: true })
    expect(amountLabel(true, 0).text).toBe('In kind')
  })

  it('reduces what was spent, for the total and for the person', () => {
    const rows = [e('EXPENDITURE', 'Pooja', 'a', 500_000), e('EXPENDITURE', 'Pooja', 'b', -161_000), e('EXPENDITURE', 'Pooja', 'c', 100_000, { person: 'Venky' })]
    expect(totals(rows).spent).toBe(439_000)
    expect(byPerson(rows).find((p) => p.person === 'Bhanu')!.spent).toBe(339_000)
  })

  it('import accepts a negative amount on expenditure only', () => {
    const b = parseImport([
      { name: 'Pooja Expenditure', rows: parseCsv('Date,Item,Quantity,Amount,Paid By\n12/09/2026,Flowers,2 kg,145,Bhanu\n13/09/2026,Kirana return,,-1610,Bhanu\n') },
      { name: 'Youth Donations', rows: parseCsv('Name,Amount,Collected By\nDonor,-500,Bhanu\nDonor Two,500,Bhanu\n') },
      { name: 'Ledger 2026', rows: parseCsv('Date,Type,Category,Name,Description,Amount,Collected / Paid by\n2026-09-14,Expenditure,Annadhanam,Rice returned,,-2000.50,Venky\n2026-09-14,Donation,Youth,Odd,,-5,Venky\n') },
    ])
    const amounts = Object.fromEntries(b.entries.map((x) => [x.name, x.amount]))
    expect(amounts['Kirana return']).toBe(-161_000)
    expect(amounts['Rice returned']).toBe(-200_050)
    expect(amounts['Donor']).toBeUndefined()
    expect(amounts['Odd']).toBeUndefined()
    expect(b.skippedRows).toBe(2)
    expect(amounts['Donor Two']).toBe(50_000)
  })
})

// ------------------------------------------------------------------------------------------------------------ the core
const YEAR = 2026
const NOW = Date.UTC(YEAR, 8, 20, 10, 0, 0)

async function signedIn(email: string, opts: { driveFails?: string } = {}) {
  const sheets = new FakeSheets()
  seedSheet(sheets, YEAR, NOW - 100_000)
  const reports = new FakeReportPublisher()
  const filesShared: FakeSharing[] = []
  const profile: Profile = { email, name: 'Tester', picture: '' }
  const auth = { configured: true, profile, signIn: async () => profile, accessToken: async () => 't', signOut: () => undefined } as unknown as GoogleAuth
  let t = NOW
  let driveAsked = 0
  const core = new Core({
    sheets, auth, reports, clock: () => (t += 1000), storage: null,
    sharing: async () => new FakeSharing(),
    fileSharing: async () => { const s = new FakeSharing([{ id: 'own', email, level: 'OWNER' }]); filesShared.push(s); return s },
    driveReady: async () => { driveAsked++; if (opts.driveFails) throw new Error(opts.driveFails) },
  })
  await core.signIn()
  return { core, sheets, reports, filesShared, driveAsked: () => driveAsked }
}

describe('report in the core', () => {
  it('generates from the Sheet’s current records, in either language, for any role', async () => {
    const { core } = await signedIn('guest@example.com') // a Viewer
    await core.generateReport(YEAR, 'TELUGU')
    const r = core.getState().report!
    expect(r.year).toBe(YEAR)
    expect(r.sheets).toHaveLength(9)
    expect(r.collections).toBeGreaterThan(0)
    await core.generateReport(YEAR, 'ENGLISH')
    expect(core.getState().report!.lang).toBe('ENGLISH')
    core.closeReport()
    expect(core.getState().report).toBeNull()
  })

  it('an Editor or an Admin can share it with Google; everyone on the Users list gets the right access', async () => {
    for (const email of ['bhanu@example.com', 'lakshmana5296@gmail.com']) { // an Editor, then the Admin
      const { core, reports, filesShared, driveAsked } = await signedIn(email)
      await core.generateReport(YEAR, 'TELUGU')
      await core.shareReportWithGoogle()
      const share = core.getState().reportShare
      expect(share.status, email).toBe('done')
      if (share.status !== 'done') return
      expect(share.url).toBe('https://docs.google.com/spreadsheets/d/report-1/edit')
      expect(driveAsked()).toBe(1)
      expect(reports.created).toHaveLength(1)
      const granted = Object.fromEntries(filesShared[0].shares.map((s) => [s.email, s.level]))
      expect(granted['guest@example.com']).toBe('READER')        // Viewer: can view
      expect(granted['venky@example.com']).toBe('WRITER')        // Editor: can edit
      expect(granted['bhanu@example.com']).toBe(email === 'bhanu@example.com' ? 'OWNER' : 'WRITER')                       // Editor
      expect(granted['lakshmana5296@gmail.com']).toBe(email === 'lakshmana5296@gmail.com' ? 'OWNER' : 'WRITER')           // Admin gets edit access
      expect(granted[email]).toBe('OWNER')                         // the sharer stays the owner and is not touched
      expect(share.lines.join(' ')).toContain('Shared with')
    }
  })

  it('a Viewer cannot share it and nothing is created', async () => {
    const { core, reports } = await signedIn('guest@example.com')
    await core.generateReport(YEAR, 'TELUGU')
    await core.shareReportWithGoogle()
    expect(reports.created).toHaveLength(0)
    expect(core.getState().reportShare.status).toBe('idle')
    expect(core.getState().toast?.error).toBe(true)
  })

  it('says why when Google access is declined, and creates nothing', async () => {
    const { core, reports } = await signedIn('bhanu@example.com', { driveFails: 'Drive access was declined.' })
    await core.generateReport(YEAR, 'TELUGU')
    await core.shareReportWithGoogle()
    expect(core.getState().reportShare).toEqual({ status: 'failed', message: 'Drive access was declined.' })
    expect(reports.created).toHaveLength(0)
    core.dismissReportShare()
    expect(core.getState().reportShare.status).toBe('idle')
  })

  it('an Editor can add a negative expenditure, but a negative donation is refused before it reaches the Sheet', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    expect(await core.addEntry({ type: 'EXPENDITURE', date: `${YEAR}-09-15`, category: 'Pooja', name: 'Kirana returned', description: '', amount: -161_000, person: 'Bhanu' })).toBe(true)
    expect(sheets.years.get(YEAR)!.at(-1)).toMatchObject({ name: 'Kirana returned', amount: -161_000, type: 'EXPENDITURE' })
    const before = sheets.writes
    expect(await core.addEntry({ type: 'DONATION', date: `${YEAR}-09-15`, category: 'Youth', name: 'Bad', description: '', amount: -100, person: 'Bhanu' })).toBe(false)
    expect(sheets.writes).toBe(before)
    expect(core.getState().toast?.text).toContain('Only Expenditure')
    const flowers = core.getState().entries[YEAR]!.find((x) => x.name === 'Flowers')!
    expect(await core.updateEntry(flowers, { date: flowers.date, category: flowers.category, name: flowers.name, description: '', amount: -500, person: 'Bhanu' })).toBe(true)
    const donor = core.getState().entries[YEAR]!.find((x) => x.name === 'Ramesh')!
    expect(await core.updateEntry(donor, { date: donor.date, category: donor.category, name: donor.name, description: '', amount: -500, person: 'Bhanu' })).toBe(false)
    await core.generateReport(YEAR, 'ENGLISH')
    // Pooja spending is now Coconuts 1,275.00 plus the return of 5.00 (Flowers edited to -500 paise); the added -1,610 return counts too.
    expect(core.getState().report!.expenses).toBe(127_500 - 500 - 161_000)
  })
})
