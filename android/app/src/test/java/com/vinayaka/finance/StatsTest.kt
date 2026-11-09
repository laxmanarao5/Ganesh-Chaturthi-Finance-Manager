package com.vinayaka.finance

import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.domain.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun e(type: EntryType, category: String, amount: Long, person: String, deleted: Boolean = false) = Entry(
    id = "${type}-${category}-$amount-$person-${System.nanoTime()}", type = type, year = 2026, date = "2026-09-14",
    category = category, name = "n", description = "", amount = amount, person = person,
    createdBy = "x", createdAt = 1, updatedBy = "x", updatedAt = 1, deletedAt = if (deleted) 5L else null,
)

class StatsTest {
    // Shaped like the committee's own workbook: income per collector, pledges without a collector, spending per payer.
    private val rows = listOf(
        e(EntryType.DONATION, "Youth", 1_000_000, "Laxman"),
        e(EntryType.DONATION, "Villagers", 3_000_000, "Bhanu"),
        e(EntryType.DONATION, "Villagers", 1_000_000, ""),          // promised, not collected
        e(EntryType.DONATION, "Annadhanam", 160_000, "Laxman"),
        e(EntryType.OTHER, "", 1_010_000, "Venky"),
        e(EntryType.EXPENDITURE, "Pooja", 245_285, "Laxman"),
        e(EntryType.EXPENDITURE, "Pooja", 459_890, "Bhanu"),
        e(EntryType.EXPENDITURE, "Annadhanam", 100_000, "Laxman"),
        e(EntryType.EXPENDITURE, "Annadhanam", 432_500, "Simhadri"),
        e(EntryType.DONATION, "Youth", 9_999_999, "Bhanu", deleted = true), // ignored
    )

    @Test fun `headline totals separate collected from still to collect`() {
        val t = Stats.totals(rows)
        assertEquals(1_000_000 + 3_000_000 + 160_000 + 1_010_000L, t.collected)
        assertEquals(1_000_000L, t.toCollect)
        assertEquals(245_285 + 459_890 + 100_000 + 432_500L, t.spent)
        assertEquals(t.collected + t.toCollect, t.listed)
        assertEquals(t.collected - t.spent, t.cash)
    }

    @Test fun `a gift in kind with no collector is not counted as not collected`() {
        val inKind = e(EntryType.DONATION, "Annadhanam", 0, "")
        val promised = e(EntryType.DONATION, "Annadhanam", 50_000, "")
        val collectedInKind = e(EntryType.DONATION, "Annadhanam", 0, "Bhanu")
        assertFalse(inKind.isPending)
        assertTrue(promised.isPending)
        assertFalse(collectedInKind.isPending)
        assertFalse(e(EntryType.EXPENDITURE, "Pooja", 100, "").isPending)      // spending is never "to collect"
        val t = Stats.totals(listOf(inKind, promised, collectedInKind))
        assertEquals(50_000L, t.toCollect)
        assertEquals(0L, t.collected)
    }

    @Test fun `per person collected spent and net`() {
        val p = Stats.byPerson(rows).associateBy { it.person }
        assertEquals(1_160_000L, p.getValue("Laxman").collected)
        assertEquals(345_285L, p.getValue("Laxman").spent)
        assertEquals(1_160_000L - 345_285L, p.getValue("Laxman").net)
        assertEquals(3_000_000L, p.getValue("Bhanu").collected)
        assertEquals(459_890L, p.getValue("Bhanu").spent)
        assertEquals(-432_500L, p.getValue("Simhadri").net)
        assertEquals(1_010_000L, p.getValue("Venky").collected)
        // Biggest collector first: Bhanu 30,000, Laxman 11,600, Venky 10,100, Simhadri 0.
        assertEquals(listOf("Bhanu", "Laxman", "Venky", "Simhadri"), Stats.byPerson(rows).map { it.person })
    }

    @Test fun `sum of people equals collected and spent in the totals`() {
        val t = Stats.totals(rows)
        val p = Stats.byPerson(rows)
        assertEquals(t.collected, p.sumOf { it.collected })
        assertEquals(t.spent, p.sumOf { it.spent })
    }

    @Test fun `names differing only in case are one person`() {
        val r = listOf(e(EntryType.DONATION, "Youth", 100, "bhanu"), e(EntryType.DONATION, "Youth", 200, "Bhanu "))
        val p = Stats.byPerson(r)
        assertEquals(1, p.size)
        assertEquals(300L, p[0].collected)
        assertEquals("Bhanu", Stats.canonicalPerson(" bhanu", listOf("Laxman", "Bhanu")))
        assertEquals("New Person", Stats.canonicalPerson("New Person", listOf("Laxman")))
    }

    @Test fun `category totals follow the usual order and skip deleted rows`() {
        val c = Stats.byCategory(rows, EntryType.DONATION)
        assertEquals(listOf("Youth", "Villagers", "Annadhanam"), c.map { it.first })
        assertEquals(1_000_000L, c[0].second)
        assertEquals(4_000_000L, c[1].second)
    }

    @Test fun `person breakdown and people list`() {
        val b = Stats.personBreakdown(rows, "laxman")
        assertTrue(b.any { it.first == EntryType.DONATION && it.second == "Youth" && it.third == 1_000_000L })
        assertTrue(b.any { it.first == EntryType.EXPENDITURE && it.second == "Pooja" })
        assertEquals(listOf("Bhanu", "Laxman", "Simhadri", "Venky", "Zed"), Stats.people(listOf("Zed", "Laxman"), rows).sorted())
        // The committee list's spelling wins over what was typed on a record.
        assertEquals(listOf("LAXMAN"), Stats.people(listOf("LAXMAN"), rows.filter { it.person == "Laxman" }.take(1)))
    }
}
