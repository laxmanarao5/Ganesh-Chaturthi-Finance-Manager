// An in-memory Sheet, used by demo mode and by the tests. Behaves like the real one: row numbers start at 2, new rows are appended,
// rewriting a row replaces it, and a year tab exists only once something has been written to it.
import type { AppUser, Contact, Entry } from './model'
import type { SheetsApi } from './sheetsApi'

const clone = <T,>(x: T): T => structuredClone(x)

export class FakeSheets implements SheetsApi {
  years = new Map<number, Entry[]>()
  contacts: Contact[] = []
  users: AppUser[] | null = null
  /** Set to make every write fail, to test error handling. */
  failWrites: string | null = null
  writes = 0

  async listYears() { return [...this.years.keys()].sort((a, b) => a - b) }

  async readYear(year: number) {
    const rows = (this.years.get(year) ?? []).map((e, i) => ({ ...clone(e), row: i + 2 }))
    return { entries: rows, skipped: 0 }
  }

  async readContacts() {
    return { contacts: this.contacts.map((c, i) => ({ ...clone(c), row: i + 2 })), skipped: 0 }
  }

  private guard() {
    if (this.failWrites) throw new Error(this.failWrites)
    this.writes++
  }

  async appendEntries(year: number, entries: Entry[]) {
    this.guard()
    const tab = this.years.get(year) ?? []
    tab.push(...entries.map(clone))
    this.years.set(year, tab)
  }

  async writeEntryRow(year: number, row: number, entry: Entry) {
    this.guard()
    const tab = this.years.get(year)
    if (!tab || !tab[row - 2]) throw new Error('No such row')
    tab[row - 2] = clone(entry)
  }

  async appendContacts(contacts: Contact[]) { this.guard(); this.contacts.push(...contacts.map(clone)) }

  async writeContactRow(row: number, contact: Contact) {
    this.guard()
    if (!this.contacts[row - 2]) throw new Error('No such row')
    this.contacts[row - 2] = clone(contact)
  }

  async readUsers() { return this.users ? clone(this.users) : null }
  async writeUsers(users: AppUser[]) { this.guard(); this.users = clone(users) }
}
