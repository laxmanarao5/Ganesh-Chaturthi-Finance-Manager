import { useEffect, useMemo, useState } from 'react'
import { displayName, isDefaultAdmin, normalizeUsers } from '../../lib/access'
import { ROLES, ROLE_KEYS, type AppUser, type Role } from '../../lib/model'
import { reportLines } from '../../lib/sharing'
import { Chip, EmptyState, Modal, Spinner } from '../components'
import { useApp, useCore } from '../hooks'
import { Icon } from '../Icon'

/**
 * Admin-only. Lists everyone allowed to use the app, grouped by role, and lets an admin add, re-role or remove them.
 * The list lives in the Sheet's Users tab (Name | Email | Role), so it can also be edited there by hand.
 */
export function UsersScreen() {
  const core = useCore()
  const s = useApp()
  const saved = useMemo(() => normalizeUsers(s.users ?? []), [s.users])
  const [list, setList] = useState<AppUser[]>(saved)
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<Role>('VIEWER')

  useEffect(() => { if (ROLES[s.role].canManageUsers) void core.loadUsers() }, [core, s.role])
  useEffect(() => setList(saved), [saved])

  if (!ROLES[s.role].canManageUsers) return <div className="page"><EmptyState icon="lock" title="Admins only" hint="Only an Admin can see and change who may use the app." /></div>
  if (s.usersLoading && s.users === null) return <div className="page"><Spinner label="Loading the list…" /></div>

  const hasTab = s.users !== null
  const changed = JSON.stringify(normalizeUsers(list)) !== JSON.stringify(saved)
  const emailOk = /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())

  return (
    <div className="page narrow-page">
      <div className="card pad note">
        <b>Only emails on this list can use the app and the portal.</b>
        <div>Viewer: can look at everything, cannot change anything.</div>
        <div>Editor: can add and edit records, but cannot see this page.</div>
        <div>Admin: everything an Editor can, plus manage users.</div>
        <small>Saving also updates who the Google Sheet is shared with: Editors and Admins can edit it, Viewers can only view it, and people you remove lose access. Google's sharing is the real lock on the data.</small>
      </div>
      {!hasTab && <p className="error-text">The Sheet has no Users tab yet. Until you save one, only the default admin can sign in.</p>}

      {(['ADMIN', 'EDITOR', 'VIEWER'] as Role[]).map((r) => {
        const group = list.filter((u) => u.role === r)
        return (
          <section key={r}>
            <h3 className="section-title">{ROLES[r].label}s ({group.length})</h3>
            {group.length === 0 && <p className="muted small-text">No one yet</p>}
            <div className="list">
              {group.map((u) => {
                const locked = isDefaultAdmin(u.email)
                return (
                  <div key={u.email} className="card pad user">
                    <div className="user-main">
                      <div className="entry-name">{displayName(u)}</div>
                      <div className="entry-sub">{u.email}</div>
                      {locked ? <div className="remark">Default admin · built into the app</div> : (
                        <div className="chips">{ROLE_KEYS.map((opt) => <Chip key={opt} selected={u.role === opt} onClick={() => setList(list.map((x) => (x.email === u.email ? { ...x, role: opt } : x)))}>{ROLES[opt].label}</Chip>)}</div>
                      )}
                    </div>
                    {!locked && <button type="button" className="icon-btn" aria-label={`Remove ${u.email}`} onClick={() => setList(list.filter((x) => x.email !== u.email))}><Icon name="close" size={22} /></button>}
                  </div>
                )
              })}
            </div>
          </section>
        )
      })}

      <h3 className="section-title">Add a user</h3>
      <form className="form" onSubmit={(e) => {
        e.preventDefault()
        const em = email.trim().toLowerCase()
        if (!emailOk) return
        if (!list.some((u) => u.email === em)) setList([...list, { name: name.trim(), email: em, role }])
        setEmail(''); setName('')
      }}>
        <label className="field"><span>Google email</span><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <label className="field"><span>Name (optional)</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
        <div className="chips">{ROLE_KEYS.map((opt) => <Chip key={opt} selected={role === opt} onClick={() => setRole(opt)}>{ROLES[opt].label}</Chip>)}</div>
        <button type="submit" className="btn outline big" disabled={!emailOk}>Add to list</button>
      </form>

      <button type="button" className="btn primary big" disabled={s.working || (!changed && hasTab)} onClick={() => void core.saveUsers(list)}>
        {s.working ? 'Saving…' : changed ? 'Save and update sharing' : 'Save to the Sheet'}
      </button>
      <button type="button" className="btn outline big" disabled={s.working || changed || !hasTab} onClick={() => void core.fixSharing()}>Update the Sheet's sharing for everyone</button>
      {changed && <p className="muted small-text">Save your changes first.</p>}
      {core.sheetUrl && <a className="btn text" href={core.sheetUrl} target="_blank" rel="noreferrer noopener"><Icon name="open" size={18} /> Open the Sheet</a>}

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
