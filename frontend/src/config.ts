/** Fixed at build time, like the Android app: the portal never asks anyone for the Sheet link. */
export const SHEET_ID: string = __SHEET_ID__
export const CLIENT_ID: string = __CLIENT_ID__
export const BUILD_TIME: string = __BUILD_TIME__

export const SHEET_URL = SHEET_ID ? `https://docs.google.com/spreadsheets/d/${SHEET_ID}/edit` : ''

/** Built in, so a blank Sheet still lets someone in to add the first users. Always an Admin, cannot be removed. */
export const DEFAULT_ADMIN_EMAIL = 'lakshmana5296@gmail.com'

export const APK_URL = 'https://raw.githubusercontent.com/laxmanarao5/Ganesh-Chaturthi-Finance-Manager/master/android/releases/vinayaka-finance.apk'
