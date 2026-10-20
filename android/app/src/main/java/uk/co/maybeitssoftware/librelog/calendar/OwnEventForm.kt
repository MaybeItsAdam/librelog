package uk.co.maybeitssoftware.librelog.calendar

import uniffi.librelog_core.KeptEvent
import uniffi.librelog_core.OwnEvent

/**
 * The "Add event" sheet's fields (docs/calendar.md, "Events you add
 * yourself"), and the checks it makes before asking the core. The core
 * checks again; these only let the sheet say what's wrong as you go.
 * Times are `HH:MM` on the day; no start time is all day.
 */
data class OwnEventForm(
    val title: String = "",
    val allDay: Boolean = true,
    val start: String? = null,
    val end: String? = null,
    val place: String = "",
) {
    /** What stops it saving, in words, or null when it can save. */
    val problem: String?
        get() = when {
            title.isBlank() -> "Give it a title"
            allDay -> null
            start == null -> "Choose a start time"
            end != null && end <= start -> "The end has to be after the start"
            else -> null
        }

    val canSave: Boolean get() = problem == null

    fun toOwnEvent(): OwnEvent = OwnEvent(
        title = title.trim(),
        startTime = if (allDay) null else start,
        endTime = if (allDay) null else end,
        location = place.trim().ifEmpty { null },
    )

    companion object {
        /** An own event, as it's shown for editing. */
        fun from(e: KeptEvent): OwnEventForm {
            val start = e.start.takeIf { !e.allDay && it.length == 16 }?.takeLast(5)
            return OwnEventForm(
                title = e.title,
                allDay = start == null,
                start = start,
                end = e.end?.takeIf { start != null && it.length == 16 }?.takeLast(5),
                place = e.location.orEmpty(),
            )
        }

        fun hhmm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

        /** (hour, minute) from `HH:MM`, or null. */
        fun parts(time: String?): Pair<Int, Int>? {
            val bits = time?.split(':')?.takeIf { it.size == 2 } ?: return null
            val h = bits[0].toIntOrNull() ?: return null
            val m = bits[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) h to m else null
        }

        /**
         * A core error as a sentence for the sheet: "invalid input: the event
         * ends before it starts" → "The event ends before it starts".
         */
        fun message(error: Throwable): String {
            val raw = error.message.orEmpty().substringAfter(": ", error.message.orEmpty()).trim()
            return raw.replaceFirstChar { it.uppercase() }.ifEmpty { "Couldn't save the event" }
        }
    }
}
