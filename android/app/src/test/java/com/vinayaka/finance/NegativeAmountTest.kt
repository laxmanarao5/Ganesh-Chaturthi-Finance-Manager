package com.vinayaka.finance

import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.domain.Stats
import com.vinayaka.finance.importer.Csv
import com.vinayaka.finance.importer.ImportMapper
import com.vinayaka.finance.importer.SheetData
import com.vinayaka.finance.util.amountLabel
import com.vinayaka.finance.util.paiseToInput
import com.vinayaka.finance.util.paiseToSheetNumber
import com.vinayaka.finance.util.parseSignedAmountToPaise
import com.vinayaka.finance.util.sheetNumberToPaise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Something taken back to the shop is entered on Expenditure as a negative amount (-1610) and shown as +1610 in green. */
class NegativeAmountTest {
    private fun spent(amount: Long, person: String = "Bhanu") = Entry(
        id = "n$amount", type = EntryType.EXPENDITURE, year = 2026, date = "2026-09-14", category = "Pooja", name = "Kirana", description = "",
        amount = amount, person = person, createdBy = "x", createdAt = 1, updatedBy = "x", updatedAt = 1,
    )

    @Test fun `a leading minus is accepted for signed amounts`() {
        assertEquals(-161000L, parseSignedAmountToPaise("-1610"))
        assertEquals(-161050L, parseSignedAmountToPaise("−1,610.50"))
        assertEquals(161000L, parseSignedAmountToPaise("1610"))
        assertEquals(0L, parseSignedAmountToPaise("-0"))
        assertNull(parseSignedAmountToPaise("-"))
        assertNull(parseSignedAmountToPaise("--5"))
        assertNull(parseSignedAmountToPaise("abc"))
    }

    @Test fun `negative amounts round trip through the sheet number and the edit field`() {
        assertEquals(-1610L, paiseToSheetNumber(-161000))
        assertEquals(-16.1, paiseToSheetNumber(-1610).toDouble(), 0.0)
        assertEquals(-161000L, sheetNumberToPaise(-1610.0))
        assertEquals("-1610", paiseToInput(-161000))
        assertEquals("-16.1", paiseToInput(-1610))
        assertEquals(-1610L, parseSignedAmountToPaise(paiseToInput(-1610)))
    }

    @Test fun `a returned purchase shows as plus in green and a normal one as minus in red`() {
        assertEquals("+₹1,610", amountLabel(isIncome = false, amount = -161000).text)
        assertTrue(amountLabel(isIncome = false, amount = -161000).moneyIn)
        assertEquals("−₹250", amountLabel(isIncome = false, amount = 25000).text)
        assertFalse(amountLabel(isIncome = false, amount = 25000).moneyIn)
        assertEquals("+₹500", amountLabel(isIncome = true, amount = 50000).text)
        assertEquals("In kind", amountLabel(isIncome = true, amount = 0).text)
    }

    @Test fun `a return reduces what was spent, for the total and for the person`() {
        val rows = listOf(spent(500_000), spent(-161_000), spent(100_000, person = "Venky"))
        assertEquals(439_000L, Stats.totals(rows).spent)
        assertEquals(339_000L, Stats.byPerson(rows).first { it.person == "Bhanu" }.spent)
        assertEquals(-439_000L, Stats.totals(rows).cash)
    }

    @Test fun `import accepts a negative amount on expenditure only`() {
        val b = ImportMapper.parse(
            listOf(
                SheetData("Pooja Expenditure", Csv.parse("Date,Item,Quantity,Amount,Paid By\n12/09/2026,Flowers,2 kg,145,Bhanu\n13/09/2026,Kirana return,,-1610,Bhanu\n")),
                SheetData("Youth Donations", Csv.parse("Name,Amount,Collected By\nDonor,-500,Bhanu\nDonor Two,500,Bhanu\n")),
                SheetData("Ledger 2026", Csv.parse("Date,Type,Category,Name,Description,Amount,Collected / Paid by\n2026-09-14,Expenditure,Annadhanam,Rice returned,,-2000.50,Venky\n2026-09-14,Donation,Youth,Odd,,-5,Venky\n")),
            ),
        )
        val amounts = b.entries.map { it.name to it.amount }
        assertTrue(amounts.contains("Kirana return" to -161_000L))
        assertTrue(amounts.contains("Rice returned" to -200_050L))
        assertFalse(b.entries.any { it.name == "Donor" })      // negative donation is skipped
        assertFalse(b.entries.any { it.name == "Odd" })
        assertEquals(2, b.skippedRows)
        assertTrue(b.entries.any { it.name == "Donor Two" })
    }
}
