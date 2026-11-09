import { describe, expect, it } from 'vitest'
import { normalizeUsers, parseRole, resolveRole } from '../lib/access'
import { dayWiseCsv, entriesCsv, parseCsv, peopleCsv, writeCsv } from '../lib/csv'
import {
  formatRupees, isoToSerial, monthOf, paiseToInput, paiseToSheetNumber, parseAmountToPaise, parseDateFlexible,
  prettyDay, prettyFull, rupeesPlain, serialToIso, sheetNumberToPaise,
} from '../lib/format'
import { bundleIsEmpty, earliestDate, parseImport, planImport, undatedCount } from '../lib/importer'
import { contactHash, entryHash, fnv, isPending, type Contact, type Entry } from '../lib/model'
import { ContactRows, EntryRows } from '../lib/rows'
import { planSharing, reconcileSharing, FakeSharing, reportLines, type Share } from '../lib/sharing'
import { byCategory, byPerson, canonicalPerson, people, personBreakdown, totals } from '../lib/stats'

const e = (p: Partial<Entry> = {}): Entry => ({
  id: 'id1', type: 'DONATION', year: 2026, date: '2026-09-14', category: 'Youth', name: 'Donor', description: 'Village', amount: 150050, person: 'Laxman',
  createdBy: 'a@x.com', createdAt: 1_000, updatedBy: 'a@x.com', updatedAt: 2_000, deletedAt: null, rev: 1, row: 2, hashMatches: true, hadId: true, ...p,
})

describe('money and dates (same as the app)', () => {
  it('formats rupees with Indian grouping', () => {
    expect(formatRupees(123456789)).toBe('₹12,34,567.89')
    expect(formatRupees(150000)).toBe('₹1,500')
    expect(formatRupees(99900)).toBe('₹999')
    expect(formatRupees(100000)).toBe('₹1,000')
    expect(formatRupees(24168750)).toBe('₹2,41,687.50')
    expect(formatRupees(0)).toBe('₹0')
    expect(formatRupees(-5000)).toBe('-₹50')
    expect(formatRupees(5)).toBe('₹0.05')
  })

  it('parses amounts and rejects junk', () => {
    expect(parseAmountToPaise('1,500.50')).toBe(150050)
    expect(parseAmountToPaise('₹10')).toBe(1000)
    expect(parseAmountToPaise('Rs. 5'.replace(/rs\./i, ''))).toBe(500)
    expect(parseAmountToPaise('.5')).toBe(50)
    expect(parseAmountToPaise('0.005')).toBe(1)
    expect(parseAmountToPaise('')).toBeNull()
    expect(parseAmountToPaise('abc')).toBeNull()
    expect(parseAmountToPaise('-5')).toBeNull()
    expect(parseAmountToPaise('1.2.3')).toBeNull()
  })

  it('converts between paise and sheet numbers', () => {
    expect(paiseToSheetNumber(150000)).toBe(1500)
    expect(paiseToSheetNumber(150050)).toBe(1500.5)
    expect(sheetNumberToPaise(1500.5)).toBe(150050)
    expect(sheetNumberToPaise(0.1 + 0.2)).toBe(30)
    expect(paiseToInput(150050)).toBe('1500.5')
    expect(paiseToInput(150000)).toBe('1500')
    expect(rupeesPlain(150050)).toBe('1500.50')
    expect(rupeesPlain(5)).toBe('0.05')
  })

  it('handles dates and Sheet serial numbers', () => {
    expect(isoToSerial('2026-09-14')).toBe(46279)
    expect(serialToIso(46279)).toBe('2026-09-14')
    expect(serialToIso(isoToSerial('2024-02-29'))).toBe('2024-02-29')
    expect(prettyDay('2026-09-14')).toBe('Mon, 14 Sep')
    expect(prettyFull('2026-09-14')).toBe('14 Sep 2026')
    expect(monthOf('2026-09-14')).toBe(9)
    expect(parseDateFlexible('14/09/2026')).toBe('2026-09-14')
    expect(parseDateFlexible('2026-09-14 10:00:00')).toBe('2026-09-14')
    expect(parseDateFlexible('31/02/2026')).toBeNull()
    expect(parseDateFlexible('hello')).toBeNull()
    expect(() => isoToSerial('nope')).toThrow()
  })
})

describe('content hash (must equal the Android app, or phones think portal rows were edited by hand)', () => {
  it('is FNV-1a 64 over UTF-16 code units', () => {
    expect(fnv('')).toBe('cbf29ce484222325')
    expect(fnv('a')).toBe('af63dc4c8601ec8c')
    expect(fnv('foobar')).toBe('85944171f73967e8')
  })

  it('hashes the visible fields in the app order and ignores surrounding spaces', () => {
    expect(entryHash(e())).toBe(fnv('2026-09-14|Donation|Youth|Donor|Village|150050|Laxman|0'))
    expect(entryHash(e({ name: ' Donor ' }))).toBe(entryHash(e()))
    expect(entryHash(e({ deletedAt: 5 }))).toBe(fnv('2026-09-14|Donation|Youth|Donor|Village|150050|Laxman|1'))
    const c = { year: 2026, name: 'A', number: '9', caller: 'B', called: true, remarks: 'r', deletedAt: null }
    expect(contactHash(c)).toBe(fnv('2026|A|9|B|true|r|0'))
  })
})

describe('rows in the Sheet', () => {
  it('round trips an entry through its cells', () => {
    const entry = e({ amount: 150050 })
    const cells = EntryRows.toCells(entry)
    expect(cells[0]).toBe(46279)
    expect(cells[1]).toBe('Donation')
    expect(cells[5]).toBe(1500.5)
    const back = EntryRows.fromCells(cells, 7, 2026)!
    expect(back.hashMatches).toBe(true)
    expect(back.hadId).toBe(true)
    expect({ ...back, row: 2 }).toEqual({ ...entry, row: 2 })
    expect(back.row).toBe(7)
  })

  it('notices a hand edit and a hand-added row', () => {
    const cells = [...EntryRows.toCells(e())]
    cells[5] = 2000
    expect(EntryRows.fromCells(cells, 2, 2026)!.hashMatches).toBe(false)
    cells[6] = 'Someone'
    const added = EntryRows.fromCells(['2026-09-08', 'donation', 'Youth', 'Cash gift', '', 250], 7, 2026)!
    expect(added.hadId).toBe(false)
    expect(added.amount).toBe(25000)
    expect(added.person).toBe('')
    expect(isPending(added)).toBe(true)
  })

  it('reads a blank amount as a gift in kind and skips unreadable rows', () => {
    const inKind = EntryRows.fromCells(['2026-09-08', 'Donation', 'Annadhanam', 'Donor', 'Rice'], 3, 2026)!
    expect(inKind.amount).toBe(0)
    expect(isPending(inKind)).toBe(false)
    expect(EntryRows.fromCells(['not a date', 'Donation', '', 'x', '', 5], 3, 2026)).toBeNull()
    expect(EntryRows.fromCells([46279, 'Mystery', '', 'x', '', 5], 3, 2026)).toBeNull()
    expect(EntryRows.fromCells([46279, 'Donation', '', 'x', '', 'abc'], 3, 2026)).toBeNull()
  })

  it('round trips a contact, including a phone number stored as a number', () => {
    const c: Contact = {
      id: 'cid:2026', contactId: 'cid', year: 2026, name: 'Anand', number: '9000000001', caller: 'Bhanu', called: true, remarks: 'r',
      createdBy: '', createdAt: 1000, updatedBy: 'a@x.com', updatedAt: 2000, deletedAt: null, rev: 3, row: 2, hashMatches: true, hadId: true,
    }
    const back = ContactRows.fromCells(ContactRows.toCells(c), 5)!
    expect(back).toEqual({ ...c, row: 5 })
    const asNumber = ContactRows.fromCells(['Bala', 9000000002, '', 2026, 'yes', ''], 2)!
    expect(asNumber.number).toBe('9000000002')
    expect(asNumber.called).toBe(true)
    expect(ContactRows.fromCells(['', '1', '', 2026], 2)).toBeNull()
    expect(ContactRows.fromCells(['X', '1', '', 'soon'], 2)).toBeNull()
  })
})

describe('totals and people', () => {
  const rows = [
    e({ id: '1', category: 'Youth', amount: 1_000_000, person: 'Laxman' }),
    e({ id: '2', category: 'Villagers', amount: 3_000_000, person: 'Bhanu' }),
    e({ id: '3', category: 'Villagers', amount: 1_000_000, person: '' }),
    e({ id: '4', category: 'Annadhanam', amount: 0, person: '', description: 'rice' }),
    e({ id: '5', type: 'OTHER', category: '', amount: 1_010_000, person: 'Venky' }),
    e({ id: '6', type: 'EXPENDITURE', category: 'Pooja', amount: 245_285, person: 'Laxman' }),
    e({ id: '7', type: 'EXPENDITURE', category: 'Annadhanam', amount: 432_500, person: 'Simhadri' }),
    e({ id: '8', amount: 9_999_999, person: 'Bhanu', deletedAt: 5 }),
  ]

  it('separates collected from still to collect, and ignores gifts in kind and deleted rows', () => {
    const t = totals(rows)
    expect(t.collected).toBe(1_000_000 + 3_000_000 + 1_010_000)
    expect(t.toCollect).toBe(1_000_000)
    expect(t.spent).toBe(245_285 + 432_500)
    expect(t.listed).toBe(t.collected + t.toCollect)
    expect(t.cash).toBe(t.collected - t.spent)
    expect(isPending(rows[3])).toBe(false)
    expect(isPending(rows[2])).toBe(true)
    expect(isPending(rows[5])).toBe(false)
  })

  it('totals per person, biggest collector first', () => {
    const p = byPerson(rows)
    expect(p.map((x) => x.person)).toEqual(['Bhanu', 'Venky', 'Laxman', 'Simhadri']) // 30,000, 10,100, 10,000, 0
    expect(p.find((x) => x.person === 'Laxman')).toMatchObject({ collected: 1_000_000, spent: 245_285, net: 754_715 })
    expect(p.find((x) => x.person === 'Simhadri')!.net).toBe(-432_500)
    expect(p.reduce((a, x) => a + x.collected, 0)).toBe(totals(rows).collected)
  })

  it('totals per category and per-person breakdown', () => {
    expect(byCategory(rows, 'DONATION').map((x) => x[0])).toEqual(['Youth', 'Villagers', 'Annadhanam'])
    expect(byCategory(rows, 'DONATION')[1][1]).toBe(4_000_000)
    expect(personBreakdown(rows, 'laxman').map((x) => `${x[0]}/${x[1]}`)).toEqual(['DONATION/Youth', 'EXPENDITURE/Pooja'])
  })

  it('treats names that differ only in case as one person', () => {
    expect(byPerson([e({ person: 'bhanu', amount: 100 }), e({ person: 'Bhanu ', amount: 200 })])).toHaveLength(1)
    expect(canonicalPerson(' bhanu', ['Laxman', 'Bhanu'])).toBe('Bhanu')
    expect(people(['Zed', 'LAXMAN'], ['laxman', 'Bhanu'])).toEqual(['Bhanu', 'LAXMAN', 'Zed'])
  })
})

describe('access', () => {
  const users = [{ name: 'Bhanu', email: 'bhanu@example.com', role: 'EDITOR' as const }]
  it('only listed emails get in; the default admin always does', () => {
    expect(resolveRole(users, 'Bhanu@Example.com')).toBe('EDITOR')
    expect(resolveRole(users, 'stranger@example.com')).toBeNull()
    expect(resolveRole(null, 'lakshmana5296@gmail.com')).toBe('ADMIN')
    expect(resolveRole(null, 'stranger@example.com')).toBeNull()
    expect(resolveRole(users, '')).toBeNull()
  })
  it('reads roles typed by hand, defaulting to Viewer', () => {
    expect(parseRole('Admin')).toBe('ADMIN')
    expect(parseRole(' edit ')).toBe('EDITOR')
    expect(parseRole('read only')).toBe('VIEWER')
    expect(parseRole(undefined)).toBe('VIEWER')
  })
  it('keeps the default admin first and drops duplicates', () => {
    const n = normalizeUsers([{ name: 'X', email: 'B@x.com', role: 'VIEWER' }, { name: 'Y', email: 'b@x.com', role: 'ADMIN' }])
    expect(n.map((u) => u.email)).toEqual(['lakshmana5296@gmail.com', 'b@x.com'])
    expect(n[0].role).toBe('ADMIN')
  })
})

describe('csv', () => {
  it('parses quotes, escaped quotes, BOM, CRLF and other delimiters', () => {
    expect(parseCsv('﻿a,b\r\n"x, y","say ""hi"""\r\n')).toEqual([['a', 'b'], ['x, y', 'say "hi"']])
    expect(parseCsv('a;b\n1;2')).toEqual([['a', 'b'], ['1', '2']])
    expect(parseCsv('a\tb\n1\t2')).toEqual([['a', 'b'], ['1', '2']])
    expect(parseCsv('a,b\n\n,\n1,2')).toEqual([['a', 'b'], ['1', '2']])
  })
  it('writes what it reads', () => {
    const rows = [['a', 'b,c'], ['say "hi"', 'x\ny']]
    expect(parseCsv(writeCsv(rows))).toEqual(rows)
  })
  it('exports records, day totals and people', () => {
    const rows = [e({ id: '1' }), e({ id: '2', type: 'EXPENDITURE', category: 'Pooja', amount: 5000, person: 'Venky' }), e({ id: '3', deletedAt: 9 })]
    expect(entriesCsv(rows).split('\r\n')[0]).toBe('Date,Type,Category,Name,Description,Amount,Collected / Paid by')
    expect(entriesCsv(rows).split('\r\n')).toHaveLength(4) // header, 2 live rows, trailing empty
    expect(dayWiseCsv(rows)).toContain('2026-09-14,Donation,1,1500.50')
    expect(peopleCsv(rows)).toContain('Still to collect,0.00')
  })
})

describe('import', () => {
  const ledger = (name: string, text: string) => ({ name, rows: parseCsv(text) })

  it('reads an exported file by its Type column and round trips', () => {
    const rows = [e({ id: '1' }), e({ id: '2', type: 'EXPENDITURE', category: 'Pooja', name: 'Flowers', description: '2 kg', amount: 1450, person: 'Venky' }), e({ id: '3', type: 'OTHER', category: '', name: 'Dhakshina', amount: 1000, person: '' })]
    const b = parseImport([ledger('Ledger 2026', entriesCsv(rows))])
    expect(b.entries).toHaveLength(3)
    expect(b.entries.map((x) => x.amount)).toEqual([150050, 1450, 1000])
    expect(b.entries[1]).toMatchObject({ type: 'EXPENDITURE', category: 'Pooja', date: '2026-09-14', person: 'Venky' })
    expect(undatedCount(b)).toBe(0)
    expect(earliestDate(b)).toBe('2026-09-14')
  })

  it('reads the committee lists by sheet name, with undated donors and in-kind gifts', () => {
    const b = parseImport([
      ledger('Youth Donations', 'Name,Village,Amount,Collected By\nDonor One,Village A,500,Collector A\nDonor Two,,,\nTotal,,500,'),
      ledger('Pooja Expenditure', 'Date,Item,Quantity,Amount,Paid By\n12/09/2026,Flowers,2 kg,14.50,Collector A\nbad,Thing,,5,'),
      ledger('Contacts', 'Name,Number,Who Calls,Status,Remarks\nContact One,9000000001,Collector A,yes,\nContact Two,9000000002,,,Later'),
      ledger('Notes', 'just,some,text'),
    ])
    expect(b.entries).toHaveLength(3)
    expect(b.entries[1]).toMatchObject({ name: 'Donor Two', amount: 0, date: null })
    expect(b.entries[2]).toMatchObject({ type: 'EXPENDITURE', date: '2026-09-12', amount: 1450, description: '2 kg' })
    expect(b.skippedRows).toBe(1)
    expect(b.contacts).toHaveLength(2)
    expect(b.contacts[0]).toMatchObject({ called: true, caller: 'Collector A' })
    expect(b.ignoredSheets).toEqual(['Notes'])
    expect(undatedCount(b)).toBe(2)
    expect(bundleIsEmpty(b)).toBe(false)
    expect(bundleIsEmpty(parseImport([ledger('Notes', 'x')]))).toBe(true)
  })

  it('plans an import, gives undated rows the chosen date, and adds nothing the second time', () => {
    const b = parseImport([ledger('Youth Donations', 'Name,Amount,Collected By\nA,100,P\nA,100,P\nB,0,'), ledger('Contacts', 'Name,Number\nC,9000000001')])
    const first = planImport(b, '2026-09-14', 2026, [], [], 'me@x.com', 5)
    expect(first.entries).toHaveLength(3)
    expect(first.entries.every((x) => x.date === '2026-09-14' && x.year === 2026 && x.rev === 1)).toBe(true)
    expect(first.contacts).toHaveLength(1)
    const again = planImport(b, '2026-09-14', 2026, first.entries, first.contacts, 'me@x.com', 6)
    expect(again.entries).toHaveLength(0)
    expect(again.duplicateEntries).toBe(3)
    expect(again.duplicateContacts).toBe(1)
    // A new year starts the same contact as un-called under the same contact id.
    const next = planImport(b, '2027-09-14', 2027, [], first.contacts, 'me@x.com', 7)
    expect(next.contacts[0].contactId).toBe(first.contacts[0].contactId)
    expect(next.contacts[0].year).toBe(2027)
  })
})

describe('sheet sharing', () => {
  const users = [
    { name: 'Boss', email: 'boss@x.com', role: 'ADMIN' as const },
    { name: 'Ed', email: 'ed@x.com', role: 'EDITOR' as const },
    { name: 'Vi', email: 'vi@x.com', role: 'VIEWER' as const },
  ]
  const share = (id: string, email: string, level: Share['level']): Share => ({ id, email, level })

  it('adds people, changes levels, removes those taken off the list and never touches the owner or kept people', () => {
    const existing = [share('1', 'ed@x.com', 'READER'), share('2', 'vi@x.com', 'READER'), share('3', 'gone@x.com', 'WRITER'), share('4', 'boss@x.com', 'OWNER'), share('5', 'me@x.com', 'WRITER')]
    const plan = planSharing(users, ['gone@x.com', 'me@x.com', 'boss@x.com'], existing, ['me@x.com'])
    expect(plan).toEqual([
      { kind: 'change', email: 'ed@x.com', shareId: '1', level: 'WRITER' },
      { kind: 'remove', email: 'gone@x.com', shareId: '3' },
    ])
  })

  it('applies the plan, reports it and carries on after a failure', async () => {
    const sharing = new FakeSharing([share('9', 'stranger@x.com', 'READER')], [{ who: 'Anyone with the link', level: 'READER' }])
    sharing.failFor.add('vi@x.com')
    const report = await reconcileSharing(sharing, users, [], [])
    expect(report.added).toEqual(['boss@x.com', 'ed@x.com'])
    expect(report.failed).toEqual([['vi@x.com', 'Google refused to share with vi@x.com.']])
    expect(report.others).toEqual(['stranger@x.com'])
    expect(sharing.shares.find((s) => s.email === 'ed@x.com')!.level).toBe('WRITER')
    const lines = reportLines(report).join('\n')
    expect(lines).toContain('Shared with boss@x.com, ed@x.com.')
    expect(lines).toContain('Anyone with the link can view the Sheet')
    const calm = await reconcileSharing(new FakeSharing(), [], [], [])
    expect(reportLines(calm)).toEqual(["The Sheet's sharing already matches the list."])
  })
})
