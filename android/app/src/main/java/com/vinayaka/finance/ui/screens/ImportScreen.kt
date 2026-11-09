package com.vinayaka.finance.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vinayaka.finance.importer.Csv
import com.vinayaka.finance.importer.ImportBundle
import com.vinayaka.finance.importer.ImportMapper
import com.vinayaka.finance.importer.ImportPlan
import com.vinayaka.finance.importer.SheetData
import com.vinayaka.finance.importer.XlsxReader
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.DatePickerField
import com.vinayaka.finance.util.formatRupees
import com.vinayaka.finance.util.todayIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<ImportBundle?>(null) }
    var undatedDate by remember { mutableStateOf(todayIso()) }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null; result = null; bundle = null; plan = null
        scope.launch {
            try {
                val (name, sheets) = withContext(Dispatchers.IO) { readFile(context, uri) }
                fileName = name
                val b = ImportMapper.parse(sheets)
                if (b.isEmpty) error = "No usable rows found. Each sheet needs a header row with at least an Amount column."
                else { bundle = b; undatedDate = b.earliestDate ?: todayIso() }
            } catch (e: Exception) {
                error = e.message ?: "Could not read that file."
            } finally {
                busy = false
            }
        }
    }

    // Work out what would be added (and what is already here) whenever the file or the chosen date changes.
    LaunchedEffect(bundle, undatedDate) {
        plan = bundle?.let { vm.planImport(it, undatedDate, undatedDate.take(4).toInt()) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.medium) {
            Text(
                "Pick the committee's Excel workbook (.xlsx) or a CSV. Sheets named Youth Donations, Villagers Donations, Children Donations, " +
                    "Outsiders Donations, Annadhanam donations, Pooja Expenditure, Annadhanam Expenditure, Other inputs and contacts are recognised. " +
                    "Only each sheet's main table is read, so totals and side tables are skipped. Importing the same file twice adds nothing.",
                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else { Icon(Icons.Rounded.UploadFile, null, Modifier.size(20.dp)); Text("  Choose file") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium) }

        bundle?.let { b ->
            Text(fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Found (compare these totals with your Excel)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    b.sections.forEach { s ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${s.label} · ${s.rows}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (s.total != 0L) Text(formatRupees(s.total), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (b.ignoredSheets.isNotEmpty()) {
                        Text("Ignored: " + b.ignoredSheets.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (b.skippedRows > 0) {
                        Text("${b.skippedRows} row(s) skipped (unreadable amount or date)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (b.undated > 0 || b.contacts.isNotEmpty()) {
                val why = buildString {
                    if (b.undated > 0) append("${b.undated} row(s) have no date")
                    if (b.contacts.isNotEmpty()) {
                        if (isNotEmpty()) append(", and ")
                        append("the contacts' called status belongs to this date's year")
                    }
                    append(". Choose the date to use:")
                }
                Text(why, style = MaterialTheme.typography.bodyMedium)
                DatePickerField(undatedDate, { undatedDate = it })
            }
            plan?.let { p ->
                Text(
                    "Will add ${p.entries.size} record(s) and ${p.contacts.size} contact(s)" +
                        if (p.duplicateEntries + p.duplicateContacts > 0) " (${p.duplicateEntries + p.duplicateContacts} already here)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = {
                        vm.applyImport(p) {
                            result = "Imported ${p.entries.size} record(s) and ${p.contacts.size} contact(s). Tap sync to send them to the Sheet."
                            bundle = null; plan = null
                        }
                    },
                    enabled = p.entries.isNotEmpty() || p.contacts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Import") }
            }
        }
    }
}

private fun readFile(context: android.content.Context, uri: Uri): Pair<String, List<SheetData>> {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "file"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open the file.")
    val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    val sheets = if (isZip) XlsxReader().read(bytes.inputStream())
    else if (name.endsWith(".xls", true)) error("Old .xls files aren't supported. Open it in Excel and save as .xlsx or .csv.")
    else listOf(SheetData(name.substringBeforeLast('.'), Csv.parse(bytes.toString(Charsets.UTF_8))))
    return name to sheets
}
