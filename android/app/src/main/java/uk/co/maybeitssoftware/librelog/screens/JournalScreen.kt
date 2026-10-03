package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.NewEntryButton
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.EntrySummary
import uniffi.librelog_core.TimelineCursor
import uniffi.librelog_core.TimelineFilter

private const val PAGE = 50u

/**
 * Every entry, newest first, grouped by day, with today always on top
 * (SPEC.md §6.1, §6.4). On This Day folds in under today when there is any.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JournalScreen(app: AppModel, openEntry: (String) -> Unit, openSettings: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var memories by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var memoriesOpen by rememberSaveable { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val today = Day.today
    val filter = TimelineFilter(null, emptyList(), null, false)

    suspend fun page(after: TimelineCursor?) =
        runCatching { app.run { it.timeline(filter, after, PAGE) } }.getOrDefault(emptyList())

    LaunchedEffect(app.revision, today) {
        val first = page(null)
        entries = first
        done = first.size < PAGE.toInt()
        memories = runCatching { app.run { it.onThisDay(today) } }.getOrDefault(emptyList())
    }
    val nearEnd by remember {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 10 }
    }
    LaunchedEffect(nearEnd, entries.size) {
        if (!nearEnd || done || loading || entries.isEmpty()) return@LaunchedEffect
        loading = true
        val last = entries.last()
        val more = page(TimelineCursor(last.date, last.time, last.id))
        entries = entries + more
        done = more.size < PAGE.toInt()
        loading = false
    }

    fun newEntry() = scope.launch { app.newEntry(today)?.let(openEntry) }

    // Today first, then every other day in timeline order.
    val days = entries.groupBy { it.date }
    val otherDays = days.keys.filter { it != today }

    Box(Modifier.fillMaxSize().background(t.paper), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        ) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MicroLabel(Day.weekday(today))
                        Text("Journal", style = Typeface.title, color = t.ink)
                    }
                    IconAction(Icons.Outlined.Settings, "Settings", openSettings, tint = t.muted)
                }
            }

            dayGroup(today, days[today].orEmpty(), openEntry, onNewEntry = { newEntry() })

            if (memories.isNotEmpty()) {
                item(key = "otd") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { memoriesOpen = !memoriesOpen }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("On this day · ${memories.size}", style = Typeface.small, color = t.primary, modifier = Modifier.weight(1f))
                        Icon(
                            if (memoriesOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            if (memoriesOpen) "Hide" else "Show",
                            Modifier.size(20.dp),
                            tint = t.primary,
                        )
                    }
                    Hairline()
                }
                if (memoriesOpen) {
                    memories.forEach { e ->
                        item(key = "otd-${e.id}") {
                            EntryRow(e, onClick = { openEntry(e.id) })
                            Hairline()
                        }
                    }
                }
            }

            otherDays.forEach { date -> dayGroup(date, days.getValue(date), openEntry) }

            if (entries.isEmpty() && done) {
                item(key = "empty") {
                    Text(
                        "Everything you write appears here, newest first.",
                        style = Typeface.small,
                        color = t.muted,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }
        }

        NewEntryButton({ newEntry() }, Modifier.align(Alignment.BottomEnd))
    }
}

/** A sticky day header and that day's entries; today gets a quiet "New entry" row when empty. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.dayGroup(
    date: String,
    entries: List<EntrySummary>,
    openEntry: (String) -> Unit,
    onNewEntry: (() -> Unit)? = null,
) {
    stickyHeader(key = "d$date") {
        val t = tokens
        Column(Modifier.fillMaxWidth().background(t.paper).padding(top = 20.dp)) {
            MicroLabel(Day.title(date), Modifier.padding(bottom = 6.dp))
            Hairline()
        }
    }
    if (entries.isEmpty() && onNewEntry != null) {
        item(key = "new$date") {
            val t = tokens
            Text(
                "New entry",
                style = Typeface.small,
                color = t.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onNewEntry)
                    .padding(vertical = 13.dp),
            )
            Hairline()
        }
    }
    entries.forEach { e ->
        item(key = e.id) {
            EntryRow(e, onClick = { openEntry(e.id) }, showDate = false)
            Hairline()
        }
    }
}
