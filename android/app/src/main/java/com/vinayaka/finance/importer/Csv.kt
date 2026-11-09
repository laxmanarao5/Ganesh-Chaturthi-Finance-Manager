package com.vinayaka.finance.importer

object Csv {
    /** Parses CSV text (quotes, escaped quotes, CRLF, BOM). Guesses , ; or tab as the delimiter. */
    fun parse(input: String): List<List<String>> {
        val text = input.removePrefix("﻿")
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() { row += cell.toString(); cell.clear() }
        fun endRow() { endCell(); if (row.any { it.isNotBlank() }) rows += row; row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> endCell()
                !inQuotes && c == '\n' -> endRow()
                !inQuotes && c == '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun write(rows: List<List<String>>): String = rows.joinToString("\r\n", postfix = "\r\n") { r ->
        r.joinToString(",") { v ->
            if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }
    }
}
