// Money is kept in paise (1/100 rupee) so totals are exact. Same rules as the Android app (util/Format.kt).

/** 123456789 -> "₹12,34,567.89" (Indian digit grouping, decimals only when needed). */
export function formatRupees(paise: number): string {
  const negative = paise < 0
  const abs = Math.abs(Math.round(paise))
  const rupees = String(Math.floor(abs / 100))
  const frac = abs % 100
  let grouped = rupees
  if (rupees.length > 3) {
    const head = rupees.slice(0, -3)
    const parts: string[] = []
    for (let i = head.length; i > 0; i -= 2) parts.unshift(head.slice(Math.max(0, i - 2), i))
    grouped = parts.join(',') + ',' + rupees.slice(-3)
  }
  return (negative ? '-' : '') + '₹' + grouped + (frac === 0 ? '' : '.' + String(frac).padStart(2, '0'))
}

/** "1,500.50" -> 150050. Null when the text is not a non-negative amount. */
export function parseAmountToPaise(text: string): number | null {
  const cleaned = text.replace(/,/g, '').replace(/₹/g, '').trim()
  if (!cleaned) return null
  const m = /^(\d*)(?:\.(\d*))?$/.exec(cleaned)
  if (!m || (m[1] === '' && (m[2] ?? '') === '')) return null
  const rupees = Number(m[1] || '0')
  const digits = (m[2] ?? '').padEnd(3, '0')
  let paise = Number(digits.slice(0, 2))
  if (Number(digits[2]) >= 5) paise += 1 // round half up
  return rupees * 100 + paise
}

/**
 * Like parseAmountToPaise but a leading minus is allowed: on Expenditure, something returned to the shop is entered as -1610.
 * Only Expenditure takes a negative amount; income never does.
 */
export function parseSignedAmountToPaise(text: string): number | null {
  const t = text.trim()
  const negative = t.startsWith('-') || t.startsWith('−')
  const v = parseAmountToPaise(negative ? t.slice(1) : t)
  if (v === null) return null
  return negative && v !== 0 ? -v : v
}

/** How an amount reads in a list, and whether it is money coming in (green). A negative Expenditure is a return: "+₹1,610" in green. */
export function amountLabel(isIncome: boolean, amount: number): { text: string; moneyIn: boolean } {
  if (amount === 0) return { text: 'In kind', moneyIn: isIncome }
  if (!isIncome && amount < 0) return { text: '+' + formatRupees(-amount), moneyIn: true }
  if (isIncome) return { text: '+' + formatRupees(amount), moneyIn: true }
  return { text: '−' + formatRupees(amount), moneyIn: false }
}

/** Paise as the number written in the Sheet: whole rupees stay whole, otherwise two decimals. */
export function paiseToSheetNumber(paise: number): number {
  return paise % 100 === 0 ? paise / 100 : Number((paise / 100).toFixed(2))
}

export function sheetNumberToPaise(value: number): number {
  return Math.round(value * 100)
}

/** Text for an amount field: 150050 -> "1500.5", 150000 -> "1500". */
export function paiseToInput(paise: number): string {
  if (paise % 100 === 0) return String(paise / 100)
  return String(Number((paise / 100).toFixed(2)))
}

/** For CSV: always two decimals so Excel sorts and adds it correctly. */
export function rupeesPlain(paise: number): string {
  const sign = paise < 0 ? '-' : ''
  const a = Math.abs(paise)
  return `${sign}${Math.floor(a / 100)}.${String(a % 100).padStart(2, '0')}`
}

const pad = (n: number) => String(n).padStart(2, '0')

export function todayIso(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export function currentYear(now: Date = new Date()): number {
  return now.getFullYear()
}

export const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function parts(iso: string): [number, number, number] | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!m) return null
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const t = new Date(Date.UTC(y, mo - 1, d))
  return t.getUTCFullYear() === y && t.getUTCMonth() === mo - 1 && t.getUTCDate() === d ? [y, mo, d] : null
}

/** "Sun, 14 Sep" */
export function prettyDay(iso: string): string {
  const p = parts(iso)
  if (!p) return iso
  const dow = new Date(Date.UTC(p[0], p[1] - 1, p[2])).getUTCDay()
  return `${dayNames[dow]}, ${p[2]} ${monthNames[p[1] - 1]}`
}

/** "14 Sep 2026" */
export function prettyFull(iso: string): string {
  const p = parts(iso)
  return p ? `${p[2]} ${monthNames[p[1] - 1]} ${p[0]}` : iso
}

export function monthOf(iso: string): number {
  return Number(iso.slice(5, 7)) || 0
}

/** Accepts yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy and "yyyy-MM-dd HH:mm:ss"; returns ISO or null. */
export function parseDateFlexible(text: string): string | null {
  const t = text.trim().split('T')[0].split(' ')[0]
  if (!t) return null
  if (parts(t)) return t
  const m = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$/.exec(t)
  if (m) {
    const iso = `${m[3]}-${pad(Number(m[2]))}-${pad(Number(m[1]))}`
    return parts(iso) ? iso : null
  }
  return null
}

const EPOCH = Date.UTC(1899, 11, 30)

/** Google Sheets / Excel serial date (days since 1899-12-30) to ISO. */
export function serialToIso(serial: number): string {
  return new Date(EPOCH + Math.floor(serial) * 86_400_000).toISOString().slice(0, 10)
}

export function isoToSerial(iso: string): number {
  const p = parts(iso)
  if (!p) throw new Error(`Not a date: ${iso}`)
  return Math.round((Date.UTC(p[0], p[1] - 1, p[2]) - EPOCH) / 86_400_000)
}
