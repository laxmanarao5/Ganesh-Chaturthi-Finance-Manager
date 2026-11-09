import { useEffect } from 'react'
import type { Core } from './core/core'
import { ROLES } from './lib/model'
import { Icon, type IconName } from './ui/Icon'
import { Spinner, Toasts } from './ui/components'
import { CoreContext, useApp, useCore, useRoute, type Route } from './ui/hooks'
import { ContactsScreen } from './ui/screens/Contacts'
import { EntryListScreen } from './ui/screens/EntryList'
import { HomeScreen } from './ui/screens/Home'
import { ImportScreen } from './ui/screens/ImportScreen'
import { MoreScreen } from './ui/screens/More'
import { PeopleScreen } from './ui/screens/People'
import { ReportsScreen } from './ui/screens/Reports'
import { UsersScreen } from './ui/screens/Users'
import { CheckingAccessScreen, NoAccessScreen, WelcomeScreen } from './ui/screens/Welcome'

const TITLES: Record<Route, string> = {
  home: 'Vinayaka Finance', donations: 'Donations', expenses: 'Expenditure', others: 'Other income', people: 'Collected vs spent',
  contacts: 'Contacts', reports: 'Reports', import: 'Import from CSV', users: 'Users', more: 'More',
}

interface NavItem { route: Route; label: string; icon: IconName; tab?: boolean; needs?: 'edit' | 'admin' }
const NAV: NavItem[] = [
  { route: 'home', label: 'Home', icon: 'home', tab: true },
  { route: 'donations', label: 'Donations', icon: 'heart', tab: true },
  { route: 'expenses', label: 'Spent', icon: 'wallet', tab: true },
  { route: 'others', label: 'Other income', icon: 'savings' },
  { route: 'people', label: 'People', icon: 'group', tab: true },
  { route: 'contacts', label: 'Contacts', icon: 'contacts' },
  { route: 'reports', label: 'Reports', icon: 'report' },
  { route: 'import', label: 'Import', icon: 'upload', needs: 'edit' },
  { route: 'users', label: 'Users', icon: 'group', needs: 'admin' },
]

export function App({ core }: { core: Core }) {
  return <CoreContext.Provider value={core}><Shell /></CoreContext.Provider>
}

function Shell() {
  const core = useCore()
  const s = useApp()
  useEffect(() => { void core.start() }, [core])
  useEffect(() => {
    const el = document.documentElement
    if (s.theme === 'system') el.removeAttribute('data-theme')
    else el.setAttribute('data-theme', s.theme)
  }, [s.theme])

  switch (s.phase) {
    case 'boot': return <div className="centered-screen"><Spinner /></div>
    case 'signedOut': return <><WelcomeScreen /><Toasts /></>
    case 'checking': return <CheckingAccessScreen />
    case 'denied': return <NoAccessScreen />
    case 'ready': return <MainShell />
  }
}

function Screen({ route, go }: { route: Route; go: (r: Route) => void }) {
  switch (route) {
    case 'home': return <HomeScreen />
    case 'donations': return <EntryListScreen type="DONATION" />
    case 'expenses': return <EntryListScreen type="EXPENDITURE" />
    case 'others': return <EntryListScreen type="OTHER" />
    case 'people': return <PeopleScreen />
    case 'contacts': return <ContactsScreen />
    case 'reports': return <ReportsScreen />
    case 'import': return <ImportScreen />
    case 'users': return <UsersScreen />
    case 'more': return <MoreScreen go={go} />
  }
}

function MainShell() {
  const core = useCore()
  const s = useApp()
  const [route, go] = useRoute()
  const role = ROLES[s.role]
  const items = NAV.filter((n) => !n.needs || (n.needs === 'edit' ? role.canEdit : role.canManageUsers))
  const tabs = items.filter((n) => n.tab)
  const activeTab = tabs.some((t) => t.route === route) ? route : 'more'

  // Coming back to the page after a while: read the Sheet again, so what other people changed shows up.
  useEffect(() => {
    const on = () => {
      if (document.visibilityState === 'visible' && s.lastLoaded !== null && Date.now() - s.lastLoaded > 60_000) void core.refresh(true)
    }
    document.addEventListener('visibilitychange', on)
    window.addEventListener('focus', on)
    return () => { document.removeEventListener('visibilitychange', on); window.removeEventListener('focus', on) }
  }, [core, s.lastLoaded])

  useEffect(() => { document.title = route === 'home' ? 'Vinayaka Finance' : `${TITLES[route]} · Vinayaka Finance`; window.scrollTo(0, 0) }, [route])

  return (
    <div className="shell">
      <aside className="sidebar" aria-label="Main menu">
        <div className="brand"><div className="brand-badge"><Icon name="rupee" size={26} /></div><span>Vinayaka Finance</span></div>
        <nav>
          {items.map((n) => (
            <button key={n.route} type="button" className={`nav-item${route === n.route ? ' on' : ''}`} aria-current={route === n.route ? 'page' : undefined} onClick={() => go(n.route)}>
              <Icon name={n.icon} size={22} /> {n.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-foot">
          <button type="button" className={`nav-item${route === 'more' ? ' on' : ''}`} onClick={() => go('more')}><Icon name="person" size={22} /> <span className="grow">{s.profile?.name || s.profile?.email}</span></button>
          <div className="muted small-text">{role.label}</div>
          <button type="button" className="nav-item" onClick={() => core.signOut()}><Icon name="logout" size={22} /> Sign out</button>
        </div>
      </aside>

      <div className="content">
        <header className="topbar">
          <h1>{TITLES[route]}</h1>
          <span className="grow" />
          {s.busy > 0 && <span className="saving" role="status">Saving…</span>}
          {!role.canEdit && <span className="badge">View only</span>}
          <button type="button" className={`icon-btn${s.refreshing ? ' spin' : ''}`} aria-label="Refresh from the Google Sheet" title="Refresh from the Google Sheet" onClick={() => void core.refresh()}>
            <Icon name="refresh" size={22} />
          </button>
        </header>
        {s.sessionExpired && (
          <div className="banner" role="alert">
            <span>Your Google session ended. Sign in again to keep saving.</span>
            <button type="button" className="btn small" onClick={() => void core.signIn()}>Sign in again</button>
          </div>
        )}
        <main><Screen route={route} go={go} /></main>
      </div>

      <nav className="tabbar" aria-label="Main menu">
        {tabs.map((n) => (
          <button key={n.route} type="button" className={`tab${activeTab === n.route ? ' on' : ''}`} aria-current={activeTab === n.route ? 'page' : undefined} onClick={() => go(n.route)}>
            <span className="tab-icon"><Icon name={n.icon} size={24} /></span>{n.label}
          </button>
        ))}
        <button type="button" className={`tab${activeTab === 'more' ? ' on' : ''}`} aria-current={activeTab === 'more' ? 'page' : undefined} onClick={() => go('more')}>
          <span className="tab-icon"><Icon name="more" size={24} /></span>More
        </button>
      </nav>
      <Toasts />
    </div>
  )
}
