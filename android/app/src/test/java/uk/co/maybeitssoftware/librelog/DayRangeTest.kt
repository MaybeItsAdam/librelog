package uk.co.maybeitssoftware.librelog

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DayRangeTest {
    private val saved = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.US)

    @After fun restore() = Locale.setDefault(saved)

    @Test fun sameMonthThisYear() = assertEquals("26 – 27 Aug", Day.range("2026-08-26", "2026-08-27", thisYear = 2026))

    @Test fun acrossMonths() = assertEquals("29 Sep – 2 Oct", Day.range("2026-09-29", "2026-10-02", thisYear = 2026))

    @Test fun otherYearSaysTheYearOnce() {
        assertEquals("26 – 27 Aug 2025", Day.range("2025-08-26", "2025-08-27", thisYear = 2026))
        assertEquals("29 Sep – 2 Oct 2025", Day.range("2025-09-29", "2025-10-02", thisYear = 2026))
    }

    @Test fun acrossYearsSaysBoth() =
        assertEquals("30 Dec 2025 – 2 Jan 2026", Day.range("2025-12-30", "2026-01-02", thisYear = 2026))

    @Test fun noOrBackwardsEndIsOneDay() {
        assertEquals("3 Oct", Day.range("2026-10-03", null, thisYear = 2026))
        assertEquals("3 Oct", Day.range("2026-10-03", "2026-10-01", thisYear = 2026))
    }

    @Test fun spanDayCounts() {
        assertEquals(2 to 4, Day.spanDay("2026-09-29", "2026-10-02", "2026-09-30"))
        assertEquals(4 to 4, Day.spanDay("2026-09-29", "2026-10-02", "2026-10-02"))
        assertNull(Day.spanDay("2026-09-29", "2026-10-02", "2026-10-03"))
        assertNull(Day.spanDay("2026-09-29", null, "2026-09-29"))
    }
}
