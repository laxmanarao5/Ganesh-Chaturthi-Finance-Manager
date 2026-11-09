import { useMemo, useState } from 'react'
import { dayWiseCsv, downloadText, entriesCsv, peopleCsv } from '../../lib/csv'
import { formatRupees, prettyDay } from '../../lib/format'
import { ENTRY_TYPE_KEYS, ENTRY_TYPES, type EntryType } from '../../lib/model'
import { Chip, ChipRow, EmptyState, Spinner, YearPicker } from '../components'
import { useEntries } from '../data'
import { useYear } from '../hooks'
import { Icon } from '../Icon'

export function ReportsScreen() {
  const [year, setYear] = useYear()
  const { entries: all, loading } = useEntries(year)
  const [scope, setScope] = useState<EntryType | null>(null)
  const shown = useMemo(() => all.filter((e) => scope === null || e.type === scope), [all, scope])
  const days = useMemo(() => {
    const m = new Map<string, typeof shown>()
    for (const e of shown) m.set(e.date, [...(m.get(e.date) ?? []), e])
    return [...m.entries()].sort((a, b) => b[0].localeCompare(a[0]))
  }, [shown])

  return (
    <div className="page">
      <YearPicker year={year} onYear={setYear} />
      {loading ? <Spinner label="Loading from the Google Sheet…" /> : all.length === 0 ? (
        <EmptyState icon="report" title={`Nothing to report for ${year}`} hint="Add records first, then come back to export them." />
      ) : (
        <>
          <ChipRow>
            <Chip selected={scope === null} onClick={() => setScope(null)}>All</Chip>
            {ENTRY_TYPE_KEYS.map((t) => <Chip key={t} selected={scope === t} onClick={() => setScope(t)}>{ENTRY_TYPES[t].label}</Chip>)}
          </ChipRow>
          <div className="button-row">
            <button type="button" className="btn primary" onClick={() => downloadText(`vinayaka-${year}-records.csv`, entriesCsv(shown))}><Icon name="download" size={18} /> Records</button>
            <button type="button" className="btn outline" onClick={() => downloadText(`vinayaka-${year}-daywise.csv`, dayWiseCsv(shown))}>Day-wise</button>
            <button type="button" className="btn outline" onClick={() => downloadText(`vinayaka-${year}-people.csv`, peopleCsv(all))}>By person</button>
          </div>
          <div className="list days-grid">
            {days.map(([date, rows]) => {
              const income = rows.filter((r) => ENTRY_TYPES[r.type].isIncome).reduce((a, r) => a + r.amount, 0)
              const spent = rows.filter((r) => !ENTRY_TYPES[r.type].isIncome).reduce((a, r) => a + r.amount, 0)
              return (
                <div key={date} className="card pad">
                  <div className="line"><span className="strong">{prettyDay(date)}</span><span className="muted small-text">{rows.length} record{rows.length === 1 ? '' : 's'}</span></div>
                  <div className="line top"><b className="income">In {formatRupees(income)}</b><b className="expense">Out {formatRupees(spent)}</b></div>
                </div>
              )
            })}
          </div>
        </>
      )}
    </div>
  )
}
