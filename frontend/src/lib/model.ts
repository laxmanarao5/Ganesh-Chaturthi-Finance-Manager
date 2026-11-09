// The records the committee tracks. Same shapes and rules as the Android app (data/model/Models.kt), so the Sheet stays
// readable by both: one row per record, `rev` counting edits, a content `hash`, and soft-delete tombstones.

export type EntryType = 'DONATION' | 'EXPENDITURE' | 'OTHER'

export interface TypeInfo {
  label: string
  sheetName: string
  isIncome: boolean
  categories: string[]
  nameLabel: string
  detailLabel: string
  personLabel: string
}

export const ENTRY_TYPES: Record<EntryType, TypeInfo> = {
  DONATION: {
    label: 'Donations', sheetName: 'Donation', isIncome: true,
    categories: ['Youth', 'Villagers', 'Children', 'Other villagers', 'Annadhanam'],
    nameLabel: 'Donor name', detailLabel: 'Village / details', personLabel: 'Collected by',
  },
  EXPENDITURE: {
    label: 'Expenditure', sheetName: 'Expenditure', isIncome: false,
    categories: ['Pooja', 'Annadhanam'],
    nameLabel: 'Item', detailLabel: 'Quantity / notes', personLabel: 'Paid by',
  },
  OTHER: {
    label: 'Other income', sheetName: 'Other', isIncome: true, categories: [],
    nameLabel: 'Name', detailLabel: 'Description', personLabel: 'Collected by',
  },
}

export const ENTRY_TYPE_KEYS = Object.keys(ENTRY_TYPES) as EntryType[]

export function typeFromSheet(text: string | null | undefined): EntryType | null {
  const t = (text ?? '').trim().toLowerCase()
  if (!t) return null
  return ENTRY_TYPE_KEYS.find((k) => ENTRY_TYPES[k].sheetName.toLowerCase() === t || ENTRY_TYPES[k].label.toLowerCase() === t || k.toLowerCase() === t) ?? null
}

export type Role = 'VIEWER' | 'EDITOR' | 'ADMIN'

export const ROLES: Record<Role, { label: string; canEdit: boolean; canManageUsers: boolean }> = {
  VIEWER: { label: 'Viewer', canEdit: false, canManageUsers: false },
  EDITOR: { label: 'Editor', canEdit: true, canManageUsers: false },
  ADMIN: { label: 'Admin', canEdit: true, canManageUsers: true },
}
export const ROLE_KEYS = Object.keys(ROLES) as Role[]

/** One row of the Sheet's Users tab. Only listed emails may use the app. */
export interface AppUser { name: string; email: string; role: Role }

/** Where a record lives in the Sheet and how it was read. */
export interface RowInfo {
  /** 1-based row number in its tab (0 for a record that is not in the Sheet yet). */
  row: number
  /** False when the visible cells no longer match the stored hash (edited by hand in the Sheet). */
  hashMatches: boolean
  /** False when the row had no id (added by hand in the Sheet). */
  hadId: boolean
}

export interface Entry extends RowInfo {
  id: string
  type: EntryType
  year: number
  /** yyyy-MM-dd */
  date: string
  category: string
  name: string
  description: string
  /** Paise. */
  amount: number
  /** Who collected the money (income) or paid (spending). Blank on income means "not collected yet". */
  person: string
  createdBy: string
  createdAt: number
  updatedBy: string
  updatedAt: number
  deletedAt: number | null
  rev: number
}

/** One phone-call target for one year; a person called every year has one row per year. */
export interface Contact extends RowInfo {
  /** `${contactId}:${year}` */
  id: string
  contactId: string
  year: number
  name: string
  number: string
  caller: string
  called: boolean
  remarks: string
  createdBy: string
  createdAt: number
  updatedBy: string
  updatedAt: number
  deletedAt: number | null
  rev: number
}

export const contactRowId = (contactId: string, year: number) => `${contactId}:${year}`

export const isDeleted = (r: { deletedAt: number | null }) => r.deletedAt !== null

/**
 * Money that has been promised but not yet collected by anyone: income with an amount and no collector.
 * A gift in kind (amount 0) is handed to the committee directly, so it is never "not collected".
 */
export const isPending = (e: Pick<Entry, 'type' | 'person' | 'amount'>) => ENTRY_TYPES[e.type].isIncome && e.person.trim() === '' && e.amount > 0

/** FNV-1a 64-bit over UTF-16 code units, as lower-case hex without padding. Identical to the Android app's `fnv`. */
export function fnv(text: string): string {
  let h = 0xcbf29ce484222325n
  const prime = 0x100000001b3n
  const mask = (1n << 64n) - 1n
  for (let i = 0; i < text.length; i++) {
    h ^= BigInt(text.charCodeAt(i))
    h = (h * prime) & mask
  }
  return h.toString(16)
}

export function entryHash(e: Pick<Entry, 'date' | 'type' | 'category' | 'name' | 'description' | 'amount' | 'person' | 'deletedAt'>): string {
  return fnv(`${e.date}|${ENTRY_TYPES[e.type].sheetName}|${e.category.trim()}|${e.name.trim()}|${e.description.trim()}|${e.amount}|${e.person.trim()}|${e.deletedAt !== null ? 1 : 0}`)
}

export function contactHash(c: Pick<Contact, 'year' | 'name' | 'number' | 'caller' | 'called' | 'remarks' | 'deletedAt'>): string {
  return fnv(`${c.year}|${c.name.trim()}|${c.number.trim()}|${c.caller.trim()}|${c.called}|${c.remarks.trim()}|${c.deletedAt !== null ? 1 : 0}`)
}

export function newId(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}
