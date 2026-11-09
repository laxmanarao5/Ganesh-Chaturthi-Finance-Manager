package com.vinayaka.finance.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Admin-only. Lists everyone allowed to use the app, grouped by role, and lets an admin add, re-role or remove them.
 * The list lives in the Sheet's Users tab (Name | Email | Role), so it can also be edited there by hand.
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
            var email by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            var role by remember { mutableStateOf(Role.VIEWER) }
            val changed = list.toList() != s.users
            val emailOk = email.trim().contains("@") && email.trim().contains(".")

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                // Everyone, grouped by role
                listOf(Role.ADMIN, Role.EDITOR, Role.VIEWER).forEach { r ->
                    val group = list.filter { it.role == r }
                    Text("${r.label}s (${group.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    if (group.isEmpty()) Text("No one yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    group.forEach { u ->
                        val locked = Access.isDefaultAdmin(u.email)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(u.name.ifBlank { u.email.substringBefore('@') }, style = MaterialTheme.typography.titleMedium)
                                    Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (locked) {
                                        Text("Default admin · built into the app", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                            Role.entries.forEach { opt ->
                                                FilterChip(
                                                    selected = u.role == opt,
                                                    onClick = { val i = list.indexOfFirst { it.email == u.email }; if (i >= 0) list[i] = u.copy(role = opt) },
                                                    label = { Text(opt.label) },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (!locked) IconButton(onClick = { list.removeAll { it.email == u.email } }) { Icon(Icons.Rounded.Close, "Remove ${u.email}") }
                            }
                        }
                    }
                }

                Text("Add a user", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    email, { email = it }, label = { Text("Google email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Role.entries.forEach { opt -> FilterChip(selected = role == opt, onClick = { role = opt }, label = { Text(opt.label) }) }
                }
                OutlinedButton(
                    onClick = {
                        val e = email.trim().lowercase()
                        if (list.none { it.email == e }) list += AppUser(name.trim(), e, role)
                        email = ""; name = ""
                    },
                    enabled = emailOk, modifier = Modifier.fillMaxWidth(),
                ) { Text("Add to list") }

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
            }
        }
    }
}
