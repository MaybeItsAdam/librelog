package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.runtime.saveable.rememberSaveable
import uk.co.maybeitssoftware.librelog.ui.NewEntryButton
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SectionHeader
import uk.co.maybeitssoftware.librelog.ui.Tokens
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.DayMark
import uniffi.librelog_core.EntrySummary

/**
 * Month grid (SPEC.md §6.3). Days with entries are tinted by their rating;
 * tapping one shows that day's entries underneath.
 */
@Composable
fun CalendarScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var marks by remember { mutableStateOf<Map<String, DayMark>>(emptyMap()) }
    var selected by rememberSaveable { mutableStateOf(Day.today) }
    var dayEntries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }

    LaunchedEffect(month, app.revision) {
        val ym = month.toString()
        marks = runCatching { app.run { it.month(ym, null) } }.getOrDefault(emptyList()).associateBy { it.date }
    }
    LaunchedEffect(selected, app.revision) {
        val d = selected
        dayEntries = runCatching { app.run { it.day(d, null) } }.getOrDefault(emptyList())
    }

    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(t.paper)) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 100.dp)
            .widthIn(max = 720.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MicroLabel("Calendar")
                Text(Day.monthTitle(month.toString()), style = Typeface.title, color = t.ink)
            }
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { month = month.minusMonths(1) })
            IconAction(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month",
                { month = month.plusMonths(1) },
                enabled = month < YearMonth.now(),
            )
        }

        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        // Weekday header
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            for (i in 0 until 7) {
                val dow = firstDay.plus(i.toLong())
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    MicroLabel(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()))
                }
            }
        }
        Hairline()

        val cells = cells(month, firstDay)
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                        if (date != null) {
                            DayCell(date, marks[date], selected == date, date == Day.today, date > Day.today, t) { selected = date }
                        }
                    }
                }
            }
        }

        SectionHeader(Day.title(selected), trailing = if (dayEntries.isEmpty()) null else dayEntries.size.toString()) {
            if (dayEntries.isEmpty()) {
                Text("Nothing written.", style = Typeface.small, color = t.muted, modifier = Modifier.padding(vertical = 14.dp))
            }
            dayEntries.forEach { e ->
                EntryRow(e, onClick = { openEntry(e.id) }, showDate = false)
                Hairline()
            }
        }
    }
    // New entry on the selected day; untimed unless it's today.
    NewEntryButton({ scope.launch { app.newEntry(selected)?.let(openEntry) } }, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun DayCell(date: String, mark: DayMark?, selected: Boolean, today: Boolean, future: Boolean, t: Tokens, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.control)
    val tint = mark?.let { ratingColor(it.rating, t) }
    Box(
        Modifier
            .fillMaxSize()
            .background(tint?.copy(alpha = 0.14f) ?: Color.Transparent, shape)
            .border(1.dp, if (selected) t.primary else tint?.copy(alpha = 0.4f) ?: Color.Transparent, shape)
            .clickable(enabled = !future, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.takeLast(2).trimStart('0'),
            style = Typeface.mono(13, if (today) FontWeight.Bold else FontWeight.Normal),
            color = when {
                future -> t.dim
                today -> t.primary
                else -> t.ink
            },
        )
        if (mark != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(4.dp)
                    .background(tint ?: t.muted, CircleShape),
            )
        }
    }
}

private fun ratingColor(rating: Double?, t: Tokens): Color = when {
    rating == null -> t.muted
    rating < 2 -> t.danger
    rating < 3.5 -> t.warning
    else -> t.success
}

/** Leading blanks, then each day of the month as `YYYY-MM-DD`. */
private fun cells(month: YearMonth, firstDay: DayOfWeek): List<String?> {
    val first = month.atDay(1)
    val lead = Math.floorMod(first.dayOfWeek.value - firstDay.value, 7)
    val days = (1..month.lengthOfMonth()).map { Day.string(LocalDate.of(month.year, month.month, it)) }
    val all = List(lead) { null } + days
    return all + List((7 - all.size % 7) % 7) { null }
}
