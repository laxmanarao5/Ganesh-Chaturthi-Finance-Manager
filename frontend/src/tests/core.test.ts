import { describe, expect, it } from 'vitest'
import { Core } from '../core/core'
import { seedDemo } from '../core/demo'
import type { GoogleAuth, Profile } from '../lib/auth'
import { FakeSheets } from '../lib/fakeSheets'
import { entryHash } from '../lib/model'
import { FakeSharing } from '../lib/sharing'
import { SheetsError } from '../lib/sheetsApi'

const YEAR = 2026
const NOW = Date.UTC(YEAR, 8, 20, 10, 0, 0)

function setup(email = 'lakshmana5296@gmail.com') {
  const sheets = new FakeSheets()
  seedDemo(sheets, YEAR, NOW - 100_000)
  const sharing = new FakeSharing([{ id: 'o', email: 'owner@x.com', level: 'OWNER' }])
  const profile: Profile = { email, name: 'Tester', picture: '' }
  const auth = { configured: true, profile, signIn: async () => profile, accessToken: async () => 't', signOut: () => undefined } as unknown as GoogleAuth
  let t = NOW
  const core = new Core({ sheets, auth, sharing: async () => sharing, clock: () => (t += 1000), storage: null })
  return { sheets, sharing, core }
}

async function signedIn(email?: string) {
  const s = setup(email)
  await s.core.signIn()
  return s
}

const live = (core: Core) => (core.getState().entries[YEAR] ?? []).filter((e) => e.deletedAt === null)

describe('access', () => {
  it('lets a listed Editor in and loads the current year and contacts', async () => {
    const { core } = await signedIn('bhanu@example.com')
    const s = core.getState()
    expect(s.phase).toBe('ready')
    expect(s.role).toBe('EDITOR')
    expect(s.myName).toBe('Bhanu')
    expect(s.years).toEqual([YEAR])
    expect(live(core)).toHaveLength(13)
    expect(s.contacts).toHaveLength(5)
  })

  it('keeps strangers out and says why', async () => {
    const { core } = await signedIn('stranger@example.com')
    expect(core.getState().phase).toBe('denied')
    expect(core.getState().deniedMessage).toContain('stranger@example.com')
    expect(core.getState().entries).toEqual({})
  })

  it('lets only the default admin in when the Sheet has no Users tab', async () => {
    const a = setup('lakshmana5296@gmail.com'); a.sheets.users = null
    await a.core.signIn()
    expect(a.core.getState().role).toBe('ADMIN')
    const b = setup('bhanu@example.com'); b.sheets.users = null
    await b.core.signIn()
    expect(b.core.getState().phase).toBe('denied')
  })

  it('shows "no access" when Google will not let the account open the Sheet', async () => {
    const { core, sheets } = setup('bhanu@example.com')
    sheets.readUsers = async () => { throw new SheetsError(403, 'forbidden') }
    await core.signIn()
    expect(core.getState().phase).toBe('denied')
    expect(core.getState().deniedMessage).toContain("can't open")
  })

  it('a Viewer can look but every write is refused before it reaches the Sheet', async () => {
    const { core, sheets } = await signedIn('guest@example.com')
    expect(core.getState().role).toBe('VIEWER')
    const before = sheets.writes
    expect(await core.addEntry({ type: 'DONATION', date: `${YEAR}-09-15`, category: 'Youth', name: 'X', description: '', amount: 100, person: '' })).toBe(false)
    expect(await core.deleteEntry(live(core)[0])).toBe(false)
    expect(sheets.writes).toBe(before)
    expect(core.getState().toast?.error).toBe(true)
  })

  it('demotes an Editor to Viewer when Google refuses the write', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    sheets.failWrites = 'x'
    sheets.appendEntries = async () => { throw new SheetsError(403, 'no') }
    expect(await core.addEntry({ type: 'DONATION', date: `${YEAR}-09-15`, category: 'Youth', name: 'X', description: '', amount: 100, person: '' })).toBe(false)
    expect(core.getState().role).toBe('VIEWER')
  })
})

describe('writing straight to the Sheet', () => {
  it('adds a record as one new row with rev 1 and the signed-in email', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    expect(await core.addEntry({ type: 'DONATION', date: `${YEAR}-09-15`, category: ' Youth ', name: ' New Donor ', description: '', amount: 250000, person: 'Bhanu' })).toBe(true)
    const row = sheets.years.get(YEAR)!.at(-1)!
    expect(row).toMatchObject({ name: 'New Donor', category: 'Youth', amount: 250000, rev: 1, createdBy: 'bhanu@example.com', updatedBy: 'bhanu@example.com', deletedAt: null })
    expect(live(core)).toHaveLength(14)
    expect(live(core).at(-1)!.row).toBe(15)
  })

  it('creates a tab for a new year', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    await core.addEntry({ type: 'OTHER', date: `${YEAR + 1}-01-02`, category: '', name: 'Next', description: '', amount: 100, person: 'Bhanu' })
    expect(sheets.years.get(YEAR + 1)).toHaveLength(1)
    expect(core.getState().years).toEqual([YEAR, YEAR + 1])
  })

  it('edits a record: rev goes up, the hash follows, nothing else moves', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const target = live(core).find((e) => e.name === 'Mahesh')!
    expect(await core.updateEntry(target, { date: target.date, category: target.category, name: 'Mahesh K', description: target.description, amount: 120000, person: 'Venky' })).toBe(true)
    const after = sheets.years.get(YEAR)![target.row - 2]
    expect(after).toMatchObject({ id: target.id, name: 'Mahesh K', amount: 120000, person: 'Venky', rev: 2, updatedBy: 'bhanu@example.com', createdBy: 'demo@local' })
    expect(entryHash(after)).not.toBe(entryHash(target))
    expect(sheets.years.get(YEAR)).toHaveLength(13)
  })

  it('setting a collector on a pending record clears "still to collect"', async () => {
    const { core } = await signedIn('bhanu@example.com')
    const pending = live(core).find((e) => e.name === 'Mahesh')!
    await core.setPerson(pending, 'Bhanu')
    expect(live(core).find((e) => e.name === 'Mahesh')!.person).toBe('Bhanu')
  })

  it('refuses to overwrite a record somebody else changed meanwhile, and shows the fresh one', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const target = live(core).find((e) => e.name === 'Suresh')!
    const theirs = sheets.years.get(YEAR)![target.row - 2]
    sheets.years.get(YEAR)![target.row - 2] = { ...theirs, amount: 999900, rev: theirs.rev + 1 }
    const writes = sheets.writes
    expect(await core.updateEntry(target, { date: target.date, category: target.category, name: 'Mine', description: '', amount: 1, person: 'Bhanu' })).toBe(false)
    expect(sheets.writes).toBe(writes)
    expect(core.getState().toast?.text).toContain('Someone else changed')
    expect(live(core).find((e) => e.id === target.id)!.amount).toBe(999900)
  })

  it('says so when the record was removed from the Sheet by hand', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const target = live(core)[0]
    sheets.years.get(YEAR)!.splice(target.row - 2, 1)
    expect(await core.updateEntry(target, { date: target.date, category: '', name: 'x', description: '', amount: 1, person: '' })).toBe(false)
    expect(core.getState().toast?.text).toContain('no longer in the Sheet')
  })

  it('delete keeps the row as a tombstone (rev+1) and Undo brings it back', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const target = live(core).find((e) => e.name === 'Kiran')!
    expect(await core.deleteEntry(target)).toBe(true)
    const row = sheets.years.get(YEAR)![target.row - 2]
    expect(row.deletedAt).not.toBeNull()
    expect(row.rev).toBe(target.rev + 1)
    expect(sheets.years.get(YEAR)).toHaveLength(13)
    expect(live(core).some((e) => e.id === target.id)).toBe(false)
    const toast = core.getState().toast!
    expect(toast.undo).toBeTypeOf('function')
    toast.undo!()
    await new Promise((r) => setTimeout(r, 0))
    const restored = sheets.years.get(YEAR)![target.row - 2]
    expect(restored.deletedAt).toBeNull()
    expect(restored.rev).toBe(target.rev + 2)
    expect(live(core).some((e) => e.id === target.id)).toBe(true)
  })

  it('a failed write reports the error and leaves the screen as it was', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    sheets.failWrites = 'disk full'
    expect(await core.addEntry({ type: 'DONATION', date: `${YEAR}-09-15`, category: 'Youth', name: 'X', description: '', amount: 100, person: '' })).toBe(false)
    expect(core.getState().toast).toMatchObject({ text: 'disk full', error: true })
    expect(live(core)).toHaveLength(13)
    expect(core.getState().busy).toBe(0)
  })

  it('refresh picks up what other people changed', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    sheets.years.get(YEAR)!.push({ ...sheets.years.get(YEAR)![0], id: 'other', name: 'Added elsewhere', rev: 1 })
    await core.refresh()
    expect(live(core).some((e) => e.name === 'Added elsewhere')).toBe(true)
  })

  it('opens an older year on demand', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    seedDemo(sheets, YEAR - 1, NOW - 9_999_999)
    await core.loadYear(YEAR - 1, true)
    expect(core.getState().entries[YEAR - 1]).toHaveLength(13)
    await core.loadYear(YEAR + 5)
    expect(core.getState().entries[YEAR + 5]).toEqual([])
  })
})

describe('contacts', () => {
  it('marks called for this year only; a new year starts un-called and gets its own row', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const bala = core.getState().contacts.find((c) => c.name === 'Bala')!
    expect(bala.called).toBe(false)
    await core.markCalled(bala.contactId, YEAR, true)
    expect(sheets.contacts.find((c) => c.name === 'Bala')).toMatchObject({ called: true, rev: 2 })
    await core.markCalled(bala.contactId, YEAR + 1, true)
    const rows = sheets.contacts.filter((c) => c.contactId === bala.contactId)
    expect(rows.map((r) => [r.year, r.called, r.rev])).toEqual([[YEAR, true, 2], [YEAR + 1, true, 1]])
    expect(rows[1].name).toBe('Bala')
  })

  it('adds, edits and deletes (every year) a contact', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    await core.addContact(' New Person ', ' 9000000009 ', 'Venky', '', YEAR)
    const added = sheets.contacts.at(-1)!
    expect(added).toMatchObject({ name: 'New Person', number: '9000000009', called: false, rev: 1 })
    await core.editContact(added.contactId, YEAR, 'New Name', '9000000009', 'Bhanu', 'note')
    expect(sheets.contacts.at(-1)).toMatchObject({ name: 'New Name', caller: 'Bhanu', remarks: 'note', rev: 2 })
    await core.markCalled(added.contactId, YEAR + 1, false)
    await core.deleteContact(added.contactId)
    expect(sheets.contacts.filter((c) => c.contactId === added.contactId).every((c) => c.deletedAt !== null)).toBe(true)
    expect(sheets.contacts.filter((c) => c.contactId === added.contactId)).toHaveLength(2)
  })

  it('refuses to change a contact somebody else changed meanwhile', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const c = core.getState().contacts.find((x) => x.name === 'Anand')!
    const i = sheets.contacts.findIndex((x) => x.id === c.id)
    sheets.contacts[i] = { ...sheets.contacts[i], called: false, rev: 2 }
    expect(await core.markCalled(c.contactId, YEAR, false)).toBe(false)
  })
})

describe('import', () => {
  it('reads CSV files, previews, writes and refreshes; a second import adds nothing', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    const bundle = core.parseFiles([
      { name: 'Youth Donations.csv', text: 'Name,Village,Amount,Collected By\nImported One,Place,700,Bhanu\nGift,Place,,\n' },
      { name: 'Contacts.csv', text: 'Name,Number,Who Calls\nImported Contact,9000000010,Bhanu\n' },
    ])
    const plan = await core.planImport(bundle, `${YEAR}-09-14`)
    expect(plan.entries).toHaveLength(2)
    expect(plan.contacts).toHaveLength(1)
    expect(await core.applyImport(plan)).toBe(true)
    expect(sheets.years.get(YEAR)).toHaveLength(15)
    expect(live(core).some((e) => e.name === 'Imported One')).toBe(true)
    expect(core.getState().contacts.some((c) => c.name === 'Imported Contact')).toBe(true)
    const again = await core.planImport(bundle, `${YEAR}-09-14`)
    expect(again.entries).toHaveLength(0)
    expect(again.duplicateEntries).toBe(2)
  })
})

describe('users and sharing', () => {
  it('only an Admin may save users', async () => {
    const { core, sheets } = await signedIn('bhanu@example.com')
    expect(await core.saveUsers([{ name: 'X', email: 'x@x.com', role: 'ADMIN' }])).toBe(false)
    expect(sheets.users!.some((u) => u.email === 'x@x.com')).toBe(false)
  })

  it('saves the list first, then shares the Sheet to match, and reports it', async () => {
    const { core, sheets, sharing } = await signedIn()
    const list = [...sheets.users!, { name: 'New', email: 'New@X.com', role: 'EDITOR' as const }].filter((u) => u.email !== 'guest@example.com')
    expect(await core.saveUsers(list)).toBe(true)
    expect(sheets.users!.map((u) => u.email)).toEqual(['lakshmana5296@gmail.com', 'bhanu@example.com', 'venky@example.com', 'new@x.com'])
    expect(sharing.shares.find((s) => s.email === 'new@x.com')).toMatchObject({ level: 'WRITER' })
    const report = core.getState().sharing!.report!
    expect(report.added).toEqual(expect.arrayContaining(['new@x.com', 'venky@example.com']))
    expect(core.getState().sharing!.error).toBeNull()
  })

  it('keeps the saved list even when sharing cannot be changed, and says why', async () => {
    const { core, sheets } = await signedIn()
    const sheetsFail = new Core({
      sheets, auth: { configured: true, profile: { email: 'lakshmana5296@gmail.com', name: '', picture: '' }, signIn: async () => ({ email: 'lakshmana5296@gmail.com', name: '', picture: '' }), accessToken: async () => 't', signOut: () => undefined } as unknown as GoogleAuth,
      sharing: async () => { throw new Error('Drive access was declined.') }, storage: null,
    })
    await sheetsFail.signIn()
    expect(await sheetsFail.saveUsers([{ name: 'Z', email: 'z@x.com', role: 'VIEWER' }])).toBe(true)
    expect(sheets.users!.some((u) => u.email === 'z@x.com')).toBe(true)
    expect(sheetsFail.getState().sharing).toMatchObject({ report: null, error: 'Drive access was declined.' })
    expect(core.getState().phase).toBe('ready')
  })
})

describe('session', () => {
  it('signing out forgets everything on screen', async () => {
    const { core } = await signedIn()
    core.signOut()
    expect(core.getState()).toMatchObject({ phase: 'signedOut', profile: null, entries: {}, contacts: [] })
  })
})
