package com.vinayaka.finance.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class SheetData(val name: String, val rows: List<List<String>>)

/**
 * Minimal .xlsx reader (no Apache POI, which is too heavy for Android): unzips the workbook and
 * reads sheet names, shared strings and cell values. Numbers come back as plain text.
 */
class XlsxReader(private val newParser: () -> XmlPullParser = { Xml.newPullParser() }) {

    fun read(input: InputStream): List<SheetData> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.startsWith("xl/") && e.name.endsWith(".xml") || e.name.endsWith(".rels")) files[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val workbook = files["xl/workbook.xml"] ?: error("This file is not an Excel .xlsx workbook.")
        val shared = files["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val rels = files["xl/_rels/workbook.xml.rels"]?.let(::relationships).orEmpty()
        return sheetRefs(workbook).mapIndexedNotNull { i, (name, rid) ->
            val target = rels[rid] ?: "worksheets/sheet${i + 1}.xml"
            val t = target.trimStart('/')
            val bytes = files[if (t.startsWith("xl/")) t else "xl/$t"] ?: return@mapIndexedNotNull null
            SheetData(name, cells(bytes, shared))
        }
    }

    private fun parser(bytes: ByteArray): XmlPullParser = newParser().also { it.setInput(ByteArrayInputStream(bytes), null) }

    private fun XmlPullParser.local() = name.substringAfter(':')

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inT = false
        var phonetic = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.local()) {
                    "si" -> sb.clear()
                    "t" -> inT = true
                    "rPh" -> phonetic++
                }
                XmlPullParser.TEXT -> if (inT && phonetic == 0) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.local()) {
                    "t" -> inT = false
                    "rPh" -> phonetic--
                    "si" -> out += sb.toString()
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
        return null
    }

    private fun sheetRefs(bytes: ByteArray): List<Pair<String, String>> {
        val p = parser(bytes)
        val out = mutableListOf<Pair<String, String>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.local() == "sheet") out += (attr(p, "name") ?: "Sheet") to (attr(p, "id") ?: "")
            ev = p.next()
        }
        return out
    }

    private fun relationships(bytes: ByteArray): Map<String, String> {
        val p = parser(bytes)
        val out = mutableMapOf<String, String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.local() == "Relationship") {
                val id = attr(p, "Id"); val target = attr(p, "Target")
                if (id != null && target != null) out[id] = target
            }
            ev = p.next()
        }
        return out
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }

    private fun cells(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val p = parser(bytes)
        val rows = mutableListOf<MutableList<String>>()
        var row: MutableList<String>? = null
        var col = 0
        var type: String? = null
        var value = StringBuilder()
        var inV = false
        var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.local()) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        type = attr(p, "t"); value = StringBuilder()
                        attr(p, "r")?.let { col = columnIndex(it) }
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.local()) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val r = row
                        if (r != null) {
                            while (r.size < col) r += ""
                            r += render(type, value.toString(), shared)
                        }
                    }
                    "row" -> { row?.let { rows += it }; row = null }
                }
            }
            ev = p.next()
        }
        return rows
    }

    private fun render(type: String?, raw: String, shared: List<String>): String = when (type) {
        "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
        "str", "inlineStr", "e" -> raw
        else -> raw.trim().toDoubleOrNull()?.let { if (it == Math.floor(it) && kotlin.math.abs(it) < 1e15) it.toLong().toString() else raw.trim() } ?: raw
    }
}
