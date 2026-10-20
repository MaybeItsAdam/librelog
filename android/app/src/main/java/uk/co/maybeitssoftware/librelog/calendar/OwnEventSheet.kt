package uk.co.maybeitssoftware.librelog.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.screens.Sheet
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.InputField
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SecondaryButton
import uk.co.maybeitssoftware.librelog.ui.ToggleRow
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.KeptEvent

/**
 * Add or change an event of the user's own on [date], any day past or
 * future (docs/calendar.md, "Events you add yourself"). [editing] is the own
 * event to change; without it the sheet adds one. The core's own checks show
 * under the fields rather than as a banner elsewhere.
 */
@Composable
fun OwnEventSheet(app: AppModel, date: String, editing: KeptEvent?, onDismiss: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var form by remember(editing?.id) { mutableStateOf(editing?.let(OwnEventForm::from) ?: OwnEventForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" or "end"

    fun commit(work: (uniffi.librelog_core.Store) -> Unit) {
        busy = true
        scope.launch {
            try {
                app.run(work)
                Haptics.confirm(haptics)
                app.changed()
                app.sync.schedulePush()
                onDismiss()
            } catch (e: Exception) {
                Haptics.reject(haptics)
                error = OwnEventForm.message(e)
            } finally {
                busy = false
            }
        }
    }

    Sheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicroLabel(Day.title(date))
            Text(if (editing == null) "Add event" else "Edit event", style = Typeface.headline, color = t.ink)
        }
        InputField(
            form.title,
            { form = form.copy(title = it); error = null },
            "Title",
            error = tried && form.title.isBlank(),
        )
        Column {
            Hairline()
            ToggleRow("All day", form.allDay, {
                form = form.copy(allDay = it, start = if (it) form.start else form.start ?: defaultStart(date))
                error = null
            })
            if (!form.allDay) {
                Hairline()
                TimeRow("Starts", form.start, "Choose") { picking = "start" }
                Hairline()
                TimeRow("Ends", form.end, "Optional", onClear = if (form.end != null) ({ form = form.copy(end = null) }) else null) {
                    picking = "end"
                }
            }
            Hairline()
        }
        InputField(form.place, { form = form.copy(place = it) }, "Place (optional)", capitalization = KeyboardCapitalization.Words)

        val shown = error ?: form.problem?.takeIf { tried || (form.title.isNotBlank() && !form.allDay) }
        shown?.let { Text(it, style = Typeface.small, color = t.danger) }

        PrimaryButton(
            if (editing == null) "Add to this day" else "Save",
            onClick = {
                tried = true
                if (!form.canSave || busy) return@PrimaryButton
                val event = form.toOwnEvent()
                if (editing == null) {
                    commit { it.addOwnEvent(null, date, event) }
                } else {
                    commit { it.updateOwnEvent(editing.id, event) }
                }
            },
            enabled = !busy,
        )
        if (editing != null) {
            SecondaryButton("Remove from this day", { if (!busy) commit { it.removeCalendarEvent(editing.id) } }, color = t.danger)
        }
    }

    picking?.let { which ->
        val current = if (which == "start") form.start else form.end ?: form.start?.let(::hourLater)
        TimePickDialog(
            title = if (which == "start") "Starts" else "Ends",
            initial = current,
            onDismiss = { picking = null },
        ) { time ->
            picking = null
            Haptics.tick(haptics)
            error = null
            form = if (which == "start") form.copy(start = time) else form.copy(end = time)
        }
    }
}

/** Today's next whole hour; otherwise nine in the morning. */
private fun defaultStart(date: String): String =
    if (date == Day.today) OwnEventForm.hhmm((LocalTime.now().hour + 1).coerceAtMost(23), 0) else "09:00"

private fun hourLater(start: String): String? =
    OwnEventForm.parts(start)?.let { (h, m) -> if (h < 23) OwnEventForm.hhmm(h + 1, m) else null }

@Composable
private fun TimeRow(label: String, value: String?, empty: String, onClear: (() -> Unit)? = null, onPick: () -> Unit) {
    val t = tokens
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Typeface.ui, color = t.ink, modifier = Modifier.weight(1f))
        if (onClear != null) {
            Text(
                "Clear",
                style = Typeface.small,
                color = t.muted,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .pressable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
        Text(
            value ?: empty,
            style = if (value != null) Typeface.mono(15) else Typeface.small,
            color = if (value != null) t.ink else t.muted,
            modifier = Modifier
                .pressable(onClick = onPick)
                .border(1.dp, t.input, RoundedCornerShape(Radius.control))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label: ${value ?: empty}" },
        )
    }
}

/** A Material clock in the house colours; 24-hour when the phone is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(title: String, initial: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val (h, m) = OwnEventForm.parts(initial) ?: (9 to 0)
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { MicroLabel(title) },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = t.well,
                    clockDialSelectedContentColor = t.onPrimary,
                    clockDialUnselectedContentColor = t.ink,
                    selectorColor = t.primary,
                    containerColor = t.raised,
                    periodSelectorBorderColor = t.input,
                    periodSelectorSelectedContainerColor = t.primary.copy(alpha = 0.1f),
                    periodSelectorUnselectedContainerColor = t.raised,
                    periodSelectorSelectedContentColor = t.primary,
                    periodSelectorUnselectedContentColor = t.muted,
                    timeSelectorSelectedContainerColor = t.primary.copy(alpha = 0.1f),
                    timeSelectorUnselectedContainerColor = t.well,
                    timeSelectorSelectedContentColor = t.primary,
                    timeSelectorUnselectedContentColor = t.ink,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onPick(OwnEventForm.hhmm(state.hour, state.minute)) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(Radius.card),
        containerColor = t.raised,
        tonalElevation = 0.dp,
    )
}
