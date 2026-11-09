package com.vinayaka.finance.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.ContactView
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.EmptyState
import com.vinayaka.finance.ui.components.PersonChips
import com.vinayaka.finance.ui.components.YearHeader
import com.vinayaka.finance.util.currentYear

private const val UNASSIGNED = "\u0000none"

/**
 * The people to phone, one list for all years. Each year has its own "called" tick, so opening a new
 * year (2027, say) shows every contact as not yet called.
 */
@Composable
fun ContactsScreen(vm: AppViewModel) {
    var year by remember { mutableIntStateOf(currentYear()) }
    val contacts by remember(year) { vm.contacts(year) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by vm.people.collectAsStateWithLifecycle()
    val canEdit by vm.canEdit.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") } // "", "pending", "done"
    var caller by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ContactView?>(null) }
    var adding by remember { mutableStateOf(false) }

    val callers = remember(contacts, people) { (contacts.map { it.caller }.filter { it.isNotBlank() }).distinctBy { it.lowercase() }.sortedBy { it.lowercase() } }
    val shown = remember(contacts, query, status, caller) {
        contacts.filter { status.isEmpty() || (status == "done") == it.called }
            .filter {
                when (caller) {
                    "" -> true
                    UNASSIGNED -> it.caller.isBlank()
                    else -> it.caller.equals(caller, true)
                }
            }
            .filter { query.isBlank() || it.name.contains(query, true) || it.number.contains(query) || it.caller.contains(query, true) }
    }
    val done = contacts.count { it.called }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            YearHeader(vm, year, { year = it })
            if (contacts.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Contacts, "No contacts yet",
                    if (canEdit) "Add the people to call about donations. Each year starts with everyone un-called." else "Contacts will appear here after the next sync.",
                )
            } else {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Called in $year", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$done of ${contacts.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { if (contacts.isEmpty()) 0f else done.toFloat() / contacts.size }, modifier = Modifier.fillMaxWidth())
                        callers.forEach { c ->
                            val mine = contacts.filter { it.caller.equals(c, true) }
                            val d = mine.count { it.called }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c, style = MaterialTheme.typography.bodyMedium)
                                Text("$d / ${mine.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search name or number") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = status.isEmpty(), onClick = { status = "" }, label = { Text("All") })
                    FilterChip(selected = status == "pending", onClick = { status = "pending" }, label = { Text("Not called") })
                    FilterChip(selected = status == "done", onClick = { status = "done" }, label = { Text("Called") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = caller.isEmpty(), onClick = { caller = "" }, label = { Text("Everyone") })
                    callers.forEach { c -> FilterChip(selected = caller.equals(c, true), onClick = { caller = c }, label = { Text(c) }) }
                    FilterChip(selected = caller == UNASSIGNED, onClick = { caller = UNASSIGNED }, label = { Text("No caller") })
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.contactId }) { c ->
                        Card(
                            Modifier.fillMaxWidth().clickable(enabled = canEdit) { editing = c },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = c.called, onCheckedChange = { vm.markCalled(c.contactId, year, it) }, enabled = canEdit)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        c.name, style = MaterialTheme.typography.titleMedium,
                                        textDecoration = if (c.called) TextDecoration.LineThrough else null,
                                        color = if (c.called) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        listOf(c.number, c.caller.takeIf { it.isNotBlank() }?.let { "calls: $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (c.remarks.isNotBlank()) Text(c.remarks, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (c.number.isNotBlank()) {
                                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                        Icon(Icons.Rounded.Call, "Call ${c.name}", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (canEdit) {
            ExtendedFloatingActionButton(
                onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (adding) {
        ContactDialog(null, people, onSave = { n, num, c, r -> vm.addContact(n, num, c, r, year); adding = false }, onDelete = null, onDismiss = { adding = false })
    }
    editing?.let { c ->
        ContactDialog(
            c, people,
            onSave = { n, num, cl, r -> vm.editContact(c.contactId, year, n, num, cl, r); editing = null },
            onDelete = { vm.deleteContact(c.contactId); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ContactDialog(
    existing: ContactView?,
    people: List<String>,
    onSave: (name: String, number: String, caller: String, remarks: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var number by remember { mutableStateOf(existing?.number.orEmpty()) }
    var caller by remember { mutableStateOf(existing?.caller.orEmpty()) }
    var remarks by remember { mutableStateOf(existing?.remarks.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    number, { number = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' } }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                )
                Text("Who calls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PersonChips(people, caller, { caller = it }, noneLabel = "No one yet")
                OutlinedTextField(remarks, { remarks = it }, label = { Text("Remarks") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, number, caller, remarks) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.name}?") },
            text = { Text("This removes the contact for every year.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
