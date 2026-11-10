package com.vinayaka.finance.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.importer.Csv
import java.io.File

object Exporter {
    /** 150050 -> "1500.50" (rupees, always two decimals, so Excel sorts and adds it correctly). */
    private fun rupees(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val a = kotlin.math.abs(paise)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    /** Same columns the importer reads back, so an export can be re-imported. */
    fun entriesCsv(entries: List<Entry>): String = Csv.write(
        listOf(listOf("Date", "Type", "Category", "Name", "Description", "Amount", "Collected / Paid by")) +
            entries.sortedWith(compareBy({ it.date }, { it.type }, { it.createdAt }))
                .map { listOf(it.date, it.type.sheetName, it.category, it.name, it.description, rupees(it.amount), it.person) },
    )

    /** One line per day and type. */
    fun dayWiseCsv(entries: List<Entry>): String {
        val rows = entries.groupBy { it.date to it.type }.toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (k, v) -> listOf(k.first, k.second.sheetName, v.size.toString(), rupees(v.sumOf { it.amount })) }
        return Csv.write(listOf(listOf("Date", "Type", "Entries", "Total")) + rows)
    }

    /** How much each person collected and spent, plus the money still to collect. */
    fun peopleCsv(entries: List<Entry>): String {
        val t = Stats.totals(entries)
        val rows = Stats.byPerson(entries).map { listOf(it.person, rupees(it.collected), rupees(it.spent), rupees(it.net)) }
        return Csv.write(
            listOf(listOf("Person", "Collected", "Spent", "Collected - Spent")) + rows +
                listOf(listOf("Everyone", rupees(t.collected), rupees(t.spent), rupees(t.cash)), listOf("Still to collect", rupees(t.toCollect), "", "")),
        )
    }

    /** Writes a binary file (the Excel report) to the cache and opens Android's share sheet: WhatsApp, Drive, email... */
    fun shareFile(context: Context, fileName: String, bytes: ByteArray, mime: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes [text] to the cache and opens Android's share sheet. */
    fun share(context: Context, fileName: String, text: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeText("﻿" + text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
