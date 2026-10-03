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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.co.maybeitsadam.dailylog.AppModel
import uk.co.maybeitsadam.dailylog.ui.EntryRow
import uk.co.maybeitsadam.dailylog.ui.Hairline
import uk.co.maybeitsadam.dailylog.ui.MicroLabel
import uk.co.maybeitsadam.dailylog.ui.Radius
import uk.co.maybeitsadam.dailylog.ui.SectionHeader
import uk.co.maybeitsadam.dailylog.ui.Typeface
import uk.co.maybeitsadam.dailylog.ui.tokens
import uniffi.dailylog_core.SearchHit

private val examples = listOf(
    "canal walk" to "words, matched as you type",
    "\"long way home\"" to "an exact phrase",
    "tag:run" to "entries with a tag",
    "rating:>=4" to "good days",
    "before:2026-01-01" to "by date (also after:, on:)",
    "is:favorite -work" to "favourites, without a word",
)

/** Full-text search with operators (SPEC.md §6.11). Results update as you type. */
@Composable
fun SearchScreen(app: AppModel, openEntry: (String) -> Unit) {
    val t = tokens
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }

    LaunchedEffect(query, app.revision) {
        delay(120)
        val q = query
        hits = if (q.isBlank()) emptyList() else runCatching { app.run { it.search(q, 100u) } }.getOrDefault(emptyList())
    }

    LazyColumn(Modifier.fillMaxSize().background(t.paper).padding(horizontal = 20.dp).widthIn(max = 720.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MicroLabel("Search")
                Text("Find anything", style = Typeface.title, color = t.ink)
            }
            val shape = RoundedCornerShape(Radius.control)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(t.raised, shape)
                    .border(1.dp, t.input, shape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = t.muted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    // 16sp keeps field text legible and matches the house touch rules.
                    textStyle = Typeface.ui.copy(color = t.ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(t.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Words, tag:, rating:, before:…", style = Typeface.ui.copy(fontSize = 16.sp), color = t.dim)
                        inner()
                    },
                )
            }
        }
        if (query.isBlank()) {
            item {
                SectionHeader("Try") {
                    examples.forEach { (q, meaning) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button) { query = q }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(q, style = Typeface.mono(13), color = t.primary, modifier = Modifier.weight(1f))
                            Text(meaning, style = Typeface.small, color = t.muted)
                        }
                        Hairline()
                    }
                }
            }
        } else {
            item {
                MicroLabel(
                    if (hits.size == 1) "1 result" else "${hits.size} results",
                    Modifier.padding(top = 20.dp, bottom = 6.dp),
                )
                Hairline()
            }
            items(hits, key = { it.entry.id }) { hit ->
                EntryRow(hit.entry, onClick = { openEntry(hit.entry.id) }, snippet = hit.snippet)
                Hairline()
            }
        }
    }
}
