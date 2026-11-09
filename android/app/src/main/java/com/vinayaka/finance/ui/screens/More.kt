package com.vinayaka.finance.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.vinayaka.finance.data.Prefs
import com.vinayaka.finance.data.ThemeMode
import com.vinayaka.finance.data.model.Role
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.SyncUi
import com.vinayaka.finance.ui.components.PersonPickerDialog
import com.vinayaka.finance.ui.components.SectionTitle

@Composable
private fun NavTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun MoreScreen(vm: AppViewModel, prefs: Prefs, nav: NavController) {
    val dirty by vm.dirtyCount.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    var pickMyName by remember { mutableStateOf(false) }
    val people by vm.people.collectAsStateWithLifecycle()
    val canEdit = prefs.role.canEdit

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text(
                        (prefs.name.ifBlank { prefs.who }).take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(prefs.name.ifBlank { if (prefs.demo) "Demo mode" else prefs.email }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(prefs.who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        when (prefs.role) {
                            Role.ADMIN -> "Admin · can edit and manage users"
                            Role.EDITOR -> "Editor · can add and edit records"
                            Role.VIEWER -> "Viewer · can look, not change"
                        },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        SectionTitle("Records")
        NavTile(Icons.Rounded.Savings, "Other income", "Name, description and amount") { nav.navigate("others") }
        NavTile(Icons.Rounded.Contacts, "Contacts", "People to call, with called / not called for each year") { nav.navigate("contacts") }
        NavTile(Icons.Rounded.Summarize, "Reports", "Day-wise totals and CSV export for Excel") { nav.navigate("reports") }
        NavTile(Icons.Rounded.Person, "Collecting as: ${prefs.myName.ifBlank { "not set" }}", "Used as the default collector on new records") { pickMyName = true }
        SectionTitle("Data")
        NavTile(Icons.Rounded.Sync, "Sync & data", if (dirty > 0) "$dirty change${if (dirty == 1) "" else "s"} waiting to be sent" else "Compare with the Google Sheet") { nav.navigate("sync") }
        if (canEdit) NavTile(Icons.Rounded.UploadFile, "Import from Excel or CSV", "Bring in old records, split into years automatically") { nav.navigate("import") }
        if (prefs.role.canManageUsers) NavTile(Icons.Rounded.Group, "Users", "Who can use the app: Viewers, Editors and Admins") { nav.navigate("users") }

        SectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(
                    selected = prefs.theme == m, onClick = { vm.setTheme(m) },
                    label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { if (dirty > 0) confirmSignOut = true else vm.signOut() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (prefs.demo) "Leave demo" else "Sign out")
        }
    }

    if (pickMyName) {
        PersonPickerDialog(
            title = "Which committee member are you?", people = people, current = prefs.myName, noneLabel = null,
            onPick = { vm.setMyName(it) }, onDismiss = { pickMyName = false },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out with unsent changes?") },
            text = { Text("$dirty change${if (dirty == 1) " has" else "s have"} not been synced. Signing out removes this phone's copy, so they would be lost. Sync first to keep them.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out anyway") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SyncScreen(vm: AppViewModel, prefs: Prefs) {
    val dirty by vm.dirtyCount.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val last by vm.lastReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var confirmChange by remember { mutableStateOf(false) }
    val running = sync == SyncUi.Running
    val canEdit = prefs.role.canEdit

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (dirty == 0) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload, null,
                        Modifier.size(32.dp), tint = if (dirty == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(
                            if (dirty == 0) "Nothing waiting to be sent" else "$dirty change${if (dirty == 1) "" else "s"} waiting to be sent",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (prefs.lastSyncMs == 0L) "Never synced"
                            else "Last synced " + DateUtils.getRelativeTimeSpanString(prefs.lastSyncMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!canEdit) {
                    Text("You have view-only access, so syncing only downloads.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = { vm.syncNow() }, enabled = !running, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text(if (running) "Syncing…" else "Sync now")
        }
        OutlinedButton(onClick = { vm.syncNow(allYears = true) }, enabled = !running, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("Download all years")
        }
        last?.let { r ->
            Text(
                if (r.nothingToSync) "Last check: everything matched, nothing to sync."
                else "Last sync: ${r.pushed} sent, ${r.pulled} received" + (if (r.removed > 0) ", ${r.removed} removed" else "") + (if (r.conflicts.isNotEmpty()) ", ${r.conflicts.size} conflict(s) resolved" else "") + ".",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("How syncing works")
        Text(
            "Everything you enter is saved on this phone first, so the app works without internet. " +
                "Tap sync to compare with the Google Sheet: new records go up, other people's records come down, " +
                "and if two people changed the same record, the newest edit is kept.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        vm.sheetUrl()?.let { url ->
            SectionTitle("Google Sheet")
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("Open in Google Sheets") }
            if (!prefs.sheetFixed) TextButton(onClick = { confirmChange = true }) { Text("Use a different sheet") }
        }

        SectionTitle("Fix problems")
        OutlinedButton(
            onClick = { confirmReset = true }, enabled = !running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Replace this phone's data with the Sheet") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Replace this phone's data?") },
            text = {
                Text(
                    "This phone's copy is deleted and downloaded again from the Google Sheet." +
                        if (dirty > 0) " $dirty unsent change${if (dirty == 1) "" else "s"} will be lost." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetFromSheet() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (confirmChange) {
        AlertDialog(
            onDismissRequest = { confirmChange = false },
            title = { Text("Use a different sheet?") },
            text = { Text("This phone's copy is removed${if (dirty > 0) " (including $dirty unsent change${if (dirty == 1) "" else "s"})" else ""} and you will connect another sheet.") },
            confirmButton = { TextButton(onClick = { confirmChange = false; vm.changeSheet() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirmChange = false }) { Text("Cancel") } },
        )
    }
}
