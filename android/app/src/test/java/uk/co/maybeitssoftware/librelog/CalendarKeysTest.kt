package uk.co.maybeitssoftware.librelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.maybeitssoftware.librelog.calendar.CalendarKeys
import uk.co.maybeitssoftware.librelog.calendar.EventTime

class CalendarKeysTest {
    @Test fun oneOffEventsAreKeyedByIdAlone() {
        val key = CalendarKeys.key(42, 1_000, rrule = null, originalId = null, originalInstanceTime = null)
        assertEquals("android:42", key)
        assertEquals(CalendarKeys.Identity(42, null), CalendarKeys.parse(key))
    }

    @Test fun anOccurrenceKeepsItsSeriesAndOriginalStart() {
        assertEquals("android:7@5000", CalendarKeys.key(7, 5_000, "FREQ=WEEKLY", null, null))
    }

    @Test fun anEditedOccurrenceKeysToTheSameIdentityAsBeforeItWasEdited() {
        // The exception row has its own id and start, but points back at the series.
        val before = CalendarKeys.key(7, 5_000, "FREQ=WEEKLY", null, null)
        val after = CalendarKeys.key(99, 9_000, null, originalId = 7, originalInstanceTime = 5_000)
        assertEquals(before, after)
        assertEquals(CalendarKeys.Identity(7, 5_000), CalendarKeys.parse(after))
    }

    @Test fun otherPlatformsKeysAreNotOurs() {
        assertNull(CalendarKeys.parse("apple:ABC@1"))
        assertNull(CalendarKeys.parse("android:x"))
    }

    @Test fun timeLabels() {
        assertEquals("09:30–10:00", EventTime.label("2026-10-03T09:30", "2026-10-03T10:00"))
        assertEquals("23:00", EventTime.label("2026-10-03T23:00", "2026-10-04T01:00"))
        assertEquals("All day", EventTime.label("2026-10-03", "2026-10-03"))
    }
}
