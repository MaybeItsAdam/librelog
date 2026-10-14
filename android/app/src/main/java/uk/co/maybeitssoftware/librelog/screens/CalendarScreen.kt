package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Motion
import uk.co.maybeitssoftware.librelog.ui.NewEntryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.Reveal
import uk.co.maybeitssoftware.librelog.ui.SectionHeader
import uk.co.maybeitssoftware.librelog.ui.Tokens
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.DayMark
import uniffi.librelog_core.EntrySummary

/**
 * Month grid (SPEC.md §6.3). Days with entries are tinted by their rating;
 * tapping one shows that day's entries underneath. Swipe or use the arrows
 * to change month.
 */
@Composable
fun CalendarScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    val m = motion
    val haptics = LocalHapticFeedback.current
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var marks by remember { mutableStateOf<Map<String, DayMark>>(emptyMap()) }
    var selected by rememberSaveable { mutableStateOf(Day.today) }
    var dayEntries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var shownDay by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(month, app.revision) {
        val ym = month.toString()
        marks = runCatching { app.run { it.month(ym, null) } }.getOrDefault(emptyList()).associateBy { it.date }
    }
    LaunchedEffect(selected, app.revision) {
        val d = selected
        dayEntries = runCatching { app.run { it.day(d, null) } }.getOrDefault(emptyList())
        shownDay = d
    }

    val canGoForward = month < YearMonth.now()
    fun step(by: Long) {
        if (by > 0 && !canGoForward) {
            Haptics.reject(haptics)
            return
        }
        Haptics.tick(haptics)
        month = month.plusMonths(by)
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
                AnimatedContent(month, transitionSpec = { monthSlide(m, targetState > initialState) }, label = "title") {
                    Text(Day.monthTitle(it.toString()), style = Typeface.title, color = t.ink)
                }
            }
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { step(-1) })
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", { step(1) }, enabled = canGoForward)
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

        // The grid follows a horizontal swipe a little, then changes month
        // once it passes the threshold.
        val drag = remember { Animatable(0f) }
        AnimatedContent(
            month,
            transitionSpec = { monthSlide(m, targetState > initialState) },
            modifier = Modifier
                .graphicsLayer { translationX = drag.value }
                .pointerInput(canGoForward) {
                    val threshold = 64.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val d = drag.value
                            if (d <= -threshold) step(1) else if (d >= threshold) step(-1)
                            scope.launch { drag.animateTo(0f, m.glide()) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, m.glide()) } },
                    ) { change, amount ->
                        change.consume()
                        // Resists past the threshold, and towards future months.
                        val resist = if (drag.value < 0 && !canGoForward) 0.15f else 0.4f
                        scope.launch { drag.snapTo(drag.value + amount * resist) }
                    }
                },
            label = "grid",
        ) { shown ->
            Column {
                cells(shown, firstDay).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEachIndexed { col, date ->
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                                if (date != null) {
                                    val mark = marks[date]
                                    // Joins the neighbouring cell's band unless
                                    // the week row (or the month) ends there.
                                    SpanBand(
                                        visible = mark != null && (mark.spanBefore || mark.spanAfter),
                                        joinStart = mark?.spanBefore == true && col > 0 && week[col - 1] != null,
                                        joinEnd = mark?.spanAfter == true && col < 6 && week[col + 1] != null,
                                        t = t,
                                        m = m,
                                    )
                                    Box(Modifier.padding(2.dp)) {
                                        DayCell(date, mark, selected == date, date == Day.today, date > Day.today, t, m) {
                                            if (selected != date) Haptics.tick(haptics)
                                            selected = date
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionHeader(Day.title(selected), trailing = if (dayEntries.isEmpty()) null else dayEntries.size.toString()) {
            // Waits for the chosen day's entries before swapping, so the old
            // day's rows don't flash under the new title.
            Column(Modifier.animateContentSize(m.glide())) {
                AnimatedContent(
                    shownDay to dayEntries,
                    transitionSpec = { fadeIn(m.standard()) togetherWith fadeOut(m.quick()) },
                    contentKey = { it.first },
                    label = "day",
                ) { (_, list) ->
                    Column {
                        if (list.isEmpty()) {
                            Text("Nothing written.", style = Typeface.small, color = t.muted, modifier = Modifier.padding(vertical = 14.dp))
                        }
                        list.forEachIndexed { i, e ->
                            Reveal(i, active = true) {
                                EntryRow(e, onClick = { openEntry(e.id) }, showDate = false, onDay = shownDay)
                                Hairline()
                            }
                        }
                    }
                }
            }
        }
    }
    // New entry on the selected day; untimed unless it's today.
    NewEntryButton(
        {
            scope.launch {
                app.newEntry(selected)?.let {
                    Haptics.confirm(haptics)
                    openEntry(it)
                }
            }
        },
        Modifier.align(Alignment.BottomEnd),
    )
    }
}

/** Months slide sideways: later months come in from the right. */
private fun monthSlide(m: Motion, forward: Boolean): ContentTransform {
    if (m.reduced) return EnterTransition.None togetherWith ExitTransition.None
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(m.glide()) { dir * it / 4 } + fadeIn(m.standard()))
        .togetherWith(slideOutHorizontally(m.glide()) { -dir * it / 4 } + fadeOut(m.quick()))
}

@Composable
private fun DayCell(date: String, mark: DayMark?, selected: Boolean, today: Boolean, future: Boolean, t: Tokens, m: Motion, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.control)
    val tint = mark?.let { ratingColor(it.rating, t) }
    val fill by animateColorAsState(tint?.copy(alpha = 0.14f) ?: Color.Transparent, m.standard(), label = "fill")
    val ring by animateColorAsState(
        if (selected) t.primary else tint?.copy(alpha = 0.4f) ?: Color.Transparent,
        m.quick(),
        label = "ring",
    )
    Box(
        Modifier
            .fillMaxSize()
            .pressable(enabled = !future, pressedScale = 0.88f, onClick = onClick)
            .background(fill, shape)
            .border(if (selected) 1.5.dp else 1.dp, ring, shape),
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
        androidx.compose.animation.AnimatedVisibility(
            visible = mark != null,
            enter = if (m.reduced) EnterTransition.None else scaleIn(m.settle()) + fadeIn(m.quick()),
            exit = fadeOut(m.quick()),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
        ) {
            Box(Modifier.size(4.dp).background(tint ?: t.muted, CircleShape))
        }
    }
}

/**
 * The band behind days a multi-day entry covers: azure at low alpha, square
 * where it runs on into the next cell, rounded where it starts or stops.
 */
@Composable
private fun BoxScope.SpanBand(visible: Boolean, joinStart: Boolean, joinEnd: Boolean, t: Tokens, m: Motion) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, m.standard(), label = "band")
    if (alpha == 0f) return
    val r = Radius.control
    Box(
        Modifier
            .matchParentSize()
            .padding(start = if (joinStart) 0.dp else 2.dp, end = if (joinEnd) 0.dp else 2.dp, top = 2.dp, bottom = 2.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(
                t.primary.copy(alpha = 0.12f),
                RoundedCornerShape(
                    topStart = if (joinStart) 0.dp else r,
                    bottomStart = if (joinStart) 0.dp else r,
                    topEnd = if (joinEnd) 0.dp else r,
                    bottomEnd = if (joinEnd) 0.dp else r,
                ),
            ),
    )
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
