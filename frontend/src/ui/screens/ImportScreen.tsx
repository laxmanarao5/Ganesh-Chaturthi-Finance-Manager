import { useEffect, useState } from 'react'
import { earliestDate, undatedCount, type ImportBundle, type ImportPlan } from '../../lib/importer'
import { formatRupees, todayIso } from '../../lib/format'
import { EmptyState, Spinner } from '../components'
import { useApp, useCore } from '../hooks'
import { ROLES } from '../../lib/model'
import { Icon } from '../Icon'

/** Pick CSV files (the committee's lists, or files exported from the app or portal). They are read in the browser and written to the Sheet. */
export function ImportScreen() {
  const core = useCore()
  const s = useApp()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [names, setNames] = useState<string[]>([])
  const [bundle, setBundle] = useState<ImportBundle | null>(null)
  const [undated, setUndated] = useState(todayIso())
  const [plan, setPlan] = useState<ImportPlan | null>(null)

  useEffect(() => {
    let live = true
    setPlan(null)
    if (bundle) void core.planImport(bundle, undated).then((p) => { if (live) setPlan(p) }).catch((e: unknown) => { if (live) setError(e instanceof Error ? e.message : 'Could not check what is already in the Sheet.') })
    return () => { live = false }
  }, [core, bundle, undated])

  if (!ROLES[s.role].canEdit) return <div className="page"><EmptyState icon="lock" title="Editors only" hint="Only an Editor or Admin can import records." /></div>

  async function pick(files: FileList | null) {
    if (!files || files.length === 0) return
    setBusy(true); setError(null); setBundle(null); setPlan(null)
    try {
      const list = await Promise.all([...files].map(async (f) => {
        if (/\.xlsx?$/i.test(f.name)) throw new Error(`${f.name}: please save each sheet as a CSV file (File, Save as, CSV) and pick those. Excel files can be imported in the Android app.`)
        return { name: f.name, text: await f.text() }
      }))
      const b = core.parseFiles(list)
      if (!core.hasUsableRows(b)) { setError('No usable rows found. Each file needs a header row with at least an Amount column (or Name and Number for contacts).'); return }
      setNames(list.map((f) => f.name)); setBundle(b); setUndated(earliestDate(b) ?? todayIso())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not read that file.')
    } finally {
      setBusy(false)
    }
  }

  const undatedRows = bundle ? undatedCount(bundle) : 0
  return (
    <div className="page narrow-page">
      <div className="card pad note">
        Pick one or more CSV files: the committee's lists (named like <b>Youth Donations</b>, <b>Villagers Donations</b>, <b>Pooja Expenditure</b>, <b>Other inputs</b>, <b>Contacts</b>),
        or a records file exported from the app or this portal. The file name tells the importer what the file is, so keep it. Importing the same file twice adds nothing.
      </div>
      <label className={`btn primary big file-btn${busy ? ' disabled' : ''}`}>
        <Icon name="upload" size={20} /> Choose CSV file(s)
        <input type="file" accept=".csv,text/csv,text/plain" multiple disabled={busy} onChange={(e) => { void pick(e.target.files); e.target.value = '' }} />
      </label>
      {busy && <Spinner label="Reading…" />}
      {error && <p className="error-text" role="alert">{error}</p>}
      {bundle && (
        <>
          <div className="strong">{names.join(', ')}</div>
          <div className="card pad">
            <div className="label">Found (compare these totals with your list)</div>
            {bundle.sections.map((x) => <div key={x.label} className="line"><span>{x.label} · {x.rows}</span>{x.total !== 0 && <span>{formatRupees(x.total)}</span>}</div>)}
            {bundle.ignoredSheets.length > 0 && <div className="muted small-text">Ignored: {bundle.ignoredSheets.join(', ')}</div>}
            {bundle.skippedRows > 0 && <div className="error-text small-text">{bundle.skippedRows} row(s) skipped (unreadable amount or date)</div>}
          </div>
          {(undatedRows > 0 || bundle.contacts.length > 0) && (
            <label className="field">
              <span>{[undatedRows > 0 ? `${undatedRows} row(s) have no date` : '', bundle.contacts.length > 0 ? "the contacts' called status belongs to this date's year" : ''].filter(Boolean).join(', and ')}. Choose the date to use:</span>
              <input type="date" value={undated} onChange={(e) => e.target.value && setUndated(e.target.value)} />
            </label>
          )}
          {plan ? (
            <>
              <div className="strong">
                Will add {plan.entries.length} record(s) and {plan.contacts.length} contact(s)
                {plan.duplicateEntries + plan.duplicateContacts > 0 ? ` (${plan.duplicateEntries + plan.duplicateContacts} already in the Sheet)` : ''}
              </div>
              <button type="button" className="btn primary big" disabled={(plan.entries.length === 0 && plan.contacts.length === 0) || s.busy > 0}
                onClick={() => { void core.applyImport(plan).then((ok) => { if (ok) { setBundle(null); setPlan(null); setNames([]) } }) }}>
                {s.busy > 0 ? 'Writing to the Sheet…' : 'Import'}
              </button>
            </>
          ) : <Spinner label="Checking what is already in the Sheet…" />}
        </>
      )}
    </div>
  )
}
