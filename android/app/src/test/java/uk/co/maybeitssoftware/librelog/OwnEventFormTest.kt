package uk.co.maybeitssoftware.librelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.maybeitssoftware.librelog.calendar.OwnEventForm
import uniffi.librelog_core.CoreException
import uniffi.librelog_core.OwnEvent

class OwnEventFormTest {
    @Test fun needsATitle() {
        assertEquals("Give it a title", OwnEventForm(title = "  ").problem)
        assertTrue(OwnEventForm(title = "Graduation").canSave)
    }

    @Test fun allDayIgnoresTimes() {
        val form = OwnEventForm(title = "Moving day", allDay = true, start = "14:00", end = "10:00")
        assertNull(form.problem)
        assertEquals(OwnEvent("Moving day", null, null, null), form.toOwnEvent())
    }

    @Test fun endMustBeAfterStart() {
        assertEquals("The end has to be after the start", OwnEventForm("Late", false, "16:00", "15:00").problem)
        assertEquals("The end has to be after the start", OwnEventForm("Late", false, "16:00", "16:00").problem)
        assertNull(OwnEventForm("Late", false, "16:00", "16:30").problem)
        assertNull(OwnEventForm("Open", false, "16:00", null).problem)
        assertEquals("Choose a start time", OwnEventForm("Open", false, null, null).problem)
    }

    @Test fun tidiesWhatItSends() {
        val sent = OwnEventForm(" Graduation ", false, "14:00", "16:30", "  ").toOwnEvent()
        assertEquals(OwnEvent("Graduation", "14:00", "16:30", null), sent)
        assertEquals("Hall", OwnEventForm("x", place = " Hall ").toOwnEvent().location)
    }

    @Test fun timesRoundTrip() {
        assertEquals("09:05", OwnEventForm.hhmm(9, 5))
        assertEquals(9 to 5, OwnEventForm.parts("09:05"))
        assertNull(OwnEventForm.parts("24:00"))
        assertNull(OwnEventForm.parts("nine"))
        assertNull(OwnEventForm.parts(null))
    }

    @Test fun coreErrorsReadAsSentences() {
        assertEquals("The event ends before it starts", OwnEventForm.message(CoreException.Invalid("invalid input: the event ends before it starts")))
        assertFalse(OwnEventForm.message(RuntimeException()).isEmpty())
    }
}
