import { useMemo, useState } from 'react'
import { ENTRY_TYPES } from '../../lib/model'
import { formatRupees } from '../../lib/format'
import { byPerson, personBreakdown, totals } from '../../lib/stats'
import { EmptyState, SectionTitle, Spinner, YearPicker } from '../components'
import { useEntries } from '../data'
import { useYear } from '../hooks'

/** How much each person collected and how much each spent, for one year. */
export function PeopleScreen() {
  const [year, setYear] = useYear()
  const { entries, loading } = useEntries(year)
  const [open, setOpen] = useState<string | null>(null)
  const t = useMemo(() => totals(entries), [entries])
  const people = useMemo(() => byPerson(entries), [entries])

  return (
    <div className="page">
      <YearPicker year={year} onYear={setYear} />
      {loading ? <Spinner label="Loading from the Google Sheet…" /> : entries.length === 0 ? (
        <EmptyState icon="group" title={`No records for ${year}`} hint="Once donations and expenses are recorded, you'll see here how much each person collected and spent." />
      ) : (
        <>
          <div className="card pad total-lines">
            <div className="label">Everyone together</div>
            <div className="line"><span>Collected</span><b className="income">{formatRupees(t.collected)}</b></div>
            <div className="line"><span>Spent</span><b className="expense">{formatRupees(t.spent)}</b></div>
            <hr />
            <div className="line strong"><span>Cash that should be in hand</span><b>{formatRupees(t.cash)}</b></div>
            {t.toCollect > 0 && <div className="line"><span>Still to collect (no collector yet)</span><b className="error">{formatRupees(t.toCollect)}</b></div>}
          </div>
          <SectionTitle>Each person</SectionTitle>
          <div className="people-grid">
            {people.map((p) => {
              const expanded = open === p.person
              return (
                <div key={p.person} className="card pad person clickable" role="button" tabIndex={0} aria-expanded={expanded}
                  onClick={() => setOpen(expanded ? null : p.person)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(expanded ? null : p.person) } }}>
                  <div className="line"><span className="strong">{p.person}</span><b className={p.net >= 0 ? 'income' : 'expense'}>Net {formatRupees(p.net)}</b></div>
                  <div className="line top">
                    <div><div className="label">Collected</div><div className="income strong">{formatRupees(p.collected)}</div></div>
                    <div className="right"><div className="label">Spent</div><div className="expense strong">{formatRupees(p.spent)}</div></div>
                  </div>
                  {expanded && (
                    <div className="breakdown">
                      {personBreakdown(entries, p.person).map(([type, cat, amt]) => (
                        <div key={`${type}${cat}`} className="line">
                          <span>{(ENTRY_TYPES[type].isIncome ? 'Collected · ' : 'Spent · ') + ENTRY_TYPES[type].label + (cat && cat !== 'Other' ? ` · ${cat}` : '')}</span>
                          <span className={ENTRY_TYPES[type].isIncome ? 'income' : 'expense'}>{formatRupees(amt)}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
          <p className="muted small-text">Net = collected − spent. Positive means that person should be holding that much of the committee's cash; negative means they paid out of pocket and are owed.</p>
        </>
      )}
    </div>
  )
}
