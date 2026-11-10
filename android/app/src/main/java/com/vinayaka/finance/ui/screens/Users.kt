package com.vinayaka.finance.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.Access
import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Role
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.UsersUi
import com.vinayaka.finance.ui.components.EmptyState

/**
 * Admin-only. Lists everyone allowed to use the app, and lets an admin add, re-role or remove them in a pop-up (like adding a donation).
 * The list lives in the Sheet's Users tab (Name | Email | Role), so it can also be edited there by hand.
 * Changes stay on this screen until "Save and update sharing".
 */
@Composable
fun UsersScreen(vm: AppViewModel) {
    val canManage by vm.canManageUsers.collectAsStateWithLifecycle()
    val state by vm.users.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (!canManage) {
        EmptyState(Icons.Rounded.Lock, "Admins only", "Only an Admin can see and change who may use the app.")
        return
    }
    LaunchedEffect(Unit) { vm.loadUsers() }

    val sharing by vm.sharing.collectAsStateWithLifecycle()
    sharing?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissSharing,
            title = { Text(if (result.error != null) "Sheet sharing not updated" else "Sheet sharing") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.error != null) Text(result.error)
                    result.report?.lines()?.forEach { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissSharing) { Text("OK") } },
        )
    }

    when (val s = state) {
        UsersUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is UsersUi.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.message)
            TextButton(onClick = vm::loadUsers) { Text("Try again") }
        }
        is UsersUi.Loaded -> {
            val list = remember(s) { mutableStateListOf<AppUser>().apply { addAll(s.users) } }
            var adding by remember { mutableStateOf(false) }
            var editing by remember { mutableStateOf<AppUser?>(null) }
            val changed = list.toList() != s.users
            val shown = list.sortedWith(compareByDescending<AppUser> { it.role.ordinal }.thenBy { Access.displayName(it).lowercase() })

            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Only emails on this list can use the app.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Viewer: can look at everything, cannot change anything.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Editor: can add and edit records, but cannot see this page.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Admin: everything an Editor can, plus manage users.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "Saving also updates who the Google Sheet is shared with: Editors and Admins can edit it, Viewers can only view it, " +
                                    "and people you remove lose access. Google's sharing is the real lock on the data.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    if (!s.hasTab) {
                        Text(
                            "The Sheet has no Users tab yet. Until you save one, only the default admin can sign in.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Text("Users (${list.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    shown.forEach { u ->
                        val locked = Access.isDefaultAdmin(u.email)
                        Card(
                            Modifier.fillMaxWidth().clickable(enabled = !locked) { editing = u },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Access.displayName(u), style = MaterialTheme.typography.titleMedium)
                                    Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (locked) Text("Default admin · built into the app", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                Surface(shape = MaterialTheme.shapes.small, color = if (u.role == Role.ADMIN) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(u.role.label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    if (!onlyBuiltInAdmin(list)) Text("Tap a user to change the role or remove them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.saveUsers(list.toList()) },
                        enabled = !working && (changed || !s.hasTab), modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (working) "Saving…" else if (changed) "Save and update sharing" else "Save to the Sheet") }
                    OutlinedButton(
                        onClick = vm::fixSharing,
                        enabled = !working && !changed && s.hasTab, modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Update the Sheet's sharing for everyone") }
                    if (changed) Text("Save your changes first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vm.sheetUrl()?.let { url ->
                        TextButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Text("  Open the Sheet") }
                    }
                    Spacer(Modifier.height(72.dp))
                }
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Add user") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }

            if (adding) {
                UserSheet(
                    existing = null, takenEmails = list.map { it.email.lowercase() },
                    onSave = { u -> list += u; adding = false },
                    onRemove = null, onDismiss = { adding = false },
                )
            }
            editing?.let { current ->
                UserSheet(
                    existing = current, takenEmails = emptyList(),
                    onSave = { u -> val i = list.indexOfFirst { it.email == current.email }; if (i >= 0) list[i] = u; editing = null },
                    onRemove = { list.removeAll { it.email == current.email }; editing = null },
                    onDismiss = { editing = null },
                )
            }
        }
    }
}

/** True when the list has nobody who can be edited (only the built-in admin), so the tap hint would be misleading. */
private fun onlyBuiltInAdmin(list: List<AppUser>) = list.all { Access.isDefaultAdmin(it.email) }

/** Add or edit one user in a pop-up, like adding a donation. The email of an existing user cannot change (remove and add instead). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserSheet(
    existing: AppUser?,
    takenEmails: List<String>,
    onSave: (AppUser) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var email by remember { mutableStateOf(existing?.email.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role ?: Role.VIEWER) }
    var confirmRemove by remember { mutableStateOf(false) }
    val cleanEmail = email.trim().lowercase()
    val emailOk = cleanEmail.contains("@") && cleanEmail.substringAfter("@").contains(".")
    val taken = existing == null && cleanEmail in takenEmails

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (existing == null) "Add user" else "Edit user", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Google email") }, singleLine = true, enabled = existing == null,
                isError = taken, supportingText = if (taken) ({ Text("This email is already on the list") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Role", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.entries.forEach { opt -> FilterChip(selected = role == opt, onClick = { role = opt }, label = { Text(opt.label) }) }
            }
            Text(
                when (role) {
                    Role.VIEWER -> "Can look at everything, cannot change anything. Gets view access to the Google Sheet."
                    Role.EDITOR -> "Can add and edit records. Gets edit access to the Google Sheet."
                    Role.ADMIN -> "Everything an Editor can, plus manage users. Gets edit access to the Google Sheet."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onSave(AppUser(name.trim(), if (existing != null) existing.email else cleanEmail, role)) },
                enabled = (existing != null || emailOk) && !taken,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (existing == null) "Add to list" else "Done") }
            if (existing != null && onRemove != null) {
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from the list", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmRemove && existing != null && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${Access.displayName(existing)}?") },
            text = { Text("They lose access to the app once you save, and to the Google Sheet when the sharing is updated.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
