package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.rowMotion
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.ui.EntryRow
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SectionHeader
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.SearchHit

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
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val m = motion
    // The search box's border lights up while it has focus.
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) t.primary else t.input, m.standard(), label = "focus")

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
                    .border(1.dp, border, shape)
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
                    modifier = Modifier.weight(1f).padding(start = 8.dp, top = 12.dp, bottom = 12.dp).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Words, tag:, rating:, before:…", style = Typeface.ui.copy(fontSize = 16.sp), color = t.dim)
                        inner()
                    },
                )
            }
        }
        if (query.isBlank()) {
            item(key = "try") {
                SectionHeader("Try", modifier = rowMotion(m)) {
                    examples.forEach { (q, meaning) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .pressable(pressedScale = 0.98f, tick = true) { query = q }
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
            item(key = "count") {
                Column(rowMotion(m)) {
                    // The count rolls as results come and go.
                    AnimatedContent(
                        hits.size,
                        transitionSpec = {
                            if (m.reduced) EnterTransition.None togetherWith ExitTransition.None
                            else {
                                val up = targetState > initialState
                                (slideInVertically(m.glide()) { if (up) it else -it } + fadeIn(m.quick()))
                                    .togetherWith(slideOutVertically(m.glide()) { if (up) -it else it } + fadeOut(m.quick()))
                            }
                        },
                        label = "count",
                    ) { n ->
                        MicroLabel(if (n == 1) "1 result" else "$n results", Modifier.padding(top = 20.dp, bottom = 6.dp))
                    }
                    Hairline()
                }
            }
            items(hits, key = { it.entry.id }) { hit ->
                Column(rowMotion(m)) {
                    EntryRow(hit.entry, onClick = { openEntry(hit.entry.id) }, snippet = hit.snippet)
                    Hairline()
                }
            }
        }
    }
}
