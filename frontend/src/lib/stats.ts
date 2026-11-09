// Everything the portal calculates, so the Sheet only needs to hold plain rows. Same results as the app (domain/Stats.kt).
import { ENTRY_TYPES, isDeleted, isPending, type Entry, type EntryType } from './model'

export interface Totals {
  /** Income that someone has collected. */
  collected: number
  /** Income promised but not collected by anyone yet (gifts in kind are not counted). */
  toCollect: number
  spent: number
  listed: number
  /** Cash that should be in people's hands: collected minus spent. */
  cash: number
}

export interface PersonTotals { person: string; collected: number; spent: number; net: number }

const live = (entries: Entry[]) => entries.filter((e) => !isDeleted(e))
const sum = (xs: Entry[]) => xs.reduce((a, e) => a + e.amount, 0)

export function totals(entries: Entry[]): Totals {
  const e = live(entries)
  const collected = sum(e.filter((x) => ENTRY_TYPES[x.type].isIncome && x.person.trim() !== ''))
  const toCollect = sum(e.filter((x) => isPending(x)))
  const spent = sum(e.filter((x) => !ENTRY_TYPES[x.type].isIncome))
  return { collected, toCollect, spent, listed: collected + toCollect, cash: collected - spent }
}

/** One row per person who collected or paid anything, biggest collector first. */
export function byPerson(entries: Entry[]): PersonTotals[] {
  const e = live(entries).filter((x) => x.person.trim() !== '')
  const groups = new Map<string, Entry[]>()
  for (const x of e) {
    const k = x.person.trim().toLowerCase()
    groups.set(k, [...(groups.get(k) ?? []), x])
  }
  return [...groups.values()].map((rows) => {
    const collected = sum(rows.filter((r) => ENTRY_TYPES[r.type].isIncome))
    const spent = sum(rows.filter((r) => !ENTRY_TYPES[r.type].isIncome))
    return { person: rows[0].person.trim(), collected, spent, net: collected - spent }
  }).sort((a, b) => b.collected - a.collected || a.person.toLowerCase().localeCompare(b.person.toLowerCase()))
}

/** Amount per category, categories of the type first in their usual order. */
export function byCategory(entries: Entry[], type: EntryType): [string, number][] {
  const sums = new Map<string, number>()
  for (const e of live(entries).filter((x) => x.type === type)) {
    const k = e.category.trim() || 'Other'
    sums.set(k, (sums.get(k) ?? 0) + e.amount)
  }
  const known = ENTRY_TYPES[type].categories
  const ordered: [string, number][] = known.filter((c) => sums.has(c)).map((c) => [c, sums.get(c)!])
  const rest = [...sums.entries()].filter(([k]) => !known.includes(k)).sort((a, b) => b[1] - a[1])
  return [...ordered, ...rest]
}

/** What one person collected or paid, split by type and category. */
export function personBreakdown(entries: Entry[], person: string): [EntryType, string, number][] {
  const p = person.trim().toLowerCase()
  const groups = new Map<string, [EntryType, string, number]>()
  for (const e of live(entries).filter((x) => x.person.trim().toLowerCase() === p)) {
    const cat = e.category.trim() || 'Other'
    const k = `${e.type}|${cat}`
    const cur = groups.get(k)
    groups.set(k, [e.type, cat, (cur?.[2] ?? 0) + e.amount])
  }
  const order: EntryType[] = ['DONATION', 'EXPENDITURE', 'OTHER']
  return [...groups.values()].sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]) || b[2] - a[2])
}

/** Reuse the existing spelling of a name ("bhanu" -> "Bhanu") so one person is not split in two. */
export function canonicalPerson(name: string, known: Iterable<string>): string {
  const t = name.trim()
  for (const k of known) if (k.toLowerCase() === t.toLowerCase()) return k
  return t
}

/** People to offer in pickers: committee members plus anyone already named on a record. */
export function people(members: Iterable<string>, ...extra: Iterable<string>[]): string[] {
  const all = new Map<string, string>()
  for (const list of [members, ...extra]) {
    for (const raw of list) {
      const t = raw.trim()
      if (t && !all.has(t.toLowerCase())) all.set(t.toLowerCase(), t)
    }
  }
  return [...all.values()].sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()))
}
