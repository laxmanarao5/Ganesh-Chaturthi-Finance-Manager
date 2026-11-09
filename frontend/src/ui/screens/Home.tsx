import { useMemo } from 'react'
import { formatRupees } from '../../lib/format'
import { byCategory, totals } from '../../lib/stats'
import { BarRow, EmptyState, EntryRow, SectionTitle, Spinner, StatTile, YearPicker } from '../components'
import { useEntries } from '../data'
import { useYear } from '../hooks'

export function HomeScreen() {
  const [year, setYear] = useYear()
  const { entries, loading } = useEntries(year)
  const t = useMemo(() => totals(entries), [entries])
  const donationRows = useMemo(() => byCategory(entries, 'DONATION'), [entries])
  const spentRows = useMemo(() => byCategory(entries, 'EXPENDITURE'), [entries])
  const otherTotal = useMemo(() => entries.filter((e) => e.type === 'OTHER').reduce((a, e) => a + e.amount, 0), [entries])

  return (
    <div className="page">
      <YearPicker year={year} onYear={setYear} />
      {loading ? <Spinner label="Loading from the Google Sheet…" /> : entries.length === 0 ? (
        <EmptyState icon="wallet" title={`No records for ${year} yet`} hint="Add donations and expenses from the menu. Everything you save goes straight to the Google Sheet." />
      ) : (
        <>
          <div className="hero">
            <div className="hero-label">Cash in hand · {year}</div>
            <div className="hero-amount">{formatRupees(t.cash)}</div>
            <div className="hero-sub">Collected minus spent</div>
          </div>
          <div className="tiles">
            <StatTile title="Collected" amount={t.collected} tone="income" />
            <StatTile title="Spent" amount={t.spent} tone="expense" />
            <StatTile title="Still to collect" amount={t.toCollect} tone="error" />
          </div>
          <div className="two-col">
            {(donationRows.length > 0 || otherTotal > 0) && (
              <section>
                <SectionTitle>Where it came from</SectionTitle>
                <div className="card pad">
                  {[...donationRows.map(([c, a]) => [`Donations · ${c}`, a] as const), ...(otherTotal > 0 ? [['Other income', otherTotal] as const] : [])].map(([label, amt]) => (
                    <BarRow key={label} label={label} amount={amt} fraction={amt / Math.max(1, t.listed)} tone="income" />
                  ))}
                </div>
              </section>
            )}
            {spentRows.length > 0 && (
              <section>
                <SectionTitle>Where the money went</SectionTitle>
                <div className="card pad">
                  {spentRows.map(([label, amt]) => <BarRow key={label} label={label} amount={amt} fraction={amt / Math.max(1, t.spent)} tone="expense" />)}
                </div>
              </section>
            )}
          </div>
          <SectionTitle>Latest records</SectionTitle>
          <div className="list">
            {entries.slice(0, 5).map((e) => <EntryRow key={e.id} entry={e} />)}
          </div>
        </>
      )}
    </div>
  )
}
