package com.vinayaka.finance.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.export.Exporter
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.EmptyState
import com.vinayaka.finance.ui.components.YearHeader
import com.vinayaka.finance.ui.theme.LocalMoneyColors
import com.vinayaka.finance.util.currentYear
import com.vinayaka.finance.util.formatRupees
import com.vinayaka.finance.util.prettyDay

@Composable
fun ReportsScreen(vm: AppViewModel) {
    var year by remember { mutableIntStateOf(currentYear()) }
    LaunchedEffect(year) { vm.ensureYear(year) }
    val all by remember(year) { vm.entries(year) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val money = LocalMoneyColors.current
    var scope by remember { mutableStateOf<EntryType?>(null) }

    val shown = remember(all, scope) { all.filter { scope == null || it.type == scope } }
    val days = remember(shown) { shown.groupBy { it.date }.toSortedMap(compareByDescending { it }) }

    Column(Modifier.fillMaxSize()) {
        YearHeader(vm, year, { year = it })
        if (all.isEmpty()) {
            EmptyState(Icons.Rounded.Summarize, "Nothing to report for $year", "Add records or sync first, then come back to export them.")
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = scope == null, onClick = { scope = null }, label = { Text("All") })
            EntryType.entries.forEach { t -> FilterChip(selected = scope == t, onClick = { scope = t }, label = { Text(t.label) }) }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { Exporter.share(context, "vinayaka-$year-records.csv", Exporter.entriesCsv(shown)) },
                modifier = Modifier.weight(1f),
            ) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Text("  Records") }
            OutlinedButton(
                onClick = { Exporter.share(context, "vinayaka-$year-daywise.csv", Exporter.dayWiseCsv(shown)) },
                modifier = Modifier.weight(1f),
            ) { Text("Day-wise") }
            OutlinedButton(
                onClick = { Exporter.share(context, "vinayaka-$year-people.csv", Exporter.peopleCsv(all)) },
                modifier = Modifier.weight(1f),
            ) { Text("By person") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(days.entries.toList(), key = { it.key }) { (date, rows) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(prettyDay(date), style = MaterialTheme.typography.titleMedium)
                            Text("${rows.size} record${if (rows.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val income = rows.filter { it.type.isIncome }.sumOf { it.amount }
                        val spent = rows.filter { !it.type.isIncome }.sumOf { it.amount }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("In ${formatRupees(income)}", color = money.income, fontWeight = FontWeight.SemiBold)
                            Text("Out ${formatRupees(spent)}", color = money.expense, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
