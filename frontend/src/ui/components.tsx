import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { ENTRY_TYPES, isPending, type Entry, type EntryType } from '../lib/model'
import { formatRupees, prettyDay } from '../lib/format'
import { Icon, type IconName } from './Icon'
import { useApp, useCore } from './hooks'

export const typeIcon = (t: EntryType): IconName => (t === 'DONATION' ? 'heart' : t === 'EXPENDITURE' ? 'wallet' : 'savings')

export function EmptyState({ icon, title, hint, children }: { icon: IconName; title: string; hint?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon"><Icon name={icon} size={40} /></div>
      <h2>{title}</h2>
      {hint && <p>{hint}</p>}
      {children}
    </div>
  )
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 className="section-title">{children}</h3>
}

export function StatTile({ title, amount, tone }: { title: string; amount: number; tone?: 'income' | 'expense' | 'error' }) {
  return (
    <div className="card tile">
      <div className="label">{title}</div>
      <div className={`tile-amount ${tone ?? ''}`}>{formatRupees(amount)}</div>
    </div>
  )
}

/** A labelled amount with a thin bar showing its share of a total. */
export function BarRow({ label, amount, fraction, tone }: { label: string; amount: number; fraction: number; tone: 'income' | 'expense' }) {
  return (
    <div className="bar-row">
      <div className="bar-head"><span>{label}</span><b>{formatRupees(amount)}</b></div>
      <div className="bar"><div className={`bar-fill ${tone}`} style={{ width: `${Math.min(100, Math.max(2, fraction * 100))}%` }} /></div>
    </div>
  )
}

export function Chip({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: ReactNode }) {
  return <button type="button" className={`chip${selected ? ' on' : ''}`} aria-pressed={selected} onClick={onClick}>{children}</button>
}

export function ChipRow({ children }: { children: ReactNode }) {
  return <div className="chips">{children}</div>
}

/** The year selector every screen shows at the top. */
export function YearPicker({ year, onYear, children }: { year: number; onYear: (y: number) => void; children?: ReactNode }) {
  const core = useCore()
  useApp() // re-render when years change
  const options = [...new Set([...core.yearOptions(), year])].sort((a, b) => b - a)
  return (
    <div className="toolbar">
      <label className="pill">
        <span className="sr">Year</span>
        <select value={year} onChange={(e) => onYear(Number(e.target.value))} aria-label="Year">
          {options.map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <Icon name="expand" size={18} />
      </label>
      {children}
    </div>
  )
}

/** A pill-shaped dropdown: shows the current choice (value to label options). */
export function PillSelect({ label, value, options, onChange }: { label: string; value: string; options: [string, string][]; onChange: (v: string) => void }) {
  return (
    <label className={`pill${value !== '' && value !== '0' ? ' active' : ''}`}>
      <span className="sr">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label}>
        {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
      </select>
      <Icon name="expand" size={18} />
    </label>
  )
}

/** Pick a person from chips. [noneLabel] adds a first chip that clears the choice; "+ New" lets you type a name that is not on the list. */
export function PersonChips({ people, value, onChange, noneLabel }: { people: string[]; value: string; onChange: (v: string) => void; noneLabel?: string }) {
  const [typing, setTyping] = useState(false)
  const [draft, setDraft] = useState('')
  const list = value && !people.some((p) => p.toLowerCase() === value.toLowerCase()) ? [...people, value] : people
  return (
    <div className="chips wrap">
      {noneLabel && <Chip selected={value === ''} onClick={() => onChange('')}>{noneLabel}</Chip>}
      {list.map((p) => <Chip key={p} selected={p.toLowerCase() === value.toLowerCase()} onClick={() => onChange(p)}>{p}</Chip>)}
      {typing ? (
        <form className="inline-add" onSubmit={(e) => { e.preventDefault(); if (draft.trim()) { onChange(draft.trim()); setDraft(''); setTyping(false) } }}>
          <input autoFocus value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Name" aria-label="New name" />
          <button type="submit" className="btn small" disabled={!draft.trim()}>Add</button>
        </form>
      ) : (
        <Chip selected={false} onClick={() => setTyping(true)}>+ New</Chip>
      )}
    </div>
  )
}

/** One record. For income, the line under the name says who collected it, or "Not collected" (tap it to say who). */
export function EntryRow({ entry, onClick, onAssign }: { entry: Entry; onClick?: () => void; onAssign?: () => void }) {
  const info = ENTRY_TYPES[entry.type]
  const sub = [entry.category.trim(), entry.description.trim(), prettyDay(entry.date)].filter(Boolean).join(' · ')
  return (
    <div className={`card entry${onClick ? ' clickable' : ''}`} onClick={onClick} role={onClick ? 'button' : undefined} tabIndex={onClick ? 0 : undefined}
      onKeyDown={onClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } } : undefined}>
      <div className={`entry-icon ${info.isIncome ? 'income' : 'expense'}`}><Icon name={typeIcon(entry.type)} size={22} /></div>
      <div className="entry-main">
        <div className="entry-name">{entry.name || info.label}</div>
        <div className="entry-sub">{sub}</div>
        {entry.person.trim() ? (
          <button type="button" className="entry-person" disabled={!onAssign} onClick={(e) => { e.stopPropagation(); onAssign?.() }}>
            {info.isIncome ? 'Collected by ' : 'Paid by '}{entry.person}
          </button>
        ) : isPending(entry) ? (
          <button type="button" className="entry-person bad" disabled={!onAssign} onClick={(e) => { e.stopPropagation(); onAssign?.() }}>
            Not collected{onAssign ? ' · tap to set' : ''}
          </button>
        ) : null}
      </div>
      <div className={`entry-amount ${info.isIncome ? 'income' : 'expense'}`}>
        {entry.amount === 0 ? 'In kind' : (info.isIncome ? '+' : '−') + formatRupees(entry.amount)}
      </div>
    </div>
  )
}

/** A dialog: centred on desktop, a bottom sheet on phones. Closes on Esc or a click outside. */
export function Modal({ title, onClose, children, actions, wide }: { title: string; onClose: () => void; children: ReactNode; actions?: ReactNode; wide?: boolean }) {
  const id = useId()
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = prev }
  }, [onClose])
  return (
    <div className="backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className={`modal${wide ? ' wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={id} ref={ref}>
        <h2 id={id}>{title}</h2>
        <div className="modal-body">{children}</div>
        {actions && <div className="modal-actions">{actions}</div>}
      </div>
    </div>
  )
}

export function ConfirmDialog({ title, text, confirmLabel, danger, onConfirm, onCancel }: {
  title: string; text: string; confirmLabel: string; danger?: boolean; onConfirm: () => void; onCancel: () => void
}) {
  return (
    <Modal title={title} onClose={onCancel} actions={<>
      <button type="button" className="btn text" onClick={onCancel}>Cancel</button>
      <button type="button" className={`btn text${danger ? ' danger' : ''}`} onClick={onConfirm} autoFocus>{confirmLabel}</button>
    </>}>
      <p>{text}</p>
    </Modal>
  )
}

export function Spinner({ label }: { label?: string }) {
  return <div className="spinner-wrap" role="status"><div className="spinner" />{label && <span>{label}</span>}</div>
}

/** The snackbar: a message with an optional Undo. */
export function Toasts() {
  const core = useCore()
  const { toast } = useApp()
  if (!toast) return null
  return (
    <div className={`toast${toast.error ? ' error' : ''}`} role={toast.error ? 'alert' : 'status'}>
      <span>{toast.text}</span>
      {toast.undo && <button type="button" onClick={() => { const u = toast.undo; core.dismissToast(); u?.() }}>Undo</button>}
      <button type="button" aria-label="Dismiss" onClick={() => core.dismissToast()}><Icon name="close" size={18} /></button>
    </div>
  )
}
