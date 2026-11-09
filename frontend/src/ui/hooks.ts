import { createContext, useContext, useEffect, useState, useSyncExternalStore } from 'react'
import type { AppState, Core } from '../core/core'
import { currentYear } from '../lib/format'

export const CoreContext = createContext<Core | null>(null)

/** Switches between the real Sheet and the in-memory demo (a separate Core, so demo mode can never touch the real Sheet). */
export interface Host { enterDemo(): void; leaveDemo(): void }
export const HostContext = createContext<Host>({ enterDemo: () => undefined, leaveDemo: () => undefined })
export const useHost = () => useContext(HostContext)

export function useCore(): Core {
  const c = useContext(CoreContext)
  if (!c) throw new Error('No core')
  return c
}

export function useApp(): AppState {
  const core = useCore()
  return useSyncExternalStore(core.subscribe, core.getState)
}

/** Every screen has its own year and opens on the current one (in 2027 it opens on 2027). */
export function useYear(): [number, (y: number) => void] {
  const core = useCore()
  const [year, setYear] = useState(currentYear())
  useEffect(() => { void core.loadYear(year) }, [core, year])
  return [year, setYear]
}

export type Route = 'home' | 'donations' | 'expenses' | 'others' | 'people' | 'contacts' | 'reports' | 'import' | 'users' | 'more'
const ROUTES: Route[] = ['home', 'donations', 'expenses', 'others', 'people', 'contacts', 'reports', 'import', 'users', 'more']

function parseHash(): Route {
  const h = window.location.hash.replace(/^#\/?/, '')
  return (ROUTES as string[]).includes(h) ? (h as Route) : 'home'
}

export function useRoute(): [Route, (r: Route) => void] {
  const [route, setRoute] = useState<Route>(parseHash)
  useEffect(() => {
    const on = () => setRoute(parseHash())
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return [route, (r) => { window.location.hash = `#/${r}` }]
}

export function useMedia(query: string): boolean {
  const [m, setM] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const mq = window.matchMedia(query)
    const on = () => setM(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [query])
  return m
}
