import { useMemo } from 'react'
import { displayName } from '../lib/access'
import { isDeleted, type Entry } from '../lib/model'
import { people } from '../lib/stats'
import { useApp } from './hooks'

/** Newest day first, and within a day the newest record first. */
export const byNewest = (a: Entry, b: Entry) => b.date.localeCompare(a.date) || b.createdAt - a.createdAt || b.row - a.row

/** The live (not deleted) records of one year; `loading` is true until the year has been read from the Sheet. */
export function useEntries(year: number): { entries: Entry[]; loading: boolean } {
  const { entries } = useApp()
  const list = entries[year]
  const live = useMemo(() => (list ?? []).filter((e) => !isDeleted(e)).sort(byNewest), [list])
  return { entries: live, loading: list === undefined }
}

/** Committee names for the collector / payer / caller pickers: the Users list, anyone already named on a record or contact, and me. */
export function usePeople(): string[] {
  const s = useApp()
  return useMemo(() => {
    const named = Object.values(s.entries).flat().filter((e) => !isDeleted(e)).map((e) => e.person)
    const callers = s.contacts.filter((c) => !isDeleted(c)).map((c) => c.caller)
    return people((s.users ?? []).map(displayName), named, callers, s.myName ? [s.myName] : [])
  }, [s.entries, s.contacts, s.users, s.myName])
}
