import { useState } from 'react'
import { APK_URL, BUILD_TIME } from '../../config'
import { ROLES } from '../../lib/model'
import { Modal, PersonChips, SectionTitle } from '../components'
import { usePeople } from '../data'
import { useApp, useCore, type Route } from '../hooks'
import { Icon, type IconName } from '../Icon'
import type { ThemeMode } from '../../core/core'

function NavTile({ icon, title, hint, onClick, href }: { icon: IconName; title: string; hint: string; onClick?: () => void; href?: string }) {
  const body = (<>
    <div className="tile-icon"><Icon name={icon} size={22} /></div>
    <div><div className="entry-name">{title}</div><div className="entry-sub">{hint}</div></div>
  </>)
  return href
    ? <a className="card navtile" href={href} target="_blank" rel="noreferrer noopener">{body}</a>
    : <button type="button" className="card navtile" onClick={onClick}>{body}</button>
}

const ROLE_TEXT = { ADMIN: 'Admin · can edit and manage users', EDITOR: 'Editor · can add and edit records', VIEWER: 'Viewer · can look, not change' } as const

export function MoreScreen({ go }: { go: (r: Route) => void }) {
  const core = useCore()
  const s = useApp()
  const people = usePeople()
  const [pickName, setPickName] = useState(false)
  const info = ROLES[s.role]

  return (
    <div className="page narrow-page">
      <div className="card pad profile">
        <div className="avatar">{(s.profile?.name || s.profile?.email || '?').slice(0, 1).toUpperCase()}</div>
        <div>
          <div className="entry-name">{s.profile?.name || s.profile?.email}</div>
          <div className="entry-sub">{s.profile?.email}</div>
          <div className="label">{ROLE_TEXT[s.role]}</div>
        </div>
      </div>

      <SectionTitle>Records</SectionTitle>
      <div className="list">
        <NavTile icon="savings" title="Other income" hint="Name, description and amount" onClick={() => go('others')} />
        <NavTile icon="contacts" title="Contacts" hint="People to call, with called / not called for each year" onClick={() => go('contacts')} />
        <NavTile icon="report" title="Reports" hint="Day-wise totals and CSV export for Excel" onClick={() => go('reports')} />
        <NavTile icon="person" title={`Collecting as: ${s.myName || 'not set'}`} hint="Used as the default collector on new records" onClick={() => setPickName(true)} />
      </div>

      <SectionTitle>Data</SectionTitle>
      <div className="list">
        <NavTile icon="refresh" title="Refresh from the Google Sheet" hint={s.lastLoaded ? `Last read ${new Date(s.lastLoaded).toLocaleTimeString()}` : 'Changes are saved straight to the Sheet, so there is nothing to sync'} onClick={() => void core.refresh()} />
        {info.canEdit && <NavTile icon="upload" title="Import from CSV" hint="Bring in old records, split into years automatically" onClick={() => go('import')} />}
        {info.canManageUsers && <NavTile icon="group" title="Users" hint="Who can use the app: Viewers, Editors and Admins" onClick={() => go('users')} />}
        {core.sheetUrl && <NavTile icon="open" title="Open the Google Sheet" hint="The source of truth for all the data" href={core.sheetUrl} />}
        <NavTile icon="download" title="Android app" hint="Download the app (works offline, syncs with the same Sheet)" href={APK_URL} />
      </div>

      <SectionTitle>Appearance</SectionTitle>
      <div className="chips">
        {(['system', 'light', 'dark'] as ThemeMode[]).map((m) => (
          <button key={m} type="button" className={`chip${s.theme === m ? ' on' : ''}`} aria-pressed={s.theme === m} onClick={() => core.setTheme(m)}>{m[0].toUpperCase() + m.slice(1)}</button>
        ))}
      </div>
      <button type="button" className="btn outline big" onClick={() => core.signOut()}><Icon name="logout" size={20} /> Sign out</button>
      <p className="muted small-text">Portal built {new Date(BUILD_TIME).toLocaleString()}</p>

      {pickName && (
        <Modal title="Which committee member are you?" onClose={() => setPickName(false)}>
          <PersonChips people={people} value={s.myName} onChange={(n) => { core.setMyName(n); setPickName(false) }} />
        </Modal>
      )}
    </div>
  )
}
