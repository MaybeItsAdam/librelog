package uk.co.maybeitssoftware.librelog

import java.time.DayOfWeek
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.maybeitssoftware.librelog.calendar.MonthPages

class MonthPagesTest {
    private val pages = MonthPages(YearMonth.of(2026, 10), count = 2400)

    @Test fun theLastPageIsThisMonth() {
        assertEquals(2399, pages.lastPage)
        assertEquals(YearMonth.of(2026, 10), pages.month(pages.lastPage))
        assertEquals(pages.lastPage, pages.page(YearMonth.of(2026, 10)))
    }

    @Test fun pagesStepBackAcrossYears() {
        assertEquals(YearMonth.of(2026, 9), pages.month(pages.lastPage - 1))
        assertEquals(YearMonth.of(2025, 12), pages.month(pages.lastPage - 10))
        assertEquals(YearMonth.of(2025, 10), pages.month(pages.lastPage - 12))
    }

    @Test fun pageAndMonthRoundTrip() {
        for (page in listOf(0, 1, 777, 2398, 2399)) assertEquals(page, pages.page(pages.month(page)))
        val ym = YearMonth.of(1999, 2)
        assertEquals(ym, pages.month(pages.page(ym)))
    }

    @Test fun theFirstPageIsTwoHundredYearsBack() {
        assertEquals(YearMonth.of(1826, 11), pages.earliest)
        assertTrue(pages.contains(YearMonth.of(1826, 11)))
        assertFalse(pages.contains(YearMonth.of(1826, 10)))
    }

    @Test fun outOfRangeClamps() {
        assertEquals(pages.lastPage, pages.page(YearMonth.of(2027, 3)))
        assertFalse(pages.contains(YearMonth.of(2026, 11)))
        assertEquals(0, pages.page(YearMonth.of(1700, 1)))
        assertEquals(pages.latest, pages.month(5000))
        assertEquals(pages.earliest, pages.month(-3))
    }

    @Test fun everyMonthIsSixWeeks() {
        // February 2026 starts on a Sunday: no lead on Sunday-first weeks, six on Monday-first.
        val sun = MonthPages.cells(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        val mon = MonthPages.cells(YearMonth.of(2026, 2), DayOfWeek.MONDAY)
        assertEquals(42, sun.size)
        assertEquals(42, mon.size)
        assertEquals("2026-02-01", sun[0])
        assertNull(mon[5])
        assertEquals("2026-02-01", mon[6])
        assertEquals(28, mon.count { it != null })
        // The longest case: a 31-day month starting on the week's last day.
        val may = MonthPages.cells(YearMonth.of(2027, 5), DayOfWeek.SUNDAY)
        assertEquals(42, may.size)
        assertEquals("2027-05-31", may[36])
    }
}
