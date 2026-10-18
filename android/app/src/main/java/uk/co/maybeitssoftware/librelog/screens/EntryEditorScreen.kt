package uk.co.maybeitssoftware.librelog.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.motion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.calendar.DayCalendarSection
import uk.co.maybeitssoftware.librelog.editor.EditorController
import uk.co.maybeitssoftware.librelog.editor.RichTextEditor
import uk.co.maybeitssoftware.librelog.editor.editorStyle
import uk.co.maybeitssoftware.librelog.photos.DayPhotosSection
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.LocalAppearance
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.RatingPicker
import uk.co.maybeitssoftware.librelog.ui.StatusBanner
import uk.co.maybeitssoftware.librelog.ui.TagChip
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.BlockKind
import uniffi.librelog_core.Entry
import uniffi.librelog_core.MarkKind
import uniffi.librelog_core.blocksToMarkdown

/**
 * Writing one entry (SPEC.md §6.2). Autosaves a moment after typing stops and
 * when leaving; there is no Save button. Mirrors
 * apple/App/Editor/EntryEditorView.swift.
 */
@Composable
fun EntryEditorScreen(app: AppModel, entryId: String, onClose: () -> Unit, onShowHistory: (String) -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appearance = LocalAppearance.current
    val controller = remember(entryId) { EditorController(editorStyle(context, t, appearance)) }
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var title by remember(entryId) { mutableStateOf("") }
    var wordCount by remember(entryId) { mutableStateOf(0u) }
    var toolbarTick by remember { mutableIntStateOf(0) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var addingTag by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editorReady by remember(entryId) { mutableStateOf(false) }
    // Set once the entry is deleted from the menu, so leaving doesn't save it.
    var deleted by remember(entryId) { mutableStateOf(false) }
    var finished by remember(entryId) { mutableStateOf(false) }

    suspend fun save() {
        val e = entry ?: return
        val blocks = controller.blocks()
        val newTitle = title.trim()
        val titleChanged = newTitle != (e.title ?: "")
        try {
            wordCount = app.run { store ->
                if (titleChanged) store.setTitle(e.id, newTitle.ifEmpty { null })
                store.saveBody(e.id, blocks)
            }
            if (titleChanged) entry = entry?.copy(title = newTitle.ifEmpty { null })
            app.changed()
            app.sync.schedulePush()
        } catch (ex: Exception) {
            app.lastError = ex.message
        }
    }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(700)
            save()
        }
    }

    // Leaving the editor (Back, the arrow, version history, or anything else
    // that takes it off screen) saves what's pending, then deletes the entry
    // if it was left untouched, so tapping New entry and backing out leaves
    // nothing behind. The app scope outlives this screen, so this finishes
    // after it goes.
    fun finish() {
        if (finished) return
        finished = true
        val pending = saveJob != null
        saveJob?.cancel()
        saveJob = null
        if (deleted) return
        app.scope.launch {
            if (pending) save()
            val discarded = runCatching { app.run { it.discardIfEmpty(entryId) } }.getOrDefault(false)
            if (discarded) {
                app.changed()
                app.sync.schedulePush()
            }
        }
    }

    fun flushAndClose() {
        finish()
        onClose()
    }

    suspend fun reloadTags() {
        val fresh = runCatching { app.run { it.entry(entryId) } }.getOrNull() ?: return
        entry = entry?.copy(tags = fresh.tags)
    }

    LaunchedEffect(entryId) {
        val e = runCatching { app.run { it.entry(entryId) } }.getOrNull() ?: return@LaunchedEffect
        entry = e
        title = e.title ?: ""
        wordCount = e.wordCount
        controller.onChange = { scheduleSave() }
        controller.onSelectionChange = { toolbarTick++ }
    }
    // Load the body once both the entry and the text view exist.
    LaunchedEffect(entry?.id, editorReady) {
        val e = entry ?: return@LaunchedEffect
        if (editorReady) controller.load(e.body)
    }
    DisposableEffect(entryId) { onDispose { finish() } }
    BackHandler { flushAndClose() }

    Column(Modifier.fillMaxSize().background(t.paper).statusBarsPadding().imePadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", ::flushAndClose)
            Spacer(Modifier.weight(1f))
            Box {
                IconAction(Icons.Filled.MoreVert, "More", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    entry?.let { e ->
                        DropdownMenuItem(
                            text = { Text(if (e.isFavorite) "Unfavourite" else "Favourite") },
                            onClick = {
                                menuOpen = false
                                val fav = !e.isFavorite
                                entry = e.copy(isFavorite = fav)
                                scope.launch { app.write { it.setFavorite(e.id, fav) } }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Share as text") },
                            onClick = {
                                menuOpen = false
                                val text = blocksToMarkdown(controller.blocks())
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                context.startActivity(Intent.createChooser(send, null))
                            },
                        )
                        DropdownMenuItem(text = { Text("Version history") }, onClick = { menuOpen = false; onShowHistory(e.id) })
                        DropdownMenuItem(text = { Text("Delete", color = t.danger) }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
                .widthIn(max = 720.dp),
        ) {
            val e = entry
            if (e != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicroLabel(if (e.endDate != null) Day.range(e.date, e.endDate) else Day.title(e.date))
                    e.time?.let { Text(it, style = Typeface.mono(11), color = t.muted, modifier = Modifier.padding(start = 8.dp)) }
                    Spacer(Modifier.weight(1f))
                    RatingPicker(e.rating) { value ->
                        entry = e.copy(rating = value)
                        scope.launch { app.write { it.setRating(e.id, value) } }
                    }
                }
                SpanControl(e.date, e.endDate) { end ->
                    entry = entry?.copy(endDate = end)
                    scope.launch { app.write { it.setEndDate(e.id, end) } }
                }
                BasicTextField(
                    value = title,
                    onValueChange = { title = it; scheduleSave() },
                    textStyle = Typeface.title.copy(color = t.ink),
                    cursorBrush = SolidColor(t.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    decorationBox = { inner ->
                        if (title.isEmpty()) Text("Title", style = Typeface.title, color = t.dim)
                        inner()
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    e.tags.forEach { tag ->
                        TagChip(
                            tag.name,
                            Modifier.clickable {
                                scope.launch {
                                    app.write { it.untagEntry(e.id, tag.id) }
                                    reloadTags()
                                }
                            },
                        )
                    }
                    if (addingTag) {
                        BasicTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            singleLine = true,
                            textStyle = Typeface.small.copy(color = t.ink),
                            cursorBrush = SolidColor(t.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val name = newTag.trim()
                                newTag = ""
                                addingTag = false
                                if (name.isNotEmpty()) scope.launch {
                                    app.write { store -> store.tagEntry(e.id, store.tagNamed(name).id, null) }
                                    reloadTags()
                                }
                            }),
                            modifier = Modifier
                                .width(120.dp)
                                .border(1.dp, t.input, RoundedCornerShape(Radius.control))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    } else {
                        Row(
                            Modifier
                                .border(1.dp, t.hairline, RoundedCornerShape(Radius.control))
                                .clickable(role = Role.Button) { addingTag = true }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(14.dp), tint = t.muted)
                            Text("Tag", style = Typeface.small, color = t.muted)
                        }
                    }
                }
                DayCalendarSection(app, e.date, Modifier.padding(bottom = 6.dp))
                DayPhotosSection(app, e.date, Modifier.padding(bottom = 6.dp))
                Hairline()
                if (e.hasConflict) {
                    StatusBanner("Edited on two devices — review", t.warning, Modifier.padding(top = 10.dp)) { onShowHistory(e.id) }
                }
                RichTextEditor(
                    controller,
                    Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(top = 8.dp),
                    placeholder = "Write about your day…",
                    onReady = { editorReady = true },
                )
            }
        }

        FormatBar(controller, wordCount, toolbarTick)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("It stays in Trash for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saveJob?.cancel()
                    deleted = true
                    scope.launch {
                        app.write { it.deleteEntry(entryId) }
                        onClose()
                    }
                }) { Text("Delete", color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(Radius.card),
            containerColor = t.raised,
        )
    }
}

/**
 * "Several days" for a trip or a festival (SPEC.md §4): when on, the entry
 * runs until the chosen last day, at least a day and at most a year on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpanControl(start: String, end: String?, onChange: (String?) -> Unit) {
    val t = tokens
    val m = motion
    val haptics = LocalHapticFeedback.current
    var picking by remember { mutableStateOf(false) }
    val first = Day.date(start) ?: return
    Row(
        Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val on = end != null
        val border by animateColorAsState(if (on) t.primary.copy(alpha = 0.4f) else t.hairline, m.quick(), label = "span")
        Row(
            Modifier
                .border(1.dp, border, RoundedCornerShape(Radius.control))
                .background(if (on) t.primary.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(Radius.control))
                .toggleable(value = on, role = Role.Switch) { now ->
                    Haptics.toggle(haptics, now)
                    onChange(if (now) Day.string(first.plusDays(1)) else null)
                }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.DateRange, null, Modifier.size(14.dp), tint = if (on) t.primary else t.muted)
            Text("Several days", style = Typeface.small, color = if (on) t.primary else t.muted, modifier = Modifier.padding(start = 4.dp))
        }
        AnimatedVisibility(
            visible = on,
            enter = expandHorizontally(m.glide()) + fadeIn(m.standard()),
            exit = shrinkHorizontally(m.glide()) + fadeOut(m.quick()),
        ) {
            Text(
                "Until ${end?.let(Day::short) ?: ""}",
                style = Typeface.small,
                color = t.ink,
                modifier = Modifier
                    .border(1.dp, t.input, RoundedCornerShape(Radius.control))
                    .clickable(role = Role.Button) { picking = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }

    if (picking) {
        // The picker speaks UTC midnight; a LocalDate is a calendar day either way.
        val toMillis = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val min = toMillis(first.plusDays(1))
        val max = toMillis(first.plusDays(365))
        val state = rememberDatePickerState(
            initialSelectedDateMillis = end?.let(Day::date)?.let(toMillis) ?: min,
            yearRange = first.year..first.plusDays(365).year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in min..max
                override fun isSelectableYear(year: Int) = year in first.year..first.plusDays(365).year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    state.selectedDateMillis?.let {
                        Haptics.tick(haptics)
                        onChange(Day.string(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(Radius.card),
            colors = DatePickerDefaults.colors(containerColor = t.raised),
        ) {
            DatePicker(state, title = { MicroLabel("Last day", Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = false)
        }
    }
}

@Composable
private fun FormatBar(controller: EditorController, wordCount: UInt, tick: Int) {
    val t = tokens
    @Suppress("UNUSED_EXPRESSION") tick // recompose when the caret moves
    val kind = controller.currentKind
    Column(Modifier.fillMaxWidth().background(t.raised).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // Scrolls sideways on narrow phones rather than squeezing.
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FormatButton(Icons.Filled.FormatBold, "Bold", controller.isActive(MarkKind.Bold)) { controller.toggle(MarkKind.Bold) }
                FormatButton(Icons.Filled.FormatItalic, "Italic", controller.isActive(MarkKind.Italic)) { controller.toggle(MarkKind.Italic) }
                FormatButton(Icons.Filled.FormatUnderlined, "Underline", controller.isActive(MarkKind.Underline)) { controller.toggle(MarkKind.Underline) }
                FormatButton(Icons.Filled.Highlight, "Highlight", controller.isActive(MarkKind.Highlight)) { controller.toggle(MarkKind.Highlight) }
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(t.hairline))
                FormatButton(Icons.Filled.Title, "Heading", kind is BlockKind.Heading) { controller.setKind(BlockKind.Heading(2u)) }
                FormatButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", kind == BlockKind.Bullet) { controller.setKind(BlockKind.Bullet) }
                FormatButton(Icons.Filled.FormatListNumbered, "Numbered list", kind == BlockKind.Numbered) { controller.setKind(BlockKind.Numbered) }
                FormatButton(Icons.Filled.CheckBox, "Checklist", kind is BlockKind.Check) { controller.setKind(BlockKind.Check(false)) }
                FormatButton(Icons.Filled.FormatQuote, "Quote", kind == BlockKind.Quote) { controller.setKind(BlockKind.Quote) }
            }
            Text(
                "$wordCount words",
                style = Typeface.mono(11),
                color = t.muted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp),
            )
        }
    }
}

@Composable
private fun FormatButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val t = tokens
    Box(
        Modifier
            .size(width = 36.dp, height = 44.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(if (active) t.primary.copy(alpha = 0.1f) else t.raised, RoundedCornerShape(Radius.control)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(20.dp), tint = if (active) t.primary else t.ink)
        }
    }
}
