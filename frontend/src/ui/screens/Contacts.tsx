import { useMemo, useState } from 'react'
import { isDeleted, ROLES, type Contact } from '../../lib/model'
import { Chip, ChipRow, ConfirmDialog, EmptyState, Modal, PersonChips, Spinner, YearPicker } from '../components'
import { usePeople } from '../data'
import { useApp, useCore, useYear } from '../hooks'
import { Icon } from '../Icon'

const UNASSIGNED = '\u0000none'

interface ContactView { contactId: string; name: string; number: string; caller: string; remarks: string; called: boolean }

/** One person's details for display: the newest row wins for name/number/caller, and `called` is for the chosen year. */
function views(rows: Contact[], year: number): ContactView[] {
  const groups = new Map<string, Contact[]>()
  for (const r of rows.filter((c) => !isDeleted(c))) groups.set(r.contactId, [...(groups.get(r.contactId) ?? []), r])
  return [...groups.entries()].map(([contactId, list]) => {
    const master = [...list].sort((a, b) => b.updatedAt - a.updatedAt)[0]
    const mine = list.find((c) => c.year === year)
    return { contactId, name: master.name, number: master.number, caller: master.caller, remarks: mine?.remarks ?? master.remarks, called: mine?.called ?? false }
  }).sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
}

/** The people to phone, one list for all years. Each year has its own "called" tick, so a new year starts with everyone un-called. */
export function ContactsScreen() {
  const core = useCore()
  const s = useApp()
  const canEdit = ROLES[s.role].canEdit
  const people = usePeople()
  const [year, setYear] = useYear()
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<'' | 'pending' | 'done'>('')
  const [caller, setCaller] = useState('')
  const [editing, setEditing] = useState<ContactView | null>(null)
  const [adding, setAdding] = useState(false)

  const contacts = useMemo(() => views(s.contacts, year), [s.contacts, year])
  const callers = useMemo(() => {
    const seen = new Map<string, string>()
    for (const c of contacts) if (c.caller.trim() && !seen.has(c.caller.toLowerCase())) seen.set(c.caller.toLowerCase(), c.caller.trim())
    return [...seen.values()].sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()))
  }, [contacts])
  const shown = useMemo(() => contacts
    .filter((c) => status === '' || (status === 'done') === c.called)
    .filter((c) => caller === '' || (caller === UNASSIGNED ? c.caller.trim() === '' : c.caller.toLowerCase() === caller.toLowerCase()))
    .filter((c) => {
      const q = query.trim().toLowerCase()
      return q === '' || c.name.toLowerCase().includes(q) || c.number.includes(q) || c.caller.toLowerCase().includes(q)
    }), [contacts, status, caller, query])
  const done = contacts.filter((c) => c.called).length

  return (
    <div className="page">
      <YearPicker year={year} onYear={setYear}>
        {canEdit && <button type="button" className="btn primary desktop-only" onClick={() => setAdding(true)}><Icon name="add" size={20} /> Add contact</button>}
      </YearPicker>
      {!s.contactsLoaded ? <Spinner label="Loading from the Google Sheet…" /> : contacts.length === 0 ? (
        <EmptyState icon="contacts" title="No contacts yet" hint={canEdit ? 'Add the people to call about donations. Each year starts with everyone un-called.' : 'Contacts will appear here once an editor adds them.'} />
      ) : (
        <>
          <div className="card pad">
            <div className="label">Called in {year}</div>
            <div className="big-number">{done} of {contacts.length}</div>
            <progress value={done} max={contacts.length} aria-label={`${done} of ${contacts.length} called`} />
            {callers.map((c) => {
              const mine = contacts.filter((x) => x.caller.toLowerCase() === c.toLowerCase())
              return <div key={c} className="line"><span>{c}</span><span className="muted">{mine.filter((x) => x.called).length} / {mine.length}</span></div>
            })}
          </div>
          <label className="search">
            <Icon name="search" size={22} />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search name or number" aria-label="Search contacts" />
          </label>
          <ChipRow>
            <Chip selected={status === ''} onClick={() => setStatus('')}>All</Chip>
            <Chip selected={status === 'pending'} onClick={() => setStatus('pending')}>Not called</Chip>
            <Chip selected={status === 'done'} onClick={() => setStatus('done')}>Called</Chip>
          </ChipRow>
          <ChipRow>
            <Chip selected={caller === ''} onClick={() => setCaller('')}>Everyone</Chip>
            {callers.map((c) => <Chip key={c} selected={caller.toLowerCase() === c.toLowerCase()} onClick={() => setCaller(c)}>{c}</Chip>)}
            <Chip selected={caller === UNASSIGNED} onClick={() => setCaller(UNASSIGNED)}>No caller</Chip>
          </ChipRow>
          <div className="list contacts-grid">
            {shown.map((c) => (
              <div key={c.contactId} className="card contact">
                <input type="checkbox" checked={c.called} disabled={!canEdit} aria-label={`${c.name} called`} onChange={(e) => void core.markCalled(c.contactId, year, e.target.checked)} />
                <div className={`contact-main${canEdit ? ' clickable' : ''}`} onClick={canEdit ? () => setEditing(c) : undefined}>
                  <div className={`entry-name${c.called ? ' done' : ''}`}>{c.name}</div>
                  <div className="entry-sub">{[c.number, c.caller.trim() ? `calls: ${c.caller}` : ''].filter(Boolean).join(' · ')}</div>
                  {c.remarks.trim() && <div className="remark">{c.remarks}</div>}
                </div>
                {c.number.trim() && <a className="icon-btn" href={`tel:${c.number}`} aria-label={`Call ${c.name}`}><Icon name="call" size={22} /></a>}
              </div>
            ))}
          </div>
        </>
      )}
      {canEdit && <button type="button" className="fab" onClick={() => setAdding(true)}><Icon name="add" size={22} /> Add</button>}
      {adding && <ContactDialog existing={null} people={people} onSave={(n, num, c, r) => core.addContact(n, num, c, r, year)} onClose={() => setAdding(false)} />}
      {editing && (
        <ContactDialog
          existing={editing} people={people}
          onSave={(n, num, c, r) => core.editContact(editing.contactId, year, n, num, c, r)}
          onDelete={() => core.deleteContact(editing.contactId)} onClose={() => setEditing(null)}
        />
      )}
    </div>
  )
}

function ContactDialog({ existing, people, onSave, onDelete, onClose }: {
  existing: ContactView | null; people: string[]
  onSave: (name: string, number: string, caller: string, remarks: string) => Promise<boolean>; onDelete?: () => Promise<boolean>; onClose: () => void
}) {
  const [name, setName] = useState(existing?.name ?? '')
  const [number, setNumber] = useState(existing?.number ?? '')
  const [caller, setCaller] = useState(existing?.caller ?? '')
  const [remarks, setRemarks] = useState(existing?.remarks ?? '')
  const [saving, setSaving] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  return (
    <Modal title={existing ? 'Edit contact' : 'Add contact'} onClose={onClose}>
      <form className="form" onSubmit={async (e) => { e.preventDefault(); if (!name.trim() || saving) return; setSaving(true); const ok = await onSave(name, number, caller, remarks); setSaving(false); if (ok) onClose() }}>
        <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} autoFocus={!existing} /></label>
        <label className="field"><span>Phone number</span><input inputMode="tel" value={number} onChange={(e) => setNumber(e.target.value.replace(/[^\d+ ]/g, ''))} /></label>
        <div className="field"><span>Who calls</span><PersonChips people={people} value={caller} onChange={setCaller} noneLabel="No one yet" /></div>
        <label className="field"><span>Remarks</span><input value={remarks} onChange={(e) => setRemarks(e.target.value)} /></label>
        <button type="submit" className="btn primary big" disabled={!name.trim() || saving}>{saving ? 'Saving…' : 'Save'}</button>
        {existing && onDelete && <button type="button" className="btn text danger" onClick={() => setConfirmDelete(true)}>Delete</button>}
      </form>
      {confirmDelete && existing && onDelete && (
        <ConfirmDialog title={`Delete ${existing.name}?`} text="This removes the contact for every year." confirmLabel="Delete" danger
          onCancel={() => setConfirmDelete(false)} onConfirm={() => { setConfirmDelete(false); void onDelete().then((ok) => { if (ok) onClose() }) }} />
      )}
    </Modal>
  )
}
