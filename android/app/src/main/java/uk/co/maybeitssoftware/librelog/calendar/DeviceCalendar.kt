package uk.co.maybeitssoftware.librelog.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.librelog_core.CalendarEvent
import uniffi.librelog_core.CalendarLookup
import uniffi.librelog_core.KeptEvent

/**
 * The device's calendars through CalendarContract, which already include any
 * Google or Exchange account on the phone (docs/calendar.md). Reading only:
 * LibreLog never writes to a calendar. READ_CALENDAR is asked for the first
 * time the user taps "Show this day's calendar", never at launch. Mirrors
 * apple/App/Calendar/DeviceCalendar.swift.
 */
class DeviceCalendar(private val context: Context) {
    val hasAccess: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Every event touching one local day, from calendars shown in the calendar app. */
    suspend fun events(day: String): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@withContext emptyList()
        if (!hasAccess) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val begin = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // All-day events are stored in UTC, so a wide query catches them in
        // any time zone; the filter below keeps the ones on this very day.
        instances(begin - DAY_MS, end + DAY_MS, "${Instances.VISIBLE} = 1", emptyArray())
            .filter { it.covers(date, begin, end) }
            .map { it.toEvent(zone) }
    }

    /**
     * Looks up each kept event this device can track. Without access the
     * result is empty, so nothing is ever taken for cancelled just because
     * the calendar couldn't be read.
     */
    suspend fun lookUp(kept: List<KeptEvent>): List<CalendarLookup> = withContext(Dispatchers.IO) {
        if (!hasAccess) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        kept.mapNotNull { k ->
            val id = CalendarKeys.parse(k.key) ?: return@mapNotNull null
            CalendarLookup(k.key, find(id)?.toEvent(zone))
        }
    }

    /**
     * A one-off event is found by id wherever it has moved. An occurrence of
     * a repeating event is first looked for as an exception made for it,
     * then as the series' occurrence on the same day (the whole series may
     * have moved to another time). Searched within two months either side.
     */
    private fun find(id: CalendarKeys.Identity): Row? {
        val original = id.originalMillis ?: return event(id.eventId)
        val rows = instances(
            original - WINDOW_MS,
            original + WINDOW_MS,
            "${Instances.EVENT_ID} = ? OR ${Instances.ORIGINAL_ID} = ?",
            arrayOf(id.eventId.toString(), id.eventId.toString()),
        )
        rows.firstOrNull { it.originalId == id.eventId && it.originalInstanceTime == original }?.let { return it }
        val day = CalendarKeys.utcOrLocalDay(original, rows.firstOrNull()?.allDay ?: false)
        return rows.firstOrNull {
            it.eventId == id.eventId && it.originalId == null &&
                CalendarKeys.utcOrLocalDay(it.begin, it.allDay) == day
        }
    }

    private fun event(eventId: Long): Row? {
        val projection = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION,
            Events.CALENDAR_DISPLAY_NAME, Events.STATUS, Events.RRULE, Events.DELETED,
        )
        return context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId), projection, null, null, null,
        )?.use { c ->
            if (!c.moveToFirst() || c.getInt(9) == 1) return null
            val begin = c.getLong(2)
            Row(
                eventId = c.getLong(0),
                title = c.getString(1),
                begin = begin,
                end = if (c.isNull(3)) begin else c.getLong(3),
                allDay = c.getInt(4) == 1,
                location = c.getString(5),
                calendar = c.getString(6),
                cancelled = !c.isNull(7) && c.getInt(7) == Events.STATUS_CANCELED,
                rrule = c.getString(8),
                originalId = null,
                originalInstanceTime = null,
            )
        }
    }

    private fun instances(from: Long, to: Long, selection: String, args: Array<String>): List<Row> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.EVENT_LOCATION, Instances.CALENDAR_DISPLAY_NAME, Instances.STATUS, Instances.RRULE,
            Instances.ORIGINAL_ID, Instances.ORIGINAL_INSTANCE_TIME,
        )
        val out = mutableListOf<Row>()
        context.contentResolver.query(uri, projection, selection, args, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += c.row()
        }
        return out
    }

    private fun Cursor.row() = Row(
        eventId = getLong(0),
        title = getString(1),
        begin = getLong(2),
        end = getLong(3),
        allDay = getInt(4) == 1,
        location = getString(5),
        calendar = getString(6),
        cancelled = !isNull(7) && getInt(7) == Events.STATUS_CANCELED,
        rrule = getString(8),
        originalId = if (isNull(9)) null else getLong(9),
        originalInstanceTime = if (isNull(10)) null else getLong(10),
    )

    /** One instance as CalendarContract has it. */
    data class Row(
        val eventId: Long,
        val title: String?,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String?,
        val calendar: String?,
        val cancelled: Boolean,
        val rrule: String?,
        val originalId: Long?,
        val originalInstanceTime: Long?,
    ) {
        fun covers(day: LocalDate, dayBegin: Long, dayEnd: Long): Boolean =
            if (allDay) {
                val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(ZoneOffset.UTC).toLocalDate()
                day in first..last
            } else {
                begin < dayEnd && maxOf(end, begin + 1) > dayBegin
            }

        fun toEvent(zone: ZoneId): CalendarEvent {
            val (start, finish) = CalendarKeys.times(begin, end, allDay, zone)
            return CalendarEvent(
                key = CalendarKeys.key(eventId, begin, rrule, originalId, originalInstanceTime),
                title = title.orEmpty(),
                start = start,
                end = finish,
                location = location,
                calendar = calendar,
                cancelled = cancelled,
            )
        }
    }

    private companion object {
        const val DAY_MS = 24L * 3600 * 1000
        const val WINDOW_MS = 62 * DAY_MS
    }
}

/** The stable identity and time format of an event, kept apart for testing. */
object CalendarKeys {
    data class Identity(val eventId: Long, val originalMillis: Long?)

    /**
     * `android:<event id>` for a one-off event, and
     * `android:<series id>@<original start, epoch millis>` for one occurrence
     * of a repeating event, whether or not it has since been edited on its
     * own (which makes it an exception row pointing back at the series).
     * Event ids are local to the phone, so a kept event is tracked by
     * Android devices that share the calendar provider's database, which in
     * practice means this phone.
     */
    fun key(eventId: Long, begin: Long, rrule: String?, originalId: Long?, originalInstanceTime: Long?): String = when {
        originalId != null && originalInstanceTime != null -> "android:$originalId@$originalInstanceTime"
        !rrule.isNullOrBlank() -> "android:$eventId@$begin"
        else -> "android:$eventId"
    }

    fun parse(key: String): Identity? {
        if (!key.startsWith("android:")) return null
        val rest = key.removePrefix("android:")
        val at = rest.lastIndexOf('@')
        return if (at < 0) {
            rest.toLongOrNull()?.let { Identity(it, null) }
        } else {
            val id = rest.substring(0, at).toLongOrNull() ?: return null
            val millis = rest.substring(at + 1).toLongOrNull() ?: return null
            Identity(id, millis)
        }
    }

    // Locale.ROOT: these go to the core, which wants ISO digits and calendar.
    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)

    /**
     * The core's forms: `YYYY-MM-DDTHH:MM` local for a timed event; for an
     * all-day one (stored in UTC, end exclusive) the first day and, when it
     * covers more than one, the last.
     */
    fun times(begin: Long, end: Long, allDay: Boolean, zone: ZoneId): Pair<String, String?> {
        if (allDay) {
            val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(ZoneOffset.UTC).toLocalDate()
            return first.toString() to (if (last > first) last.toString() else null)
        }
        val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), zone).format(minute)
        val to = if (end > begin) LocalDateTime.ofInstant(Instant.ofEpochMilli(end), zone).format(minute) else null
        return from to to
    }

    fun utcOrLocalDay(millis: Long, allDay: Boolean): LocalDate =
        Instant.ofEpochMilli(millis).atZone(if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate()
}
