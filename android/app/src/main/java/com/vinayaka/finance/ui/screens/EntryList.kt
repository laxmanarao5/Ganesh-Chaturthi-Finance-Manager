package com.vinayaka.finance.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.DatePickerField
import com.vinayaka.finance.ui.components.EmptyState
import com.vinayaka.finance.ui.components.EntryRow
import com.vinayaka.finance.ui.components.PersonChips
import com.vinayaka.finance.ui.components.PersonPickerDialog
import com.vinayaka.finance.ui.components.PillDropdown
import com.vinayaka.finance.ui.components.YearHeader
import com.vinayaka.finance.ui.components.typeIcon
import com.vinayaka.finance.ui.theme.LocalMoneyColors
import com.vinayaka.finance.util.currentYear
import com.vinayaka.finance.util.formatRupees
import com.vinayaka.finance.util.monthNames
import com.vinayaka.finance.util.monthOf
import com.vinayaka.finance.util.paiseToInput
import com.vinayaka.finance.util.parseAmountToPaise
import com.vinayaka.finance.util.parseSignedAmountToPaise
import com.vinayaka.finance.util.prettyDay
import java.time.LocalDate

private const val NOT_COLLECTED = "\u0000none"

@Composable
fun EntryListScreen(vm: AppViewModel, type: EntryType) {
    // Every screen opens on the current year (so in 2027 it opens on 2027) and keeps its own choice.
    var year by remember { mutableIntStateOf(currentYear()) }
    LaunchedEffect(year) { vm.ensureYear(year) }
    val all by remember(year) { vm.entries(year) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by vm.people.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val canEdit by vm.canEdit.collectAsStateWithLifecycle()
    val money = LocalMoneyColors.current

    var query by remember { mutableStateOf("") }
    var month by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") } // "" = everyone, NOT_COLLECTED, or a person's name
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<Entry?>(null) }

    val ofType = remember(all, type) { all.filter { it.type == type } }
    val categories = remember(ofType) { (type.categories + ofType.map { it.category }.filter { it.isNotBlank() }).distinct() }
    val chipPeople = remember(people, ofType, prefs?.myName) {
        val everyone = Stats.people(people, emptyList(), ofType.map { it.person })
        val me = prefs?.myName.orEmpty()
        val mine = everyone.filter { it.equals(me, true) }
        mine + everyone.filter { !it.equals(me, true) }
    }
    val filtered = remember(ofType, query, month, category, who) {
        ofType.filter { month == 0 || monthOf(it.date) == month }
            .filter { category.isEmpty() || it.category == category }
            .filter {
                when (who) {
                    "" -> true
                    NOT_COLLECTED -> it.isPending
                    else -> it.person.equals(who, true)
                }
            }
            .filter {
                query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) ||
                    it.category.contains(query, true) || it.person.contains(query, true)
            }
    }
    val total = filtered.sumOf { it.amount }
    val collectedPart = filtered.filter { it.person.isNotBlank() }.sumOf { it.amount }
    val tint = if (type.isIncome) money.income else money.expense
    val filtersOn = month != 0 || category.isNotEmpty() || who.isNotEmpty() || query.isNotBlank()
    val verb = if (type.isIncome) "Collected by " else "Paid by "

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            YearHeader(vm, year, { year = it }) {
                PillDropdown(
                    "Month", if (month == 0) null else monthNames[month - 1],
                    listOf("0" to "All months") + monthNames.mapIndexed { i, m -> "${i + 1}" to m },
                    { month = it.toInt() },
                )
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search ${type.label.lowercase()}") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            // Who collected / paid
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = who.isEmpty(), onClick = { who = "" }, label = { Text("Everyone") })
                if (type.isIncome) FilterChip(selected = who == NOT_COLLECTED, onClick = { who = NOT_COLLECTED }, label = { Text("Not collected") })
                chipPeople.forEach { p -> FilterChip(selected = who.equals(p, true), onClick = { who = p }, label = { Text(p) }) }
            }
            if (categories.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = category.isEmpty(), onClick = { category = "" }, label = { Text("All categories") })
                    categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                }
            }

            // The total for whatever is filtered: e.g. choose a name to see how much that person collected.
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(
                        when (who) {
                            "" -> if (type.isIncome) "Total listed" else "Total spent"
                            NOT_COLLECTED -> "Not collected yet"
                            else -> verb + who
                        },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(formatRupees(total), style = MaterialTheme.typography.headlineMedium, color = tint, fontWeight = FontWeight.Bold)
                    Text(
                        "${filtered.size} ${if (filtered.size == 1) "record" else "records"}" +
                            (if (type.isIncome && who.isEmpty()) " · collected ${formatRupees(collectedPart)} · to collect ${formatRupees(total - collectedPart)}" else "") +
                            (if (filtersOn && who.isEmpty()) " (filtered)" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyState(
                    typeIcon(type),
                    if (filtersOn) "Nothing matches" else "No ${type.label.lowercase()} for $year",
                    if (filtersOn) "Try clearing the search or filters." else if (canEdit) "Tap Add to record the first one." else "Ask an editor to add records, then sync.",
                )
            } else {
                val groups = filtered.groupBy { it.date }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEach { (date, rows) ->
                        item(key = "h$date") {
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prettyDay(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(formatRupees(rows.sumOf { it.amount }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(rows, key = { it.id }) { e ->
                            // No swipe-to-delete: a stray swipe must never remove a record. Deleting is in the edit sheet, behind a confirmation.
                            if (canEdit) EntryRow(e, onClick = { editing = e }, onAssign = { assigning = e })
                            else EntryRow(e)
                        }
                    }
                }
            }
        }
        if (canEdit) {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (adding) {
        EntryEditorSheet(
            type = type, year = year, existing = null, people = chipPeople,
            defaultCategory = category.ifEmpty { type.categories.firstOrNull().orEmpty() },
            defaultPerson = when {
                who.isNotEmpty() && who != NOT_COLLECTED -> who
                who == NOT_COLLECTED -> ""
                else -> prefs?.myName.orEmpty()
            },
            onSave = { d, c, n, desc, amt, p -> vm.addEntry(type, d, c, n, desc, amt, p); adding = false },
            onDismiss = { adding = false },
        )
    }
    editing?.let { e ->
        EntryEditorSheet(
            type = type, year = e.year, existing = e, people = chipPeople, defaultCategory = e.category, defaultPerson = e.person,
            onSave = { d, c, n, desc, amt, p -> vm.updateEntry(e, d, c, n, desc, amt, p); editing = null },
            onDelete = { vm.deleteEntry(e); editing = null },
            onDismiss = { editing = null },
        )
    }
    assigning?.let { e ->
        PersonPickerDialog(
            title = (if (type.isIncome) "Who collected " else "Who paid ") + e.name.take(24) + "?",
            people = chipPeople, current = e.person,
            noneLabel = if (type.isIncome && e.amount > 0) "Not collected yet" else "Not set",
            onPick = { vm.setPerson(e, it) },
            onDismiss = { assigning = null },
        )
    }
}

/** Keep digits and at most one point with two decimals; a leading minus survives only when [allowNegative] (Expenditure returns). */
private fun cleanAmount(input: String, allowNegative: Boolean = false): String {
    val sb = StringBuilder()
    var seenDot = false
    var decimals = 0
    if (allowNegative && (input.startsWith("-") || input.startsWith("−"))) sb.append('-')
    for (ch in input) {
        when {
            ch.isDigit() -> { if (!seenDot || decimals < 2) { sb.append(ch); if (seenDot) decimals++ } }
            ch == '.' && !seenDot -> { seenDot = true; sb.append(ch) }
        }
    }
    return sb.toString().take(12)
}

/** Add or edit one record. The date can be changed freely within the record's year (each year is its own Sheet tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorSheet(
    type: EntryType,
    year: Int,
    existing: Entry?,
    people: List<String>,
    defaultCategory: String,
    defaultPerson: String,
    onSave: (date: String, category: String, name: String, description: String, amount: Long, person: String) -> Unit,
    onDismiss: () -> Unit,
    /** Shown only when editing; always asks "Are you sure?" first. */
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialDate = existing?.date ?: LocalDate.now().let { runCatching { it.withYear(year) }.getOrDefault(it) }.toString()
    var date by remember { mutableStateOf(initialDate) }
    var category by remember { mutableStateOf(existing?.category ?: defaultCategory) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var amountText by remember { mutableStateOf(existing?.amount?.let { paiseToInput(it) }.orEmpty()) }
    var person by remember { mutableStateOf(existing?.person ?: defaultPerson) }
    val canReturn = type == EntryType.EXPENDITURE   // only spending can be negative: something taken back to the shop
    val amount = if (canReturn) parseSignedAmountToPaise(amountText) else parseAmountToPaise(amountText)
    val inKind = type == EntryType.DONATION && description.isNotBlank()
    val valid = name.isNotBlank() && amount != null &&
        (if (canReturn) amount != 0L else amount > 0 || (amount == 0L && inKind))
    val options = remember(existing) { (type.categories + listOfNotNull(existing?.category?.takeIf { it.isNotBlank() })).distinct() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text((if (existing == null) "Add " else "Edit ") + type.label.lowercase().removeSuffix("s"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text(type.nameLabel) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it }, label = { Text(type.detailLabel) },
                maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = amountText, onValueChange = { amountText = cleanAmount(it, canReturn) },
                label = { Text("Amount") }, prefix = { Text("₹ ") }, singleLine = true,
                supportingText = when (type) {
                    EntryType.DONATION -> ({ Text("Leave 0 for a gift in kind (add what it is above)") })
                    EntryType.EXPENDITURE -> ({ Text("For something returned, use a negative amount (-1610); it shows as +1610 in green") })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (canReturn) {
                // Many phone keyboards have no minus key on the number pad, so this chip adds or removes it.
                val isReturn = amountText.startsWith("-")
                FilterChip(
                    selected = isReturn,
                    onClick = { amountText = if (isReturn) amountText.removePrefix("-") else "-$amountText" },
                    label = { Text("Return / refund (money back)") },
                )
            }
            if (options.isNotEmpty()) {
                Text("Category", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                }
            }
            Text(type.personLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PersonChips(people, person, { person = it }, noneLabel = if (type.isIncome && (amount ?: 1L) > 0L) "Not collected yet" else "Not set")
            Text("Date", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DatePickerField(date, { date = it }, onlyYear = year)
            Button(
                onClick = { onSave(date, category, name, description, amount ?: 0L, person) }, enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (existing == null) "Save" else "Save changes") }
            if (existing != null && onDelete != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete this record", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete && existing != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${existing.name.take(40)}\"?") },
            text = { Text("It is removed here and, after the next sync, from the Google Sheet. You can undo right after deleting.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
