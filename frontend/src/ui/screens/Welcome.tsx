import { CLIENT_ID } from '../../config'
import { Icon } from '../Icon'
import { EmptyState, Spinner } from '../components'
import { useApp, useCore } from '../hooks'

export function WelcomeScreen() {
  const core = useCore()
  const s = useApp()
  const configured = CLIENT_ID !== ''
  return (
    <div className="welcome">
      <div className="welcome-hero">
        <div className="welcome-badge"><Icon name="rupee" size={52} /></div>
        <h1>Vinayaka Finance</h1>
        <p>Festival accounts, straight from your committee's Google Sheet. The same data as the Android app, on any screen.</p>
      </div>
      <div className="welcome-card">
        <button type="button" className="btn primary big" onClick={() => void core.signIn()} disabled={s.working || !configured}>
          {s.working ? <span className="spinner small" /> : 'Sign in with Google'}
        </button>
        {!configured && (
          <p className="hint">Google sign-in isn't set up in this build yet. Add your Web client ID to <code>android/secrets.properties</code> and rebuild the portal.</p>
        )}
        {s.authError && <p className="error-text" role="alert">{s.authError}</p>}
        <p className="hint">Only people on the committee's list can open the data. Changes are saved straight to the Google Sheet; there is nothing to sync.</p>
      </div>
    </div>
  )
}

/** Shown right after sign-in while the Users tab is read to see whether this account may use the portal. */
export function CheckingAccessScreen() {
  const core = useCore()
  const s = useApp()
  return (
    <div className="centered-screen">
      {s.accessError === null ? (
        <>
          <Spinner />
          <h2>Checking your access…</h2>
          <p className="muted">{s.profile?.email}</p>
        </>
      ) : (
        <>
          <h2>Couldn't check your access</h2>
          <p className="muted">{s.accessError}</p>
          <button type="button" className="btn primary big" onClick={() => void core.checkAccess()}>Try again</button>
          <button type="button" className="btn outline big" onClick={() => core.signOut()}>Use a different Google account</button>
        </>
      )}
    </div>
  )
}

/** This Google account is not on the Users list (or cannot open the Sheet), so the portal stays locked. */
export function NoAccessScreen() {
  const core = useCore()
  const s = useApp()
  return (
    <div className="centered-screen">
      <EmptyState icon="lock" title="No access" hint={s.deniedMessage} />
      <div className="stack narrow">
        <button type="button" className="btn primary big" onClick={() => void core.checkAccess()}>Check again</button>
        <button type="button" className="btn outline big" onClick={() => core.signOut()}>Use a different Google account</button>
      </div>
    </div>
  )
}
