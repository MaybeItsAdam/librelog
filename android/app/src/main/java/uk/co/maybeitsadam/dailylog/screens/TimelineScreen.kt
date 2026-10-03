package uk.co.maybeitsadam.dailylog.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import uk.co.maybeitsadam.dailylog.AppModel
import uk.co.maybeitsadam.dailylog.Day
import uk.co.maybeitsadam.dailylog.ui.EntryRow
import uk.co.maybeitsadam.dailylog.ui.Hairline
import uk.co.maybeitsadam.dailylog.ui.MicroLabel
import uk.co.maybeitsadam.dailylog.ui.Radius
import uk.co.maybeitsadam.dailylog.ui.Typeface
import uk.co.maybeitsadam.dailylog.ui.tokens
import uniffi.dailylog_core.EntrySummary
import uniffi.dailylog_core.TimelineCursor
import uniffi.dailylog_core.TimelineFilter

private const val PAGE = 50u

/** Every entry, newest first, loaded a page at a time (SPEC.md §6.4). */
@Composable
fun TimelineScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    var entries by remember { mutableStateOf<List<EntrySummary>>(emptyList()) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val filter = TimelineFilter(null, emptyList(), null, favoritesOnly)

    suspend fun page(after: TimelineCursor?) = runCatching { app.run { it.timeline(filter, after, PAGE) } }.getOrDefault(emptyList())

    LaunchedEffect(favoritesOnly, app.revision) {
        val first = page(null)
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
        entries = entries + more
        done = more.size < PAGE.toInt()
        loading = false
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(t.paper).padding(horizontal = 20.dp).widthIn(max = 720.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MicroLabel("Timeline")
                    Text("Every entry", style = Typeface.title, color = t.ink)
                }
                val shape = RoundedCornerShape(Radius.control)
                Text(
                    "Favourites",
                    style = Typeface.small,
                    color = if (favoritesOnly) t.primary else t.muted,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .padding(vertical = 6.dp)
                        .background(if (favoritesOnly) t.primary.copy(alpha = 0.1f) else t.paper, shape)
                        .border(1.dp, if (favoritesOnly) t.primary.copy(alpha = 0.4f) else t.hairline, shape)
                        .clickable(role = Role.Button) { favoritesOnly = !favoritesOnly }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    if (favoritesOnly) "No favourites yet." else "Nothing written yet.",
                    style = Typeface.small,
                    color = t.muted,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        }
        var lastMonth = ""
        entries.forEach { e ->
            val month = e.date.take(7)
            if (month != lastMonth) {
                lastMonth = month
                item(key = "m$month") {
                    Column(Modifier.fillMaxWidth().background(t.paper).padding(top = 20.dp)) {
                        MicroLabel(Day.monthTitle(month), Modifier.padding(bottom = 6.dp))
                        Hairline()
                    }
                }
            }
            item(key = e.id) {
                EntryRow(e, onClick = { openEntry(e.id) })
                Hairline()
            }
        }
    }
}
