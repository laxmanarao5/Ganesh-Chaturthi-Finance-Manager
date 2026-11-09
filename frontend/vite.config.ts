import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * The Sheet link and the Google Web client id are fixed at build time, exactly like the Android app: they are read from the same
 * git-ignored android/secrets.properties (or from the environment). Nothing here is a secret: the client id is public by design, and
 * the Sheet is protected by Google's own sharing (restricted), not by hiding its id.
 */
function secrets(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const file of [resolve(__dirname, '../android/secrets.properties'), resolve(__dirname, '.env.local')]) {
    if (!existsSync(file)) continue
    for (const raw of readFileSync(file, 'utf8').split(/\r?\n/)) {
      const line = raw.trim()
      if (!line || line.startsWith('#') || !line.includes('=')) continue
      const i = line.indexOf('=')
      out[line.slice(0, i).trim()] = line.slice(i + 1).trim()
    }
  }
  return out
}

function setting(file: Record<string, string>, key: string): string {
  return (process.env[key] ?? '').trim() || (file[key] ?? '').trim()
}

export default defineConfig(() => {
  const file = secrets()
  const sheetUrl = setting(file, 'SPREADSHEET_URL')
  const sheetId = /\/spreadsheets\/d\/([a-zA-Z0-9_-]+)/.exec(sheetUrl)?.[1] ?? (/^[a-zA-Z0-9_-]{20,}$/.test(sheetUrl) ? sheetUrl : '')
  const clientId = setting(file, 'GOOGLE_WEB_CLIENT_ID')
  return {
    plugins: [react()],
    // Relative URLs so the same build works at https://<user>.github.io/<repo>/ and from any folder.
    base: './',
    define: {
      __SHEET_ID__: JSON.stringify(sheetId),
      __CLIENT_ID__: JSON.stringify(clientId),
      __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
    },
    build: { outDir: 'dist', emptyOutDir: true, sourcemap: false },
    server: { port: 5173, strictPort: true },
    test: { environment: 'node', include: ['src/**/*.test.ts'] },
  }
})
