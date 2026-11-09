// Who may use the portal. Same rules as the Android app (data/Access.kt): only emails on the Sheet's Users tab get in.
import { DEFAULT_ADMIN_EMAIL } from '../config'
import type { AppUser, Role } from './model'

export const isDefaultAdmin = (email: string) => email.trim().toLowerCase() === DEFAULT_ADMIN_EMAIL

/**
 * The role for [email], or null when the person is not on the list and so may not use the app.
 * [users] is null (or empty) when the Sheet has no Users tab yet; then only the default admin gets in.
 */
export function resolveRole(users: AppUser[] | null, email: string): Role | null {
  if (isDefaultAdmin(email)) return 'ADMIN'
  const e = email.trim().toLowerCase()
  if (!e) return null
  return (users ?? []).find((u) => u.email.toLowerCase() === e)?.role ?? null
}

/** "Editor", "admin", "read only"... as typed in the Sheet. Anything unrecognised is the safest role, Viewer. */
export function parseRole(text: string | null | undefined): Role {
  switch ((text ?? '').trim().toLowerCase().replace(/[\s_-]/g, '')) {
    case 'admin': case 'administrator': case 'owner': return 'ADMIN'
    case 'editor': case 'edit': case 'write': return 'EDITOR'
    default: return 'VIEWER'
  }
}

/** The list as it should be saved: the default admin is always present as Admin, and no email appears twice. */
export function normalizeUsers(users: AppUser[]): AppUser[] {
  const seen = new Set<string>()
  const rest = users
    .filter((u) => u.email.trim() !== '' && !isDefaultAdmin(u.email))
    .map((u) => ({ ...u, email: u.email.trim().toLowerCase(), name: u.name.trim() }))
    .filter((u) => (seen.has(u.email) ? false : (seen.add(u.email), true)))
  const admin = users.find((u) => isDefaultAdmin(u.email))?.name ?? ''
  return [{ name: admin, email: DEFAULT_ADMIN_EMAIL, role: 'ADMIN' }, ...rest]
}

export const displayName = (u: AppUser) => u.name.trim() || u.email.split('@')[0]
