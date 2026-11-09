package com.vinayaka.finance.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Amounts are kept in paise. 123456789 -> "₹12,34,567.89" (Indian digit grouping, decimals only when needed). */
fun formatRupees(paise: Long): String {
    val negative = paise < 0
    val abs = kotlin.math.abs(paise)
    val rupees = (abs / 100).toString()
    val frac = (abs % 100).toInt()
    val grouped = if (rupees.length <= 3) rupees else {
        rupees.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + rupees.takeLast(3)
    }
    return (if (negative) "-" else "") + "₹" + grouped + if (frac == 0) "" else "." + frac.toString().padStart(2, '0')
}

/** "1,500.50" -> 150050. Null when the text is not a non-negative amount. */
fun parseAmountToPaise(text: String): Long? {
    val cleaned = text.replace(",", "").replace("₹", "").trim()
    if (cleaned.isEmpty()) return null
    val d = cleaned.toBigDecimalOrNull() ?: return null
    if (d.signum() < 0) return null
    return d.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
}

/** Paise as the number written in the Sheet: whole rupees stay whole, otherwise two decimals. */
fun paiseToSheetNumber(paise: Long): Number = if (paise % 100 == 0L) paise / 100 else BigDecimal(paise).movePointLeft(2).toDouble()

fun sheetNumberToPaise(value: Double): Long = Math.round(value * 100)

/** Text for an amount field: 150050 -> "1500.5", 150000 -> "1500". */
fun paiseToInput(paise: Long): String =
    if (paise % 100 == 0L) (paise / 100).toString() else BigDecimal(paise).movePointLeft(2).stripTrailingZeros().toPlainString()

fun todayIso(): String = LocalDate.now().toString()

fun currentYear(): Int = LocalDate.now().year

private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val fullFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun prettyDay(iso: String): String =
    runCatching { LocalDate.parse(iso).format(dayFmt) }.getOrDefault(iso)

fun prettyFull(iso: String): String =
    runCatching { LocalDate.parse(iso).format(fullFmt) }.getOrDefault(iso)

val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun monthOf(iso: String): Int = iso.substring(5, 7).toIntOrNull() ?: 0

/** Accepts yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy and "yyyy-MM-dd HH:mm:ss"; returns ISO or null. */
fun parseDateFlexible(text: String): String? {
    val t = text.trim().substringBefore('T').substringBefore(' ').ifEmpty { return null }
    runCatching { return LocalDate.parse(t).toString() }
    val m = Regex("""^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$""").find(t)
    if (m != null) {
        val (d, mo, y) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString() }.getOrNull()
    }
    return null
}

/** Google Sheets / Excel serial date (days since 1899-12-30) to ISO. */
fun serialToIso(serial: Double): String = LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).toString()

fun isoToSerial(iso: String): Long = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.parse(iso))
