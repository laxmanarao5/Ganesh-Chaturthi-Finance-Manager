// The portal's brain, kept free of React so it can be tested: sign-in state, access, loading from the Sheet, and every write.
// There is no local copy and no sync step. Every change is written straight to the Google Sheet; before changing a record the
// portal re-reads it and refuses if someone else changed it meanwhile (the person is told, and the fresh version is shown).
import { displayName, isDefaultAdmin, normalizeUsers, resolveRole } from '../lib/access'
import { DEFAULT_ADMIN_EMAIL } from '../config'
import { currentYear } from '../lib/format'
import {
  bundleIsEmpty, parseImport, planImport, type ImportBundle, type ImportPlan, type SheetData,
} from '../lib/importer'
import {
  contactHash, contactRowId, entryHash, isDeleted, newId, ROLES,
  type AppUser, type Contact, type Entry, type EntryType, type Role,
} from '../lib/model'
import { reconcileSharing, reportLines, type SharingReport, type SheetSharing } from '../lib/sharing'
import { buildReport, type Report, type ReportLang } from '../lib/report'
import type { ReportPublisher } from '../lib/googleReport'
import { ConflictError, SheetsError, type SheetsApi } from '../lib/sheetsApi'
import { SessionExpired, type GoogleAuth, type Profile } from '../lib/auth'
import { parseCsv } from '../lib/csv'

export type Phase = 'boot' | 'signedOut' | 'checking' | 'denied' | 'ready'
export type ThemeMode = 'system' | 'light' | 'dark'

export interface Toast { id: number; text: string; undo?: () => void; error?: boolean }

export interface AppState {
  phase: Phase
  profile: Profile | null
  role: Role
  users: AppUser[] | null
  /** A message to show on the sign-in / checking screens. */
  authError: string | null
  accessError: string | null
  deniedMessage: string
  /** Year tabs that exist in the Sheet. */
  years: number[]
  /** Records by year (deleted rows included; screens filter them out). Only years that were opened are here. */
  entries: Record<number, Entry[]>
  loadingYears: number[]
  contacts: Contact[]
  contactsLoaded: boolean
  /** Writes in flight. */
  busy: number
  refreshing: boolean
  lastLoaded: number | null
  toast: Toast | null
  theme: ThemeMode
  myName: string
  /** The quiet token renewal failed: show "Sign in again". */
  sessionExpired: boolean
  working: boolean
  sharing: { report: SharingReport | null; error: string | null } | null
  usersLoading: boolean
  /** The report that was last generated; the report screen shows it. */
  report: Report | null
  reportShare: ReportShare
}

/** Progress of "Share with Google" on the report screen. */
export type ReportShare =
  | { status: 'idle' }
  | { status: 'working' }
  | { status: 'done'; url: string; lines: string[] }
  | { status: 'failed'; message: string }

export interface CoreDeps {
  sheets: SheetsApi
  auth?: GoogleAuth | null
  /** Returns the Drive sharing client; for the real one this asks for the Drive scope, so call it straight from a click. */
  sharing?: () => Promise<SheetSharing>
  clock?: () => number
  storage?: Pick<Storage, 'getItem' | 'setItem'> | null
  sheetUrl?: string
  /** Publishes a finished report as a new Google Sheet. */
  reports?: ReportPublisher
  /** The Drive client for a file that was just created. */
  fileSharing?: (fileId: string) => Promise<SheetSharing>
  /** Asks for Drive access (a consent pop-up the first time), so it must be called straight from a click. */
  driveReady?: () => Promise<void>
}

export interface EntryInput {
  type: EntryType; date: string; category: string; name: string; description: string; amount: number; person: string
}

const THEME_KEY = 'vf.theme'
const NAME_KEY = 'vf.myName'

function friendly(e: unknown): string {
  if (e instanceof ConflictError) return e.message
  if (e instanceof SessionExpired) return e.message
  if (e instanceof SheetsError) {
    if (e.isForbidden) return "This Google account can't change the Sheet. Ask an admin to give you Editor access."
    if (e.isNotFound) return 'The Google Sheet could not be found. Check that this portal was built with the right Sheet link.'
    if (e.isAuth) return 'Your Google session ended. Please sign in again.'
    return `Google said: ${e.message}`
  }
  if (e instanceof TypeError) return 'No internet connection. Try again when you are online.'
  return e instanceof Error ? e.message : 'Something went wrong.'
}

export class Core {
  private state: AppState
  private listeners = new Set<() => void>()
  private toastId = 0
  private toastTimer: ReturnType<typeof setTimeout> | null = null
  private now: () => number
  private store: Pick<Storage, 'getItem' | 'setItem'> | null

  constructor(private deps: CoreDeps) {
    this.now = deps.clock ?? Date.now
    this.store = deps.storage === undefined ? (typeof localStorage === 'undefined' ? null : localStorage) : deps.storage
    this.state = {
      phase: 'boot', profile: null, role: 'VIEWER', users: null,
      authError: null, accessError: null, deniedMessage: '',
      years: [], entries: {}, loadingYears: [], contacts: [], contactsLoaded: false,
      busy: 0, refreshing: false, lastLoaded: null, toast: null,
      theme: this.read(THEME_KEY, 'system') as ThemeMode, myName: this.read(NAME_KEY, ''),
      sessionExpired: false, working: false, sharing: null, usersLoading: false,
      report: null, reportShare: { status: 'idle' },
    }
  }

  // ------------------------------------------------------------------------------------------ state plumbing
  getState = () => this.state
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  private set(patch: Partial<AppState>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l())
  }
  private read(key: string, fallback: string) {
    try { return this.store?.getItem(key) ?? fallback } catch { return fallback }
  }
  private save(key: string, value: string) {
    try { this.store?.setItem(key, value) } catch { /* private mode */ }
  }

  get canEdit() { return ROLES[this.state.role].canEdit }
  get canManageUsers() { return ROLES[this.state.role].canManageUsers }
  get who() { return this.state.profile?.email ?? '' }
  get sheetUrl() { return this.deps.sheetUrl ?? '' }

  /** Years offered in every year picker: the Sheet's year tabs, years that have contacts, and the current one. */
  yearOptions(): number[] {
    const s = this.state
    return [...new Set([...s.years, ...s.contacts.map((c) => c.year), ...Object.keys(s.entries).map(Number), currentYear(new Date(this.now()))])].sort((a, b) => b - a)
  }

  // ------------------------------------------------------------------------------------------ messages
  toast(text: string, opts: { undo?: () => void; error?: boolean } = {}) {
    if (this.toastTimer) clearTimeout(this.toastTimer)
    const id = ++this.toastId
    this.set({ toast: { id, text, ...opts } })
    this.toastTimer = setTimeout(() => { if (this.state.toast?.id === id) this.set({ toast: null }) }, opts.undo ? 8000 : 4500)
  }
  dismissToast() { this.set({ toast: null }) }

  setTheme(theme: ThemeMode) { this.save(THEME_KEY, theme); this.set({ theme }) }
  setMyName(name: string) { this.save(NAME_KEY, name); this.set({ myName: name }) }

  /** Turns any failure into a message; a 403 on a write demotes this session to Viewer, like the app does. */
  private fail(e: unknown, quiet = false): string {
    if (e instanceof SessionExpired) this.set({ sessionExpired: true })
    if (e instanceof SheetsError && e.isForbidden && this.canEdit && !isDefaultAdmin(this.who)) this.set({ role: 'VIEWER' })
    const msg = friendly(e)
    if (!quiet) this.toast(msg, { error: true })
    return msg
  }

  // ------------------------------------------------------------------------------------------ sign-in and access
  /** Called once when the page opens: continue quietly if this browser has signed in before. */
  async start() {
    const auth = this.deps.auth
    if (!auth?.configured) { this.set({ phase: 'signedOut' }); return }
    if (!auth.profile) { this.set({ phase: 'signedOut' }); return }
    this.set({ profile: auth.profile, phase: 'checking', accessError: null })
    try {
      await auth.accessToken()
    } catch {
      this.set({ phase: 'signedOut', authError: null, sessionExpired: false })
      return
    }
    await this.checkAccess()
  }

  async signIn() {
    const auth = this.deps.auth
    if (!auth) return
    this.set({ working: true, authError: null })
    try {
      const profile = await auth.signIn()
      this.set({ profile, phase: 'checking', accessError: null, sessionExpired: false, working: false })
      await this.checkAccess()
    } catch (e) {
      this.set({ working: false, authError: friendly(e) })
    }
  }

  /** Reads the Sheet's Users tab and decides whether this Google account may use the portal, and as what. */
  async checkAccess() {
    const profile = this.state.profile
    if (!profile) { this.set({ phase: 'signedOut' }); return }
    this.set({ phase: 'checking', accessError: null })
    try {
      let users: AppUser[] | null
      try {
        users = await this.deps.sheets.readUsers()
      } catch (e) {
        if (e instanceof SheetsError && (e.isForbidden || e.isNotFound)) {
          this.set({
            phase: 'denied', users: null, role: 'VIEWER',
            deniedMessage: e.isForbidden
              ? "This Google account can't open the committee's Google Sheet. Ask an admin to share the Sheet with you, then check again."
              : 'The Google Sheet could not be found. Check that this portal was built with the right Sheet link.',
          })
          return
        }
        throw e
      }
      const role = resolveRole(users, profile.email)
      if (!role) {
        this.set({ phase: 'denied', users, role: 'VIEWER', deniedMessage: `${profile.email} is not on the list of people allowed to use this app. Ask an admin to add you.` })
        return
      }
      const mine = (users ?? []).find((u) => u.email.toLowerCase() === profile.email.toLowerCase())
      this.set({ phase: 'ready', users, role, myName: this.state.myName || (mine ? displayName(mine) : profile.name) })
      await this.refresh()
    } catch (e) {
      this.set({ accessError: this.fail(e, true) })
    }
  }

  signOut() {
    this.deps.auth?.signOut()
    this.set({
      phase: 'signedOut', profile: null, role: 'VIEWER', users: null, entries: {}, contacts: [], contactsLoaded: false,
      years: [], lastLoaded: null, authError: null, accessError: null, sessionExpired: false, sharing: null,
      report: null, reportShare: { status: 'idle' },
    })
  }

  // ------------------------------------------------------------------------------------------ reading
  /** Reads the year list, the contacts and every year screen that is open, fresh from the Sheet. */
  async refresh(quiet = false) {
    if (this.state.phase !== 'ready') return
    this.set({ refreshing: true })
    try {
      const sheets = this.deps.sheets
      const years = await sheets.listYears()
      const open = new Set([...Object.keys(this.state.entries).map(Number), currentYear(new Date(this.now()))])
      const [contacts, ...loaded] = await Promise.all([
        sheets.readContacts(),
        ...[...open].filter((y) => years.includes(y)).map(async (y) => [y, (await sheets.readYear(y)).entries] as const),
      ])
      const entries = { ...this.state.entries }
      for (const [y, list] of loaded) entries[y] = list
      for (const y of open) if (!years.includes(y) && !entries[y]) entries[y] = []
      this.set({ years, entries, contacts: contacts.contacts, contactsLoaded: true, lastLoaded: this.now(), sessionExpired: false })
    } catch (e) {
      this.fail(e, quiet)
    } finally {
      this.set({ refreshing: false })
    }
  }

  /** Makes sure a year's records are loaded (each screen has its own year). */
  async loadYear(year: number, force = false) {
    if (this.state.phase !== 'ready') return
    if (!force && (this.state.entries[year] || this.state.loadingYears.includes(year))) return
    this.set({ loadingYears: [...this.state.loadingYears, year] })
    try {
      const years = this.state.years.includes(year) ? this.state.years : await this.deps.sheets.listYears()
      const list = years.includes(year) ? (await this.deps.sheets.readYear(year)).entries : []
      this.set({ years, entries: { ...this.state.entries, [year]: list } })
    } catch (e) {
      this.fail(e)
    } finally {
      this.set({ loadingYears: this.state.loadingYears.filter((y) => y !== year) })
    }
  }

  // ------------------------------------------------------------------------------------------ writing
  private requireEdit() {
    if (!this.canEdit) throw new Error('You have view-only access. Ask an admin to make you an Editor.')
  }

  /** Runs one write: counts it as in flight, reports failures, and returns whether it worked. */
  private async runWrite<T>(fn: () => Promise<T>): Promise<{ ok: true; value: T } | { ok: false }> {
    this.set({ busy: this.state.busy + 1 })
    try {
      this.requireEdit()
      return { ok: true, value: await fn() }
    } catch (e) {
      this.fail(e)
      return { ok: false }
    } finally {
      this.set({ busy: this.state.busy - 1 })
    }
  }

  private async rereadYear(year: number) {
    const list = (await this.deps.sheets.readYear(year)).entries
    this.set({ entries: { ...this.state.entries, [year]: list }, years: this.state.years.includes(year) ? this.state.years : [...this.state.years, year].sort((a, b) => a - b) })
    return list
  }

  async addEntry(input: EntryInput): Promise<boolean> {
    const r = await this.runWrite(async () => {
      if (input.amount < 0 && input.type !== 'EXPENDITURE') throw new Error('Only Expenditure can have a negative amount (a return).')
      const now = this.now()
      const e: Entry = {
        id: newId(), type: input.type, year: Number(input.date.slice(0, 4)), date: input.date,
        category: input.category.trim(), name: input.name.trim(), description: input.description.trim(),
        amount: input.amount, person: input.person.trim(),
        createdBy: this.who, createdAt: now, updatedBy: this.who, updatedAt: now, deletedAt: null, rev: 1,
        row: 0, hashMatches: true, hadId: true,
      }
      await this.deps.sheets.appendEntries(e.year, [e])
      await this.rereadYear(e.year)
    })
    return r.ok
  }

  /**
   * Changes one record in the Sheet. Re-reads it first: if its content or rev differs from what this screen showed, someone else
   * edited it, so nothing is written and the fresh version is shown instead.
   */
  private async mutateEntry(entry: Entry, change: (cur: Entry) => Entry): Promise<Entry> {
    const fresh = await this.rereadYear(entry.year)
    const cur = fresh.find((x) => (entry.hadId ? x.id === entry.id : x.row === entry.row))
    if (!cur) throw new ConflictError('That record is no longer in the Sheet. The list has been refreshed.')
    if (cur.rev !== entry.rev || entryHash(cur) !== entryHash(entry)) throw new ConflictError('Someone else changed this record just now. The latest version is shown; please try again.')
    const next = change(cur)
    await this.deps.sheets.writeEntryRow(cur.year, cur.row, next)
    await this.rereadYear(cur.year)
    return next
  }

  async updateEntry(entry: Entry, c: Omit<EntryInput, 'type'>): Promise<boolean> {
    const r = await this.runWrite(() => this.mutateEntry(entry, (cur) => {
      if (c.amount < 0 && cur.type !== 'EXPENDITURE') throw new Error('Only Expenditure can have a negative amount (a return).')
      return {
        ...cur, date: c.date, category: c.category.trim(), name: c.name.trim(), description: c.description.trim(),
        amount: c.amount, person: c.person.trim(), updatedBy: this.who, updatedAt: this.now(), rev: cur.rev + 1,
        id: cur.hadId ? cur.id : newId(), createdBy: cur.createdBy || this.who, createdAt: cur.createdAt || this.now(),
      }
    }))
    return r.ok
  }

  setPerson(entry: Entry, person: string) {
    return this.updateEntry(entry, { date: entry.date, category: entry.category, name: entry.name, description: entry.description, amount: entry.amount, person })
  }

  /** Deleting keeps the row in the Sheet (greyed and struck through) so other devices learn about it. */
  async deleteEntry(entry: Entry): Promise<boolean> {
    const r = await this.runWrite(() => this.mutateEntry(entry, (cur) => ({
      ...cur, deletedAt: this.now(), updatedAt: this.now(), updatedBy: this.who, rev: cur.rev + 1, id: cur.hadId ? cur.id : newId(),
    })))
    if (!r.ok) return false
    const tombstone = this.state.entries[entry.year]?.find((x) => x.id === r.value.id)
    this.toast(`Deleted "${entry.name.slice(0, 28)}"`, { undo: () => { if (tombstone) void this.undoDelete(tombstone) } })
    return true
  }

  async undoDelete(tombstone: Entry): Promise<boolean> {
    const r = await this.runWrite(() => this.mutateEntry(tombstone, (cur) => ({ ...cur, deletedAt: null, updatedAt: this.now(), updatedBy: this.who, rev: cur.rev + 1 })))
    if (r.ok) this.toast('Restored')
    return r.ok
  }

  // ------------------------------------------------------------------------------------------ contacts
  private async rereadContacts() {
    const list = (await this.deps.sheets.readContacts()).contacts
    this.set({ contacts: list, contactsLoaded: true })
    return list
  }

  private async mutateContact(row: Contact, change: (cur: Contact) => Contact) {
    const fresh = await this.rereadContacts()
    const cur = fresh.find((x) => x.id === row.id)
    if (!cur) throw new ConflictError('That contact is no longer in the Sheet. The list has been refreshed.')
    if (cur.rev !== row.rev || contactHash(cur) !== contactHash(row)) throw new ConflictError('Someone else changed this contact just now. The latest version is shown; please try again.')
    await this.deps.sheets.writeContactRow(cur.row, change(cur))
    await this.rereadContacts()
  }

  private liveRows = (contactId: string) => this.state.contacts.filter((c) => c.contactId === contactId && !isDeleted(c))

  async markCalled(contactId: string, year: number, called: boolean): Promise<boolean> {
    const r = await this.runWrite(async () => {
      const rows = this.liveRows(contactId)
      const existing = rows.find((c) => c.year === year)
      const now = this.now()
      if (existing) {
        await this.mutateContact(existing, (cur) => ({ ...cur, called, updatedBy: this.who, updatedAt: now, rev: cur.rev + 1 }))
      } else {
        const master = [...rows].sort((a, b) => b.updatedAt - a.updatedAt)[0]
        if (!master) return
        await this.deps.sheets.appendContacts([{ ...master, id: contactRowId(contactId, year), year, called, createdBy: this.who, createdAt: now, updatedBy: this.who, updatedAt: now, deletedAt: null, rev: 1, row: 0 }])
        await this.rereadContacts()
      }
    })
    return r.ok
  }

  async addContact(name: string, number: string, caller: string, remarks: string, year: number): Promise<boolean> {
    const r = await this.runWrite(async () => {
      const now = this.now()
      const cid = newId()
      await this.deps.sheets.appendContacts([{
        id: contactRowId(cid, year), contactId: cid, year, name: name.trim(), number: number.trim(), caller: caller.trim(), called: false, remarks: remarks.trim(),
        createdBy: this.who, createdAt: now, updatedBy: this.who, updatedAt: now, deletedAt: null, rev: 1, row: 0, hashMatches: true, hadId: true,
      }])
      await this.rereadContacts()
    })
    return r.ok
  }

  /** Edits show everywhere, because the newest row supplies the name, number and caller. */
  async editContact(contactId: string, year: number, name: string, number: string, caller: string, remarks: string): Promise<boolean> {
    const r = await this.runWrite(async () => {
      const rows = this.liveRows(contactId)
      const existing = rows.find((c) => c.year === year)
      const now = this.now()
      const fields = { name: name.trim(), number: number.trim(), caller: caller.trim(), remarks: remarks.trim() }
      if (existing) {
        await this.mutateContact(existing, (cur) => ({ ...cur, ...fields, updatedBy: this.who, updatedAt: now, rev: cur.rev + 1 }))
      } else {
        const base = [...rows].sort((a, b) => b.updatedAt - a.updatedAt)[0]
        if (!base) return
        await this.deps.sheets.appendContacts([{ ...base, ...fields, id: contactRowId(contactId, year), year, called: false, createdBy: this.who, createdAt: now, updatedBy: this.who, updatedAt: now, deletedAt: null, rev: 1, row: 0 }])
        await this.rereadContacts()
      }
    })
    return r.ok
  }

  async deleteContact(contactId: string): Promise<boolean> {
    const r = await this.runWrite(async () => {
      for (const row of this.liveRows(contactId)) {
        await this.mutateContact(row, (cur) => ({ ...cur, deletedAt: this.now(), updatedAt: this.now(), updatedBy: this.who, rev: cur.rev + 1 }))
      }
    })
    if (r.ok) this.toast('Contact deleted')
    return r.ok
  }

  // ------------------------------------------------------------------------------------------ import
  /** Reads CSV files the same way the app does: the file name is the sheet name. */
  parseFiles(files: { name: string; text: string }[]): ImportBundle {
    const sheets: SheetData[] = files.map((f) => ({ name: f.name.replace(/\.[^.]+$/, ''), rows: parseCsv(f.text) }))
    return parseImport(sheets)
  }

  async planImport(bundle: ImportBundle, undatedDate: string): Promise<ImportPlan> {
    const year = Number(undatedDate.slice(0, 4))
    const years = new Set<number>([year, ...bundle.entries.map((e) => (e.date ? Number(e.date.slice(0, 4)) : year))])
    const existing: Entry[] = []
    for (const y of years) {
      if (!this.state.years.includes(y)) continue
      existing.push(...((await this.deps.sheets.readYear(y)).entries))
    }
    const contacts = (await this.deps.sheets.readContacts()).contacts
    return planImport(bundle, undatedDate, year, existing, contacts, this.who, this.now())
  }

  async applyImport(plan: ImportPlan): Promise<boolean> {
    const r = await this.runWrite(async () => {
      const byYear = new Map<number, Entry[]>()
      for (const e of plan.entries) byYear.set(e.year, [...(byYear.get(e.year) ?? []), e])
      for (const [y, list] of byYear) await this.deps.sheets.appendEntries(y, list)
      if (plan.contacts.length) await this.deps.sheets.appendContacts(plan.contacts)
    })
    if (!r.ok) return false
    await this.refresh(true)
    this.toast(`Imported ${plan.entries.length} record(s) and ${plan.contacts.length} contact(s)`)
    return true
  }

  hasUsableRows = (b: ImportBundle) => !bundleIsEmpty(b)

  // ------------------------------------------------------------------------------------------ users and sharing
  async loadUsers() {
    this.set({ usersLoading: true })
    try {
      this.set({ users: await this.deps.sheets.readUsers() })
    } catch (e) {
      this.fail(e)
    } finally {
      this.set({ usersLoading: false })
    }
  }

  /**
   * Saves the list in the Sheet's Users tab, then makes the Google Sheet's own sharing match it. The list is saved first, so a
   * sharing problem (no Drive access, someone not owning the Sheet) never loses the list; it is reported instead.
   */
  async saveUsers(list: AppUser[]): Promise<boolean> {
    if (!this.canManageUsers) { this.toast('Only an Admin can change the list of users.', { error: true }); return false }
    const before = (this.state.users ?? []).map((u) => u.email.toLowerCase())
    const next = normalizeUsers(list)
    const removed = before.filter((e) => !next.some((u) => u.email === e))
    // Ask for Drive access first, while the click that started this save is still fresh (browsers block pop-ups otherwise).
    let sharing: SheetSharing | null = null
    let sharingError: string | null = null
    try { sharing = (await this.deps.sharing?.()) ?? null } catch (e) { sharingError = friendly(e) }
    this.set({ working: true })
    try {
      await this.deps.sheets.writeUsers(next)
      this.set({ users: next })
    } catch (e) {
      this.set({ working: false })
      this.fail(e)
      return false
    }
    let report: SharingReport | null = null
    if (sharing) {
      try { report = await reconcileSharing(sharing, next, removed, [DEFAULT_ADMIN_EMAIL, this.who]) } catch (e) { sharingError = sharingFriendly(e) }
    } else if (!sharingError) sharingError = 'Google Drive sharing is not available here.'
    this.set({ working: false, sharing: { report, error: sharingError } })
    return true
  }

  /** Makes the Sheet's sharing match the saved list, without changing the list. */
  async fixSharing() {
    if (!this.canManageUsers) return
    let sharing: SheetSharing | null = null
    let error: string | null = null
    try { sharing = (await this.deps.sharing?.()) ?? null } catch (e) { error = friendly(e) }
    this.set({ working: true })
    let report: SharingReport | null = null
    if (sharing) {
      try { report = await reconcileSharing(sharing, this.state.users ?? [], [], [DEFAULT_ADMIN_EMAIL, this.who]) } catch (e) { error = sharingFriendly(e) }
    } else if (!error) error = 'Google Drive sharing is not available here.'
    this.set({ working: false, sharing: { report, error } })
  }

  dismissSharing() { this.set({ sharing: null }) }

  // ------------------------------------------------------------------------------------------ annual report
  /** Builds the report from the Sheet's current records for [year]. Anyone may look at it: it only uses data they can already see. */
  async generateReport(year: number, lang: ReportLang): Promise<void> {
    await this.loadYear(year, true)
    this.set({ report: buildReport(this.state.entries[year] ?? [], year, lang), reportShare: { status: 'idle' } })
  }

  closeReport() { this.set({ report: null, reportShare: { status: 'idle' } }) }
  dismissReportShare() { this.set({ reportShare: { status: 'idle' } }) }

  /**
   * Editors and Admins: creates the report as a new Google Sheet owned by the person who shares it, then shares it with everyone on the Users list
   * (Viewers can view it, Editors and Admins can edit it): the same rule as the committee's own Sheet.
   */
  async shareReportWithGoogle(): Promise<void> {
    const report = this.state.report
    if (!report || this.state.reportShare.status === 'working') return
    if (!this.canEdit) { this.toast('Only an Editor or Admin can share the report as a Google Sheet.', { error: true }); return }
    this.set({ reportShare: { status: 'working' } })
    try {
      await this.deps.driveReady?.() // first, while the click is fresh: the consent pop-up is blocked otherwise
      if (!this.deps.reports || !this.deps.fileSharing) throw new Error('Creating Google Sheets is not available here.')
      const created = await this.deps.reports.create(report)
      const users = normalizeUsers((await this.deps.sheets.readUsers()) ?? [])
      const shared = await reconcileSharing(await this.deps.fileSharing(created.id), users, [], [this.who])
      this.set({ reportShare: { status: 'done', url: created.url, lines: reportLines(shared) } })
    } catch (e) {
      this.set({ reportShare: { status: 'failed', message: reportFriendly(e) } })
    }
  }
}

function reportFriendly(e: unknown): string {
  if (e instanceof SheetsError) {
    if (e.isForbidden && /has not been used|disabled/i.test(e.message)) return 'The Google Sheets or Google Drive API is switched off for this app\'s Google Cloud project. Turn both on (APIs & Services, Library) and try again.'
    if (e.isForbidden) return 'Google would not let this account create the report Sheet. Try signing out and in again.'
  }
  if (e instanceof TypeError) return 'No internet connection. Try again when you are online.'
  return e instanceof Error ? e.message : 'Something went wrong while creating the Google Sheet.'
}

function sharingFriendly(e: unknown): string {
  if (e instanceof SheetsError) {
    if (e.isForbidden) return "Google would not let this account change who the Sheet is shared with. Only the Sheet's owner can do that, and the Drive API must be enabled in the Google Cloud project."
    if (e.isNotFound) return 'The Sheet was not found in Google Drive.'
  }
  return friendly(e)
}
