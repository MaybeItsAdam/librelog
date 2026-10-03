package uk.co.maybeitssoftware.librelog.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.SecondaryButton
import uk.co.maybeitssoftware.librelog.ui.StatusBanner
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.ExportFormat
import uniffi.librelog_core.ImportPreview
import uniffi.librelog_core.ImportSource
import uniffi.librelog_core.exportExtension
import java.io.File

/**
 * Import and export (SPEC.md §16). The core does the work; this picks files
 * through the system document picker and shows a preview before importing.
 * Exports are written to the cache first and then copied to wherever the
 * user chooses, so the core only ever sees plain file paths.
 */
@Composable
fun ImportExportSection(app: AppModel) {
    val t = tokens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosingFormat by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var preview by remember { mutableStateOf<PendingImport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Message?>(null) }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val export = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        scope.launch {
            message = if (uri == null) {
                null // cancelled
            } else {
                runCatching { withContext(Dispatchers.IO) { copyOut(context, export.file, uri) } }
                    .fold(
                        { Message(plural(export.entries, "Exported %d entry", "Exported %d entries"), false) },
                        { Message(it.message ?: "Couldn't save the export.", true) },
                    )
            }
            export.file.delete()
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = copyIn(context, uri)
                    try {
                        PendingImport(file, app.store.previewImport(file.path))
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                }
            }
            result.onSuccess { preview = it }.onFailure { message = Message(it.message ?: "Couldn't read that file.", true) }
            busy = false
        }
    }

    Section("Import & export") {
        ActionRow("Export…", "Save a copy of your journal") { choosingFormat = true }
        Hairline()
        ActionRow("Import…", "Day One, Daylio, Markdown, or a Librelog backup") {
            pickFile.launch(arrayOf("*/*"))
        }
        if (busy) Text("Working…", style = Typeface.small, color = t.muted)
        message?.let { StatusBanner(it.text, if (it.error) t.danger else t.success) }
    }

    if (choosingFormat) {
        Sheet(onDismiss = { choosingFormat = false }) {
            Text("Export", style = Typeface.headline, color = t.ink)
            ExportChoice.entries.forEachIndexed { i, choice ->
                if (i > 0) Hairline()
                ActionRow(choice.title, choice.detail) {
                    choosingFormat = false
                    scope.launch {
                        busy = true
                        message = null
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val dir = File(context.cacheDir, "export").apply { mkdirs() }
                                val file = File(dir, "Librelog ${Day.today}.${exportExtension(choice.format)}")
                                val summary = app.store.export(choice.format, file.path, null)
                                PendingExport(file, summary.entries.toInt())
                            }
                        }
                        busy = false
                        result
                            .onSuccess { pendingExport = it; saveTo.launch(it.file.name) }
                            .onFailure { message = Message(it.message ?: "Export failed.", true) }
                    }
                }
            }
        }
    }

    preview?.let { pending ->
        val p = pending.preview
        val toImport = (p.entries - p.alreadyImported).toInt()
        val dismiss = {
            pending.file.delete()
            preview = null
        }
        Sheet(onDismiss = dismiss) {
            Text("Import from ${sourceName(p.source)}", style = Typeface.headline, color = t.ink)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val range = listOfNotNull(p.firstDate, p.lastDate).distinct().joinToString(" – ")
                Text(
                    plural(p.entries.toInt(), "%d entry", "%d entries") + if (range.isNotEmpty()) " · $range" else "",
                    style = Typeface.ui,
                    color = t.ink,
                )
                if (p.alreadyImported > 0u) {
                    Text("${p.alreadyImported} already imported, will be skipped", style = Typeface.small, color = t.muted)
                }
                if (p.tags > 0u) {
                    Text(plural(p.tags.toInt(), "%d tag", "%d tags"), style = Typeface.small, color = t.muted)
                }
                p.warnings.forEach { Text(it, style = Typeface.small, color = t.muted) }
            }
            PrimaryButton(
                if (toImport > 0) plural(toImport, "Import %d entry", "Import %d entries") else "Nothing new to import",
                enabled = toImport > 0 && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val result = runCatching { withContext(Dispatchers.IO) { app.store.importFile(pending.file.path, null) } }
                        busy = false
                        pending.file.delete()
                        preview = null
                        result
                            .onSuccess { r ->
                                val skipped = if (r.skipped > 0u) ", skipped ${r.skipped}" else ""
                                message = Message(plural(r.imported.toInt(), "Imported %d entry", "Imported %d entries") + skipped, false)
                                app.changed()
                                app.sync.schedulePush()
                            }
                            .onFailure { message = Message(it.message ?: "Import failed.", true) }
                    }
                },
            )
            SecondaryButton("Cancel", dismiss, color = t.muted)
        }
    }
}

private data class PendingExport(val file: File, val entries: Int)
private data class PendingImport(val file: File, val preview: ImportPreview)
private data class Message(val text: String, val error: Boolean)

private enum class ExportChoice(val format: ExportFormat, val title: String, val detail: String) {
    Backup(ExportFormat.JSON, "Complete backup (JSON)", "Everything, to restore into Librelog"),
    Markdown(ExportFormat.MARKDOWN, "Markdown files (.zip)", "One file per entry; opens in Obsidian and most editors"),
    Html(ExportFormat.HTML, "Web page (.html)", "One page to read or print in a browser"),
    Text(ExportFormat.TEXT, "Plain text (.txt)", "Just the words"),
}

@Composable
private fun ActionRow(title: String, detail: String, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Typeface.ui, color = t.ink)
            Text(detail, style = Typeface.small, color = t.muted)
        }
    }
}

private fun sourceName(s: ImportSource) = when (s) {
    ImportSource.LIBRELOG -> "Librelog"
    ImportSource.DAY_ONE -> "Day One"
    ImportSource.DAYLIO -> "Daylio"
    ImportSource.MARKDOWN -> "Markdown"
}

private fun plural(n: Int, one: String, many: String) = (if (n == 1) one else many).format(n)

/** Copies a picked document into the cache, keeping its file name (and so its
 *  extension, which tells the core what it is). */
private fun copyIn(context: Context, uri: Uri): File {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "import"
    val safe = name.replace(Regex("[/\\\\]"), "_")
    val dir = File(context.cacheDir, "import").apply { mkdirs() }
    val file = File(dir, safe)
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Couldn't open that file." }
        file.outputStream().use { input.copyTo(it) }
    }
    return file
}

private fun copyOut(context: Context, file: File, uri: Uri) {
    context.contentResolver.openOutputStream(uri, "wt").use { out ->
        requireNotNull(out) { "Couldn't write to that location." }
        file.inputStream().use { it.copyTo(out) }
    }
}
