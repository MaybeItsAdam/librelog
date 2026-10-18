package uk.co.maybeitssoftware.librelog

import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.maybeitssoftware.librelog.photos.PhotoImport

/** The day range the chooser asks MediaStore for, and the import's sizing. */
class DayPhotosTest {
    private val london = ZoneId.of("Europe/London")

    @Test fun aDayIsLocalMidnightToMidnight() {
        // 18 Oct 2026 is BST (UTC+1): it starts at 23:00 UTC the day before.
        val (start, end) = Day.millis("2026-10-18", london)!!
        assertEquals(1_792_278_000_000L, start)
        assertEquals(start + 24 * 3_600_000L, end)
    }

    @Test fun clockChangeDaysAreTwentyThreeOrTwentyFiveHours() {
        val (a, b) = Day.millis("2026-03-29", london)!!
        assertEquals(23 * 3_600_000L, b - a)
        val (c, d) = Day.millis("2026-10-25", london)!!
        assertEquals(25 * 3_600_000L, d - c)
    }

    @Test fun consecutiveDaysMeetWithoutOverlap() {
        val (_, end) = Day.millis("2026-10-17", london)!!
        val (start, _) = Day.millis("2026-10-18", london)!!
        assertEquals(end, start)
    }

    @Test fun anInvalidDateHasNoRange() = assertNull(Day.millis("2026-13-01", london))

    @Test fun relativeNames() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.UK)
        try {
            assertEquals("Today", Day.relative("2026-10-18", from = "2026-10-18"))
            assertEquals("Yesterday", Day.relative("2026-10-17", from = "2026-10-18"))
            assertEquals("Tomorrow", Day.relative("2026-10-19", from = "2026-10-18"))
            assertEquals("Thursday", Day.relative("2026-10-15", from = "2026-10-18"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun fitScalesTheLongSideDown() {
        assertEquals(1024 to 768, PhotoImport.fit(4032, 3024, 1024))
        assertEquals(768 to 1024, PhotoImport.fit(3024, 4032, 1024))
        assertEquals(100 to 75, PhotoImport.fit(4032, 3024, 100))
        assertEquals(100 to 1, PhotoImport.fit(10_000, 10, 100))
    }

    @Test fun fitNeverEnlarges() {
        assertEquals(640 to 480, PhotoImport.fit(640, 480, 1024))
        assertEquals(1 to 1, PhotoImport.fit(0, 0, 100))
    }

    @Test fun takenAtIsRfc3339WithTheLocalOffset() {
        assertEquals("2026-10-18T09:41:07+01:00", PhotoImport.rfc3339(1_792_312_867_000L, london))
        assertEquals("2026-10-18T08:41:07Z", PhotoImport.rfc3339(1_792_312_867_000L, ZoneOffset.UTC))
    }
}
