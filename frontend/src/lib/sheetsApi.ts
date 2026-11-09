// The Sheet as the portal sees it. One interface, two implementations: Google Sheets (real) and an in-memory copy (demo and tests).
import type { AppUser, Contact, Entry } from './model'

export interface SheetsApi {
  /** Year tabs that exist right now, ascending. */
  listYears(): Promise<number[]>
  readYear(year: number): Promise<{ entries: Entry[]; skipped: number }>
  readContacts(): Promise<{ contacts: Contact[]; skipped: number }>
  /** Adds new rows (creating the year tab if needed). */
  appendEntries(year: number, entries: Entry[]): Promise<void>
  /** Overwrites one existing row. */
  writeEntryRow(year: number, row: number, entry: Entry): Promise<void>
  appendContacts(contacts: Contact[]): Promise<void>
  writeContactRow(row: number, contact: Contact): Promise<void>
  /** Null when the Sheet has no Users tab yet. */
  readUsers(): Promise<AppUser[] | null>
  writeUsers(users: AppUser[]): Promise<void>
}

export class SheetsError extends Error {
  constructor(public code: number, message: string) { super(message) }
  /** The signed-in account can see the sheet but may not change it. */
  get isForbidden() { return this.code === 403 }
  get isNotFound() { return this.code === 404 }
  get isAuth() { return this.code === 401 }
}

/** Another person changed the record since this screen loaded it. */
export class ConflictError extends Error {}
