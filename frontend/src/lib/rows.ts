// How records are laid out in the Sheet. Identical to the Android app (data/sheets/RowMapper.kt) so both read and write the same rows.
import { isoToSerial, paiseToSheetNumber, parseDateFlexible, serialToIso, sheetNumberToPaise } from './format'
import { contactHash, contactRowId, entryHash, newId, typeFromSheet, ENTRY_TYPES, type Contact, type Entry } from './model'

export type Cell = string | number | boolean | null | undefined

const isoMs = (ms: number) => new Date(ms).toISOString()

function parseMs(v: Cell): number {
  const t = v === null || v === undefined ? '' : String(v).trim()
  if (!t) return 0
  const ms = Date.parse(t)
  return Number.isNaN(ms) ? 0 : ms
}

function cell(cells: Cell[], i: number): Cell {
  const v = cells[i]
  return v === null || v === undefined || String(v).trim() === '' ? null : v
}

function text(cells: Cell[], i: number): string {
  const v = cell(cells, i)
  return v === null ? '' : String(v).trim()
}

function intOf(v: Cell): number | null {
  if (typeof v === 'number') return Math.trunc(v)
  if (typeof v === 'string' && /^\s*-?\d+\s*$/.test(v)) return Number(v)
  return null
}

/** A phone number may arrive as a number (8.97E9) or as text. */
function phoneText(v: Cell): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'number') return BigInt(Math.round(v)).toString()
  return String(v).trim()
}

/**
 * Ledger tab (one per year):
 * A Date | B Type | C Category | D Name | E Description | F Amount | G Collected / Paid by | H Created by   <- for humans
 * I id | J rev | K hash | L created_at | M updated_by | N updated_at | O deleted_at                           <- system
 */
export const EntryRows = {
  HEADERS: ['Date', 'Type', 'Category', 'Name', 'Description', 'Amount', 'Collected / Paid by', 'Created by',
    'id', 'rev', 'hash', 'created_at', 'updated_by', 'updated_at', 'deleted_at'],
  LAST_COLUMN: 'O',

  toCells(e: Entry): (string | number)[] {
    return [
      isoToSerial(e.date), ENTRY_TYPES[e.type].sheetName, e.category, e.name, e.description,
      paiseToSheetNumber(e.amount), e.person, e.createdBy,
      e.id, e.rev, entryHash(e), isoMs(e.createdAt), e.updatedBy, isoMs(e.updatedAt),
      e.deletedAt !== null ? isoMs(e.deletedAt) : '',
    ]
  },

  /** Null means the row is blank or unreadable (no valid date, type or amount). */
  fromCells(cells: Cell[], row: number, year: number): Entry | null {
    const dv = cell(cells, 0)
    const date = dv === null ? null : typeof dv === 'number' ? serialToIso(dv) : parseDateFlexible(String(dv))
    if (!date) return null
    const type = typeFromSheet(text(cells, 1))
    if (!type) return null
    const av = cell(cells, 5)
    let amount: number
    if (av === null) amount = 0 // blank amount: a gift in kind
    else if (typeof av === 'number') amount = sheetNumberToPaise(av)
    else {
      const n = Number(String(av).replace(/,/g, '').replace(/₹/g, '').trim())
      if (Number.isNaN(n)) return null
      amount = sheetNumberToPaise(n)
    }
    const storedId = text(cells, 8)
    const e: Entry = {
      id: storedId || newId(), type, year, date,
      category: text(cells, 2), name: text(cells, 3), description: text(cells, 4),
      amount, person: text(cells, 6), createdBy: text(cells, 7),
      createdAt: parseMs(cell(cells, 11)), updatedBy: text(cells, 12), updatedAt: parseMs(cell(cells, 13)),
      deletedAt: parseMs(cell(cells, 14)) || null,
      rev: intOf(cell(cells, 9)) ?? 1,
      row, hashMatches: false, hadId: storedId !== '',
    }
    e.hashMatches = text(cells, 10) === entryHash(e)
    return e
  },
}

/**
 * "Contacts" tab, all years in one tab (one row per contact per year):
 * A Name | B Number | C Who calls | D Year | E Called | F Remarks   <- for humans
 * G contact_id | H rev | I hash | J created_at | K updated_by | L updated_at | M deleted_at   <- system
 */
export const ContactRows = {
  HEADERS: ['Name', 'Number', 'Who calls', 'Year', 'Called', 'Remarks',
    'contact_id', 'rev', 'hash', 'created_at', 'updated_by', 'updated_at', 'deleted_at'],
  LAST_COLUMN: 'M',

  toCells(c: Contact): (string | number | boolean)[] {
    return [
      c.name, c.number, c.caller, c.year, c.called, c.remarks,
      c.contactId, c.rev, contactHash(c), isoMs(c.createdAt), c.updatedBy, isoMs(c.updatedAt),
      c.deletedAt !== null ? isoMs(c.deletedAt) : '',
    ]
  },

  fromCells(cells: Cell[], row: number): Contact | null {
    const name = text(cells, 0)
    const year = intOf(cell(cells, 3))
    if (!name || year === null || year < 2000 || year > 2100) return null
    const storedContactId = text(cells, 6)
    const contactId = storedContactId || newId()
    const called = ((): boolean => {
      const v = cell(cells, 4)
      if (v === null) return false
      if (typeof v === 'boolean') return v
      if (typeof v === 'number') return v !== 0
      return ['true', 'yes', 'y', '1', 'done', 'called', '✓', 'x'].includes(String(v).trim().toLowerCase())
    })()
    const c: Contact = {
      id: contactRowId(contactId, year), contactId, year,
      name, number: phoneText(cell(cells, 1)), caller: text(cells, 2), called, remarks: text(cells, 5),
      createdBy: '', createdAt: parseMs(cell(cells, 9)),
      updatedBy: text(cells, 10), updatedAt: parseMs(cell(cells, 11)),
      deletedAt: parseMs(cell(cells, 12)) || null,
      rev: intOf(cell(cells, 7)) ?? 1,
      row, hashMatches: false, hadId: storedContactId !== '',
    }
    c.hashMatches = text(cells, 8) === contactHash(c)
    return c
  },
}

export const isBlankRow = (cells: Cell[]) => cells.every((c) => c === null || c === undefined || String(c).trim() === '')
