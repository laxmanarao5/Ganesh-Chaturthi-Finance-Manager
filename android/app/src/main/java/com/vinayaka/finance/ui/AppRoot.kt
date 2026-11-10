package com.vinayaka.finance.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vinayaka.finance.BuildConfig
import com.vinayaka.finance.data.Prefs
import com.vinayaka.finance.data.ThemeMode
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.sync.SyncReport
import com.vinayaka.finance.ui.screens.ConnectSheetScreen
import com.vinayaka.finance.ui.screens.ContactsScreen
import com.vinayaka.finance.ui.screens.EntryListScreen
import com.vinayaka.finance.ui.screens.HomeScreen
import com.vinayaka.finance.ui.screens.ImportScreen
import com.vinayaka.finance.ui.screens.CheckingAccessScreen
import com.vinayaka.finance.ui.screens.MoreScreen
import com.vinayaka.finance.ui.screens.NoAccessScreen
import com.vinayaka.finance.ui.screens.UsersScreen
import com.vinayaka.finance.ui.screens.PeopleScreen
import com.vinayaka.finance.ui.screens.ReportScreen
import com.vinayaka.finance.ui.screens.ReportsScreen
import com.vinayaka.finance.ui.screens.SyncScreen
import com.vinayaka.finance.ui.screens.WelcomeScreen
import com.vinayaka.finance.ui.theme.VinayakaTheme

@Composable
fun AppRoot(vm: AppViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val p = prefs
    VinayakaTheme(p?.theme ?: ThemeMode.SYSTEM) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                p == null -> Unit
                !p.signedIn -> WelcomeScreen(vm)
                // Only reached when the build has no fixed Sheet (see SPREADSHEET_URL in secrets.properties).
                !p.connected -> ConnectSheetScreen(vm, p)
                // Only people on the Sheet's Users tab (and the built-in default admin) get past this point.
                p.denied -> NoAccessScreen(vm, p)
                !p.accessChecked -> CheckingAccessScreen(vm, p)
                else -> MainScaffold(vm, p)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home),
    Tab("donations", "Donations", Icons.Rounded.VolunteerActivism),
    Tab("expenses", "Spent", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab("people", "People", Icons.Rounded.Group),
    Tab("more", "More", Icons.Rounded.MoreHoriz),
)

private val titles = mapOf(
    "home" to "Vinayaka Finance", "donations" to "Donations", "expenses" to "Expenditure", "people" to "Collected vs spent",
    "others" to "Other income", "contacts" to "Contacts", "more" to "More", "reports" to "Reports", "sync" to "Sync & data",
    "users" to "Users", "import" to "Import", "report" to "Annual report",
)

private fun NavController.goTab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel, prefs: Prefs) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val snackbar = remember { SnackbarHostState() }
    val dirty by vm.dirtyCount.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val exit by vm.exit.collectAsStateWithLifecycle()
    var askExit by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()

    // Once per launch, quietly look on GitHub for a newer version.
    LaunchedEffect(Unit) { vm.checkForUpdate(manual = false) }

    // First time on this phone: download everything from the Sheet without asking.
    LaunchedEffect(prefs.spreadsheetId, prefs.demo) {
        if (!prefs.demo && prefs.lastSyncMs == 0L) vm.syncNow(allYears = true)
    }
    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            val r = snackbar.showSnackbar(ev.message, actionLabel = if (ev.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) ev.undo?.let(vm::undoDelete)
        }
    }

    val isTab = tabs.any { it.route == route }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(titles[route] ?: "") },
                    navigationIcon = {
                        if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    actions = { SyncButton(running = sync == SyncUi.Running, pending = dirty, onClick = { vm.syncNow() }) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (sync == SyncUi.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    val selected = if (isTab) route == t.route else t.route == "more"
                    NavigationBarItem(selected = selected, onClick = { nav.goTab(t.route) }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(inner)) {
            composable("home") { HomeScreen(vm) }
            composable("donations") { EntryListScreen(vm, EntryType.DONATION) }
            composable("expenses") { EntryListScreen(vm, EntryType.EXPENDITURE) }
            composable("people") { PeopleScreen(vm) }
            composable("others") { EntryListScreen(vm, EntryType.OTHER) }
            composable("contacts") { ContactsScreen(vm) }
            composable("more") { MoreScreen(vm, prefs, nav) }
            composable("reports") { ReportsScreen(vm, onOpenReport = { nav.navigate("report") }) }
            composable("report") { ReportScreen(vm) }
            composable("sync") { SyncScreen(vm, prefs) }
            composable("users") { UsersScreen(vm) }
            composable("import") { ImportScreen(vm) }
        }
    }

    // Back never drops to Home or the menu from a main tab: it asks before leaving. Inside a sub-screen it goes back one step.
    // Declared after the NavHost so it takes priority over the NavHost's own back handling.
    BackHandler {
        when {
            exit != ExitUi.Idle -> Unit          // already leaving
            !isTab -> nav.popBackStack()
            else -> askExit = true
        }
    }

    if (askExit && exit == ExitUi.Idle) {
        val willSync = vm.needsSyncOnLeave(prefs, dirty)
        AlertDialog(
            onDismissRequest = { askExit = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, null) },
            title = { Text("Exit Vinayaka Finance?") },
            text = {
                Text(
                    if (willSync) "Are you sure you want to exit? $dirty change${if (dirty == 1) "" else "s"} not yet in the Google Sheet will be synced first."
                    else "Are you sure you want to exit?",
                )
            },
            confirmButton = { TextButton(onClick = { askExit = false; vm.exitApp() }) { Text(if (willSync) "Sync and exit" else "Yes, exit") } },
            dismissButton = { TextButton(onClick = { askExit = false }) { Text("Stay") } },
        )
    }
    when (val e = exit) {
        ExitUi.Syncing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Syncing before closing") },
            text = { Column { Text("Sending your changes to the Google Sheet…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) } },
            confirmButton = {},
        )
        is ExitUi.Failed -> AlertDialog(
            onDismissRequest = vm::cancelExit,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Couldn't sync") },
            text = { Text(e.message) },
            confirmButton = { TextButton(onClick = vm::exitAnyway) { Text("Exit anyway") } },
            dismissButton = { TextButton(onClick = vm::cancelExit) { Text("Stay") } },
        )
        ExitUi.Close -> LaunchedEffect(Unit) {
            vm.cancelExit()          // so a recreated screen does not close again
            activity?.finish()
        }
        ExitUi.Idle -> Unit
    }

    UpdateHost(vm, activity)

    when (val s = sync) {
        is SyncUi.Done -> SyncDoneDialog(s.report, vm::dismissSync)
        is SyncUi.Failed -> AlertDialog(
            onDismissRequest = vm::dismissSync,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Couldn't sync") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::dismissSync) { Text("OK") } },
        )
        else -> Unit
    }
}

/** Dialogs for the in-app update: found, downloading, allow-installs, done or failed. Works from any screen. */
@Composable
private fun UpdateHost(vm: AppViewModel, activity: Activity?) {
    val state by vm.update.collectAsStateWithLifecycle()
    when (val u = state) {
        UpdateUi.Idle -> Unit
        UpdateUi.Checking -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Checking for updates") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = vm::dismissUpdate) { Text("Cancel") } },
        )
        UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            icon = { Icon(Icons.Rounded.CloudDone, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("You're up to date") },
            text = { Text("Version ${BuildConfig.VERSION_NAME} is the newest one.") },
            confirmButton = { TextButton(onClick = vm::dismissUpdate) { Text("OK") } },
        )
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Version ${u.info.versionName} is ready (you have ${BuildConfig.VERSION_NAME}).")
                    if (u.info.size > 0) Text("Download size: ${"%.1f".format(u.info.size / 1_048_576.0)} MB", style = MaterialTheme.typography.bodySmall)
                    if (u.info.notes.isNotBlank()) Text("What's new:\n${u.info.notes}", style = MaterialTheme.typography.bodyMedium)
                    Text("Your data stays on the phone.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::startUpdate) { Text("Update now") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Later") } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${u.percent}%")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Cancel") } },
        )
        is UpdateUi.Install -> LaunchedEffect(u) {
            // Android installs it over this app (same signing key) after the user confirms; the app then restarts.
            if (vm.updater.canInstall()) {
                activity?.startActivity(vm.updater.installIntent(u.file))
                vm.dismissUpdate()
            } else {
                activity?.startActivity(vm.updater.permissionIntent())
                vm.needInstallPermission(u)
            }
        }
        is UpdateUi.NeedsPermission -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Allow installing updates") },
            text = { Text("Android asks you to allow this app to install updates. Turn on \"Allow from this source\", press Back, then tap Continue.") },
            confirmButton = { TextButton(onClick = vm::retryInstall) { Text("Continue") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Cancel") } },
        )
        is UpdateUi.Failed -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Update failed") },
            text = { Text(u.message) },
            confirmButton = { TextButton(onClick = vm::dismissUpdate) { Text("OK") } },
        )
    }
}

@Composable
private fun SyncButton(running: Boolean, pending: Int, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    IconButton(onClick = onClick, enabled = !running) {
        BadgedBox(badge = { if (pending > 0 && !running) Badge { Text("$pending") } }) {
            Icon(Icons.Rounded.Sync, "Sync", Modifier.rotate(if (running) angle else 0f))
        }
    }
}

@Composable
private fun SyncDoneDialog(r: SyncReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CloudDone, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text(if (r.nothingToSync) "Nothing to sync" else "Sync complete") },
        text = {
            Column {
                if (r.nothingToSync) Text("Everything on this phone already matches the Google Sheet.")
                if (r.pushed > 0) Text("↑ ${r.pushed} sent to the Sheet")
                if (r.pulled > 0) Text("↓ ${r.pulled} received from the Sheet")
                if (r.removed > 0) Text("${r.removed} removed here because they were deleted in the Sheet")
                if (r.restored > 0) Text("${r.restored} put back into an emptied Sheet tab")
                if (r.conflicts.isNotEmpty()) {
                    Text("${r.conflicts.size} changed in two places, newest edit kept:", modifier = Modifier.padding(top = 8.dp))
                    r.conflicts.take(5).forEach { Text("• ${it.summary} (${if (it.keptMine) "yours" else "the Sheet's"})", style = MaterialTheme.typography.bodySmall) }
                }
                if (r.skippedRows > 0) Text("${r.skippedRows} Sheet row(s) were ignored because they have no valid date, type or amount.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (r.skippedWrites > 0) Text("${r.skippedWrites} change(s) were not sent because you have view-only access.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
