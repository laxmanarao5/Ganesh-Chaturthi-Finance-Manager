package com.vinayaka.finance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.domain.PersonTotals
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.EmptyState
import com.vinayaka.finance.ui.components.YearHeader
import com.vinayaka.finance.ui.theme.LocalMoneyColors
import com.vinayaka.finance.util.currentYear
import com.vinayaka.finance.util.formatRupees

/** How much each person collected and how much each spent, for one year. */
@Composable
fun PeopleScreen(vm: AppViewModel) {
    var year by remember { mutableIntStateOf(currentYear()) }
    LaunchedEffect(year) { vm.ensureYear(year) }
    val entries by remember(year) { vm.entries(year) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val money = LocalMoneyColors.current
    var open by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        YearHeader(vm, year, { year = it })
        if (entries.isEmpty()) {
            EmptyState(Icons.Rounded.Group, "No records for $year", "Once donations and expenses are recorded, you'll see here how much each person collected and spent.")
            return@Column
        }
        val totals = remember(entries) { Stats.totals(entries) }
        val people = remember(entries) { Stats.byPerson(entries) }

        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Everyone together", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TotalLine("Collected", totals.collected, money.income)
                        TotalLine("Spent", totals.spent, money.expense)
                        HorizontalDivider()
                        TotalLine("Cash that should be in hand", totals.cash, MaterialTheme.colorScheme.onSurface, bold = true)
                        if (totals.toCollect > 0) {
                            TotalLine("Still to collect (no collector yet)", totals.toCollect, MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            items(people, key = { it.person }) { p ->
                PersonCard(p, open == p.person, onToggle = { open = if (open == p.person) null else p.person }) {
                    val rows = Stats.personBreakdown(entries, p.person)
                    Column(Modifier.padding(top = 8.dp)) {
                        rows.forEach { (type, cat, amt) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    (if (type.isIncome) "Collected · " else "Spent · ") + type.label + if (cat.isNotBlank() && cat != "Other") " · $cat" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(formatRupees(amt), style = MaterialTheme.typography.bodyMedium, color = if (type.isIncome) money.income else money.expense)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Net = collected − spent. Positive means that person should be holding that much of the committee's cash; negative means they paid out of pocket and are owed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TotalLine(label: String, amount: Long, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(formatRupees(amount), style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun PersonCard(p: PersonTotals, expanded: Boolean, onToggle: () -> Unit, details: @Composable () -> Unit) {
    val money = LocalMoneyColors.current
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.person, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Net " + formatRupees(p.net), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (p.net >= 0) money.income else money.expense,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Collected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatRupees(p.collected), style = MaterialTheme.typography.titleMedium, color = money.income)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatRupees(p.spent), style = MaterialTheme.typography.titleMedium, color = money.expense)
                }
            }
            if (expanded) details()
        }
    }
}
