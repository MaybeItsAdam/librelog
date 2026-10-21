package uk.co.maybeitssoftware.librelog

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.maybeitssoftware.librelog.calendar.OwnEventForm

/** What rememberSaveable keeps across rotation comes back the same. */
class SavedStateTest {
    private val scope = SaverScope { true }

    @Test fun theNavigationStackRoundTrips() {
        val stack = mutableStateListOf(Route.OnThisDay, Route.Entry("e:1"), Route.History("e:1"))
        val saved = with(Route.StackSaver) { scope.save(stack) }!!
        assertEquals(stack.toList(), Route.StackSaver.restore(saved)!!.toList())
    }

    @Test fun anAddEventFormRoundTrips() {
        for (form in listOf(OwnEventForm(), OwnEventForm("Graduation", false, "14:00", null, "Hall"))) {
            val saved = with(OwnEventForm.Saver) { scope.save(form) }!!
            assertEquals(form, OwnEventForm.Saver.restore(saved))
        }
    }
}
