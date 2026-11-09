// CSV in and out. Same format the Android app reads and writes (importer/Csv.kt, export/Exporter.kt), so files move between the two.
import { rupeesPlain } from './format'
import { ENTRY_TYPES, isDeleted, type Entry } from './model'
import { byPerson, totals } from './stats'

/** Parses CSV text (quotes, escaped quotes, CRLF, BOM). Guesses , ; or tab as the delimiter. */
export function parseCsv(input: string): string[][] {
  const text = input.replace(/^﻿/, '')
  const firstLine = text.split('\n', 1)[0] ?? ''
  const count = (d: string) => firstLine.split(d).length - 1
  const delimiter = [',', ';', '\t'].reduce((best, d) => (count(d) > count(best) ? d : best), ',')
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let inQuotes = false
  const endCell = () => { row.push(cell); cell = '' }
  const endRow = () => { endCell(); if (row.some((c) => c.trim() !== '')) rows.push(row); row = [] }
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (inQuotes && c === '"' && text[i + 1] === '"') { cell += '"'; i++ }
    else if (c === '"') inQuotes = !inQuotes
    else if (!inQuotes && c === delimiter) endCell()
    else if (!inQuotes && c === '\n') endRow()
    else if (!inQuotes && c === '\r') { /* ignore */ }
    else cell += c
  }
  if (cell !== '' || row.length > 0) endRow()
  return rows
}

export function writeCsv(rows: string[][]): string {
  return rows.map((r) => r.map((v) => (/[,"\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)).join(',')).join('\r\n') + '\r\n'
}

/** Same columns the importer reads back, so an export can be re-imported. */
export function entriesCsv(entries: Entry[]): string {
  const rows = [...entries].filter((e) => !isDeleted(e)).sort((a, b) =>
    a.date.localeCompare(b.date) || a.type.localeCompare(b.type) || a.createdAt - b.createdAt)
  return writeCsv([
    ['Date', 'Type', 'Category', 'Name', 'Description', 'Amount', 'Collected / Paid by'],
    ...rows.map((e) => [e.date, ENTRY_TYPES[e.type].sheetName, e.category, e.name, e.description, rupeesPlain(e.amount), e.person]),
  ])
}

/** One line per day and type. */
export function dayWiseCsv(entries: Entry[]): string {
  const groups = new Map<string, Entry[]>()
  for (const e of entries.filter((x) => !isDeleted(x))) {
    const k = `${e.date}|${ENTRY_TYPES[e.type].sheetName}`
    groups.set(k, [...(groups.get(k) ?? []), e])
  }
  const keys = [...groups.keys()].sort()
  return writeCsv([
    ['Date', 'Type', 'Entries', 'Total'],
    ...keys.map((k) => {
      const [date, type] = k.split('|')
      const v = groups.get(k)!
      return [date, type, String(v.length), rupeesPlain(v.reduce((a, e) => a + e.amount, 0))]
    }),
  ])
}

/** How much each person collected and spent, plus the money still to collect. */
export function peopleCsv(entries: Entry[]): string {
  const t = totals(entries)
  return writeCsv([
    ['Person', 'Collected', 'Spent', 'Collected - Spent'],
    ...byPerson(entries).map((p) => [p.person, rupeesPlain(p.collected), rupeesPlain(p.spent), rupeesPlain(p.net)]),
    ['Everyone', rupeesPlain(t.collected), rupeesPlain(t.spent), rupeesPlain(t.cash)],
    ['Still to collect', rupeesPlain(t.toCollect), '', ''],
  ])
}

/** Hands the browser a file to save (with a BOM so Excel reads it as UTF-8). */
export function downloadText(fileName: string, text: string) {
  const blob = new Blob(['﻿', text], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
