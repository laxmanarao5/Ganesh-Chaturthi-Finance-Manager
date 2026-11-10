package com.vinayaka.finance.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.export.Exporter
import com.vinayaka.finance.report.Money
import com.vinayaka.finance.report.Report
import com.vinayaka.finance.report.ReportLang
import com.vinayaka.finance.report.ReportRow
import com.vinayaka.finance.report.ReportSheet
import com.vinayaka.finance.report.RowStyle
import com.vinayaka.finance.report.XLSX_MIME
import com.vinayaka.finance.report.XlsxWriter
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.ReportShareUi
import com.vinayaka.finance.ui.components.EmptyState
import com.vinayaka.finance.util.formatRupees

/** The generated annual report: every tab of the committee's workbook on screen, plus download, share and "share with Google". */
@Composable
fun ReportScreen(vm: AppViewModel) {
    val report by vm.report.collectAsStateWithLifecycle()
    val share by vm.reportShare.collectAsStateWithLifecycle()
    val canShare by vm.canEdit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val r = report
    if (r == null) {
        EmptyState(Icons.Rounded.Summarize, "No report yet", "Open Reports, choose the year and tap Generate report.")
        return
    }
    var tab by remember(r.year, r.lang) { mutableIntStateOf(r.abstractIndex) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(XlsxWriter.write(r.sheets, r.abstractIndex)) } != null }.getOrDefault(false)
        Toast.makeText(context, if (ok) "Report saved" else "Could not save the report", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${r.year}", style = MaterialTheme.typography.titleMedium)
            ReportLang.entries.forEach { l -> FilterChip(selected = r.lang == l, onClick = { vm.generateReport(r.year, l) }, label = { Text(l.label) }) }
        }
        ScrollableTabRow(selectedTabIndex = tab.coerceIn(0, r.sheets.lastIndex), edgePadding = 8.dp) {
            r.sheets.forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s.name) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { SheetTable(r.sheets[tab.coerceIn(0, r.sheets.lastIndex)]) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save.launch("${r.fileBaseName}.xlsx") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Download, null); Text("  Download Excel")
                }
                OutlinedButton(
                    onClick = { Exporter.shareFile(context, "${r.fileBaseName}.xlsx", XlsxWriter.write(r.sheets, r.abstractIndex), XLSX_MIME) },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.Share, null); Text("  Share file") }
            }
            if (canShare) {
                OutlinedButton(onClick = vm::shareReportWithGoogle, enabled = share !is ReportShareUi.Working, modifier = Modifier.fillMaxWidth()) {
                    Text("Share with Google (Sheet for all app users)")
                }
            } else {
                Text(
                    "Only an Editor or Admin can share the report as a Google Sheet. You can download the Excel file and send it on WhatsApp.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (val s = share) {
        ReportShareUi.Idle -> Unit
        ReportShareUi.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Sharing with Google") },
            text = { Column { Text("Creating the Google Sheet and sharing it with everyone on the Users list…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) } },
            confirmButton = {},
        )
        is ReportShareUi.Done -> AlertDialog(
            onDismissRequest = vm::dismissReportShare,
            title = { Text("Report shared") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A new Google Sheet \"${r.googleTitle}\" was created in your Google Drive.")
                    s.lines.forEach { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open the Sheet") }
            },
            dismissButton = { TextButton(onClick = vm::dismissReportShare) { Text("OK") } },
        )
        is ReportShareUi.Failed -> AlertDialog(
            onDismissRequest = vm::dismissReportShare,
            title = { Text("Could not share the report") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::dismissReportShare) { Text("OK") } },
        )
    }
}

private fun cellText(v: Any?): String = when (v) {
    null -> ""
    is Money -> formatRupees(v.paise).replace("₹", "")
    else -> v.toString()
}

private const val DP_PER_CHAR = 8

/** One tab of the report as a table that scrolls both ways, so a wide tab is readable on a phone. */
@Composable
private fun SheetTable(sheet: ReportSheet) {
    val scheme = MaterialTheme.colorScheme
    val merges = remember(sheet) { sheet.merges.associateBy { it.row to it.col } }
    val hidden = remember(sheet) { sheet.coveredByMerges() - sheet.merges.map { it.row to it.col }.toSet() }
    val columns = maxOf(sheet.widths.size, sheet.rows.maxOfOrNull { it.cells.size } ?: 0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        sheet.rows.forEachIndexed { r, row ->
            val bg = when (row.style) {
                RowStyle.TITLE -> scheme.primaryContainer
                RowStyle.HEADER -> scheme.surfaceContainerHigh
                RowStyle.TOTAL -> scheme.surfaceContainer
                else -> scheme.background
            }
            Row(Modifier.background(bg)) {
                var c = 0
                while (c < columns) {
                    val m = merges[r to c]
                    val span = if (m != null) m.lastCol - c + 1 else 1
                    if ((r to c) !in hidden) {
                        val width = (c until c + span).sumOf { (sheet.widths.getOrElse(it) { 8 }) } * DP_PER_CHAR
                        val v = row.cells.getOrNull(c)
                        val centered = row.style == RowStyle.TITLE || row.style == RowStyle.HEADER
                        Box(Modifier.width(width.dp).padding(horizontal = 6.dp, vertical = 5.dp), contentAlignment = if (v is Money) Alignment.CenterEnd else if (centered) Alignment.Center else Alignment.CenterStart) {
                            Text(
                                cellText(v),
                                style = if (row.style == RowStyle.NOTE) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (row.style == RowStyle.BODY || row.style == RowStyle.NOTE) FontWeight.Normal else FontWeight.Bold,
                                textAlign = if (v is Money) TextAlign.End else if (centered) TextAlign.Center else TextAlign.Start,
                            )
                        }
                    }
                    c += span
                }
            }
        }
    }
}
