package uk.co.maybeitssoftware.librelog

import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.maybeitssoftware.librelog.calendar.CalendarKeys
import uk.co.maybeitssoftware.librelog.calendar.OwnEventForm

/**
 * Times sent to the core keep ASCII digits whatever the phone's language: a
 * Persian locale's default formatting gives ۰۹:۰۵, which the core refuses.
 */
class WireDigitsTest {
    private val original = Locale.getDefault()

    @Before fun persian() = Locale.setDefault(Locale.forLanguageTag("fa-IR"))

    @After fun restore() = Locale.setDefault(original)

    @Test fun theLocaleReallyWouldLocaliseDigits() {
        // Guards the test itself: without Locale.ROOT this is what we'd send.
        assertEquals("۰۹:۰۵", "%02d:%02d".format(9, 5))
    }

    @Test fun ownEventTimesStayAscii() {
        assertEquals("09:05", OwnEventForm.hhmm(9, 5))
    }

    @Test fun nowStaysAscii() {
        assertTrue(Day.nowTime, Day.nowTime.matches(Regex("[0-9]{2}:[0-9]{2}")))
    }

    @Test fun deviceEventTimesStayAscii() {
        val (from, to) = CalendarKeys.times(0, 3_600_000, allDay = false, zone = ZoneOffset.UTC)
        assertEquals("1970-01-01T00:00", from)
        assertEquals("1970-01-01T01:00", to)
    }
}
