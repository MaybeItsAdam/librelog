package uk.co.maybeitssoftware.librelog.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Reveal
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.rowMotion
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.EntrySummary

/**
 * On This Day (SPEC.md §6.8): what was written on today's date in earlier
 * years, grouped by how long ago, nearest first. Opened from the bottom bar.
 */
@Composable
fun OnThisDayScreen(app: AppModel, openEntry: (String) -> Unit, onClose: () -> Unit) {
    val t = tokens
    val m = motion
    val today = Day.today
    var memories by remember { mutableStateOf<List<EntrySummary>?>(null) }
    LaunchedEffect(app.revision, today) {
        memories = runCatching { app.run { it.onThisDay(today) } }.getOrDefault(emptyList())
    }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(t.paper).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClose)
        }
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = 720.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("On this day", style = Typeface.title, color = t.ink)
                    Text(Day.longDate(today), style = Typeface.small, color = t.muted)
                }
            }
            val list = memories
            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing written on this date in earlier years yet. Entries from today will show here this time next year.",
                        style = Typeface.small,
                        color = t.muted,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }
            list.orEmpty().groupBy { Day.yearsAgo(it.date, today) }.forEach { (years, entries) ->
                item(key = "y$years") {
                    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        MicroLabel(if (years == 1) "1 year ago" else "$years years ago", Modifier.padding(bottom = 6.dp))
                        Hairline()
                    }
                }
                entries.forEachIndexed { i, e ->
                    item(key = e.id) {
                        Reveal(i, active = true, rowMotion(m)) {
                            EntryRow(e, onClick = { openEntry(e.id) })
                            Hairline()
                        }
                    }
                }
            }
        }
    }
}
