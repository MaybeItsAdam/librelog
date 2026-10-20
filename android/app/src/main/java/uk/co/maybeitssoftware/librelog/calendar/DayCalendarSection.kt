package uk.co.maybeitssoftware.librelog.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.CalendarDay
import uniffi.librelog_core.CalendarEvent
import uniffi.librelog_core.KeptEvent
import uniffi.librelog_core.KeptEventState

/**
 * A day's calendar, in the editor and under a day in Calendar
 * (docs/calendar.md). Kept events are part of the journal, each with room for
 * a note; the calendar's other events that day are offered underneath, one
 * tap to keep. Nothing is stored until then. Mirrors
 * apple/App/Calendar/DayCalendarSection.swift.
 */
@Composable
fun DayCalendarSection(app: AppModel, date: String, modifier: Modifier = Modifier) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var day by remember(date) { mutableStateOf(CalendarDay(emptyList(), emptyList())) }
    var declined by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<KeptEvent?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        declined = !granted
        app.calendarAccessChanged()
    }

    LaunchedEffect(date, app.revision, app.calendarAccess) {
        val granted = app.calendarAccess
        val events = if (granted) app.calendar.events(date) else emptyList()
        day = runCatching {
            app.run { store ->
                if (granted) {
                    store.calendarDay(null, date, events)
                } else {
                    CalendarDay(store.keptCalendarEvents(null, date), emptyList())
                }
            }
        }.getOrNull() ?: return@LaunchedEffect
    }

    Column(modifier.fillMaxWidth().animateContentSize(motion.glide())) {
        if (day.kept.isNotEmpty() || day.suggestions.isNotEmpty()) {
            MicroLabel("Calendar", Modifier.padding(top = 6.dp, bottom = 2.dp))
        }
        day.kept.forEach { event -> KeptEventRow(app, event, onEdit = { editing = event }) }
        day.suggestions.forEach { event ->
            SuggestionRow(event) {
                Haptics.confirm(haptics)
                day = day.copy(suggestions = day.suggestions.filterNot { it.key == event.key })
                scope.launch { app.write { it.keepCalendarEvent(null, date, event) } }
            }
        }
        // Any day, past or future, with or without calendar access.
        QuietAction(Icons.Filled.Add, "Add event") { adding = true }
        if (!app.calendarAccess && !declined) {
            QuietAction(Icons.Filled.DateRange, "Show this day's calendar") { ask.launch(Manifest.permission.READ_CALENDAR) }
        }
    }

    if (adding) OwnEventSheet(app, date, editing = null) { adding = false }
    editing?.let { event -> OwnEventSheet(app, date, editing = event) { editing = null } }
}

/** A muted icon-and-words row, 48dp tall: "Add event", "Show this day's calendar". */
@Composable
private fun QuietAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = t.muted)
        Text(text, style = Typeface.small, color = t.muted, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SuggestionRow(event: CalendarEvent, onKeep: () -> Unit) {
    val t = tokens
    val title = event.title.ifBlank { "Untitled event" }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .pressable(onClick = onKeep)
            .semantics { contentDescription = "Add $title to the journal" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).border(1.dp, t.hairline, RoundedCornerShape(Radius.control)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(14.dp), tint = t.primary)
        }
        Text(
            title,
            style = Typeface.ui,
            color = t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(EventTime.label(event.start, event.end), style = Typeface.mono(11), color = t.dim, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * One kept event: title, time, a quiet tag when it moved or was cancelled,
 * and the user's note. One the user added by hand opens for editing.
 */
@Composable
private fun KeptEventRow(app: AppModel, event: KeptEvent, onEdit: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    var note by remember(event.id) { mutableStateOf(event.note.orEmpty()) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val cancelled = event.state == KeptEventState.CANCELLED

    fun save() {
        saveJob = null
        val text = note
        if (text.trim() == event.note.orEmpty()) return
        app.scope.launch { app.write { it.setCalendarEventNote(event.id, text) } }
    }

    LaunchedEffect(event.note) { if (saveJob == null) note = event.note.orEmpty() }
    DisposableEffect(event.id) { onDispose { if (saveJob != null) { saveJob?.cancel(); save() } } }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.then(if (event.isOwn) Modifier.pressable(onClick = onEdit) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (event.isOwn) Icons.Filled.Edit else Icons.Filled.DateRange,
                null,
                Modifier.size(14.dp),
                tint = if (cancelled) t.dim else t.primary,
            )
            Text(
                event.title.ifBlank { "Untitled event" },
                style = Typeface.ui.copy(fontWeight = FontWeight.Medium, textDecoration = if (cancelled) TextDecoration.LineThrough else null),
                color = if (cancelled) t.muted else t.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            when (event.state) {
                KeptEventState.MOVED -> StateTag("Moved", t.warning)
                KeptEventState.CANCELLED -> StateTag("Cancelled", t.danger)
                KeptEventState.SCHEDULED -> {}
            }
            Box {
                Box(
                    Modifier
                        .size(36.dp, 44.dp)
                        .pressable { menuOpen = true }
                        .semantics { contentDescription = "More for ${event.title}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(18.dp), tint = t.muted)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(Radius.card),
                    containerColor = t.raised,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, t.hairline),
                ) {
                    if (event.isOwn) {
                        DropdownMenuItem(
                            text = { Text("Edit event") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from this day", color = t.danger) },
                        onClick = {
                            menuOpen = false
                            scope.launch { app.write { it.removeCalendarEvent(event.id) } }
                        },
                    )
                }
            }
        }
        Text(details(event), style = Typeface.mono(11), color = t.muted, modifier = Modifier.padding(start = 22.dp))
        BasicTextField(
            value = note,
            onValueChange = {
                note = it
                saveJob?.cancel()
                saveJob = scope.launch {
                    delay(700)
                    save()
                }
            },
            textStyle = Typeface.ui.copy(color = t.ink),
            cursorBrush = SolidColor(t.primary),
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 6.dp).heightIn(min = 28.dp),
            decorationBox = { inner ->
                if (note.isEmpty()) Text("Note", style = Typeface.ui, color = t.dim)
                inner()
            },
        )
    }
    Hairline()
}

@Composable
private fun StateTag(text: String, hue: Color) {
    val shape = RoundedCornerShape(Radius.control)
    Text(
        text.uppercase(),
        style = Typeface.micro,
        color = hue,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(hue.copy(alpha = 0.1f), shape)
            .border(1.dp, hue.copy(alpha = 0.4f), shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** "09:30–10:00 · Work · Room 2", with the new day first when it moved off this one. */
private fun details(e: KeptEvent): String = buildList {
    if (e.movedToAnotherDay) add("Now ${Day.short(e.start.take(10))}")
    add(if (e.allDay) "All day" else e.timeLabel)
    e.calendar?.let(::add)
    e.location?.let(::add)
}.joinToString(" · ")

/** Times the way the core writes them: `YYYY-MM-DDTHH:MM`, or a bare date for all day. */
object EventTime {
    fun label(start: String, end: String?): String {
        if (start.length != 16) return "All day"
        val from = start.takeLast(5)
        return if (end != null && end.length == 16 && end.take(10) == start.take(10) && end.takeLast(5) != from) {
            "$from–${end.takeLast(5)}"
        } else {
            from
        }
    }
}
