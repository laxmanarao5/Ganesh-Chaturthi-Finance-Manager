// Google sign-in in the browser (Google Identity Services, token flow): one sign-in, like the Android app. No password and no secret
// is stored here; the access token lives only in memory and lasts about an hour, then is renewed quietly while the Google session is open.

export interface Profile { email: string; name: string; picture: string }

const SHEETS_SCOPE = 'openid email profile https://www.googleapis.com/auth/spreadsheets'
const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive'
const PROFILE_KEY = 'vf.profile'

interface TokenResponse { access_token?: string; expires_in?: number | string; error?: string; error_description?: string }
interface GisTokenClient { requestAccessToken(overrides?: Record<string, unknown>): void }
interface Gis {
  accounts: { oauth2: { initTokenClient(config: Record<string, unknown>): GisTokenClient } }
}

declare global { interface Window { google?: Gis } }

/** Thrown when the quiet renewal is not possible (no Google session, or the browser blocked the popup): the person must tap Sign in. */
export class SessionExpired extends Error {
  constructor() { super('Your Google session ended. Please sign in again.') }
}

let gisLoading: Promise<Gis> | null = null
function loadGis(): Promise<Gis> {
  if (window.google?.accounts?.oauth2) return Promise.resolve(window.google)
  gisLoading ??= new Promise<Gis>((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://accounts.google.com/gsi/client'
    s.async = true
    s.onload = () => (window.google?.accounts?.oauth2 ? resolve(window.google) : reject(new Error('Google sign-in did not load.')))
    s.onerror = () => { gisLoading = null; reject(new Error('Could not reach Google sign-in. Check your internet connection.')) }
    document.head.appendChild(s)
  })
  return gisLoading
}

function readProfile(): Profile | null {
  try {
    const p = JSON.parse(localStorage.getItem(PROFILE_KEY) ?? 'null') as Profile | null
    return p && p.email ? p : null
  } catch { return null }
}
function saveProfile(p: Profile | null) {
  try { if (p) localStorage.setItem(PROFILE_KEY, JSON.stringify(p)); else localStorage.removeItem(PROFILE_KEY) } catch { /* private mode */ }
}

export class GoogleAuth {
  profile: Profile | null = readProfile()
  private access = { token: '', expiresAt: 0 }
  private drive = { token: '', expiresAt: 0 }
  private pending = new Map<string, Promise<string>>()

  constructor(private clientId: string) {}

  get configured() { return this.clientId !== '' }

  private valid(t: { token: string; expiresAt: number }) { return t.token !== '' && t.expiresAt - 60_000 > Date.now() }

  private request(scope: string, prompt: string, hint: string | undefined, store: { token: string; expiresAt: number }): Promise<string> {
    const key = `${scope}|${prompt}`
    const running = this.pending.get(key)
    if (running) return running
    const p = loadGis().then((gis) => new Promise<string>((resolve, reject) => {
      const client = gis.accounts.oauth2.initTokenClient({
        client_id: this.clientId,
        scope,
        include_granted_scopes: true,
        ...(hint ? { hint } : {}),
        callback: (r: TokenResponse) => {
          if (r.error || !r.access_token) { reject(new Error(r.error_description || r.error || 'Sign-in was cancelled.')); return }
          store.token = r.access_token
          store.expiresAt = Date.now() + Number(r.expires_in ?? 3600) * 1000
          resolve(r.access_token)
        },
        error_callback: (e: { type?: string; message?: string }) => reject(new Error(e.type === 'popup_closed' ? 'Sign-in was cancelled.' : e.message || e.type || 'Sign-in failed.')),
      })
      client.requestAccessToken({ prompt })
    })).finally(() => this.pending.delete(key))
    this.pending.set(key, p)
    return p
  }

  /** Full sign-in with the account chooser. Must be called from a click. */
  async signIn(): Promise<Profile> {
    const token = await this.request(SHEETS_SCOPE, 'select_account', undefined, this.access)
    const res = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', { headers: { Authorization: `Bearer ${token}` } })
    if (!res.ok) throw new Error('Could not read your Google profile.')
    const j = (await res.json()) as { email?: string; name?: string; picture?: string }
    if (!j.email) throw new Error('Google did not share your email address.')
    this.profile = { email: j.email.toLowerCase(), name: j.name ?? '', picture: j.picture ?? '' }
    saveProfile(this.profile)
    return this.profile
  }

  /** A valid token for the Sheet; renews quietly when it is about to expire (or [force]). */
  async accessToken(force = false): Promise<string> {
    if (!force && this.valid(this.access)) return this.access.token
    try {
      return await this.request(SHEETS_SCOPE, '', this.profile?.email, this.access)
    } catch {
      throw new SessionExpired()
    }
  }

  /** The restricted Drive scope, requested only when an admin saves the Users list. Must be called from a click the first time. */
  async driveToken(force = false): Promise<string> {
    if (!force && this.valid(this.drive)) return this.drive.token
    return this.request(`${SHEETS_SCOPE} ${DRIVE_SCOPE}`, '', this.profile?.email, this.drive)
  }

  /** Forgets this browser's session. Google's consent is kept, so signing back in is quick; it can be removed in the Google account's security page. */
  signOut() {
    this.access = { token: '', expiresAt: 0 }
    this.drive = { token: '', expiresAt: 0 }
    this.profile = null
    saveProfile(null)
  }
}
