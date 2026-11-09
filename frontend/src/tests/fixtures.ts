// Made-up committee data for the tests (the real names and numbers never go in this repository).
import { currentYear } from '../lib/format'
import type { FakeSheets } from '../lib/fakeSheets'
import { contactRowId, newId, type Contact, type Entry, type EntryType } from '../lib/model'

export function seedSheet(sheets: FakeSheets, year = currentYear(), now = Date.now()) {
  let n = 0
  const e = (type: EntryType, category: string, date: string, name: string, description: string, amount: number, person: string): Entry => ({
    id: `seed${++n}`, type, year, date: `${year}-${date}`, category, name, description, amount: amount * 100, person,
    createdBy: 'seed@example.com', createdAt: now - n * 1000, updatedBy: 'seed@example.com', updatedAt: now - n * 1000, deletedAt: null, rev: 1,
    row: 0, hashMatches: true, hadId: true,
  })
  sheets.years.set(year, [
    e('DONATION', 'Youth', '09-10', 'Arjun', 'Hostel road', 1100, 'Bhanu'),
    e('DONATION', 'Youth', '09-10', 'Kiran', '', 501, 'Bhanu'),
    e('DONATION', 'Villagers', '09-11', 'Ramesh', 'Main street', 2100, 'Venky'),
    e('DONATION', 'Villagers', '09-11', 'Suresh', 'Temple lane', 1500, 'Laxman'),
    e('DONATION', 'Villagers', '09-12', 'Mahesh', 'Market', 1000, ''),
    e('DONATION', 'Children', '09-12', 'Baby Anu', '', 101, 'Laxman'),
    e('DONATION', 'Other villagers', '09-12', 'Ravi', 'Neighbouring village', 5000, 'Venky'),
    e('DONATION', 'Annadhanam', '09-13', 'Lakshmi', '25 kg rice', 0, ''),
    e('DONATION', 'Annadhanam', '09-13', 'Srinu', '', 3000, 'Laxman'),
    e('EXPENDITURE', 'Pooja', '09-12', 'Flowers', '4 kg', 1450, 'Laxman'),
    e('EXPENDITURE', 'Pooja', '09-13', 'Coconuts', '51', 1275, 'Bhanu'),
    e('EXPENDITURE', 'Annadhanam', '09-14', 'Vegetables', '', 4200, 'Venky'),
    e('OTHER', '', '09-14', 'Dhakshina', '', 1155, 'Laxman'),
  ])
  const c = (name: string, number: string, caller: string, called: boolean, remarks = ''): Contact => {
    const id = newId()
    return {
      id: contactRowId(id, year), contactId: id, year, name, number, caller, called, remarks,
      createdBy: 'seed@example.com', createdAt: now, updatedBy: 'seed@example.com', updatedAt: now, deletedAt: null, rev: 1, row: 0, hashMatches: true, hadId: true,
    }
  }
  sheets.contacts = [
    c('Anand', '9000000001', 'Bhanu', true), c('Bala', '9000000002', 'Bhanu', false, 'Call after 6 pm'),
    c('Chandu', '9000000003', 'Venky', true), c('Deepak', '9000000004', 'Laxman', false), c('Eswar', '9000000005', '', false),
  ]
  sheets.users = [
    { name: 'Laxman', email: 'lakshmana5296@gmail.com', role: 'ADMIN' },
    { name: 'Bhanu', email: 'bhanu@example.com', role: 'EDITOR' },
    { name: 'Venky', email: 'venky@example.com', role: 'EDITOR' },
    { name: 'Guest', email: 'guest@example.com', role: 'VIEWER' },
  ]
}
