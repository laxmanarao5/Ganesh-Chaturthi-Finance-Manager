import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { CLIENT_ID, SHEET_ID, SHEET_URL } from './config'
import { Core } from './core/core'
import { GoogleAuth } from './lib/auth'
import { GoogleSheets } from './lib/googleSheets'
import { DriveSharing } from './lib/sharing'
import './styles.css'

function createCore(): Core {
  const auth = new GoogleAuth(CLIENT_ID)
  return new Core({
    sheets: new GoogleSheets(SHEET_ID, (force) => auth.accessToken(force)),
    auth,
    // Asking for the Drive scope happens here, so it must run straight from the click that saves the Users list.
    sharing: async () => {
      await auth.driveToken()
      return new DriveSharing(SHEET_ID, (force) => auth.driveToken(force))
    },
    sheetUrl: SHEET_URL,
  })
}

createRoot(document.getElementById('root')!).render(<StrictMode><App core={createCore()} /></StrictMode>)
