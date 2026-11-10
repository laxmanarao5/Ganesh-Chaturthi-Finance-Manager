import { useEffect, useMemo, useState } from 'react'
import { displayName, isDefaultAdmin, normalizeUsers } from '../../lib/access'
import { ROLES, ROLE_KEYS, type AppUser, type Role } from '../../lib/model'
import { reportLines } from '../../lib/sharing'
import { Chip, ChipRow, ConfirmDialog, EmptyState, Modal, Spinner } from '../components'
import { useApp, useCore } from '../hooks'
import { Icon } from '../Icon'

const ROLE_HELP: Record<Role, string> = {
  VIEWER: 'Can look at everything, cannot change anything. Gets view access to the Google Sheet.',
  EDITOR: 'Can add and edit records. Gets edit access to the Google Sheet.',
  ADMIN: 'Everything an Editor can, plus manage users. Gets edit access to the Google Sheet.',
}
const rank: Record<Role, number> = { ADMIN: 2, EDITOR: 1, VIEWER: 0 }

/**
 * Admin-only. Lists everyone allowed to use the app and portal, and lets an admin add, re-role or remove them in a pop-up
 * (like adding a donation). The list lives in the Sheet's Users tab (Name | Email | Role), so it can also be edited there by hand.
 * Changes stay on this screen until "Save and update sharing".
 */
export function UsersScreen() {
  const core = useCore()
  const s = useApp()
  const saved = useMemo(() => normalizeUsers(s.users ?? []), [s.users])
  const [list, setList] = useState<AppUser[]>(saved)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<AppUser | null>(null)

  useEffect(() => { if (ROLES[s.role].canManageUsers) void core.loadUsers() }, [core, s.role])
  useEffect(() => setList(saved), [saved])

  if (!ROLES[s.role].canManageUsers) return <div className="page"><EmptyState icon="lock" title="Admins only" hint="Only an Admin can see and change who may use the app." /></div>
  if (s.usersLoading && s.users === null) return <div className="page"><Spinner label="Loading the list…" /></div>

  const hasTab = s.users !== null
  const changed = JSON.stringify(normalizeUsers(list)) !== JSON.stringify(saved)
  const shown = [...list].sort((a, b) => rank[b.role] - rank[a.role] || displayName(a).toLowerCase().localeCompare(displayName(b).toLowerCase()))
  const onlyBuiltIn = list.every((u) => isDefaultAdmin(u.email))

  return (
    <div className="page narrow-page">
      <div className="toolbar">
        <span className="strong">Users ({list.length})</span>
        <button type="button" className="btn primary desktop-only" onClick={() => setAdding(true)}><Icon name="add" size={20} /> Add user</button>
      </div>
      <div className="card pad note">
        <b>Only emails on this list can use the app and the portal.</b>
        <div>Viewer: can look at everything, cannot change anything.</div>
        <div>Editor: can add and edit records, but cannot see this page.</div>
        <div>Admin: everything an Editor can, plus manage users.</div>
        <small>Saving also updates who the Google Sheet is shared with: Editors and Admins can edit it, Viewers can only view it, and people you remove lose access. Google's sharing is the real lock on the data.</small>
      </div>
      {!hasTab && <p className="error-text">The Sheet has no Users tab yet. Until you save one, only the default admin can sign in.</p>}

      <div className="list">
        {shown.map((u) => {
          const locked = isDefaultAdmin(u.email)
          return (
            <div key={u.email} className={`card pad user${locked ? '' : ' clickable'}`} role={locked ? undefined : 'button'} tabIndex={locked ? undefined : 0}
              onClick={locked ? undefined : () => setEditing(u)}
              onKeyDown={locked ? undefined : (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setEditing(u) } }}>
              <div className="user-main">
                <div className="entry-name">{displayName(u)}</div>
                <div className="entry-sub">{u.email}</div>
                {locked && <div className="remark">Default admin · built into the app</div>}
              </div>
              <span className={`badge${u.role === 'ADMIN' ? '' : ' soft'}`}>{ROLES[u.role].label}</span>
            </div>
          )
        })}
      </div>
      {!onlyBuiltIn && <p className="muted small-text">Click a user to change the role or remove them.</p>}

      <button type="button" className="btn primary big" disabled={s.working || (!changed && hasTab)} onClick={() => void core.saveUsers(list)}>
        {s.working ? 'Saving…' : changed ? 'Save and update sharing' : 'Save to the Sheet'}
      </button>
      <button type="button" className="btn outline big" disabled={s.working || changed || !hasTab} onClick={() => void core.fixSharing()}>Update the Sheet's sharing for everyone</button>
      {changed && <p className="muted small-text">Save your changes first.</p>}
      {core.sheetUrl && <a className="btn text" href={core.sheetUrl} target="_blank" rel="noreferrer noopener"><Icon name="open" size={18} /> Open the Sheet</a>}

      <button type="button" className="fab" onClick={() => setAdding(true)}><Icon name="add" size={22} /> Add user</button>

      {adding && (
        <UserDialog existing={null} taken={list.map((u) => u.email.toLowerCase())}
          onSave={(u) => { setList((l) => [...l, u]); setAdding(false) }} onClose={() => setAdding(false)} />
      )}
      {editing && (
        <UserDialog existing={editing} taken={[]}
          onSave={(u) => { setList((l) => l.map((x) => (x.email === editing.email ? u : x))); setEditing(null) }}
          onRemove={() => { setList((l) => l.filter((x) => x.email !== editing.email)); setEditing(null) }}
          onClose={() => setEditing(null)} />
      )}

      {s.sharing && (
        <Modal title={s.sharing.error && !s.sharing.report ? 'Sheet sharing not updated' : 'Sheet sharing'} onClose={() => core.dismissSharing()}
          actions={<button type="button" className="btn text" onClick={() => core.dismissSharing()}>OK</button>}>
          <div className="stack">
            {s.sharing.error && <p>{s.sharing.error}</p>}
            {s.sharing.report && reportLines(s.sharing.report).map((l) => <p key={l}>{l}</p>)}
          </div>
        </Modal>
      )}
    </div>
  )
}

/** Add or edit one user in a pop-up, like adding a donation. The email of an existing user cannot change (remove and add instead). */
function UserDialog({ existing, taken, onSave, onRemove, onClose }: {
  existing: AppUser | null; taken: string[]; onSave: (u: AppUser) => void; onRemove?: () => void; onClose: () => void
}) {
  const [email, setEmail] = useState(existing?.email ?? '')
  const [name, setName] = useState(existing?.name ?? '')
  const [role, setRole] = useState<Role>(existing?.role ?? 'VIEWER')
  const [confirmRemove, setConfirmRemove] = useState(false)
  const clean = email.trim().toLowerCase()
  const emailOk = /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(clean)
  const isTaken = existing === null && taken.includes(clean)
  const valid = (existing !== null || emailOk) && !isTaken

  return (
    <Modal title={existing ? 'Edit user' : 'Add user'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); if (valid) onSave({ name: name.trim(), email: existing ? existing.email : clean, role }) }}>
        <label className="field">
          <span>Google email</span>
          <input type="email" value={email} disabled={existing !== null} autoFocus={existing === null} onChange={(e) => setEmail(e.target.value)} />
          {isTaken && <small className="error-text">This email is already on the list</small>}
        </label>
        <label className="field"><span>Name (optional)</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
        <div className="field"><span>Role</span>
          <ChipRow>{ROLE_KEYS.map((opt) => <Chip key={opt} selected={role === opt} onClick={() => setRole(opt)}>{ROLES[opt].label}</Chip>)}</ChipRow>
          <small>{ROLE_HELP[role]}</small>
        </div>
        <button type="submit" className="btn primary big" disabled={!valid}>{existing ? 'Done' : 'Add to list'}</button>
        {existing && onRemove && <button type="button" className="btn text danger" onClick={() => setConfirmRemove(true)}>Remove from the list</button>}
      </form>
      {confirmRemove && existing && onRemove && (
        <ConfirmDialog title={`Remove ${displayName(existing)}?`} danger confirmLabel="Remove"
          text="They lose access to the app once you save, and to the Google Sheet when the sharing is updated."
          onCancel={() => setConfirmRemove(false)} onConfirm={() => { setConfirmRemove(false); onRemove() }} />
      )}
    </Modal>
  )
}
