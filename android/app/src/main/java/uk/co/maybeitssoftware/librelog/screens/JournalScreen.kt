package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.photos.DayPhotosSection
import uk.co.maybeitssoftware.librelog.photos.DayPreview
import uk.co.maybeitssoftware.librelog.photos.DayThumb
import uk.co.maybeitssoftware.librelog.lock.LockedNoteSection
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Motion
import uk.co.maybeitssoftware.librelog.ui.NewEntryButton
import uk.co.maybeitssoftware.librelog.ui.Reveal
import uk.co.maybeitssoftware.librelog.ui.Stagger
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.rowMotion
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.DayPhoto
import uniffi.librelog_core.EntrySummary
import uniffi.librelog_core.TimelineCursor
import uniffi.librelog_core.TimelineFilter

private const val PAGE = 50u

/**
 * Every entry, newest first, grouped by day, with today always on top
 * (SPEC.md §6.1, §6.4) and its photos under its entries. A day with photos
 * shows its preview on the left of its first row (DayPreview). Holding the
 * new-entry button writes up another day. On This Day has its own button in
 * the bottom bar.
 */
@Composable
fun JournalScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    val m = motion
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var entries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var done by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var previews by remember { mutableStateOf<Map<String, DayPhoto>>(emptyMap()) }
    var addPhotoRequests by remember { mutableIntStateOf(0) }
    var choosingDay by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val today = Day.today
    val filter = TimelineFilter(null, emptyList(), null, false)

    suspend fun page(after: TimelineCursor?) =
        runCatching { app.run { it.timeline(filter, after, PAGE) } }.getOrDefault(emptyList())

    LaunchedEffect(app.revision, today) {
        val first = page(null)
        previews = DayPreview.load(app, first.map { it.date } + today)
        entries = first
        done = first.size < PAGE.toInt()
    }
    val nearEnd by remember {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 10 }
    }
    LaunchedEffect(nearEnd, entries.size) {
        if (!nearEnd || done || loading || entries.isEmpty()) return@LaunchedEffect
        loading = true
        val last = entries.last()
        val more = page(TimelineCursor(last.date, last.time, last.id))
        previews = previews + DayPreview.load(app, more.map { it.date })
        entries = entries + more
        done = more.size < PAGE.toInt()
        loading = false
    }

    // Rows rise in, staggered, the first time the journal loads; after that
    // (new pages, tab switches, coming back from an entry) they just appear.
    val loaded = entries.isNotEmpty() || done
    var introDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loaded) {
        if (loaded) {
            delay(700)
            introDone = true
        }
    }

    fun newEntry(date: String = today, time: String? = Day.defaultTime(date)) = scope.launch {
        app.newEntry(date, time)?.let {
            Haptics.confirm(haptics)
            openEntry(it)
        }
    }


    // Today first, then every other day in timeline order.
    val days = entries.groupBy { it.date }
    val otherDays = days.keys.filter { it != today }

    Box(Modifier.fillMaxSize().background(t.paper), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        ) {
            val stagger = Stagger(active = !introDone && loaded)
            // The day's name, large, then its full date: the screen opens on
            // today, so there's no separate "Journal" heading above it.
            item(key = "title") {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Day.relative(today), style = Typeface.title, color = t.ink)
                    Text(Day.longDate(today), style = Typeface.small, color = t.muted)
                }
            }

            dayGroup(
                today, days[today].orEmpty(), previews[today], openEntry, m, stagger,
                onNewEntry = { newEntry() }, onAddPhoto = { addPhotoRequests++ }, titled = false,
            )
            // Adding is the icon in today's rule, so no worded button here.
            item(key = "photos$today") {
                DayPhotosSection(app, today, rowMotion(m).padding(top = 4.dp), showAdd = false, addRequests = addPhotoRequests)
            }
            item(key = "locked$today") {
                LockedNoteSection(app, today, rowMotion(m))
            }

            otherDays.forEach { date -> dayGroup(date, days.getValue(date), previews[date], openEntry, m, stagger) }

            if (entries.isEmpty() && done) {
                item(key = "empty") {
                    Text(
                        "Everything you write appears here, newest first.",
                        style = Typeface.small,
                        color = t.muted,
                        modifier = rowMotion(m).padding(vertical = 20.dp),
                    )
                }
            }
        }

        NewEntryButton({ newEntry() }, Modifier.align(Alignment.BottomEnd), onLongClick = { choosingDay = true })
    }

    if (choosingDay) {
        NewEntrySheet(today, onDismiss = { choosingDay = false }) { date, time ->
            choosingDay = false
            newEntry(date, time)
        }
    }
}

/**
 * A sticky day header and that day's entries, the first beside the day's
 * [preview] photo. Today gets a quiet "New entry" row when empty, and
 * [onAddPhoto] as an unworded icon set into its rule: between today's
 * heading and its entries, it can only mean today.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.dayGroup(
    date: String,
    entries: List<EntrySummary>,
    preview: DayPhoto?,
    openEntry: (String) -> Unit,
    m: Motion,
    stagger: Stagger,
    onNewEntry: (() -> Unit)? = null,
    onAddPhoto: (() -> Unit)? = null,
    titled: Boolean = true,
) {
    val headerIndex = stagger.take()
    // Today's group sits under the screen's own title, so it needs only the rule.
    stickyHeader(key = "d$date") {
        val t = tokens
        Reveal(headerIndex, stagger.active, Modifier.fillMaxWidth().background(t.paper).padding(top = if (titled) 20.dp else 12.dp)) {
            if (titled) MicroLabel(Day.title(date), Modifier.padding(bottom = 6.dp))
            if (onAddPhoto != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hairline(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .size(48.dp)
                            .pressable(onClick = onAddPhoto)
                            .semantics { contentDescription = "Add photo to today" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, null, Modifier.size(20.dp), tint = t.muted)
                    }
                    Hairline(Modifier.weight(1f))
                }
            } else {
                Hairline()
            }
        }
    }
    if (entries.isEmpty() && onNewEntry != null) {
        val index = stagger.take()
        item(key = "new$date") {
            val t = tokens
            Reveal(index, stagger.active, rowMotion(m)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .pressable(onClick = onNewEntry),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    preview?.let { DayThumb(it, Modifier.padding(vertical = 8.dp)) }
                    Text("New entry", style = Typeface.small, color = t.muted, modifier = Modifier.padding(vertical = 13.dp))
                }
                Hairline()
            }
        }
    }
    entries.forEachIndexed { i, e ->
        val index = stagger.take()
        item(key = e.id) {
            Reveal(index, stagger.active, rowMotion(m)) {
                // The photo sits beside the day's first row only; later rows start at the margin.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (i == 0) preview?.let { DayThumb(it, Modifier.padding(top = 12.dp)) }
                    EntryRow(e, onClick = { openEntry(e.id) }, showDate = false)
                }
                Hairline()
            }
        }
    }
}
