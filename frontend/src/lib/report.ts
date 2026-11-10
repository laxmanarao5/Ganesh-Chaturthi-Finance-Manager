// The annual report, laid out like the committee's own Excel workbook: one tab per donation list, the Dakshina list, the Annadhanam and
// pooja expenses, and an ABSTRACT. Port of the Android app's report/ReportModel.kt (same tabs, same Telugu headings, same totals).
// Donations count at their listed amount whether or not a collector is recorded (the committee only lists what it received);
// a note says how much has no collector yet. Expenditure may be negative (a return); it simply reduces the expense.
import { formatRupees } from './format'
import { isDeleted, isPending, type Entry } from './model'

export type ReportLang = 'TELUGU' | 'ENGLISH'
export const REPORT_LANGS: { key: ReportLang; label: string }[] = [{ key: 'TELUGU', label: 'తెలుగు' }, { key: 'ENGLISH', label: 'English' }]

export type RowStyle = 'TITLE' | 'HEADER' | 'BODY' | 'TOTAL' | 'SECTION' | 'NOTE'

/** A rupee amount, in paise so nothing is lost. */
export interface Money { paise: number }
/** A cell is text, a [Money], an integer (serial numbers) or null (empty). */
export type ReportCell = string | number | Money | null

export const isMoney = (c: ReportCell | undefined): c is Money => typeof c === 'object' && c !== null

export interface ReportRow { cells: ReportCell[]; style: RowStyle }

/** Zero-based, inclusive cell range that is merged into one cell. */
export interface CellMerge { row: number; col: number; lastRow: number; lastCol: number }

export interface ReportSheet { name: string; rows: ReportRow[]; widths: number[]; merges: CellMerge[] }

export interface Report {
  year: number
  lang: ReportLang
  sheets: ReportSheet[]
  collections: number
  expenses: number
  surplus: number
}

export const ABSTRACT = 'ABSTRACT'
export const reportBaseName = (r: Report) => `vinayaka-finance-report-${r.year}`
export const reportGoogleTitle = (r: Report) => `Vinayaka Finance report ${r.year}`
export const abstractIndex = (r: Report) => Math.max(0, r.sheets.findIndex((s) => s.name === ABSTRACT))

/** Every (row, col) that lies inside a merged range. */
export function coveredByMerges(sheet: ReportSheet): Set<string> {
  const out = new Set<string>()
  for (const m of sheet.merges) for (let r = m.row; r <= m.lastRow; r++) for (let c = m.col; c <= m.lastCol; c++) out.add(`${r},${c}`)
  return out
}

interface Labels {
  sl: string; donor: string; amount: string; total: string
  childDetails: string; village: string; inKindItem: string
  dateRecorded: string; date: string; item: string; quantity: string
  youthTitle(y: number): string; villagersTitle(y: number): string; childrenTitle(y: number): string; outsidersTitle(y: number): string
  dakshinaTitle(y: number): string; annDonTitle(y: number): string; annExpTitle(y: number): string; poojaTitle(y: number): string
  collections: string; annHeading: string; annDonations: string; annExpenses: string; surplusWord: string
  catYouth: string; catVillagers: string; catChildren: string; catOutsiders: string
  otherCollections: string; dakshina: string; annSurplusLine: string; allCollections: string
  expensesSection: string; poojaLine: string; currentSurplus(y: number): string
  pendingNote(amount: string): string
}

/** Headings in both languages. The Telugu ones are the committee workbook's own wording. */
const LABELS: Record<ReportLang, Labels> = {
  TELUGU: {
    sl: 'క్రమ సంక్య', donor: 'ధాత వివరములు', amount: 'మొత్తము రూపాయలలో', total: 'మొత్తము',
    childDetails: 'తండ్రి/ తల్లి / సంరక్షకుడు, గ్రామము', village: 'గ్రామము', inKindItem: 'వస్తువు రూపాన అయినచో వస్తువు పేరు',
    dateRecorded: 'లెక్క కట్టి రాసిన తేదీ', date: 'తేదీ', item: 'వస్తువు పేరు', quantity: 'కొలత/ పరిమాణము',
    youthTitle: (y) => `యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు - ${y}`, villagersTitle: (y) => `గ్రామపురజనుల నుంచి వచ్చిన విరాళాలు - ${y}`,
    childrenTitle: (y) => `చిన్నపిల్లల నుంచి వచ్చిన విరాళాలు - ${y}`, outsidersTitle: (y) => `బయటగ్రామల నుంచి వచ్చిన విరాళాలు - ${y}`,
    dakshinaTitle: (y) => `దక్షిణ ${y}`, annDonTitle: (y) => `అన్నదాన కార్యక్రమానికి వచ్చిన విరాళములు - ${y}`,
    annExpTitle: (y) => `అన్నదానమునకు అయిన ఖర్చులు - ${y}`, poojaTitle: (y) => `గణేష్ పూజ ఖర్చుల వివరములు - ${y}`,
    collections: 'వసూళ్ల వివరములు', annHeading: 'అన్నసమారాధన', annDonations: 'అన్నదాన కార్యక్రమానికి వచ్చిన విరాళములు',
    annExpenses: 'అన్నదానమునకు అయిన ఖర్చులు', surplusWord: 'మిగులు',
    catYouth: 'యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు', catVillagers: 'గ్రామపురజనుల నుంచి వచ్చిన విరాళాలు',
    catChildren: 'చిన్నపిల్లల నుంచి వచ్చిన విరాళాలు', catOutsiders: 'బయటగ్రామల నుంచి వచ్చిన విరాళాలు',
    otherCollections: 'ఇతర ఆదాయం', dakshina: 'దక్షిణ',
    annSurplusLine: 'అన్నదానము కార్యక్రమానికి వచ్చిన విరాళములు నుంచి అన్నదానము ఖర్చులు పోను మిగిలిన మొత్తము',
    allCollections: 'అన్నివిధముల వసూళ్ల మొత్తము', expensesSection: 'గణేష్ పూజ ఖర్చుల వివరములు',
    poojaLine: 'గణేష్ పూజ మరియు సౌండింగ్ ఖర్చులు', currentSurplus: (y) => `ప్రస్తుత సంవత్సర మిగులు - ${y}`,
    pendingNote: (a) => `గమనిక: పై మొత్తములలో ${a} వసూలు చేసిన వ్యక్తి పేరు ఇంకా నమోదు కాలేదు.`,
  },
  ENGLISH: {
    sl: 'Sl. No.', donor: 'Donor details', amount: 'Amount (Rs.)', total: 'Total',
    childDetails: 'Parent / guardian, village', village: 'Village', inKindItem: 'Item name, if given in kind',
    dateRecorded: 'Date recorded', date: 'Date', item: 'Item', quantity: 'Quantity',
    youthTitle: (y) => `Donations from youth association members - ${y}`, villagersTitle: (y) => `Donations from villagers - ${y}`,
    childrenTitle: (y) => `Donations from children - ${y}`, outsidersTitle: (y) => `Donations from other villages - ${y}`,
    dakshinaTitle: (y) => `Dakshina ${y}`, annDonTitle: (y) => `Donations for the Annadhanam programme - ${y}`,
    annExpTitle: (y) => `Annadhanam expenses - ${y}`, poojaTitle: (y) => `Ganesh pooja expenses - ${y}`,
    collections: 'Collections', annHeading: 'Annadhanam', annDonations: 'Donations received for Annadhanam',
    annExpenses: 'Annadhanam expenses', surplusWord: 'Surplus',
    catYouth: 'Donations from youth association members', catVillagers: 'Donations from villagers',
    catChildren: 'Donations from children', catOutsiders: 'Donations from other villages',
    otherCollections: 'Other income', dakshina: 'Dakshina',
    annSurplusLine: 'Annadhanam donations minus Annadhanam expenses',
    allCollections: 'Total of all collections', expensesSection: 'Ganesh pooja expenses',
    poojaLine: 'Ganesh pooja and sound expenses', currentSurplus: (y) => `This year's surplus - ${y}`,
    pendingNote: (a) => `Note: ${a} of the amounts above has no collector recorded yet.`,
  },
}

const DAKSHINA_NAMES = new Set(['dakshina', 'dhakshina', 'dakshna', 'దక్షిణ'])
/** Letters and combining marks only (Telugu vowel signs are marks), lower-cased: "Dhakshina " and "దక్షిణ" both compare cleanly. */
const lettersOf = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{M}]/gu, '')
const isDakshina = (e: Entry) => DAKSHINA_NAMES.has(lettersOf(e.name))

const order = (a: Entry, b: Entry) => a.date.localeCompare(b.date) || a.createdAt - b.createdAt || a.row - b.row
const ddmmyyyy = (iso: string) => (iso.length === 10 ? `${iso.slice(8, 10)}-${iso.slice(5, 7)}-${iso.slice(0, 4)}` : iso)
const money = (e: Entry): Money | null => (e.amount === 0 ? null : { paise: e.amount })
const sum = (xs: { amount: number }[]) => xs.reduce((a, e) => a + e.amount, 0)

function sheet(
  name: string, title: string, header: string[], widths: number[], list: Entry[], totalLabel: string, cells: (i: number, e: Entry) => ReportCell[],
): ReportSheet {
  const last = header.length - 1
  const rows: ReportRow[] = [{ cells: [title], style: 'TITLE' }, { cells: header, style: 'HEADER' }]
  list.forEach((e, i) => rows.push({ cells: cells(i + 1, e), style: 'BODY' }))
  rows.push({ cells: [...Array.from({ length: last }, (_, i) => (i === 0 ? totalLabel : null)), { paise: sum(list) }], style: 'TOTAL' })
  const merges: CellMerge[] = [{ row: 0, col: 0, lastRow: 0, lastCol: last }]
  if (last > 1) merges.push({ row: rows.length - 1, col: 0, lastRow: rows.length - 1, lastCol: last - 1 })
  return { name, rows, widths, merges }
}

export function buildReport(entries: Entry[], year: number, lang: ReportLang): Report {
  const L = LABELS[lang]
  const live = entries.filter((e) => !isDeleted(e) && e.year === year)
  const donations = live.filter((e) => e.type === 'DONATION').sort(order)
  const others = live.filter((e) => e.type === 'OTHER').sort(order)
  const spending = live.filter((e) => e.type === 'EXPENDITURE').sort(order)
  const donors = (cat: string) => donations.filter((e) => e.category.trim() === cat)
  const youth = donors('Youth'), villagers = donors('Villagers'), children = donors('Children')
  const outsiders = donors('Other villagers'), annDon = donors('Annadhanam')
  const knownDonation = new Set(['Youth', 'Villagers', 'Children', 'Other villagers', 'Annadhanam'])
  const group = (list: Entry[]) => {
    const m = new Map<string, Entry[]>()
    for (const e of list) { const k = e.category.trim() || '—'; m.set(k, [...(m.get(k) ?? []), e]) }
    return [...m.entries()]
  }
  const extraDonations = group(donations.filter((e) => !knownDonation.has(e.category.trim())))
  const dakshina = others.filter(isDakshina)
  const auctions = others.filter((e) => !isDakshina(e))
  const annExp = spending.filter((e) => e.category.trim() === 'Annadhanam')
  const pooja = spending.filter((e) => e.category.trim() === 'Pooja')
  const extraSpending = group(spending.filter((e) => e.category.trim() !== 'Annadhanam' && e.category.trim() !== 'Pooja'))

  const sheets: ReportSheet[] = []
  sheets.push(sheet('Youth Donations', L.youthTitle(year), [L.sl, L.donor, L.amount], [8, 44, 18], youth, L.total, (i, e) => [i, e.name, money(e)]))
  sheets.push(sheet('Villagers Donations', L.villagersTitle(year), [L.sl, L.donor, L.amount], [8, 44, 18], villagers, L.total, (i, e) => [i, e.name, money(e)]))
  sheets.push(sheet('Children Donations', L.childrenTitle(year), [L.sl, L.donor, L.childDetails, L.amount], [8, 36, 40, 18], children, L.total, (i, e) => [i, e.name, e.description, money(e)]))
  sheets.push(sheet('OUTSIDERS DONATIONS', L.outsidersTitle(year), [L.sl, L.donor, L.village, L.amount], [8, 36, 28, 18], outsiders, L.total, (i, e) => [i, e.name, e.description, money(e)]))
  sheets.push(sheet('Dakshina', L.dakshinaTitle(year), [L.sl, L.dateRecorded, L.amount], [8, 24, 18], dakshina, L.total, (i, e) => [i, ddmmyyyy(e.date), money(e)]))
  sheets.push(sheet('Annadanam Donation', L.annDonTitle(year), [L.sl, L.donor, L.inKindItem, L.amount], [8, 36, 40, 18], annDon, L.total, (i, e) => [i, e.name, e.description, money(e)]))
  sheets.push(sheet('Annadanam Expenditure', L.annExpTitle(year), [L.sl, L.item, L.quantity, L.amount], [8, 40, 28, 18], annExp, L.total, (i, e) => [i, e.name, e.description, money(e)]))
  sheets.push(sheet('Expenditure', L.poojaTitle(year), [L.sl, L.date, L.item, L.quantity, L.amount], [8, 14, 40, 28, 18], pooja, L.total, (i, e) => [i, ddmmyyyy(e.date), e.name, e.description, money(e)]))

  // ---- ABSTRACT
  const rows: ReportRow[] = []
  const merges: CellMerge[] = []
  rows.push({ cells: [`${ABSTRACT} - ${year}`], style: 'TITLE' }); merges.push({ row: 0, col: 0, lastRow: 0, lastCol: 2 })
  rows.push({ cells: [L.sl, L.collections, L.amount, null, L.annHeading, null], style: 'HEADER' }); merges.push({ row: 1, col: 4, lastRow: 1, lastCol: 5 })

  const block1: [string, number][] = [[L.catYouth, sum(youth)], [L.catVillagers, sum(villagers)], [L.catChildren, sum(children)], [L.catOutsiders, sum(outsiders)]]
  for (const [cat, list] of extraDonations) block1.push([cat, sum(list)])
  const annDonTotal = sum(annDon), annExpTotal = sum(annExp), annSurplus = annDonTotal - annExpTotal
  const right: [string, number][] = [[L.annDonations, annDonTotal], [L.annExpenses, annExpTotal], [L.surplusWord, annSurplus]]
  block1.forEach(([label, amt], i) => {
    const cells: ReportCell[] = [i + 1, label, { paise: amt }]
    if (i < right.length) cells.push(null, right[i][0], { paise: right[i][1] })
    rows.push({ cells, style: 'BODY' })
  })
  const total1 = block1.reduce((a, [, v]) => a + v, 0)
  rows.push({ cells: [null, L.total, { paise: total1 }], style: 'TOTAL' })
  rows.push({ cells: [], style: 'BODY' })

  rows.push({ cells: [L.sl, L.otherCollections, L.amount], style: 'HEADER' })
  // Dakshina first, then every other Other-income entry that was added, then the Annadhanam surplus.
  const block2: [string, number][] = []
  if (dakshina.length > 0) block2.push([L.dakshina, sum(dakshina)])
  for (const e of auctions) block2.push([e.description.trim() === '' ? e.name : `${e.name} (${e.description})`, e.amount])
  block2.push([L.annSurplusLine, annSurplus])
  block2.forEach(([label, amt], i) => rows.push({ cells: [i + 1, label, { paise: amt }], style: 'BODY' }))
  const total2 = block2.reduce((a, [, v]) => a + v, 0)
  rows.push({ cells: [null, L.total, { paise: total2 }], style: 'TOTAL' })
  const allCollections = total1 + total2
  rows.push({ cells: [null, L.allCollections, { paise: allCollections }], style: 'TOTAL' })
  rows.push({ cells: [], style: 'BODY' })

  rows.push({ cells: [L.expensesSection], style: 'SECTION' }); merges.push({ row: rows.length - 1, col: 0, lastRow: rows.length - 1, lastCol: 2 })
  const block3: [string, number][] = [[L.poojaLine, sum(pooja)]]
  for (const [cat, list] of extraSpending) block3.push([cat, sum(list)])
  block3.forEach(([label, amt], i) => rows.push({ cells: [i + 1, label, { paise: amt }], style: 'BODY' }))
  const expenses = block3.reduce((a, [, v]) => a + v, 0)
  rows.push({ cells: [null, L.total, { paise: expenses }], style: 'TOTAL' })
  rows.push({ cells: [], style: 'BODY' })
  rows.push({ cells: [null, L.currentSurplus(year), { paise: allCollections - expenses }], style: 'TOTAL' })

  const pending = sum(live.filter(isPending))
  if (pending > 0) {
    rows.push({ cells: [], style: 'BODY' })
    rows.push({ cells: [L.pendingNote(formatRupees(pending))], style: 'NOTE' }); merges.push({ row: rows.length - 1, col: 0, lastRow: rows.length - 1, lastCol: 5 })
  }
  sheets.push({ name: ABSTRACT, rows, widths: [7, 64, 18, 3, 46, 18], merges })
  return { year, lang, sheets, collections: allCollections, expenses, surplus: allCollections - expenses }
}
