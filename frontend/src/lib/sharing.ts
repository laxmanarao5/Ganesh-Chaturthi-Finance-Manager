// Keeps the Google Sheet's own sharing in step with the Users list, through the Drive API (identical rules to the Android app's
// data/sharing/Sharing.kt): Viewers can view the Sheet, Editors and Admins can edit it, removed people lose access. The owner is never touched.
import type { AppUser, Role } from './model'
import { ROLES } from './model'
import { makeHttp } from './googleSheets'

export type ShareLevel = 'OWNER' | 'WRITER' | 'READER'
export const levelPhrase: Record<ShareLevel, string> = { OWNER: 'owner', WRITER: 'can edit', READER: 'can view' }

/** The Sheet access an app role needs: Editors and Admins edit the Sheet, Viewers only look. */
export const shareLevel = (role: Role): ShareLevel => (ROLES[role].canEdit ? 'WRITER' : 'READER')

export interface Share { id: string; email: string; level: ShareLevel }
/** Access that is not tied to a person: "anyone with the link" or a whole organisation. */
export interface OpenAccess { who: string; level: ShareLevel }
export interface ShareSnapshot { people: Share[]; open: OpenAccess[] }

export interface SheetSharing {
  list(): Promise<ShareSnapshot>
  add(email: string, level: ShareLevel): Promise<void>
  change(shareId: string, level: ShareLevel): Promise<void>
  remove(shareId: string): Promise<void>
}

export type ShareAction =
  | { kind: 'add'; email: string; level: ShareLevel }
  | { kind: 'change'; email: string; shareId: string; level: ShareLevel }
  | { kind: 'remove'; email: string; shareId: string }

export interface SharingReport {
  added: string[]; changed: string[]; removed: string[]
  failed: [string, string][]
  /** Have access to the Sheet but are not on the app's list (they could still open the Sheet directly). */
  others: string[]
  open: OpenAccess[]
}

export const reportChanges = (r: SharingReport) => r.added.length + r.changed.length + r.removed.length

/** Plain sentences for the result dialog. */
export function reportLines(r: SharingReport): string[] {
  const out: string[] = []
  if (reportChanges(r) === 0 && r.failed.length === 0) out.push("The Sheet's sharing already matches the list.")
  if (r.added.length) out.push(`Shared with ${r.added.join(', ')}.`)
  if (r.changed.length) out.push(`Access changed for ${r.changed.join(', ')}.`)
  if (r.removed.length) out.push(`Access removed for ${r.removed.join(', ')}.`)
  for (const [email, why] of r.failed) out.push(`Could not update ${email}: ${why}`)
  if (r.others.length) out.push(`Also has access to the Sheet, but is not on the list: ${r.others.join(', ')}. They cannot use the app, but can open the Sheet directly.`)
  for (const o of r.open) out.push(`${o.who} ${levelPhrase[o.level]} the Sheet. Turn that off in Google Sheets (Share) if only listed people should see it.`)
  return out
}

/**
 * What to change in the Sheet's sharing so it matches the app's list. Everyone on the list gets the level their role needs.
 * People who were removed from the list lose access. Anyone else who already has access is left alone (and reported).
 */
export function planSharing(desired: AppUser[], removed: Iterable<string>, existing: Share[], keep: Iterable<string>): ShareAction[] {
  const have = new Map(existing.map((s) => [s.email.trim().toLowerCase(), s]))
  const keepSet = new Set([...keep].map((k) => k.trim().toLowerCase()))
  const wanted = new Set(desired.map((u) => u.email.trim().toLowerCase()).filter((e) => e !== ''))
  const actions: ShareAction[] = []
  for (const u of desired) {
    const email = u.email.trim().toLowerCase()
    if (!email) continue
    const level = shareLevel(u.role)
    const current = have.get(email)
    if (!current) actions.push({ kind: 'add', email, level })
    else if (current.level === 'OWNER') continue
    else if (current.level !== level) actions.push({ kind: 'change', email, shareId: current.id, level })
  }
  for (const r of new Set([...removed].map((x) => x.trim().toLowerCase()))) {
    if (wanted.has(r) || keepSet.has(r)) continue
    const current = have.get(r)
    if (current && current.level !== 'OWNER') actions.push({ kind: 'remove', email: r, shareId: current.id })
  }
  return actions
}

export async function reconcileSharing(sharing: SheetSharing, desired: AppUser[], removed: string[] = [], keep: string[] = []): Promise<SharingReport> {
  const snapshot = await sharing.list()
  const report: SharingReport = { added: [], changed: [], removed: [], failed: [], others: [], open: snapshot.open }
  for (const a of planSharing(desired, removed, snapshot.people, keep)) {
    try {
      if (a.kind === 'add') { await sharing.add(a.email, a.level); report.added.push(a.email) }
      else if (a.kind === 'change') { await sharing.change(a.shareId, a.level); report.changed.push(a.email) }
      else { await sharing.remove(a.shareId); report.removed.push(a.email) }
    } catch (e) {
      report.failed.push([a.email, e instanceof Error ? e.message : 'Google refused the change.'])
    }
  }
  const listed = new Set(desired.map((u) => u.email.trim().toLowerCase()))
  const removedSet = new Set(removed.map((x) => x.trim().toLowerCase()))
  report.others = snapshot.people
    .filter((s) => s.level !== 'OWNER' && !listed.has(s.email.toLowerCase()) && !removedSet.has(s.email.toLowerCase()))
    .map((s) => s.email)
  return report
}

/** In-memory stand-in for Drive sharing: used by the tests. */
export class FakeSharing implements SheetSharing {
  shares: Share[]
  failFor = new Set<string>()
  private next = 1
  constructor(initial: Share[] = [], private openAccess: OpenAccess[] = []) { this.shares = [...initial] }
  async list() { return { people: [...this.shares], open: this.openAccess } }
  async add(email: string, level: ShareLevel) {
    if (this.failFor.has(email)) throw new Error(`Google refused to share with ${email}.`)
    this.shares.push({ id: `p${this.next++}`, email, level })
  }
  async change(id: string, level: ShareLevel) {
    const i = this.shares.findIndex((s) => s.id === id)
    if (i < 0) throw new Error('No such permission.')
    if (this.failFor.has(this.shares[i].email)) throw new Error(`Google refused to change ${this.shares[i].email}.`)
    this.shares[i] = { ...this.shares[i], level }
  }
  async remove(id: string) {
    const i = this.shares.findIndex((s) => s.id === id)
    if (i < 0) throw new Error('No such permission.')
    if (this.failFor.has(this.shares[i].email)) throw new Error(`Google refused to remove ${this.shares[i].email}.`)
    this.shares.splice(i, 1)
  }
}

interface DrivePermission { id: string; type: string; role: string; emailAddress?: string; domain?: string }

const levelOf = (role: string): ShareLevel | null =>
  role === 'owner' ? 'OWNER' : role === 'writer' || role === 'organizer' || role === 'fileOrganizer' ? 'WRITER' : role === 'reader' || role === 'commenter' ? 'READER' : null

/** The Drive v3 permissions API for the Sheet file. Needs the Drive API enabled and the `drive` scope (asked only from an admin). */
export class DriveSharing implements SheetSharing {
  private base: string
  private call: ReturnType<typeof makeHttp>
  constructor(fileId: string, token: (force: boolean) => Promise<string>, fetchImpl?: typeof fetch) {
    this.base = `https://www.googleapis.com/drive/v3/files/${fileId}/permissions`
    this.call = makeHttp(token, fetchImpl)
  }

  async list(): Promise<ShareSnapshot> {
    const people: Share[] = []
    const open: OpenAccess[] = []
    let page = ''
    do {
      const res = await this.call('GET', `${this.base}?supportsAllDrives=true&pageSize=100&fields=${encodeURIComponent('nextPageToken,permissions(id,type,role,emailAddress,domain)')}${page}`)
      for (const p of (res.permissions as DrivePermission[] | undefined) ?? []) {
        const level = levelOf(p.role)
        if (!level) continue
        if ((p.type === 'user' || p.type === 'group') && p.emailAddress) people.push({ id: p.id, email: p.emailAddress, level })
        else if (p.type === 'anyone') open.push({ who: 'Anyone with the link', level })
        else if (p.type === 'domain') open.push({ who: `Everyone at ${p.domain ?? 'the organisation'}`, level })
      }
      page = res.nextPageToken ? `&pageToken=${encodeURIComponent(res.nextPageToken as string)}` : ''
    } while (page)
    return { people, open }
  }

  private role = (level: ShareLevel) => (level === 'WRITER' ? 'writer' : 'reader')

  async add(email: string, level: ShareLevel) {
    await this.call('POST', `${this.base}?supportsAllDrives=true&sendNotificationEmail=true`, { type: 'user', role: this.role(level), emailAddress: email })
  }
  async change(id: string, level: ShareLevel) {
    await this.call('PATCH', `${this.base}/${encodeURIComponent(id)}?supportsAllDrives=true`, { role: this.role(level) })
  }
  async remove(id: string) {
    await this.call('DELETE', `${this.base}/${encodeURIComponent(id)}?supportsAllDrives=true`)
  }
}
