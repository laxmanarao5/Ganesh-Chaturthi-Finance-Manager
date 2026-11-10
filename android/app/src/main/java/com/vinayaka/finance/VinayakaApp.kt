package com.vinayaka.finance

import android.app.Application
import android.content.Context
import com.vinayaka.finance.data.EntryRepository
import com.vinayaka.finance.data.Prefs
import com.vinayaka.finance.data.RoomEntryStore
import com.vinayaka.finance.data.Settings
import com.vinayaka.finance.data.auth.GoogleAuth
import com.vinayaka.finance.data.local.AppDatabase
import com.vinayaka.finance.data.update.Updater
import com.vinayaka.finance.data.sharing.DriveSharing
import com.vinayaka.finance.data.sharing.FakeSharing
import com.vinayaka.finance.data.sharing.SheetSharing
import com.vinayaka.finance.data.sheets.FakeSheetGateway
import com.vinayaka.finance.data.sheets.GoogleHttp
import com.vinayaka.finance.data.sheets.GoogleSheetsGateway
import com.vinayaka.finance.data.sync.SheetGateway
import com.vinayaka.finance.report.FakeReportPublisher
import com.vinayaka.finance.report.GoogleReports
import com.vinayaka.finance.report.ReportPublisher

class AppContainer(context: Context) {
    private val db = AppDatabase.create(context)
    val repo = EntryRepository(db.entryDao(), db.contactDao())
    val store = RoomEntryStore(db.entryDao(), db.contactDao())
    val settings = Settings(context)
    val auth = GoogleAuth(context)
    val updater = Updater(context)

    /** Demo mode talks to this in-memory sheet so the app can be tried without a Google account. */
    private val demoSheet = FakeSheetGateway()

    private val demoSharing = FakeSharing()

    fun gateway(p: Prefs): SheetGateway =
        if (p.demo) demoSheet else GoogleSheetsGateway(p.spreadsheetId, accessToken = { auth.accessToken(p.email) })

    /** Sharing of the Google Sheet itself. Asks for Drive access only when an admin actually changes it. */
    fun sharing(p: Prefs): SheetSharing =
        if (p.demo) demoSharing else DriveSharing(p.spreadsheetId, GoogleHttp({ auth.accessToken(p.email, drive = true) }))

    private val demoReports = FakeReportPublisher()
    private val demoReportSharing = FakeSharing()

    /** Creates the annual report as a new Google Sheet. The token carries the Drive scope too, so the same sign-in lets the admin share it. */
    fun reportPublisher(p: Prefs): ReportPublisher =
        if (p.demo) demoReports else GoogleReports(GoogleHttp({ auth.accessToken(p.email, drive = true) }))

    /** Sharing of a report Sheet that was just created (a different file from the committee's Sheet). */
    fun fileSharing(p: Prefs, fileId: String): SheetSharing =
        if (p.demo) demoReportSharing else DriveSharing(fileId, GoogleHttp({ auth.accessToken(p.email, drive = true) }))
}

class VinayakaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
