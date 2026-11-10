import { useMemo, useState } from 'react'
import { ENTRY_TYPES, ROLES, isPending, type Entry, type EntryType } from '../../lib/model'
import { formatRupees, monthNames, monthOf, paiseToInput, parseAmountToPaise, parseSignedAmountToPaise, prettyDay } from '../../lib/format'
import { Chip, ChipRow, ConfirmDialog, EmptyState, EntryRow, Modal, PersonChips, PillSelect, Spinner, YearPicker, typeIcon } from '../components'
import { useEntries, usePeople } from '../data'
import { useApp, useCore, useYear } from '../hooks'
import { Icon } from '../Icon'

const NOT_COLLECTED = '\u0000none'

/** Keep digits and at most one point with two decimals; a leading minus survives only when [allowNegative] (Expenditure returns). */
export function cleanAmount(input: string, allowNegative = false): string {
  let out = allowNegative && (input.startsWith('-') || input.startsWith('−')) ? '-' : ''
  let seenDot = false
  let decimals = 0
  for (const ch of input) {
    if (/\d/.test(ch)) { if (!seenDot || decimals < 2) { out += ch; if (seenDot) decimals++ } }
    else if (ch === '.' && !seenDot) { seenDot = true; out += ch }
  }
  return out.slice(0, 12)
}

/** Donations, Expenditure and Other income: one screen shape, filtered by type. */
export function EntryListScreen({ type }: { type: EntryType }) {
  const core = useCore()
  const s = useApp()
  const info = ENTRY_TYPES[type]
  const canEdit = ROLES[s.role].canEdit
  const [year, setYear] = useYear()
  const { entries: all, loading } = useEntries(year)
  const everyone = usePeople()

  const [query, setQuery] = useState('')
  const [month, setMonth] = useState(0)
  const [category, setCategory] = useState('')
  const [who, setWho] = useState('') // '' = everyone, NOT_COLLECTED, or a person's name
  const [editing, setEditing] = useState<Entry | null>(null)
  const [adding, setAdding] = useState(false)
  const [assigning, setAssigning] = useState<Entry | null>(null)

  const ofType = useMemo(() => all.filter((e) => e.type === type), [all, type])
  const categories = useMemo(() => [...new Set([...info.categories, ...ofType.map((e) => e.category.trim()).filter(Boolean)])], [info, ofType])
  const chipPeople = useMemo(() => {
    const named = new Set(ofType.map((e) => e.person.trim()).filter(Boolean))
    const mine = s.myName
    const list = everyone.filter((p) => named.has(p) || p.toLowerCase() === mine.toLowerCase())
    return [...list.filter((p) => p.toLowerCase() === mine.toLowerCase()), ...list.filter((p) => p.toLowerCase() !== mine.toLowerCase())]
  }, [everyone, ofType, s.myName])

  const filtered = useMemo(() => ofType
    .filter((e) => month === 0 || monthOf(e.date) === month)
    .filter((e) => category === '' || e.category.trim() === category)
    .filter((e) => who === '' || (who === NOT_COLLECTED ? isPending(e) : e.person.trim().toLowerCase() === who.toLowerCase()))
    .filter((e) => {
      const q = query.trim().toLowerCase()
      return q === '' || [e.name, e.description, e.category, e.person].some((x) => x.toLowerCase().includes(q))
    }), [ofType, month, category, who, query])

  const total = filtered.reduce((a, e) => a + e.amount, 0)
  const collectedPart = filtered.filter((e) => e.person.trim() !== '').reduce((a, e) => a + e.amount, 0)
  const filtersOn = month !== 0 || category !== '' || who !== '' || query.trim() !== ''
  const groups = useMemo(() => {
    const m = new Map<string, Entry[]>()
    for (const e of filtered) m.set(e.date, [...(m.get(e.date) ?? []), e])
    return [...m.entries()]
  }, [filtered])
  const verb = info.isIncome ? 'Collected by ' : 'Paid by '
  const defaultPerson = who !== '' && who !== NOT_COLLECTED ? who : who === NOT_COLLECTED ? '' : s.myName

  return (
    <div className="page">
      <YearPicker year={year} onYear={setYear}>
        <PillSelect label="Month" value={String(month)} options={[['0', 'All months'], ...monthNames.map((m, i): [string, string] => [String(i + 1), m])]} onChange={(v) => setMonth(Number(v))} />
        {canEdit && <button type="button" className="btn primary desktop-only" onClick={() => setAdding(true)}><Icon name="add" size={20} /> Add {info.label.toLowerCase().replace(/s$/, '')}</button>}
      </YearPicker>

      <label className="search">
        <Icon name="search" size={22} />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={`Search ${info.label.toLowerCase()}`} aria-label={`Search ${info.label.toLowerCase()}`} />
      </label>

      <ChipRow>
        <Chip selected={who === ''} onClick={() => setWho('')}>Everyone</Chip>
        {info.isIncome && <Chip selected={who === NOT_COLLECTED} onClick={() => setWho(NOT_COLLECTED)}>Not collected</Chip>}
        {chipPeople.map((p) => <Chip key={p} selected={who.toLowerCase() === p.toLowerCase()} onClick={() => setWho(p)}>{p}</Chip>)}
      </ChipRow>
      {categories.length > 0 && (
        <ChipRow>
          <Chip selected={category === ''} onClick={() => setCategory('')}>All categories</Chip>
          {categories.map((c) => <Chip key={c} selected={category === c} onClick={() => setCategory(c)}>{c}</Chip>)}
        </ChipRow>
      )}

      {/* The total for whatever is filtered: e.g. choose a name to see how much that person collected. */}
      <div className="card total">
        <div className="label">{who === '' ? (info.isIncome ? 'Total listed' : 'Total spent') : who === NOT_COLLECTED ? 'Not collected yet' : verb + who}</div>
        <div className={`total-amount ${info.isIncome ? 'income' : 'expense'}`}>{formatRupees(total)}</div>
        <div className="muted small-text">
          {filtered.length} {filtered.length === 1 ? 'record' : 'records'}
          {info.isIncome && who === '' ? ` · collected ${formatRupees(collectedPart)} · to collect ${formatRupees(total - collectedPart)}` : ''}
          {filtersOn && who === '' ? ' (filtered)' : ''}
        </div>
      </div>

      {loading ? <Spinner label="Loading from the Google Sheet…" /> : filtered.length === 0 ? (
        <EmptyState
          icon={typeIcon(type)}
          title={filtersOn ? 'Nothing matches' : `No ${info.label.toLowerCase()} for ${year}`}
          hint={filtersOn ? 'Try clearing the search or filters.' : canEdit ? 'Tap Add to record the first one.' : 'Ask an editor to add records.'}
        />
      ) : (
        <div className="list">
          {groups.map(([date, rows]) => (
            <section key={date}>
              <div className="day-head"><span>{prettyDay(date)}</span><span>{formatRupees(rows.reduce((a, e) => a + e.amount, 0))}</span></div>
              <div className="list">
                {rows.map((e) => canEdit
                  ? <EntryRow key={e.id} entry={e} onClick={() => setEditing(e)} onAssign={() => setAssigning(e)} />
                  : <EntryRow key={e.id} entry={e} />)}
              </div>
            </section>
          ))}
        </div>
      )}

      {canEdit && <button type="button" className="fab" onClick={() => setAdding(true)}><Icon name="add" size={22} /> Add</button>}

      {adding && (
        <EntryEditor
          type={type} year={year} people={chipPeople.length ? everyone : everyone} existing={null}
          defaultCategory={category || info.categories[0] || ''} defaultPerson={defaultPerson}
          onSave={(d) => core.addEntry({ type, ...d })} onClose={() => setAdding(false)}
        />
      )}
      {editing && (
        <EntryEditor
          type={type} year={editing.year} people={everyone} existing={editing}
          defaultCategory={editing.category} defaultPerson={editing.person}
          onSave={(d) => core.updateEntry(editing, d)} onDelete={() => core.deleteEntry(editing)} onClose={() => setEditing(null)}
        />
      )}
      {assigning && (
        <Modal title={(info.isIncome ? 'Who collected ' : 'Who paid ') + assigning.name.slice(0, 24) + '?'} onClose={() => setAssigning(null)}>
          <PersonChips
            people={everyone} value={assigning.person} noneLabel={info.isIncome && assigning.amount > 0 ? 'Not collected yet' : 'Not set'}
            onChange={(p) => { const e = assigning; setAssigning(null); void core.setPerson(e, p) }}
          />
        </Modal>
      )}
    </div>
  )
}

export interface EditorValues { date: string; category: string; name: string; description: string; amount: number; person: string }

/** Add or edit one record. The date can be changed freely within the record's year (each year is its own Sheet tab). */
export function EntryEditor({ type, year, existing, people, defaultCategory, defaultPerson, onSave, onDelete, onClose }: {
  type: EntryType; year: number; existing: Entry | null; people: string[]; defaultCategory: string; defaultPerson: string
  onSave: (v: EditorValues) => Promise<boolean>; onDelete?: () => Promise<boolean>; onClose: () => void
}) {
  const s = useApp()
  const info = ENTRY_TYPES[type]
  const today = new Date()
  const initialDate = existing?.date ?? `${year}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  const [date, setDate] = useState(initialDate)
  const [category, setCategory] = useState(existing?.category ?? defaultCategory)
  const [name, setName] = useState(existing?.name ?? '')
  const [description, setDescription] = useState(existing?.description ?? '')
  const [amountText, setAmountText] = useState(existing ? paiseToInput(existing.amount) : '')
  const [person, setPerson] = useState(existing?.person ?? defaultPerson)
  const [saving, setSaving] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const canReturn = type === 'EXPENDITURE' // only spending can be negative: something taken back to the shop
  const amount = canReturn ? parseSignedAmountToPaise(amountText) : parseAmountToPaise(amountText)
  const inKind = type === 'DONATION' && description.trim() !== ''
  const dateOk = date.startsWith(`${year}-`) && !Number.isNaN(Date.parse(date))
  const valid = name.trim() !== '' && amount !== null && (canReturn ? amount !== 0 : amount > 0 || (amount === 0 && inKind)) && dateOk
  const options = [...new Set([...info.categories, ...(existing?.category.trim() ? [existing.category.trim()] : [])])]

  async function submit() {
    if (!valid || amount === null || saving) return
    setSaving(true)
    const ok = await onSave({ date, category, name, description, amount, person })
    setSaving(false)
    if (ok) onClose()
  }

  return (
    <Modal title={(existing ? 'Edit ' : 'Add ') + info.label.toLowerCase().replace(/s$/, '')} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <label className="field"><span>{info.nameLabel}</span><input value={name} onChange={(e) => setName(e.target.value)} autoFocus={!existing} /></label>
        <label className="field"><span>{info.detailLabel}</span><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        <label className="field">
          <span>Amount</span>
          <div className="prefixed"><i>₹</i><input inputMode={canReturn ? 'text' : 'decimal'} value={amountText} onChange={(e) => setAmountText(cleanAmount(e.target.value, canReturn))} /></div>
          {type === 'DONATION' && <small>Leave 0 for a gift in kind (add what it is above)</small>}
          {canReturn && <small>For something returned, use a negative amount (-1610); it shows as +1610 in green</small>}
        </label>
        {canReturn && (
          // Many phone keyboards have no minus key on the number pad, so this chip adds or removes it.
          <ChipRow><Chip selected={amountText.startsWith('-')} onClick={() => setAmountText((t) => (t.startsWith('-') ? t.slice(1) : `-${t}`))}>Return / refund (money back)</Chip></ChipRow>
        )}
        {options.length > 0 && (
          <div className="field"><span>Category</span>
            <ChipRow>{options.map((c) => <Chip key={c} selected={category === c} onClick={() => setCategory(c)}>{c}</Chip>)}</ChipRow>
          </div>
        )}
        <div className="field"><span>{info.personLabel}</span>
          {/* A gift in kind is handed to the committee directly: nobody needs to be named as the collector. */}
          <PersonChips people={people} value={person} onChange={setPerson} noneLabel={info.isIncome && (amount ?? 1) > 0 ? 'Not collected yet' : 'Not set'} />
        </div>
        <label className="field"><span>Date</span>
          <input type="date" value={date} min={`${year}-01-01`} max={`${year}-12-31`} onChange={(e) => setDate(e.target.value)} />
        </label>
        <button type="submit" className="btn primary big" disabled={!valid || saving || s.role === 'VIEWER'}>{saving ? 'Saving…' : existing ? 'Save changes' : 'Save'}</button>
        {existing && onDelete && <button type="button" className="btn text danger" onClick={() => setConfirmDelete(true)}>Delete this record</button>}
      </form>
      {confirmDelete && existing && onDelete && (
        <ConfirmDialog
          title={`Delete "${existing.name.slice(0, 40)}"?`} danger confirmLabel="Delete"
          text="It is removed from the Google Sheet's list (the row stays there, struck through). You can undo right after deleting."
          onCancel={() => setConfirmDelete(false)}
          onConfirm={() => { setConfirmDelete(false); void onDelete().then((ok) => { if (ok) onClose() }) }}
        />
      )}
    </Modal>
  )
}
