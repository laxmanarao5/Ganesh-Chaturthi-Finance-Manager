// Imports a CSV (the committee's own lists, or a file exported by the app/portal). Same recognition rules as the Android app
// (importer/ImportMapper.kt): a CSV is one "sheet" whose name is the file name, so "Contacts.csv" is the contact list,
// "Youth Donations.csv" is the Youth donations list, and any other name is read by its Type column.
import { canonicalPerson } from './stats'
import { isoToSerial, parseAmountToPaise, parseDateFlexible, serialToIso } from './format'
import { contactRowId, ENTRY_TYPES, isDeleted, newId, typeFromSheet, type Contact, type Entry, type EntryType } from './model'

export interface SheetData { name: string; rows: string[][] }

export interface ImportEntry {
  type: EntryType; category: string
  /** Null for rows with no date (donor lists); the importer asks for one date for all of them. */
  date: string | null
  name: string; description: string; amount: number; person: string
}
export interface ImportContact { name: string; number: string; caller: string; called: boolean; remarks: string }
export interface SectionSummary { label: string; rows: number; total: number }
export interface ImportBundle {
  entries: ImportEntry[]; contacts: ImportContact[]; sections: SectionSummary[]; ignoredSheets: string[]; skippedRows: number
}
export interface ImportPlan { entries: Entry[]; contacts: Contact[]; duplicateEntries: number; duplicateContacts: number }

export const bundleIsEmpty = (b: ImportBundle) => b.entries.length === 0 && b.contacts.length === 0
export const undatedCount = (b: ImportBundle) => b.entries.filter((e) => e.date === null).length
export const earliestDate = (b: ImportBundle) => b.entries.map((e) => e.date).filter((d): d is string => d !== null).sort()[0] ?? null

interface SheetSpec {
  label: string; type: EntryType; category: string
  nameHeaders: string[]; detailHeaders: string[]; personHeaders: string[]
  hasDate: boolean; amountRequired: boolean
}

const donorDetail = ['parent/guardian', 'village', 'item']
const collectors = ['collectedby']
const norm = (s: string) => [...s.toLowerCase()].filter((c) => /[\p{L}\p{N}/]/u.test(c)).join('')

const KNOWN: SheetSpec[] = [
  { label: 'Youth Donations', type: 'DONATION', category: 'Youth', nameHeaders: ['name'], detailHeaders: donorDetail, personHeaders: collectors, hasDate: false, amountRequired: false },
  { label: 'Villagers Donations', type: 'DONATION', category: 'Villagers', nameHeaders: ['name'], detailHeaders: donorDetail, personHeaders: collectors, hasDate: false, amountRequired: false },
  { label: 'Children Donations', type: 'DONATION', category: 'Children', nameHeaders: ['name'], detailHeaders: donorDetail, personHeaders: collectors, hasDate: false, amountRequired: false },
  { label: 'Outsiders Donations', type: 'DONATION', category: 'Other villagers', nameHeaders: ['name'], detailHeaders: donorDetail, personHeaders: collectors, hasDate: false, amountRequired: false },
  { label: 'Annadhanam donations', type: 'DONATION', category: 'Annadhanam', nameHeaders: ['name'], detailHeaders: donorDetail, personHeaders: collectors, hasDate: false, amountRequired: false },
  { label: 'Pooja Expenditure', type: 'EXPENDITURE', category: 'Pooja', nameHeaders: ['item'], detailHeaders: ['quantity'], personHeaders: ['paidby', 'spentby'], hasDate: true, amountRequired: true },
  { label: 'Annadhanam Expenditure', type: 'EXPENDITURE', category: 'Annadhanam', nameHeaders: ['item'], detailHeaders: ['quantity'], personHeaders: ['paidby', 'spentby'], hasDate: false, amountRequired: true },
  { label: 'Other inputs', type: 'OTHER', category: '', nameHeaders: ['purpose', 'name'], detailHeaders: ['description'], personHeaders: collectors, hasDate: false, amountRequired: true },
  { label: 'Dhakshina', type: 'OTHER', category: '', nameHeaders: [], detailHeaders: [], personHeaders: collectors, hasDate: true, amountRequired: true },
]
const KNOWN_BY_KEY = new Map(KNOWN.map((s) => [norm(s.label), s]))

const amountOf = (s: string) => parseAmountToPaise(s.replace(/rs\./gi, ''))

function dateOf(s: string): string | null {
  const t = s.trim()
  if (!t) return null
  const n = Number(t)
  if (!Number.isNaN(n) && n >= 20000 && n <= 80000) return serialToIso(n)
  return parseDateFlexible(t)
}

/** Column of each wanted header: the leftmost match, so side tables further right never win. */
const columns = (header: string[], wanted: string[]) => wanted.map((w) => header.indexOf(w)).filter((i) => i >= 0)
const headerRow = (rows: string[][], mustHave: string[]) => rows.slice(0, 5).findIndex((r) => r.map(norm).some((c) => mustHave.includes(c)))
const tcell = (r: string[], i: number) => (i >= 0 ? (r[i] ?? '').trim() : '')

export function parseImport(sheets: SheetData[]): ImportBundle {
  const entries: ImportEntry[] = []
  const contacts: ImportContact[] = []
  const sections: SectionSummary[] = []
  const ignored: string[] = []
  let skipped = 0
  const known: string[] = []
  const person = (raw: string) => {
    const p = canonicalPerson(raw, known)
    if (p && !known.includes(p)) known.push(p)
    return p
  }
  const total = (xs: ImportEntry[]) => xs.reduce((a, e) => a + e.amount, 0)

  for (const sheet of sheets) {
    const key = norm(sheet.name)
    const spec = KNOWN_BY_KEY.get(key)
    if (spec) {
      const found: ImportEntry[] = []
      skipped += readKnown(sheet, spec, person, found)
      entries.push(...found)
      sections.push({ label: `${sheet.name} → ${ENTRY_TYPES[spec.type].label}${spec.category ? ' / ' + spec.category : ''}`, rows: found.length, total: total(found) })
    } else if (key === 'contacts') {
      const found: ImportContact[] = []
      readContacts(sheet, person, found)
      contacts.push(...found)
      sections.push({ label: `${sheet.name} → Contacts`, rows: found.length, total: 0 })
    } else {
      const found: ImportEntry[] = []
      const n = readGeneric(sheet, person, found)
      if (n === null) ignored.push(sheet.name)
      else { skipped += n; entries.push(...found); sections.push({ label: `${sheet.name} → by Type column`, rows: found.length, total: total(found) }) }
    }
  }
  return { entries, contacts, sections, ignoredSheets: ignored, skippedRows: skipped }
}

function readKnown(sheet: SheetData, spec: SheetSpec, person: (s: string) => string, out: ImportEntry[]): number {
  const h = headerRow(sheet.rows, ['amount'])
  if (h < 0) return 0
  const header = sheet.rows[h].map(norm)
  const cAmount = columns(header, ['amount'])[0] ?? -1
  if (cAmount < 0) return 0
  const cName = columns(header, spec.nameHeaders)[0] ?? -1
  const cDetails = columns(header, spec.detailHeaders)
  const cPerson = columns(header, spec.personHeaders)[0] ?? -1
  const cDate = spec.hasDate ? (columns(header, ['date'])[0] ?? -1) : -1
  let skipped = 0
  for (const r of sheet.rows.slice(h + 1)) {
    const amountText = tcell(r, cAmount)
    // Dhakshina has no name column: its name is the sheet's own name.
    const name = cName >= 0 ? tcell(r, cName) : spec.label
    if (!name || name.toLowerCase() === 'total') continue
    const amount = amountText === '' && !spec.amountRequired ? 0 : amountOf(amountText)
    if (amount === null) { skipped++; continue }
    const dateText = tcell(r, cDate)
    const date = dateOf(dateText)
    if (spec.hasDate && dateText !== '' && date === null) { skipped++; continue }
    out.push({
      type: spec.type, category: spec.category, date, name,
      description: cDetails.map((i) => tcell(r, i)).filter((x) => x !== '').join(' · '),
      amount, person: person(tcell(r, cPerson)),
    })
  }
  return skipped
}

function readContacts(sheet: SheetData, person: (s: string) => string, out: ImportContact[]) {
  const h = headerRow(sheet.rows, ['number', 'name'])
  if (h < 0) return
  const header = sheet.rows[h].map(norm)
  const cName = columns(header, ['name'])[0] ?? -1
  if (cName < 0) return
  const cNumber = columns(header, ['number', 'phone', 'mobile'])[0] ?? -1
  const cCaller = columns(header, ['whocalls', 'caller'])[0] ?? -1
  const cStatus = columns(header, ['status', 'called'])[0] ?? -1
  const cRemarks = columns(header, ['remarks', 'notes'])[0] ?? -1
  for (const r of sheet.rows.slice(h + 1)) {
    const name = tcell(r, cName)
    if (!name) continue
    out.push({
      name, number: tcell(r, cNumber), caller: person(tcell(r, cCaller)),
      called: ['1', 'true', 'yes', 'y', 'done', 'called'].includes(tcell(r, cStatus).toLowerCase()),
      remarks: tcell(r, cRemarks),
    })
  }
}

/** A CSV with a Type column, like the files the app and portal export. Null when it is not that shape. */
function readGeneric(sheet: SheetData, person: (s: string) => string, out: ImportEntry[]): number | null {
  const h = headerRow(sheet.rows, ['amount'])
  if (h < 0) return null
  const header = sheet.rows[h].map(norm)
  const cType = columns(header, ['type'])[0] ?? -1
  const cAmount = columns(header, ['amount'])[0] ?? -1
  if (cType < 0 || cAmount < 0) return null
  const cDate = columns(header, ['date'])[0] ?? -1
  const cCategory = columns(header, ['category'])[0] ?? -1
  const cName = columns(header, ['name', 'item', 'purpose'])[0] ?? -1
  const cDesc = columns(header, ['description', 'details'])[0] ?? -1
  const cPerson = columns(header, ['collected/paidby', 'collectedby', 'paidby', 'person'])[0] ?? -1
  let skipped = 0
  for (const r of sheet.rows.slice(h + 1)) {
    if (r.every((c) => c.trim() === '')) continue
    const type = typeFromSheet(tcell(r, cType))
    const amount = amountOf(tcell(r, cAmount))
    if (type === null || amount === null) { skipped++; continue }
    out.push({ type, category: tcell(r, cCategory), date: dateOf(tcell(r, cDate)), name: tcell(r, cName), description: tcell(r, cDesc), amount, person: person(tcell(r, cPerson)) })
  }
  return skipped
}

const dupKey = (type: string, date: string, category: string, name: string, desc: string, amount: number, person: string) =>
  [type, date, category.trim().toLowerCase(), name.trim().toLowerCase(), desc.trim().toLowerCase(), amount, person.trim().toLowerCase()].join('|')

/**
 * Rows already present (same everything) are skipped, counting repeats, so importing the same file twice
 * adds nothing while two genuinely identical rows both survive. [undatedDate] is used for rows without a date.
 */
export function planImport(
  bundle: ImportBundle, undatedDate: string, contactYear: number,
  existingEntries: Entry[], existingContacts: Contact[], who: string, now: number,
): ImportPlan {
  const counts = new Map<string, number>()
  for (const e of existingEntries.filter((x) => !isDeleted(x))) {
    const k = dupKey(e.type, e.date, e.category, e.name, e.description, e.amount, e.person)
    counts.set(k, (counts.get(k) ?? 0) + 1)
  }
  const created: Entry[] = []
  let dupEntries = 0
  for (const r of bundle.entries) {
    const date = r.date ?? undatedDate
    const k = dupKey(r.type, date, r.category, r.name, r.description, r.amount, r.person)
    const left = counts.get(k) ?? 0
    if (left > 0) { counts.set(k, left - 1); dupEntries++; continue }
    isoToSerial(date) // throws on an impossible date instead of writing garbage
    created.push({
      id: newId(), type: r.type, year: Number(date.slice(0, 4)), date,
      category: r.category, name: r.name, description: r.description, amount: r.amount, person: r.person,
      createdBy: who, createdAt: now, updatedBy: who, updatedAt: now, deletedAt: null, rev: 1,
      row: 0, hashMatches: true, hadId: true,
    })
  }

  const newContacts: Contact[] = []
  let dupContacts = 0
  const liveContacts = existingContacts.filter((c) => !isDeleted(c))
  for (const c of bundle.contacts) {
    const same = liveContacts.filter((x) => x.name.toLowerCase() === c.name.toLowerCase() && x.number === c.number)
    if (same.some((x) => x.year === contactYear)) { dupContacts++; continue }
    const cid = same[0]?.contactId ?? newId()
    newContacts.push({
      id: contactRowId(cid, contactYear), contactId: cid, year: contactYear,
      name: c.name, number: c.number, caller: c.caller, called: c.called, remarks: c.remarks,
      createdBy: who, createdAt: now, updatedBy: who, updatedAt: now, deletedAt: null, rev: 1,
      row: 0, hashMatches: true, hadId: true,
    })
  }
  return { entries: created, contacts: newContacts, duplicateEntries: dupEntries, duplicateContacts: dupContacts }
}
