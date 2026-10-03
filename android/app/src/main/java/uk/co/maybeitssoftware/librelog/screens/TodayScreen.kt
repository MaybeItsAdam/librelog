package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Prompts
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SectionHeader
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.VerticalHairline
import uk.co.maybeitssoftware.librelog.ui.card
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.EntrySummary
import uniffi.librelog_core.Stats

/**
 * The start screen (SPEC.md §6.1): the selected day's entries, a writing
 * prompt when it's empty, On This Day, and the streak.
 */
@Composable
fun TodayScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var memories by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val date = app.selectedDate

    LaunchedEffect(date, app.revision) {
        val today = Day.today
        runCatching {
            app.run { Triple(it.day(date, null), it.onThisDay(date), it.stats(today)) }
        }.onSuccess { (e, m, s) ->
            entries = e
            memories = m
            stats = s
        }
        loaded = true
    }

    fun newEntry() = scope.launch { app.newEntry()?.let(openEntry) }

    Box(Modifier.fillMaxSize().background(t.paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(date) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -120 && date < Day.today) app.selectedDate = Day.adding(1, date)
                            else if (total > 120) app.selectedDate = Day.adding(-1, date)
                        },
                    ) { _, dx -> total += dx }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 100.dp)
                .widthIn(max = 720.dp),
        ) {
            // Day header
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MicroLabel(if (date == Day.today) Day.weekday(date) else "Journal")
                    Text(Day.title(date), style = Typeface.title, color = t.ink)
                }
                IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", { app.selectedDate = Day.adding(-1, date) })
                if (date != Day.today) {
                    Text(
                        "Today",
                        style = Typeface.small.copy(fontWeight = FontWeight.Medium),
                        color = t.primary,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { app.selectedDate = Day.today }
                            .padding(horizontal = 6.dp, vertical = 13.dp),
                    )
                }
                IconAction(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day",
                    { app.selectedDate = Day.adding(1, date) },
                    enabled = date < Day.today,
                )
            }

            // Write card
            if (entries.isEmpty()) {
                if (loaded) {
                    Column(
                        Modifier.card().clickable(role = Role.Button) { newEntry() },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MicroLabel("Write")
                        Text(Prompts.today(date), style = Typeface.prompt, color = t.ink)
                        Text(
                            "Start writing",
                            style = Typeface.small.copy(fontWeight = FontWeight.SemiBold),
                            color = t.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                Column(Modifier.card(androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp))) {
                    entries.forEachIndexed { i, e ->
                        EntryRow(e, onClick = { openEntry(e.id) }, showDate = false)
                        if (i < entries.lastIndex) Hairline()
                    }
                }
            }

            // On this day
            if (memories.isNotEmpty()) {
                SectionHeader("On this day", trailing = memories.size.toString()) {
                    memories.take(5).forEach { e ->
                        val years = Day.yearsAgo(e.date, date)
                        Text(
                            if (years == 1) "1 year ago" else "$years years ago",
                            style = Typeface.mono(10),
                            color = t.primary,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        EntryRow(e, onClick = { openEntry(e.id) })
                        Hairline()
                    }
                }
            }

            // Streak
            stats?.let { s ->
                SectionHeader("Streak") {
                    Row(Modifier.fillMaxWidth().height(64.dp)) {
                        Metric(s.currentStreak.toString(), "days running", Modifier.weight(1f))
                        VerticalHairline()
                        Metric(s.longestStreak.toString(), "longest", Modifier.weight(1f))
                        VerticalHairline()
                        Metric(compact(s.words), "words", Modifier.weight(1f))
                    }
                }
            }
        }

        // New entry: a square-cornered azure button, no shadow.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .background(t.primary, RoundedCornerShape(Radius.card))
                .clickable(role = Role.Button) { newEntry() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, "New entry", Modifier.size(26.dp), tint = Color.White)
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier) {
    val t = tokens
    Column(modifier.padding(start = 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = Typeface.mono(20), color = t.ink)
        Text(label, style = Typeface.small.copy(fontSize = Typeface.small.fontSize * 0.85f), color = t.muted)
    }
}

private fun compact(n: ULong): String = when {
    n >= 1_000_000u -> "%.1fM".format(n.toDouble() / 1_000_000)
    n >= 10_000u -> "${n / 1_000u}K"
    n >= 1_000u -> "%.1fK".format(n.toDouble() / 1_000)
    else -> n.toString()
}
