package com.vinayaka.finance.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.util.prettyFull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The year selector every screen shows at the top. Screens start on the current year. */
@Composable
fun YearHeader(
    vm: AppViewModel,
    year: Int,
    onYear: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val years by vm.yearOptions.collectAsStateWithLifecycle()
    val options = (years + year).distinct().sortedDescending()
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillDropdown("Year", "$year", options.map { "$it" to "$it" }, { onYear(it.toInt()) })
        trailing()
    }
}

private fun isoToMillis(iso: String): Long = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun millisToIso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

/** A date button that opens Android's calendar. [onlyYear] limits the choice to one year (each year is its own Sheet tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(date: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, onlyYear: Int? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(20.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(prettyFull(date))
    }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = isoToMillis(date),
            initialDisplayedMonthMillis = isoToMillis(date),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = onlyYear == null || millisToIso(utcTimeMillis).startsWith("$onlyYear")
                override fun isSelectableYear(year: Int) = onlyYear == null || year == onlyYear
            },
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onChange(millisToIso(it)) }; show = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/**
 * Pick a person from chips. [noneLabel] adds a first chip that clears the choice (for example
 * "Not collected yet"); "+ New" lets you type a name that is not on the list.
 */
@Composable
fun PersonChips(
    people: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String? = null,
) {
    var adding by remember { mutableStateOf(false) }
    val all = (people + selected).filter { it.isNotBlank() }.distinctBy { it.trim().lowercase() }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (noneLabel != null) FilterChip(selected = selected.isBlank(), onClick = { onSelect("") }, label = { Text(noneLabel) })
        all.forEach { p -> FilterChip(selected = selected.equals(p, true), onClick = { onSelect(p) }, label = { Text(p) }) }
        AssistChip(onClick = { adding = true }, label = { Text("+ New") })
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New person") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onSelect(name.trim()); adding = false }, enabled = name.isNotBlank()) { Text("Use") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

/** Choose who collected (or paid) for one record. */
@Composable
fun PersonPickerDialog(
    title: String,
    people: List<String>,
    current: String,
    noneLabel: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { PersonChips(people, chosen, { chosen = it }, noneLabel = noneLabel) },
        confirmButton = { TextButton(onClick = { onPick(chosen); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
