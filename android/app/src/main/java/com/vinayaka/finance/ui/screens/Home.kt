package com.vinayaka.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.BarRow
import com.vinayaka.finance.ui.components.EmptyState
import com.vinayaka.finance.ui.components.EntryRow
import com.vinayaka.finance.ui.components.SectionTitle
import com.vinayaka.finance.ui.components.StatTile
import com.vinayaka.finance.ui.components.YearHeader
import com.vinayaka.finance.ui.theme.LocalMoneyColors
import com.vinayaka.finance.util.currentYear
import com.vinayaka.finance.util.formatRupees

@Composable
fun HomeScreen(vm: AppViewModel) {
    var year by remember { mutableIntStateOf(currentYear()) }
    LaunchedEffect(year) { vm.ensureYear(year) }
    val entries by remember(year) { vm.entries(year) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val money = LocalMoneyColors.current

    Column(Modifier.fillMaxSize()) {
        YearHeader(vm, year, { year = it })
        if (entries.isEmpty()) {
            EmptyState(
                Icons.Rounded.AccountBalanceWallet, "No records for $year yet",
                "Add donations and expenses from the tabs below, or tap the sync button to download what's already in the Sheet.",
            )
            return@Column
        }
        val t = remember(entries) { Stats.totals(entries) }
        val donationRows = remember(entries) { Stats.byCategory(entries, EntryType.DONATION) }
        val otherTotal = remember(entries) { entries.filter { it.type == EntryType.OTHER }.sumOf { it.amount } }
        val spentRows = remember(entries) { Stats.byCategory(entries, EntryType.EXPENDITURE) }

        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item {
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)))
                            .padding(22.dp),
                    ) {
                        Column {
                            Text("Cash in hand · $year", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(formatRupees(t.cash), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Text("Collected minus spent", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Collected", t.collected, money.income, Modifier.weight(1f))
                    StatTile("Spent", t.spent, money.expense, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                StatTile("Still to collect", t.toCollect, MaterialTheme.colorScheme.error, Modifier.fillMaxWidth())
            }
            if (donationRows.isNotEmpty() || otherTotal > 0) {
                item { SectionTitle("Where it came from") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp)) {
                            val rows = donationRows.map { "Donations · ${it.first}" to it.second } + (if (otherTotal > 0) listOf("Other income" to otherTotal) else emptyList())
                            rows.forEach { (label, amt) -> BarRow(label, amt, amt.toFloat() / t.listed.coerceAtLeast(1), money.income) }
                        }
                    }
                }
            }
            if (spentRows.isNotEmpty()) {
                item { SectionTitle("Where the money went") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp)) {
                            spentRows.forEach { (label, amt) -> BarRow(label, amt, amt.toFloat() / t.spent.coerceAtLeast(1), money.expense) }
                        }
                    }
                }
            }
            item { SectionTitle("Latest records") }
            items(entries.take(5), key = { it.id }) { e -> EntryRow(e, Modifier.padding(bottom = 8.dp)) }
        }
    }
}
