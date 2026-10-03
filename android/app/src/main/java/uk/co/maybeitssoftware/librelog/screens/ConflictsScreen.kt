package uk.co.maybeitssoftware.librelog.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.StatusBanner
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.card
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.Revision
import uniffi.librelog_core.blocksToMarkdown

/**
 * Earlier versions of an entry, including the losing side of any edit made on
 * two devices at once (SPEC.md §12.5). Copy what you need back, then mark them
 * reviewed. Mirrors apple/App/Screens/ConflictsView.swift.
 */
@Composable
fun ConflictsScreen(app: AppModel, entryId: String, onClose: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    var revisions by remember { mutableStateOf<List<Revision>>(emptyList()) }
    BackHandler(onBack = onClose)

    LaunchedEffect(entryId, app.revision) {
        revisions = runCatching { app.run { it.revisions(entryId) } }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().background(t.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClose)
            Text("Version history", style = Typeface.headline, color = t.ink)
        }
        Hairline()
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.padding(top = 4.dp)) }
            if (revisions.any { it.isConflict }) {
                item {
                    StatusBanner(
                        "This entry was edited on two devices at once. The current text kept the later edit; " +
                            "the other version is below. Copy anything you need into the entry, then mark it reviewed.",
                        t.warning,
                    )
                }
                item {
                    PrimaryButton("Mark reviewed", {
                        scope.launch {
                            app.write { it.resolveConflicts(entryId) }
                            onClose()
                        }
                    })
                }
            }
            if (revisions.isEmpty()) {
                item { Text("No earlier versions.", style = Typeface.small, color = t.muted) }
            }
            items(revisions, key = { it.id }) { r ->
                Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MicroLabel(if (r.isConflict) "Other device's version" else "Earlier version", color = if (r.isConflict) t.warning else t.muted)
                        Spacer(Modifier.weight(1f))
                        Text(r.createdAt.take(16).replace('T', ' '), style = Typeface.mono(11), color = t.muted)
                    }
                    SelectionContainer {
                        Text(blocksToMarkdown(r.body).ifEmpty { "Empty" }, style = Typeface.body.copy(fontSize = Typeface.body.fontSize * 0.9f), color = t.ink)
                    }
                }
            }
            item { Spacer(Modifier.padding(bottom = 40.dp)) }
        }
    }
}
