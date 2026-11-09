package com.vinayaka.finance

import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.data.sheets.ContactRows
import com.vinayaka.finance.data.sheets.EntryRows
import com.vinayaka.finance.util.formatRupees
import com.vinayaka.finance.util.parseAmountToPaise
import com.vinayaka.finance.util.parseDateFlexible
import com.vinayaka.finance.util.paiseToSheetNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMapperTest {
    private val e = Entry(
        id = "abc", type = EntryType.DONATION, year = 2025, date = "2025-09-07", category = "Youth",
        name = "Ravi", description = "Village A", amount = 150050, person = "Bhanu",
        createdBy = "a@x.com", createdAt = 1_700_000_000_000,
        updatedBy = "a@x.com", updatedAt = 1_700_000_100_000, rev = 3,
    )

    @Test fun `round trip through sheet cells keeps everything and the hash matches`() {
        val back = EntryRows.fromCells(EntryRows.toCells(e), 5, 2025)!!
        assertTrue(back.hashMatches); assertTrue(back.hadId)
        assertEquals(e.copy(dirty = false, baseRev = 0), back.record)
        assertEquals(5, back.rowNumber)
    }

    @Test fun `amount in the sheet is rupees with decimals`() {
        assertEquals(1500.5, EntryRows.toCells(e)[5])
        assertEquals(1500L, paiseToSheetNumber(150000))
    }

    @Test fun `hand edited amount no longer matches the stored hash`() {
        val cells = EntryRows.toCells(e).toMutableList().also { it[5] = 2000 }
        assertFalse(EntryRows.fromCells(cells, 2, 2025)!!.hashMatches)
    }

    @Test fun `hand edited collector no longer matches the stored hash`() {
        val cells = EntryRows.toCells(e).toMutableList().also { it[6] = "Laxman" }
        assertFalse(EntryRows.fromCells(cells, 2, 2025)!!.hashMatches)
    }

    @Test fun `hand added row without id is accepted and flagged`() {
        val r = EntryRows.fromCells(listOf("2025-09-08", "donation", "Youth", "Cash gift", "", 250), 7, 2025)!!
        assertFalse(r.hadId)
        assertEquals(25000L, r.record.amount)
        assertEquals("", r.record.person)
        assertTrue(r.record.isPending)
    }

    @Test fun `blank amount is a gift in kind worth nothing`() {
        assertEquals(0L, EntryRows.fromCells(listOf("2025-09-08", "Donation", "Annadhanam", "Murali", "Aakulu"), 3, 2025)!!.record.amount)
    }

    @Test fun `typed dates and serial dates both parse`() {
        assertEquals("2025-09-07", EntryRows.fromCells(listOf(45907.0, "Other", "", "x", "", 5), 2, 2025)!!.record.date)
        assertEquals("2025-09-07", EntryRows.fromCells(listOf("7/9/2025", "Other", "", "x", "", 5), 2, 2025)!!.record.date)
    }

    @Test fun `rows missing date or type or with unreadable amount are skipped`() {
        assertNull(EntryRows.fromCells(listOf("", "Other", "", "x", "", 5), 2, 2025))
        assertNull(EntryRows.fromCells(listOf("2025-09-07", "Nope", "", "x", "", 5), 2, 2025))
        assertNull(EntryRows.fromCells(listOf("2025-09-07", "Other", "", "x", "", "abc"), 2, 2025))
        assertNotNull(EntryRows.fromCells(listOf("2025-09-07", "Other", "", "x", "", "5"), 2, 2025))
    }

    @Test fun `contact round trip and called parsing`() {
        val c = Contact(
            id = Contact.rowId("cid1", 2026), contactId = "cid1", year = 2026, name = "Ravi", number = "8978713813",
            caller = "Venky", called = true, remarks = "busy", createdBy = "a", createdAt = 1_700_000_000_000,
            updatedBy = "a", updatedAt = 1_700_000_100_000, rev = 2,
        )
        val back = ContactRows.fromCells(ContactRows.toCells(c), 4)!!
        assertTrue(back.hashMatches)
        assertEquals("cid1:2026", back.record.id)
        assertEquals(true, back.record.called)
        assertEquals("8978713813", back.record.number)
        val typed = ContactRows.fromCells(listOf("Sita", 9966403683.0, "Bhanu", 2026, "Yes"), 5)!!
        assertEquals("9966403683", typed.record.number)
        assertTrue(typed.record.called)
        assertFalse(typed.hadId)
        assertNull(ContactRows.fromCells(listOf("", "1", "x", 2026), 2))
        assertNull(ContactRows.fromCells(listOf("Name", "1", "x", ""), 2))
    }

    @Test fun `indian rupee grouping with paise`() {
        assertEquals("₹0", formatRupees(0))
        assertEquals("₹999", formatRupees(99900))
        assertEquals("₹1,000", formatRupees(100000))
        assertEquals("₹12,34,567", formatRupees(123456700))
        assertEquals("₹14.50", formatRupees(1450))
        assertEquals("-₹1,500", formatRupees(-150000))
    }

    @Test fun `amount text to paise`() {
        assertEquals(150050L, parseAmountToPaise("1,500.5"))
        assertEquals(1450L, parseAmountToPaise("14.50"))
        assertEquals(50000L, parseAmountToPaise("₹500"))
        assertNull(parseAmountToPaise("abc"))
        assertNull(parseAmountToPaise("-5"))
        assertNull(parseAmountToPaise(""))
    }

    @Test fun `flexible date parsing`() {
        assertEquals("2025-09-07", parseDateFlexible("2025-09-07"))
        assertEquals("2025-09-07", parseDateFlexible("07-09-2025"))
        assertEquals("2025-09-07", parseDateFlexible("2025-09-07 10:11:12"))
        assertNull(parseDateFlexible("hello"))
    }
}
