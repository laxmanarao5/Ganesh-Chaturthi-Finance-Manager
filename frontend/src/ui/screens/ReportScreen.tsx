import { useMemo, useState } from 'react'
import { downloadBytes } from '../../lib/csv'
import { formatRupees } from '../../lib/format'
import { abstractIndex, coveredByMerges, isMoney, REPORT_LANGS, reportBaseName, type Report, type ReportCell, type ReportSheet } from '../../lib/report'
import { writeXlsx, XLSX_MIME } from '../../lib/xlsx'
import { ROLES } from '../../lib/model'
import { Chip, ChipRow, EmptyState, Modal, Spinner } from '../components'
import { useApp, useCore } from '../hooks'
import { Icon } from '../Icon'

const cellText = (v: ReportCell | undefined) => (v === null || v === undefined ? '' : isMoney(v) ? formatRupees(v.paise).replace('₹', '') : String(v))

/** One tab of the report as a table; it scrolls sideways on a phone. */
function SheetTable({ sheet }: { sheet: ReportSheet }) {
  const merges = useMemo(() => new Map(sheet.merges.map((m) => [`${m.row},${m.col}`, m])), [sheet])
  const hidden = useMemo(() => {
    const h = coveredByMerges(sheet)
    for (const m of sheet.merges) h.delete(`${m.row},${m.col}`)
    return h
  }, [sheet])
  const columns = Math.max(sheet.widths.length, ...sheet.rows.map((r) => r.cells.length))
  // Fixed column widths add up to the table's width, so a wide tab scrolls sideways on a phone instead of being squeezed.
  const tableWidth = Array.from({ length: columns }, (_, c) => (sheet.widths[c] ?? 8) * 8).reduce((a, b) => a + b, 0)
  return (
    <div className="report-scroll">
      <table className="report-table" style={{ width: tableWidth }}>
        <colgroup>{Array.from({ length: columns }, (_, c) => <col key={c} style={{ width: `${(sheet.widths[c] ?? 8) * 8}px` }} />)}</colgroup>
        <tbody>
          {sheet.rows.map((row, r) => {
            const tds = []
            for (let c = 0; c < columns; c++) {
              if (hidden.has(`${r},${c}`)) continue
              const m = merges.get(`${r},${c}`)
              const v = row.cells[c]
              tds.push(<td key={c} colSpan={m ? m.lastCol - c + 1 : 1} className={isMoney(v) ? 'num' : undefined}>{cellText(v)}</td>)
            }
            return <tr key={r} className={`rr-${row.style.toLowerCase()}${row.cells.length === 0 ? ' rr-blank' : ''}`}>{tds}</tr>
          })}
        </tbody>
      </table>
    </div>
  )
}

/** The generated annual report: every tab of the committee's workbook on screen, plus download, share and "share with Google". */
export function ReportScreen() {
  const core = useCore()
  const s = useApp()
  const report: Report | null = s.report
  const [tab, setTab] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)

  if (!report) return <div className="page"><EmptyState icon="report" title="No report yet" hint="Open Reports, choose the year and press Generate report." /></div>
  const current = Math.min(tab ?? abstractIndex(report), report.sheets.length - 1)
  const canShare = ROLES[s.role].canEdit
  const fileName = `${reportBaseName(report)}.xlsx`
  const bytes = () => writeXlsx(report.sheets, abstractIndex(report))
  const canShareFile = typeof navigator !== 'undefined' && typeof navigator.canShare === 'function' &&
    navigator.canShare({ files: [new File([new Uint8Array(1)], 'x.xlsx', { type: XLSX_MIME })] })
  const share = s.reportShare

  async function shareFile() {
    try {
      const file = new File([bytes()], fileName, { type: XLSX_MIME })
      await navigator.share({ files: [file], title: fileName })
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) core.toast('Could not share the file. Download it instead.', { error: true })
    }
  }

  return (
    <div className="page">
      <div className="toolbar">
        <b>{report.year}</b>
        {REPORT_LANGS.map((l) => (
          <Chip key={l.key} selected={report.lang === l.key} onClick={() => { setBusy(true); void core.generateReport(report.year, l.key).then(() => setBusy(false)) }}>{l.label}</Chip>
        ))}
      </div>
      <ChipRow>{report.sheets.map((sh, i) => <Chip key={sh.name} selected={current === i} onClick={() => setTab(i)}>{sh.name}</Chip>)}</ChipRow>
      {busy ? <Spinner label="Generating…" /> : <SheetTable sheet={report.sheets[current]} />}

      <div className="button-row">
        <button type="button" className="btn primary" onClick={() => downloadBytes(fileName, bytes(), XLSX_MIME)}><Icon name="download" size={18} /> Download Excel</button>
        {canShareFile && <button type="button" className="btn outline" onClick={() => void shareFile()}>Share file</button>}
        {canShare && <button type="button" className="btn outline" disabled={share.status === 'working'} onClick={() => void core.shareReportWithGoogle()}>Share with Google (Sheet for all app users)</button>}
      </div>
      {!canShare && <p className="muted small-text">Only an Editor or Admin can share the report as a Google Sheet. You can download the Excel file and send it on WhatsApp.</p>}

      {share.status === 'working' && (
        <Modal title="Sharing with Google" onClose={() => undefined}><Spinner label="Creating the Google Sheet and sharing it with everyone on the Users list…" /></Modal>
      )}
      {share.status === 'done' && (
        <Modal title="Report shared" onClose={() => core.dismissReportShare()} actions={<>
          <button type="button" className="btn text" onClick={() => core.dismissReportShare()}>OK</button>
          <a className="btn text" href={share.url} target="_blank" rel="noreferrer noopener"><Icon name="open" size={18} /> Open the Sheet</a>
        </>}>
          <div className="stack">
            <p>A new Google Sheet "Vinayaka Finance report {report.year}" was created in your Google Drive.</p>
            {share.lines.map((l) => <p key={l}>{l}</p>)}
          </div>
        </Modal>
      )}
      {share.status === 'failed' && (
        <Modal title="Could not share the report" onClose={() => core.dismissReportShare()} actions={<button type="button" className="btn text" onClick={() => core.dismissReportShare()}>OK</button>}>
          <p>{share.message}</p>
        </Modal>
      )}
    </div>
  )
}
