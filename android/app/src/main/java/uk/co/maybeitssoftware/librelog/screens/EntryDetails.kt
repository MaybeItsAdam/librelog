package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.calendar.TimePickDialog
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.ToggleRow
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.Entry

/** The editor's one line of metadata under the title, and the sums behind Details. */
object EntryMeta {
    /**
     * "Tue 14 Oct · 09:30", or "14 – 16 Oct · 09:30 · 3 days" for an entry
     * covering several days. The year shows when it isn't [thisYear]. Shown
     * as a micro-label, so it's uppercased there.
     */
    fun line(date: String, time: String?, endDate: String?, thisYear: Int = LocalDate.now().year): String {
        val start = Day.date(date) ?: return listOfNotNull(date, time).joinToString(" · ")
        val days = days(date, endDate)
        val day = if (days > 1) {
            Day.range(date, endDate, thisYear)
        } else {
            DateTimeFormatter.ofPattern(if (start.year == thisYear) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()).format(start)
        }
        return listOfNotNull(day, time, if (days > 1) "$days days" else null).joinToString(" · ")
    }

    /** How many days an entry covers, counting both ends; 1 without a later end. */
    fun days(date: String, endDate: String?): Int {
        val a = Day.date(date) ?: return 1
        val b = endDate?.let(Day::date)?.takeIf { it > a } ?: return 1
        return ChronoUnit.DAYS.between(a, b).toInt() + 1
    }

    /** Where the end goes when the start moves to [newDate]: the same length on. */
    fun movedEnd(date: String, endDate: String?, newDate: String): String? {
        val n = days(date, endDate)
        return if (n > 1) Day.adding((n - 1).toLong(), newDate) else null
    }
}

/**
 * Details, behind the editor's metadata line: the day and time, several
 * days, favourite. None of it needs to be in view while writing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailsSheet(
    entry: Entry,
    onDismiss: () -> Unit,
    onDateTime: (date: String, time: String?) -> Unit,
    onEndDate: (String?) -> Unit,
    onFavorite: (Boolean) -> Unit,
) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    var picking by remember { mutableStateOf<String?>(null) } // "day", "time", "end"
    val first = Day.date(entry.date) ?: return

    Sheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicroLabel("Details")
            Text(entry.title?.ifBlank { null } ?: "Untitled entry", style = Typeface.headline, color = t.ink, maxLines = 2)
        }
        Column {
            Hairline()
            ValueRow("Day", Day.longDate(entry.date)) { picking = "day" }
            Hairline()
            ToggleRow("Time", entry.time != null, { on -> onDateTime(entry.date, if (on) Day.nowTime else null) })
            entry.time?.let { time ->
                ValueRow("At", time, mono = true) { picking = "time" }
            }
            Hairline()
            ToggleRow(
                "Several days",
                entry.endDate != null,
                { on -> onEndDate(if (on) Day.string(first.plusDays(1)) else null) },
                detail = "For a trip or a festival, written up as one entry.",
            )
            entry.endDate?.let { end ->
                ValueRow("Last day", Day.longDate(end)) { picking = "end" }
            }
            Hairline()
            ToggleRow("Favourite", entry.isFavorite, onFavorite)
            Hairline()
        }
        PrimaryButton("Done", onDismiss)
    }

    when (picking) {
        "time" -> TimePickDialog("Time", entry.time, onDismiss = { picking = null }) { time ->
            picking = null
            Haptics.tick(haptics)
            onDateTime(entry.date, time)
        }
        "day" -> DayPickDialog("Day", first, null, onDismiss = { picking = null }) { day ->
            picking = null
            Haptics.tick(haptics)
            onDateTime(Day.string(day), entry.time)
        }
        "end" -> DayPickDialog("Last day", entry.endDate?.let(Day::date) ?: first.plusDays(1), first, onDismiss = { picking = null }) { day ->
            picking = null
            Haptics.tick(haptics)
            onEndDate(Day.string(day))
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, mono: Boolean = false, onClick: () -> Unit) {
    val t = tokens
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Typeface.ui, color = t.ink, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (mono) Typeface.mono(15) else Typeface.small,
            color = t.ink,
            maxLines = 1,
            modifier = Modifier
                .pressable(onClick = onClick)
                .border(1.dp, t.input, RoundedCornerShape(Radius.control))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label: $value. Change" },
        )
    }
}

/**
 * A day picker. With [after], only the year following it can be chosen: the
 * last day of an entry covering several days (SPEC.md §4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickDialog(title: String, initial: LocalDate, after: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val t = tokens
    // The picker speaks UTC midnight; a LocalDate is a calendar day either way.
    val toMillis = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val min = after?.let { toMillis(it.plusDays(1)) }
    val max = after?.let { toMillis(it.plusDays(365)) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = toMillis(initial),
        yearRange = if (after != null) after.year..after.plusDays(365).year else 1900..(LocalDate.now().year + 10),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = min == null || max == null || utcTimeMillis in min..max
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(Radius.card),
        tonalElevation = 0.dp,
        colors = DatePickerDefaults.colors(containerColor = t.raised),
    ) {
        DatePicker(state, title = { MicroLabel(title, Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = false)
    }
}
