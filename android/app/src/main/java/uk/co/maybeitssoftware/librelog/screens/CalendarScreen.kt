package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.drop
import uk.co.maybeitssoftware.librelog.calendar.MonthPages
import uk.co.maybeitssoftware.librelog.photos.DayPhotosSection
import uk.co.maybeitssoftware.librelog.lock.LockedNoteSection
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.calendar.DayCalendarSection
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
 * tapping one shows that day's entries, calendar and photos underneath.
 * Months are pages: swipe (the grid follows the finger and settles with a
 * spring), use the arrows, or tap the title to jump to any month.
 */
@Composable
fun CalendarScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    val m = motion
    val haptics = LocalHapticFeedback.current
    val pages = remember { MonthPages(YearMonth.now()) }
    // Its page is saved, so the shown month survives tab switches.
    val pager = rememberPagerState(initialPage = pages.lastPage) { pages.count }
    val month = pages.month(pager.currentPage)
    var selected by rememberSaveable { mutableStateOf(Day.today) }
    var dayEntries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var shownDay by remember { mutableStateOf<String?>(null) }
    var choosingMonth by remember { mutableStateOf(false) }

    LaunchedEffect(selected, app.revision) {
        val d = selected
        dayEntries = runCatching { app.run { it.day(d, null) } }.getOrDefault(emptyList())
        shownDay = d
    }
    // A tick each time a swipe settles on a new month.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { Haptics.tick(haptics) }
    }

    val scope = rememberCoroutineScope()
    val canGoForward = pager.currentPage < pages.lastPage
    fun show(page: Int) {
        if (page > pages.lastPage) {
            Haptics.reject(haptics)
            return
        }
        scope.launch {
            if (m.reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page, animationSpec = m.glide())
        }
    }

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
            // The title opens the month and year chooser.
            Column(
                Modifier
                    .weight(1f)
                    .pressable(pressedScale = 0.98f) { choosingMonth = true }
                    .semantics { contentDescription = "${Day.monthTitle(month.toString())}, choose a month" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MicroLabel("Calendar")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(month, Modifier.weight(1f, fill = false), transitionSpec = { monthSlide(m, targetState > initialState) }, label = "title") {
                        // One line: long month names shrink to fit a narrow phone.
                        val style = Typeface.title
                        BasicText(
                            Day.monthTitle(it.toString()),
                            style = style.copy(color = t.ink),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.6f, maxFontSize = style.fontSize),
                        )
                    }
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(24.dp), tint = t.muted)
                }
            }
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { show(pager.currentPage - 1) })
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", { show(pager.currentPage + 1) }, enabled = canGoForward)
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

        // The grid follows the finger and settles on a month with a spring.
        // Sideways drags over it are the months' alone: the tabs' pager
        // underneath never takes them, even at this month's end.
        HorizontalPager(
            pager,
            modifier = Modifier.fillMaxWidth().nestedScroll(KeepHorizontal),
            beyondViewportPageCount = 1,
            key = { it },
            flingBehavior = PagerDefaults.flingBehavior(
                pager,
                snapAnimationSpec = if (m.reduced) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            ),
        ) { page ->
            MonthGrid(app, pages.month(page), firstDay, selected, t, m) { date ->
                if (selected != date) Haptics.tick(haptics)
                selected = date
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
                shownDay?.let {
                    DayCalendarSection(app, it, Modifier.padding(top = 8.dp))
                    DayPhotosSection(app, it, Modifier.padding(top = 4.dp))
                    LockedNoteSection(app, it)
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

    if (choosingMonth) {
        MonthChooser(pages, month, onDismiss = { choosingMonth = false }) { picked ->
            choosingMonth = false
            Haptics.tick(haptics)
            show(pages.page(picked))
        }
    }
}

/** Months slide sideways: later months come in from the right. */
/** Takes every sideways scroll and fling the month pager leaves over. */
private object KeepHorizontal : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

/** One month's six weeks, with its own marks, loaded as the page comes in. */
@Composable
private fun MonthGrid(app: AppModel, month: YearMonth, firstDay: DayOfWeek, selected: String, t: Tokens, m: Motion, onSelect: (String) -> Unit) {
    var marks by remember(month) { mutableStateOf<Map<String, DayMark>>(emptyMap()) }
    LaunchedEffect(month, app.revision) {
        val ym = month.toString()
        marks = runCatching { app.run { it.month(ym, null) } }.getOrDefault(emptyList()).associateBy { it.date }
    }
    val today = Day.today
    Column {
        MonthPages.cells(month, firstDay).chunked(7).forEach { week ->
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
                                DayCell(date, mark, selected == date, date == today, date > today, t, m) { onSelect(date) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Jump to any month: a year stepper over the twelve months. Months after
 * this one can't be chosen.
 */
@Composable
private fun MonthChooser(pages: MonthPages, shown: YearMonth, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    var year by remember { mutableStateOf(shown.year) }
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MicroLabel("Go to month")
                Text(year.toString(), style = Typeface.headline, color = t.ink)
            }
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year", {
                Haptics.tick(haptics)
                year--
            }, enabled = year > pages.earliest.year)
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year", {
                Haptics.tick(haptics)
                year++
            }, enabled = year < pages.latest.year)
        }
        (1..12).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { n ->
                    val ym = YearMonth.of(year, n)
                    val enabled = pages.contains(ym)
                    val current = ym == shown
                    val shape = RoundedCornerShape(Radius.control)
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .pressable(enabled = enabled) { onPick(ym) }
                            .background(if (current) t.primary.copy(alpha = 0.1f) else Color.Transparent, shape)
                            .border(if (current) 1.5.dp else 1.dp, if (current) t.primary else t.hairline, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ym.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = Typeface.ui.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                            color = when {
                                !enabled -> t.dim
                                current -> t.primary
                                else -> t.ink
                            },
                        )
                    }
                }
            }
        }
    }
}

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
