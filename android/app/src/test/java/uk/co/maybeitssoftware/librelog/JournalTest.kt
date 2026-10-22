package uk.co.maybeitssoftware.librelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.maybeitssoftware.librelog.photos.DayPreview

/** New entries' default time and the day-preview menu. */
class JournalTest {
    @Test fun todayStartsNowAndAMissedDayHasNoTime() {
        assertEquals(5, Day.defaultTime("2026-10-22", from = "2026-10-22")?.length)
        assertNull(Day.defaultTime("2026-10-03", from = "2026-10-22"))
    }

    @Test fun previewMenuOffersWhatIsntAlreadyChosen() {
        // The current preview can be turned off but not chosen again.
        assertEquals(DayPreview.Actions(useAsPreview = false, noPreview = true), DayPreview.actions("a", "a"))
        // Another photo can take over, or the preview can go.
        assertEquals(DayPreview.Actions(useAsPreview = true, noPreview = true), DayPreview.actions("b", "a"))
        // With no preview, any photo can become it; there's nothing to turn off.
        assertEquals(DayPreview.Actions(useAsPreview = true, noPreview = false), DayPreview.actions("a", null))
    }
}
