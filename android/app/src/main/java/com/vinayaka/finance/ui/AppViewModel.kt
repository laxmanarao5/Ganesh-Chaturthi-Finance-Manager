package com.vinayaka.finance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinayaka.finance.VinayakaApp
import com.vinayaka.finance.data.Access
import com.vinayaka.finance.data.ContactView
import com.vinayaka.finance.data.DEFAULT_ADMIN_EMAIL
import com.vinayaka.finance.data.Prefs
import com.vinayaka.finance.data.ThemeMode
import com.vinayaka.finance.data.auth.AuthException
import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.model.Role
import com.vinayaka.finance.data.sharing.SharingReport
import com.vinayaka.finance.data.sharing.SheetSharingService
import com.vinayaka.finance.data.sheets.GoogleSheetsGateway
import com.vinayaka.finance.data.sheets.SheetsApiException
import com.vinayaka.finance.data.sync.SyncEngine
import com.vinayaka.finance.data.sync.SyncReport
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.importer.ImportBundle
import com.vinayaka.finance.importer.ImportMapper
import com.vinayaka.finance.importer.ImportPlan
import com.vinayaka.finance.util.currentYear
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface SyncUi {
    data object Idle : SyncUi
    data object Running : SyncUi
    data class Done(val report: SyncReport) : SyncUi
    data class Failed(val message: String) : SyncUi
}

data class UiEvent(val message: String, val undo: Entry? = null)

sealed interface UsersUi {
    data object Loading : UsersUi
    data class Loaded(val users: List<AppUser>, val hasTab: Boolean) : UsersUi
    data class Failed(val message: String) : UsersUi
}

sealed interface SignOutUi {
    data object Idle : SignOutUi
    data object Syncing : SignOutUi
    data class Failed(val message: String, val pending: Int) : SignOutUi
}

sealed interface ExitUi {
    data object Idle : ExitUi
    data object Syncing : ExitUi
    data class Failed(val message: String) : ExitUi
    data object Close : ExitUi
}

data class SharingUi(val report: SharingReport? = null, val error: String? = null)

/** Year is not held here: every screen starts on the current year and keeps its own choice. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as VinayakaApp).container

    val prefs: StateFlow<Prefs?> = c.settings.flow.map<Prefs, Prefs?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val googleConfigured get() = c.auth.isConfigured

    /** Years the user can pick: everything on this phone, everything in the Sheet, and this year. */
    val yearOptions: StateFlow<List<Int>> = combine(c.repo.observeYears(), prefs) { local, p ->
        (local + p?.knownYears.orEmpty() + currentYear()).distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(currentYear()))

    val dirtyCount: StateFlow<Int> = c.repo.observeDirtyCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Committee names for the collector / payer / caller pickers. */
    val people: StateFlow<List<String>> = combine(c.repo.observePeople(), prefs) { named, p ->
        Stats.people(p?.people.orEmpty(), emptyList(), named + listOfNotNull(p?.myName?.takeIf { it.isNotBlank() }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sync = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val sync: StateFlow<SyncUi> = _sync.asStateFlow()

    private val _lastReport = MutableStateFlow<SyncReport?>(null)
    val lastReport: StateFlow<SyncReport?> = _lastReport.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _users = MutableStateFlow<UsersUi>(UsersUi.Loading)
    val users: StateFlow<UsersUi> = _users.asStateFlow()

    private val _sharing = MutableStateFlow<SharingUi?>(null)
    /** Outcome of the last change to the Google Sheet's sharing; shown once, then dismissed. */
    val sharing: StateFlow<SharingUi?> = _sharing.asStateFlow()

    private val _accessError = MutableStateFlow<String?>(null)
    /** Why the access check could not finish (offline, Google error). Null while checking or when done. */
    val accessError: StateFlow<String?> = _accessError.asStateFlow()

    /** Viewers can look but not change anything; Editors and Admins can. */
    val canEdit: StateFlow<Boolean> = prefs.map { it?.role?.canEdit == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Only Admins manage users. */
    val canManageUsers: StateFlow<Boolean> = prefs.map { it?.role?.canManageUsers == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private suspend fun who() = c.settings.current().who

    // ------------------------------------------------------------ data for screens
    fun entries(year: Int): Flow<List<Entry>> = c.repo.observeYear(year)
    fun contacts(year: Int): Flow<List<ContactView>> = c.repo.observeContacts(year)

    /** Called when a screen shows a year that is not on this phone yet: fetches it from the Sheet quietly. */
    fun ensureYear(year: Int) {
        viewModelScope.launch {
            val p = c.settings.current()
            if (year !in c.store.years() && year in p.knownYears) {
                syncNow(extraYears = setOf(year), quiet = true)
            }
        }
    }

    // ------------------------------------------------------------ account
    fun signIn() {
        viewModelScope.launch {
            _working.value = true; _authError.value = null
            try {
                val u = c.auth.signIn()
                c.settings.setUser(u.email, u.name)
            } catch (e: AuthException) {
                _authError.value = e.message
            } catch (e: Exception) {
                _authError.value = friendly(e)
            } finally {
                _working.value = false
            }
        }
    }

    fun startDemo() {
        viewModelScope.launch {
            c.repo.clear()
            c.settings.setDemo(true)
            c.settings.setAccess(Role.ADMIN, emptySet(), "")
            seedDemo()
            syncNow(allYears = true)
        }
    }

    private suspend fun seedDemo() {
        val y = currentYear()
        fun d(m: Int, day: Int) = "%04d-%02d-%02d".format(y, m, day)
        val me = "demo@local"
        suspend fun don(cat: String, name: String, detail: String, rupees: Long, by: String, m: Int, day: Int) =
            c.repo.add(EntryType.DONATION, d(m, day), cat, name, detail, rupees * 100, by, me)
        don("Youth", "Ravi Kumar", "", 1001, "Laxman", 8, 24)
        don("Youth", "Suresh", "", 501, "Bhanu", 8, 24)
        don("Villagers", "Narayana Rao", "Main street", 5000, "Bhanu", 8, 25)
        don("Villagers", "Appalaswamy", "Temple street", 1001, "", 8, 25)
        don("Villagers", "Ramana", "Market road", 500, "Laxman", 8, 26)
        don("Children", "Anitha", "d/o Murali", 100, "Bhanu", 8, 26)
        don("Children", "Rohit", "s/o Kumar", 101, "Simhadri", 8, 27)
        don("Other villagers", "Gopal", "Chellaivalasa", 200, "Bhanu", 8, 27)
        don("Annadhanam", "Lakshmi", "Rice bag", 1600, "Laxman", 9, 1)
        don("Annadhanam", "Satya", "Vegetables", 0, "", 9, 2)
        c.repo.add(EntryType.OTHER, d(9, 7), "", "Laddu", "Auction", 1010000, "Venky", me)
        c.repo.add(EntryType.OTHER, d(9, 7), "", "Stage support", "Given by committee", 1000000, "", me)
        suspend fun exp(cat: String, item: String, qty: String, amt: Long, by: String, m: Int, day: Int) =
            c.repo.add(EntryType.EXPENDITURE, d(m, day), cat, item, qty, amt, by, me)
        exp("Pooja", "Idol and decoration", "", 1800000, "Laxman", 8, 28)
        exp("Pooja", "Banana", "5 dozen", 22000, "Bhanu", 9, 5)
        exp("Pooja", "Atukulu", "6 kg", 37500, "Bhanu", 9, 5)
        exp("Annadhanam", "Kirana", "", 429500, "Bhanu", 9, 5)
        exp("Annadhanam", "Gas", "", 100000, "Simhadri", 9, 5)
        for ((i, row) in listOf(
            Triple("Venkat", "9876543210", "Laxman"), Triple("Srinu", "9876500001", "Bhanu"),
            Triple("Murali", "9876500002", "Venky"), Triple("Kishore", "9876500003", "Laxman"),
        ).withIndex()) {
            c.repo.addContact(row.first, row.second, row.third, "", y, me)
            if (i == 0) {
                val v = c.repo.allContacts().first { it.name == row.first }
                c.repo.markCalled(v.contactId, y, true, me)
            }
        }
    }

    fun connectSheet(input: String) {
        viewModelScope.launch {
            _working.value = true; _authError.value = null
            try {
                val id = GoogleSheetsGateway.parseSpreadsheetId(input)
                    ?: throw AuthException("That doesn't look like a Google Sheets link. Paste the full link from your browser.")
                val email = c.settings.current().email
                GoogleSheetsGateway(id, accessToken = { c.auth.accessToken(email) }).listYears() // proves we can reach it
                val previous = c.settings.current().spreadsheetId
                if (previous.isNotEmpty() && previous != id) c.repo.clear()
                c.settings.setSpreadsheet(id)
                syncNow(allYears = true)
            } catch (e: Exception) {
                _authError.value = friendly(e)
            } finally {
                _working.value = false
            }
        }
    }

    private val _signOut = MutableStateFlow<SignOutUi>(SignOutUi.Idle)
    /** Progress of signing out: waiting changes are synced first, because signing out removes this phone's copy. */
    val signOutState: StateFlow<SignOutUi> = _signOut.asStateFlow()

    /**
     * Signs out. If changes are waiting (and this account may write), they are sent to the Sheet first;
     * if that fails nothing is deleted and [signOutState] asks whether to stay or sign out anyway ([force]).
     */
    fun signOut(force: Boolean = false) {
        if (_signOut.value == SignOutUi.Syncing) return
        viewModelScope.launch {
            if (!force) {
                if (_sync.value == SyncUi.Running) { _signOut.value = SignOutUi.Syncing; _sync.first { it != SyncUi.Running } }
                val p = c.settings.current()
                if (needsSyncOnLeave(p, dirtyCount.value)) {
                    _signOut.value = SignOutUi.Syncing
                    performSync(allYears = false, extraYears = emptySet(), quiet = true)
                    val s = _sync.value
                    if (s is SyncUi.Failed) {
                        _sync.value = SyncUi.Idle   // shown in the sign-out dialog instead
                        _signOut.value = SignOutUi.Failed(s.message, dirtyCount.value)
                        return@launch
                    }
                }
            }
            _signOut.value = SignOutUi.Idle
            c.auth.signOut()
            c.repo.clear()
            c.settings.clearAccount()
            _sync.value = SyncUi.Idle
            _lastReport.value = null
        }
    }

    fun changeSheet() {
        viewModelScope.launch {
            c.repo.clear()
            c.settings.setSpreadsheet("")
        }
    }

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { c.settings.setTheme(mode) } }
    fun setMyName(name: String) { viewModelScope.launch { c.settings.setMyName(name) } }
    fun clearAuthError() { _authError.value = null }

    // ------------------------------------------------------------ records
    private fun canon(person: String): String = Stats.canonicalPerson(person, people.value)

    fun addEntry(type: EntryType, date: String, category: String, name: String, description: String, amount: Long, person: String) {
        viewModelScope.launch {
            c.repo.add(type, date, category, name, description, amount, canon(person), who())
            _events.emit(UiEvent("Saved on this phone"))
        }
    }

    fun updateEntry(old: Entry, date: String, category: String, name: String, description: String, amount: Long, person: String) {
        viewModelScope.launch {
            c.repo.update(old, date, category, name, description, amount, canon(person), who())
            _events.emit(UiEvent("Changes saved"))
        }
    }

    fun setPerson(e: Entry, person: String) {
        viewModelScope.launch { c.repo.setPerson(e, canon(person), who()) }
    }

    fun deleteEntry(e: Entry) {
        viewModelScope.launch {
            c.repo.delete(e, who())
            _events.emit(UiEvent("Deleted \"${e.name.take(28)}\"", undo = e))
        }
    }

    fun undoDelete(e: Entry) { viewModelScope.launch { c.repo.undoDelete(e) } }

    // ------------------------------------------------------------ contacts
    fun markCalled(contactId: String, year: Int, called: Boolean) {
        viewModelScope.launch { c.repo.markCalled(contactId, year, called, who()) }
    }

    fun addContact(name: String, number: String, caller: String, remarks: String, year: Int) {
        viewModelScope.launch {
            c.repo.addContact(name, number, canon(caller), remarks, year, who())
            _events.emit(UiEvent("Contact added"))
        }
    }

    fun editContact(contactId: String, year: Int, name: String, number: String, caller: String, remarks: String) {
        viewModelScope.launch { c.repo.editContact(contactId, year, name, number, canon(caller), remarks, who()) }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            c.repo.deleteContact(contactId, who())
            _events.emit(UiEvent("Contact deleted"))
        }
    }

    // ------------------------------------------------------------ import
    suspend fun planImport(bundle: ImportBundle, undatedDate: String, contactYear: Int): ImportPlan {
        val years = (bundle.entries.mapNotNull { it.date?.take(4)?.toIntOrNull() } + undatedDate.take(4).toInt()).distinct()
        val existing = years.flatMap { c.repo.allForYear(it) }
        return ImportMapper.plan(bundle, undatedDate, contactYear, existing, c.repo.allContacts(), who(), System.currentTimeMillis())
    }

    fun applyImport(plan: ImportPlan, onDone: () -> Unit) {
        viewModelScope.launch {
            c.repo.addMany(plan.entries)
            c.repo.addManyContacts(plan.contacts)
            onDone()
        }
    }

    // ------------------------------------------------------------ access
    private fun committeeNames(users: List<AppUser>?) = users.orEmpty().map(Access::displayName).filter { it.isNotBlank() }.toSet()

    private fun myNameIn(users: List<AppUser>?, email: String) =
        users.orEmpty().firstOrNull { it.email.equals(email, true) }?.let(Access::displayName).orEmpty()

    /**
     * Reads the Sheet's Users tab and decides whether this Google account may use the app, and as what.
     * Only listed emails get in; the built-in default admin always does. Offline, the last result is kept.
     */
    fun checkAccess() {
        viewModelScope.launch {
            _accessError.value = null
            val p = c.settings.current()
            if (p.demo) { c.settings.setAccess(Role.ADMIN, emptySet(), ""); return@launch }
            try {
                val users = c.gateway(p).readUsers()
                val role = Access.resolve(users, p.email)
                if (role == null) {
                    c.repo.clear()
                    c.settings.setDenied("${p.email} is not on this app's access list. Ask an admin to add this email, then check again.")
                } else {
                    c.settings.setAccess(role, committeeNames(users), myNameIn(users, p.email))
                }
            } catch (e: SheetsApiException) {
                if (e.isForbidden && !Access.isDefaultAdmin(p.email)) {
                    c.repo.clear()
                    c.settings.setDenied("The Google Sheet is not shared with ${p.email}. Ask an admin to share it with you and to add this email to the access list.")
                } else {
                    _accessError.value = friendly(e, p)
                }
            } catch (e: Exception) {
                _accessError.value = friendly(e, p)
            }
        }
    }

    // ------------------------------------------------------------ sync
    fun syncNow(allYears: Boolean = false, extraYears: Set<Int> = emptySet(), quiet: Boolean = false) {
        if (_sync.value == SyncUi.Running) return
        viewModelScope.launch { performSync(allYears, extraYears, quiet) }
    }

    private suspend fun performSync(allYears: Boolean, extraYears: Set<Int>, quiet: Boolean) {
        run {
            _sync.value = SyncUi.Running
            val p = c.settings.current()
            try {
                // The phone's copy came from another sheet (the build was pointed somewhere else): start afresh.
                if (!p.demo && p.dataSheetId.isNotEmpty() && p.dataSheetId != p.spreadsheetId) c.repo.clear()
                val gateway = c.gateway(p)
                val users = if (p.demo) null else gateway.readUsers()
                val role = if (p.demo) Role.ADMIN else Access.resolve(users, p.email)
                if (role == null) {
                    // Removed from the access list since the last check: lock this phone and forget the data.
                    c.repo.clear()
                    c.settings.setDenied("${p.email} is no longer on this app's access list. Ask an admin to add this email, then check again.")
                    _sync.value = SyncUi.Idle
                    return@run
                }
                val report = SyncEngine(c.store, gateway).sync(
                    currentYear = currentYear(), canWrite = role.canEdit,
                    extraYears = extraYears, allYears = allYears,
                )
                c.settings.setSynced(
                    System.currentTimeMillis(), report.remoteYears, role,
                    people = committeeNames(users),
                    defaultMyName = myNameIn(users, p.email),
                    sheetId = p.spreadsheetId,
                )
                _lastReport.value = report
                _sync.value = if (quiet) SyncUi.Idle else SyncUi.Done(report)
            } catch (e: SheetsApiException) {
                if (e.isForbidden && !p.demo && p.role.canEdit && !Access.isDefaultAdmin(p.email)) c.settings.setRole(Role.VIEWER)
                _sync.value = SyncUi.Failed(friendly(e, p))
            } catch (e: Exception) {
                _sync.value = SyncUi.Failed(friendly(e, p))
            }
        }
    }

    fun dismissSync() { _sync.value = SyncUi.Idle }

    // ------------------------------------------------------------ exit
    private val _exit = MutableStateFlow<ExitUi>(ExitUi.Idle)
    /** Progress of "Yes, exit": syncing first when something is waiting, then [ExitUi.Close]. */
    val exit: StateFlow<ExitUi> = _exit.asStateFlow()

    fun cancelSignOut() { _signOut.value = SignOutUi.Idle }

    /** Whether leaving (closing the app or signing out) should first send this phone's changes to the Sheet. */
    fun needsSyncOnLeave(p: Prefs, pending: Int) = !p.demo && !p.denied && p.role.canEdit && pending > 0

    /** The user confirmed they want to leave. Sends waiting changes first (if any), then asks the screen to close. */
    fun exitApp() {
        if (_exit.value != ExitUi.Idle) return
        viewModelScope.launch {
            _exit.value = ExitUi.Syncing
            if (_sync.value == SyncUi.Running) _sync.first { it != SyncUi.Running }   // a sync is already under way: let it finish
            val p = c.settings.current()
            if (needsSyncOnLeave(p, dirtyCount.value)) {
                performSync(allYears = false, extraYears = emptySet(), quiet = true)
                val s = _sync.value
                if (s is SyncUi.Failed) {
                    _sync.value = SyncUi.Idle   // shown in the exit dialog instead
                    _exit.value = ExitUi.Failed(s.message)
                    return@launch
                }
            }
            _exit.value = ExitUi.Close
        }
    }

    /** After a failed sync: leave anyway (the changes stay safe on this phone). */
    fun exitAnyway() { _exit.value = ExitUi.Close }

    fun cancelExit() { _exit.value = ExitUi.Idle }

    /** Throws away this phone's copy and downloads everything from the Sheet. */
    fun resetFromSheet() {
        viewModelScope.launch {
            c.repo.clear()
            syncNow(allYears = true)
        }
    }

    // ------------------------------------------------------------ users (admins only)
    fun loadUsers() {
        viewModelScope.launch {
            _users.value = UsersUi.Loading
            try {
                val p = c.settings.current()
                val list = c.gateway(p).readUsers()
                _users.value = UsersUi.Loaded(Access.normalized(list.orEmpty()), hasTab = list != null)
            } catch (e: Exception) {
                _users.value = UsersUi.Failed(friendly(e))
            }
        }
    }

    /**
     * Writes the list to the Users tab, then makes the Google Sheet's own sharing match it
     * (new people are shared with, changed roles are updated, removed people lose access).
     * The list is saved even if the sharing step fails; the result dialog says what happened.
     */
    fun saveUsers(list: List<AppUser>) {
        viewModelScope.launch {
            if (!canManageUsers.value) return@launch
            _working.value = true
            try {
                val clean = Access.normalized(list)
                val p = c.settings.current()
                val before = (_users.value as? UsersUi.Loaded)?.users.orEmpty()
                c.gateway(p).writeUsers(clean)
                _users.value = UsersUi.Loaded(clean, hasTab = true)
                c.settings.setAccess(Access.resolve(clean, p.email) ?: p.role, committeeNames(clean), myNameIn(clean, p.email))
                _events.emit(UiEvent("Users saved to the Sheet"))
                val removed = before.map { it.email.lowercase() } - clean.map { it.email.lowercase() }.toSet()
                reconcileSharing(p, clean, removed)
            } catch (e: Exception) {
                _events.emit(UiEvent(friendly(e)))
            } finally {
                _working.value = false
            }
        }
    }

    /** Re-applies the saved list to the Google Sheet's sharing, e.g. for people who were shared with by hand. */
    fun fixSharing() {
        viewModelScope.launch {
            val saved = (_users.value as? UsersUi.Loaded)?.users ?: return@launch
            if (!canManageUsers.value || _working.value) return@launch
            _working.value = true
            try { reconcileSharing(c.settings.current(), saved, emptySet()) } finally { _working.value = false }
        }
    }

    private suspend fun reconcileSharing(p: Prefs, desired: List<AppUser>, removed: Collection<String>) {
        _sharing.value = try {
            val report = SheetSharingService.reconcile(c.sharing(p), desired, removed, keep = listOf(DEFAULT_ADMIN_EMAIL, p.email))
            SharingUi(report = report)
        } catch (e: Exception) {
            SharingUi(error = sharingFriendly(e))
        }
    }

    fun dismissSharing() { _sharing.value = null }

    private fun sharingFriendly(e: Throwable): String = when {
        e is AuthException -> (e.message ?: "Sign-in problem.") + " The list is saved, but the Sheet's sharing was not changed."
        e is SheetsApiException && e.isForbidden && (e.message.orEmpty().contains("has not been used", true) || e.message.orEmpty().contains("disabled", true)) ->
            "Google Drive API is switched off for this app's Google Cloud project. Turn it on (APIs & Services, Library, Google Drive API, Enable) and try again."
        e is SheetsApiException && e.isForbidden ->
            "Google will not let this account change the Sheet's sharing. Only the Sheet's owner can (or an editor, if the owner allows editors to share). The list is saved; share the Sheet by hand for now."
        e is SheetsApiException && e.isNotFound -> "Google could not find the Sheet to share. Check the link in the build."
        e is IOException -> "No internet connection. The list is saved; try updating the sharing again when you are online."
        else -> e.message ?: "Something went wrong while changing the Sheet's sharing."
    }

    fun sheetUrl(): String? = prefs.value?.takeIf { !it.demo && it.spreadsheetId.isNotEmpty() }
        ?.let { "https://docs.google.com/spreadsheets/d/${it.spreadsheetId}/edit" }

    private fun friendly(e: Throwable, p: Prefs? = prefs.value): String = when {
        e is AuthException -> e.message ?: "Sign-in problem."
        e is SheetsApiException && e.isForbidden ->
            "This Google account can't edit the sheet. Ask the owner to share it with ${p?.email.orEmpty().ifEmpty { "you" }} as Editor. Your changes are still safe on this phone."
        e is SheetsApiException && e.isNotFound -> "Spreadsheet not found. Check the link, and that it is shared with your Google account."
        e is SheetsApiException && e.code == 401 -> "Google rejected the sign-in. Sign out and sign in again."
        e is IOException -> "No internet connection. Your changes are safe on this phone; sync again when you're online."
        else -> e.message ?: "Something went wrong."
    }
}
