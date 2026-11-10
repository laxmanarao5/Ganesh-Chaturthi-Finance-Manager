package com.vinayaka.finance.report

import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType

/** The report is made in Telugu like the committee's own workbook, or in English. */
enum class ReportLang(val label: String) { TELUGU("తెలుగు"), ENGLISH("English") }

enum class RowStyle { TITLE, HEADER, BODY, TOTAL, SECTION, NOTE }

/** A rupee amount, in paise so nothing is lost. Cells hold a String, a [Money], an Int (serial numbers) or null (empty). */
data class Money(val paise: Long)

data class ReportRow(val cells: List<Any?>, val style: RowStyle = RowStyle.BODY)

/** Zero-based, inclusive cell range that is merged into one cell. */
data class CellMerge(val row: Int, val col: Int, val lastRow: Int, val lastCol: Int)

/** One tab of the report. The same grid is shown in the app, written to Excel and written to a Google Sheet. */
data class ReportSheet(val name: String, val rows: List<ReportRow>, val widths: List<Int>, val merges: List<CellMerge> = emptyList()) {
    /** Every (row, col) that lies inside a merged range. */
    fun coveredByMerges(): Set<Pair<Int, Int>> = buildSet {
        merges.forEach { m -> for (r in m.row..m.lastRow) for (c in m.col..m.lastCol) add(r to c) }
    }
}

data class Report(val year: Int, val lang: ReportLang, val sheets: List<ReportSheet>, val collections: Long, val expenses: Long) {
    val surplus: Long get() = collections - expenses
    val fileBaseName: String get() = "vinayaka-finance-report-$year"
    val googleTitle: String get() = "Vinayaka Finance report $year"
    /** The tab that is shown first. */
    val abstractIndex: Int get() = sheets.indexOfFirst { it.name == ReportBuilder.ABSTRACT }.coerceAtLeast(0)
}

/** Headings, in both languages. Telugu ones are the committee workbook's own wording. */
internal class Labels(
    val sl: String, val donor: String, val amount: String, val total: String,
    val childDetails: String, val village: String, val inKindItem: String,
    val dateRecorded: String, val date: String, val item: String, val quantity: String,
    val youthTitle: (Int) -> String, val villagersTitle: (Int) -> String, val childrenTitle: (Int) -> String, val outsidersTitle: (Int) -> String,
    val dakshinaTitle: (Int) -> String, val annDonTitle: (Int) -> String, val annExpTitle: (Int) -> String, val poojaTitle: (Int) -> String,
    val collections: String, val annHeading: String, val annDonations: String, val annExpenses: String, val surplusWord: String,
    val catYouth: String, val catVillagers: String, val catChildren: String, val catOutsiders: String,
    val otherCollections: String, val dakshina: String, val annSurplusLine: String, val allCollections: String,
    val expensesSection: String, val poojaLine: String, val currentSurplus: (Int) -> String,
    val pendingNote: (String) -> String,
) {
    companion object {
        val TELUGU = Labels(
            sl = "క్రమ సంక్య", donor = "ధాత వివరములు", amount = "మొత్తము రూపాయలలో", total = "మొత్తము",
            childDetails = "తండ్రి/ తల్లి / సంరక్షకుడు, గ్రామము", village = "గ్రామము", inKindItem = "వస్తువు రూపాన అయినచో వస్తువు పేరు",
            dateRecorded = "లెక్క కట్టి రాసిన తేదీ", date = "తేదీ", item = "వస్తువు పేరు", quantity = "కొలత/ పరిమాణము",
            youthTitle = { "యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు - $it" }, villagersTitle = { "గ్రామపురజనుల నుంచి వచ్చిన విరాళాలు - $it" },
            childrenTitle = { "చిన్నపిల్లల నుంచి వచ్చిన విరాళాలు - $it" }, outsidersTitle = { "బయటగ్రామల నుంచి వచ్చిన విరాళాలు - $it" },
            dakshinaTitle = { "దక్షిణ $it" }, annDonTitle = { "అన్నదాన కార్యక్రమానికి వచ్చిన విరాళములు - $it" },
            annExpTitle = { "అన్నదానమునకు అయిన ఖర్చులు - $it" }, poojaTitle = { "గణేష్ పూజ ఖర్చుల వివరములు - $it" },
            collections = "వసూళ్ల వివరములు", annHeading = "అన్నసమారాధన", annDonations = "అన్నదాన కార్యక్రమానికి వచ్చిన విరాళములు",
            annExpenses = "అన్నదానమునకు అయిన ఖర్చులు", surplusWord = "మిగులు",
            catYouth = "యువజనసంఘ సభ్యులు నుంచి వచ్చిన విరాళాలు", catVillagers = "గ్రామపురజనుల నుంచి వచ్చిన విరాళాలు",
            catChildren = "చిన్నపిల్లల నుంచి వచ్చిన విరాళాలు", catOutsiders = "బయటగ్రామల నుంచి వచ్చిన విరాళాలు",
            otherCollections = "ఇతర ఆదాయం", dakshina = "దక్షిణ",
            annSurplusLine = "అన్నదానము కార్యక్రమానికి వచ్చిన విరాళములు నుంచి అన్నదానము ఖర్చులు పోను మిగిలిన మొత్తము",
            allCollections = "అన్నివిధముల వసూళ్ల మొత్తము", expensesSection = "గణేష్ పూజ ఖర్చుల వివరములు",
            poojaLine = "గణేష్ పూజ మరియు సౌండింగ్ ఖర్చులు", currentSurplus = { "ప్రస్తుత సంవత్సర మిగులు - $it" },
            pendingNote = { "గమనిక: పై మొత్తములలో $it వసూలు చేసిన వ్యక్తి పేరు ఇంకా నమోదు కాలేదు." },
        )
        val ENGLISH = Labels(
            sl = "Sl. No.", donor = "Donor details", amount = "Amount (Rs.)", total = "Total",
            childDetails = "Parent / guardian, village", village = "Village", inKindItem = "Item name, if given in kind",
            dateRecorded = "Date recorded", date = "Date", item = "Item", quantity = "Quantity",
            youthTitle = { "Donations from youth association members - $it" }, villagersTitle = { "Donations from villagers - $it" },
            childrenTitle = { "Donations from children - $it" }, outsidersTitle = { "Donations from other villages - $it" },
            dakshinaTitle = { "Dakshina $it" }, annDonTitle = { "Donations for the Annadhanam programme - $it" },
            annExpTitle = { "Annadhanam expenses - $it" }, poojaTitle = { "Ganesh pooja expenses - $it" },
            collections = "Collections", annHeading = "Annadhanam", annDonations = "Donations received for Annadhanam",
            annExpenses = "Annadhanam expenses", surplusWord = "Surplus",
            catYouth = "Donations from youth association members", catVillagers = "Donations from villagers",
            catChildren = "Donations from children", catOutsiders = "Donations from other villages",
            otherCollections = "Other income", dakshina = "Dakshina",
            annSurplusLine = "Annadhanam donations minus Annadhanam expenses",
            allCollections = "Total of all collections", expensesSection = "Ganesh pooja expenses",
            poojaLine = "Ganesh pooja and sound expenses", currentSurplus = { "This year's surplus - $it" },
            pendingNote = { "Note: $it of the amounts above has no collector recorded yet." },
        )
        fun of(lang: ReportLang) = if (lang == ReportLang.TELUGU) TELUGU else ENGLISH
    }
}

/**
 * Builds the annual report from one year's records, laid out like the committee's own workbook: one tab per donation list,
 * the Dakshina list, the Annadhanam and pooja expenses, and an ABSTRACT. Donations count at their listed amount whether or not a
 * collector is recorded (the committee only lists what it received); a note says how much has no collector yet.
 * Expenditure may be negative (a return); it simply reduces the expense.
 */
object ReportBuilder {
    const val ABSTRACT = "ABSTRACT"
    private val DAKSHINA_NAMES = setOf("dakshina", "dhakshina", "dakshna", "దక్షిణ")
    private val order = compareBy<Entry>({ it.date }, { it.createdAt })

    /** Letters and combining marks only (Telugu vowel signs are marks), lower-cased: "Dhakshina " and "దక్షిణ" both compare cleanly. */
    private fun lettersOf(s: String) = s.lowercase().filter {
        it.isLetter() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() }
    }

    private fun isDakshina(e: Entry) = lettersOf(e.name) in DAKSHINA_NAMES

    private fun ddmmyyyy(iso: String) = if (iso.length == 10) "${iso.substring(8, 10)}-${iso.substring(5, 7)}-${iso.substring(0, 4)}" else iso

    private fun money(e: Entry): Money? = if (e.amount == 0L) null else Money(e.amount)

    private fun sheet(name: String, title: String, header: List<String>, widths: List<Int>, list: List<Entry>, totalLabel: String, cells: (Int, Entry) -> List<Any?>): ReportSheet {
        val last = header.size - 1
        val rows = mutableListOf(ReportRow(listOf(title), RowStyle.TITLE), ReportRow(header, RowStyle.HEADER))
        list.forEachIndexed { i, e -> rows += ReportRow(cells(i + 1, e)) }
        rows += ReportRow(List(last) { if (it == 0) totalLabel else null } + Money(list.sumOf { it.amount }), RowStyle.TOTAL)
        val merges = mutableListOf(CellMerge(0, 0, 0, last))
        if (last > 1) merges += CellMerge(rows.size - 1, 0, rows.size - 1, last - 1)
        return ReportSheet(name, rows, widths, merges)
    }

    fun build(entries: List<Entry>, year: Int, lang: ReportLang): Report {
        val L = Labels.of(lang)
        val live = entries.filter { !it.isDeleted && it.year == year }
        val donations = live.filter { it.type == EntryType.DONATION }.sortedWith(order)
        val others = live.filter { it.type == EntryType.OTHER }.sortedWith(order)
        val spending = live.filter { it.type == EntryType.EXPENDITURE }.sortedWith(order)
        fun donors(cat: String) = donations.filter { it.category.trim() == cat }
        val youth = donors("Youth"); val villagers = donors("Villagers"); val children = donors("Children")
        val outsiders = donors("Other villagers"); val annDon = donors("Annadhanam")
        val knownDonation = setOf("Youth", "Villagers", "Children", "Other villagers", "Annadhanam")
        val extraDonations = donations.filter { it.category.trim() !in knownDonation }.groupBy { it.category.trim().ifEmpty { "—" } }
        val dakshina = others.filter(::isDakshina)
        val auctions = others.filterNot(::isDakshina)
        val annExp = spending.filter { it.category.trim() == "Annadhanam" }
        val pooja = spending.filter { it.category.trim() == "Pooja" }
        val extraSpending = spending.filter { it.category.trim() != "Annadhanam" && it.category.trim() != "Pooja" }.groupBy { it.category.trim().ifEmpty { "—" } }

        val sheets = mutableListOf<ReportSheet>()
        sheets += sheet("Youth Donations", L.youthTitle(year), listOf(L.sl, L.donor, L.amount), listOf(8, 44, 18), youth, L.total) { i, e -> listOf(i, e.name, money(e)) }
        sheets += sheet("Villagers Donations", L.villagersTitle(year), listOf(L.sl, L.donor, L.amount), listOf(8, 44, 18), villagers, L.total) { i, e -> listOf(i, e.name, money(e)) }
        sheets += sheet("Children Donations", L.childrenTitle(year), listOf(L.sl, L.donor, L.childDetails, L.amount), listOf(8, 36, 40, 18), children, L.total) { i, e -> listOf(i, e.name, e.description, money(e)) }
        sheets += sheet("OUTSIDERS DONATIONS", L.outsidersTitle(year), listOf(L.sl, L.donor, L.village, L.amount), listOf(8, 36, 28, 18), outsiders, L.total) { i, e -> listOf(i, e.name, e.description, money(e)) }
        sheets += sheet("Dakshina", L.dakshinaTitle(year), listOf(L.sl, L.dateRecorded, L.amount), listOf(8, 24, 18), dakshina, L.total) { i, e -> listOf(i, ddmmyyyy(e.date), money(e)) }
        sheets += sheet("Annadanam Donation", L.annDonTitle(year), listOf(L.sl, L.donor, L.inKindItem, L.amount), listOf(8, 36, 40, 18), annDon, L.total) { i, e -> listOf(i, e.name, e.description, money(e)) }
        sheets += sheet("Annadanam Expenditure", L.annExpTitle(year), listOf(L.sl, L.item, L.quantity, L.amount), listOf(8, 40, 28, 18), annExp, L.total) { i, e -> listOf(i, e.name, e.description, money(e)) }
        sheets += sheet("Expenditure", L.poojaTitle(year), listOf(L.sl, L.date, L.item, L.quantity, L.amount), listOf(8, 14, 40, 28, 18), pooja, L.total) { i, e -> listOf(i, ddmmyyyy(e.date), e.name, e.description, money(e)) }

        // ---- ABSTRACT
        fun sum(xs: List<Entry>) = xs.sumOf { it.amount }
        val rows = mutableListOf<ReportRow>()
        val merges = mutableListOf<CellMerge>()
        rows += ReportRow(listOf("$ABSTRACT - $year"), RowStyle.TITLE); merges += CellMerge(0, 0, 0, 2)
        rows += ReportRow(listOf(L.sl, L.collections, L.amount, null, L.annHeading, null), RowStyle.HEADER); merges += CellMerge(1, 4, 1, 5)

        val block1 = mutableListOf(L.catYouth to sum(youth), L.catVillagers to sum(villagers), L.catChildren to sum(children), L.catOutsiders to sum(outsiders))
        extraDonations.forEach { (cat, list) -> block1 += cat to sum(list) }
        val annDonTotal = sum(annDon); val annExpTotal = sum(annExp); val annSurplus = annDonTotal - annExpTotal
        val right = listOf(L.annDonations to annDonTotal, L.annExpenses to annExpTotal, L.surplusWord to annSurplus)
        block1.forEachIndexed { i, (label, amt) ->
            val cells = mutableListOf<Any?>(i + 1, label, Money(amt))
            if (i < right.size) { cells += null; cells += right[i].first; cells += Money(right[i].second) }
            rows += ReportRow(cells)
        }
        val total1 = block1.sumOf { it.second }
        rows += ReportRow(listOf(null, L.total, Money(total1)), RowStyle.TOTAL)
        rows += ReportRow(emptyList())

        rows += ReportRow(listOf(L.sl, L.otherCollections, L.amount), RowStyle.HEADER)
        // Dakshina first, then every other Other-income entry that was added, then the Annadhanam surplus.
        val block2 = mutableListOf<Pair<String, Long>>()
        if (dakshina.isNotEmpty()) block2 += L.dakshina to sum(dakshina)
        auctions.forEach { block2 += (if (it.description.isBlank()) it.name else "${it.name} (${it.description})") to it.amount }
        block2 += L.annSurplusLine to annSurplus
        block2.forEachIndexed { i, (label, amt) -> rows += ReportRow(listOf(i + 1, label, Money(amt))) }
        val total2 = block2.sumOf { it.second }
        rows += ReportRow(listOf(null, L.total, Money(total2)), RowStyle.TOTAL)
        val allCollections = total1 + total2
        rows += ReportRow(listOf(null, L.allCollections, Money(allCollections)), RowStyle.TOTAL)
        rows += ReportRow(emptyList())

        rows += ReportRow(listOf(L.expensesSection), RowStyle.SECTION); merges += CellMerge(rows.size - 1, 0, rows.size - 1, 2)
        val block3 = mutableListOf(L.poojaLine to sum(pooja))
        extraSpending.forEach { (cat, list) -> block3 += cat to sum(list) }
        block3.forEachIndexed { i, (label, amt) -> rows += ReportRow(listOf(i + 1, label, Money(amt))) }
        val expenses = block3.sumOf { it.second }
        rows += ReportRow(listOf(null, L.total, Money(expenses)), RowStyle.TOTAL)
        rows += ReportRow(emptyList())
        rows += ReportRow(listOf(null, L.currentSurplus(year), Money(allCollections - expenses)), RowStyle.TOTAL)

        val pending = live.filter { it.isPending }.sumOf { it.amount }
        if (pending > 0) {
            rows += ReportRow(emptyList())
            rows += ReportRow(listOf(L.pendingNote(rupeesText(pending))), RowStyle.NOTE); merges += CellMerge(rows.size - 1, 0, rows.size - 1, 5)
        }
        sheets += ReportSheet(ABSTRACT, rows, listOf(7, 64, 18, 3, 46, 18), merges)
        return Report(year, lang, sheets, allCollections, expenses)
    }

    /** "₹1,610" without depending on Android, for the note. */
    private fun rupeesText(paise: Long): String = com.vinayaka.finance.util.formatRupees(paise)
}
