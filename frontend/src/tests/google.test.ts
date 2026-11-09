import { describe, expect, it } from 'vitest'
import { GoogleSheets, makeHttp } from '../lib/googleSheets'
import { DriveSharing } from '../lib/sharing'
import { SheetsError } from '../lib/sheetsApi'
import { ContactRows, EntryRows } from '../lib/rows'
import type { Contact, Entry } from '../lib/model'

interface Call { method: string; url: string; body: unknown }

/** A scripted stand-in for fetch: answers by URL pattern and records every call. */
function scripted(handler: (c: Call) => unknown | Response) {
  const calls: Call[] = []
  const fetchImpl = (async (url: string, init?: RequestInit) => {
    const call: Call = { method: init?.method ?? 'GET', url, body: init?.body ? JSON.parse(String(init.body)) : undefined }
    calls.push(call)
    const r = handler(call)
    if (r instanceof Response) return r
    return new Response(r === undefined ? '' : JSON.stringify(r), { status: 200 })
  }) as typeof fetch
  return { calls, fetchImpl }
}

const entry = (p: Partial<Entry> = {}): Entry => ({
  id: 'e1', type: 'DONATION', year: 2026, date: '2026-09-14', category: 'Youth', name: 'Donor', description: '', amount: 150050, person: 'Laxman',
  createdBy: 'a@x.com', createdAt: 1000, updatedBy: 'a@x.com', updatedAt: 2000, deletedAt: null, rev: 1, row: 0, hashMatches: true, hadId: true, ...p,
})

const tabsReply = (titles: string[]) => ({ sheets: titles.map((t, i) => ({ properties: { sheetId: i + 1, title: t } })) })

describe('http helper', () => {
  it('sends the bearer token and JSON, and parses the reply', async () => {
    const { calls, fetchImpl } = scripted(() => ({ ok: true }))
    const call = makeHttp(async () => 'tok', fetchImpl)
    expect(await call('POST', 'https://x/y', { a: 1 })).toEqual({ ok: true })
    expect(calls[0]).toMatchObject({ method: 'POST', url: 'https://x/y', body: { a: 1 } })
  })

  it('renews the token once on a 401 and retries', async () => {
    let n = 0
    const forced: boolean[] = []
    const { calls, fetchImpl } = scripted(() => (++n === 1 ? new Response('{"error":{"message":"expired"}}', { status: 401 }) : { ok: 1 }))
    const call = makeHttp(async (force) => { forced.push(force); return force ? 'fresh' : 'old' }, fetchImpl)
    expect(await call('GET', 'https://x')).toEqual({ ok: 1 })
    expect(calls).toHaveLength(2)
    expect(forced).toEqual([false, true, false])
  })

  it('gives up on a second 401 and reports Google’s message with the status', async () => {
    const { fetchImpl } = scripted(() => new Response('{"error":{"message":"nope"}}', { status: 401 }))
    const call = makeHttp(async () => 't', fetchImpl)
    await expect(call('GET', 'https://x')).rejects.toMatchObject({ code: 401, message: 'nope' })
  })

  it('retries a 503 and succeeds, but a 403 fails straight away', async () => {
    let n = 0
    const a = scripted(() => (++n < 2 ? new Response('busy', { status: 503 }) : { fine: true }))
    expect(await makeHttp(async () => 't', a.fetchImpl)('GET', 'https://x')).toEqual({ fine: true })
    expect(a.calls).toHaveLength(2)
    const b = scripted(() => new Response('{"error":{"message":"The caller does not have permission"}}', { status: 403 }))
    const err = await makeHttp(async () => 't', b.fetchImpl)('GET', 'https://x').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(SheetsError)
    expect((err as SheetsError).isForbidden).toBe(true)
    expect(b.calls).toHaveLength(1)
  })

  it('treats an empty 204 reply as success', async () => {
    const { fetchImpl } = scripted(() => new Response(null, { status: 204 }))
    expect(await makeHttp(async () => 't', fetchImpl)('DELETE', 'https://x')).toEqual({})
  })
})

describe('Google Sheets client', () => {
  it('lists year tabs only', async () => {
    const { fetchImpl } = scripted(() => tabsReply(['2025', '2026', 'Contacts', 'Users', 'Notes']))
    expect(await new GoogleSheets('SID', async () => 't', fetchImpl).listYears()).toEqual([2025, 2026])
  })

  it('reads a year with unformatted values, skipping blank rows and counting unreadable ones', async () => {
    const good = EntryRows.toCells(entry())
    const { calls, fetchImpl } = scripted((c) => {
      if (c.url.includes('/values/')) return { values: [good, [], ['bad date', 'Donation', '', 'x', '', 5]] }
      return tabsReply(['2026'])
    })
    const r = await new GoogleSheets('SID', async () => 't', fetchImpl).readYear(2026)
    expect(r.entries).toHaveLength(1)
    expect(r.entries[0]).toMatchObject({ id: 'e1', row: 2, amount: 150050, hashMatches: true })
    expect(r.skipped).toBe(1)
    const read = calls.find((c) => c.url.includes('/values/'))!
    expect(read.url).toContain('valueRenderOption=UNFORMATTED_VALUE')
    expect(read.url).toContain(encodeURIComponent("'2026'!A2:O"))
  })

  it('reads nothing for a year with no tab, without asking for values', async () => {
    const { calls, fetchImpl } = scripted(() => tabsReply(['2026']))
    expect((await new GoogleSheets('SID', async () => 't', fetchImpl).readYear(2030)).entries).toEqual([])
    expect(calls.some((c) => c.url.includes('/values/'))).toBe(false)
  })

  it('creates a missing year tab with header and formatting before appending, in that order', async () => {
    const { calls, fetchImpl } = scripted((c) => {
      if (c.method === 'GET') return tabsReply(['Contacts'])
      if (c.url.endsWith(':batchUpdate') && JSON.stringify(c.body).includes('addSheet')) return { replies: [{ addSheet: { properties: { sheetId: 77 } } }] }
      return {}
    })
    await new GoogleSheets('SID', async () => 't', fetchImpl).appendEntries(2027, [entry({ year: 2027, date: '2027-01-02' })])
    const writes = calls.filter((c) => c.method === 'POST')
    expect(JSON.stringify(writes[0].body)).toContain('"title":"2027"')
    expect((writes[1].body as { values: unknown[][] }).values[0]).toEqual(EntryRows.HEADERS)
    const fmt = JSON.stringify(writes[2].body)
    expect(fmt).toContain('"sheetId":77')
    expect(fmt).toContain('yyyy-mm-dd')
    expect(fmt).toContain('setBasicFilter')
    expect(fmt).toContain('=$O2<>')
    const row = (writes[3].body as { values: unknown[][] }).values[0]
    expect(row).toHaveLength(15)
    expect(row[0]).toBe(46389) // 2027-01-02 as a Sheet date
    expect(writes[3].url).toContain('valueInputOption=RAW')
    expect(writes[3].url).toContain('insertDataOption=INSERT_ROWS')
  })

  it('appends to an existing tab once, fixing its number formats only the first time', async () => {
    const { calls, fetchImpl } = scripted((c) => (c.method === 'GET' ? tabsReply(['2026']) : {}))
    const g = new GoogleSheets('SID', async () => 't', fetchImpl)
    await g.appendEntries(2026, [entry()])
    await g.appendEntries(2026, [entry({ id: 'e2' })])
    expect(calls.filter((c) => c.url.endsWith(':batchUpdate'))).toHaveLength(1)
    expect(calls.filter((c) => c.url.includes(':append'))).toHaveLength(2)
    expect(JSON.stringify(calls.find((c) => c.url.endsWith(':batchUpdate'))!.body)).toContain('#,##0.##')
  })

  it('overwrites exactly one row by its A1 range', async () => {
    const { calls, fetchImpl } = scripted((c) => (c.method === 'GET' ? tabsReply(['2026']) : {}))
    await new GoogleSheets('SID', async () => 't', fetchImpl).writeEntryRow(2026, 9, entry({ rev: 4 }))
    const w = calls.find((c) => c.url.endsWith('/values:batchUpdate'))!
    const body = w.body as { valueInputOption: string; data: { range: string; values: unknown[][] }[] }
    expect(body.valueInputOption).toBe('RAW')
    expect(body.data).toHaveLength(1)
    expect(body.data[0].range).toBe("'2026'!A9:O9")
    expect(body.data[0].values[0][9]).toBe(4) // rev
  })

  it('reads and writes contacts on the Contacts tab', async () => {
    const c: Contact = {
      id: 'c1:2026', contactId: 'c1', year: 2026, name: 'Anand', number: '9000000001', caller: 'Bhanu', called: false, remarks: '',
      createdBy: '', createdAt: 1, updatedBy: 'a', updatedAt: 2, deletedAt: null, rev: 1, row: 0, hashMatches: true, hadId: true,
    }
    const { calls, fetchImpl } = scripted((q) => (q.method === 'GET' ? (q.url.includes('/values/') ? { values: [ContactRows.toCells(c)] } : tabsReply(['Contacts'])) : {}))
    const g = new GoogleSheets('SID', async () => 't', fetchImpl)
    expect((await g.readContacts()).contacts[0]).toMatchObject({ id: 'c1:2026', name: 'Anand', row: 2, hashMatches: true })
    await g.writeContactRow(2, { ...c, called: true })
    const w = calls.find((x) => x.url.endsWith('/values:batchUpdate'))!
    expect((w.body as { data: { range: string }[] }).data[0].range).toBe("'Contacts'!A2:M2")
  })

  it('reads the Users tab (skipping the header and bad rows) and writes it back with a dropdown on a new tab', async () => {
    const read = scripted((c) => (c.url.includes('/values/') ? { values: [['Name', 'Email', 'Role'], ['Bhanu', 'Bhanu@X.com', 'Editor'], ['No email', '', 'Admin'], ['Vi', 'vi@x.com', 'read only']] } : tabsReply(['Users'])))
    expect(await new GoogleSheets('SID', async () => 't', read.fetchImpl).readUsers()).toEqual([
      { name: 'Bhanu', email: 'bhanu@x.com', role: 'EDITOR' }, { name: 'Vi', email: 'vi@x.com', role: 'VIEWER' },
    ])
    expect(await new GoogleSheets('SID', async () => 't', scripted(() => tabsReply(['2026'])).fetchImpl).readUsers()).toBeNull()

    const w = scripted((c) => {
      if (c.method === 'GET') return tabsReply(['2026'])
      if (JSON.stringify(c.body ?? {}).includes('addSheet')) return { replies: [{ addSheet: { properties: { sheetId: 5 } } }] }
      return {}
    })
    await new GoogleSheets('SID', async () => 't', w.fetchImpl).writeUsers([{ name: 'A', email: 'a@x.com', role: 'ADMIN' }])
    const all = JSON.stringify(w.calls)
    expect(all).toContain('ONE_OF_LIST')
    expect(all).toContain(':clear')
    const put = w.calls.find((c) => c.method === 'PUT')!
    expect((put.body as { values: string[][] }).values).toEqual([['A', 'a@x.com', 'Admin']])
  })
})

describe('Drive sharing client', () => {
  it('lists people, groups and open access across pages', async () => {
    let page = 0
    const { calls, fetchImpl } = scripted(() => (page++ === 0
      ? { nextPageToken: 'P2', permissions: [
        { id: '1', type: 'user', role: 'owner', emailAddress: 'owner@x.com' },
        { id: '2', type: 'user', role: 'writer', emailAddress: 'ed@x.com' },
        { id: '3', type: 'anyone', role: 'reader' },
      ] }
      : { permissions: [
        { id: '4', type: 'user', role: 'commenter', emailAddress: 'vi@x.com' },
        { id: '5', type: 'domain', role: 'reader', domain: 'x.com' },
        { id: '6', type: 'user', role: 'unknownrole', emailAddress: 'weird@x.com' },
      ] }))
    const snap = await new DriveSharing('FID', async () => 't', fetchImpl).list()
    expect(snap.people).toEqual([
      { id: '1', email: 'owner@x.com', level: 'OWNER' }, { id: '2', email: 'ed@x.com', level: 'WRITER' }, { id: '4', email: 'vi@x.com', level: 'READER' },
    ])
    expect(snap.open).toEqual([{ who: 'Anyone with the link', level: 'READER' }, { who: 'Everyone at x.com', level: 'READER' }])
    expect(calls[1].url).toContain('pageToken=P2')
    expect(calls[0].url).toContain('supportsAllDrives=true')
  })

  it('adds, changes and removes with the right verbs, roles and notification', async () => {
    const { calls, fetchImpl } = scripted(() => ({}))
    const d = new DriveSharing('FID', async () => 't', fetchImpl)
    await d.add('new@x.com', 'WRITER')
    await d.change('perm/1', 'READER')
    await d.remove('perm/1')
    expect(calls[0]).toMatchObject({ method: 'POST', body: { type: 'user', role: 'writer', emailAddress: 'new@x.com' } })
    expect(calls[0].url).toContain('sendNotificationEmail=true')
    expect(calls[1]).toMatchObject({ method: 'PATCH', body: { role: 'reader' } })
    expect(calls[1].url).toContain('/permissions/perm%2F1')
    expect(calls[2].method).toBe('DELETE')
  })
})
