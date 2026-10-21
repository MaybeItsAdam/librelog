package uk.co.maybeitssoftware.librelog.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.screens.SecureWindow
import uk.co.maybeitssoftware.librelog.screens.activity
import uk.co.maybeitssoftware.librelog.screens.Sheet
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.InputField
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.tokens

/**
 * A day's locked note (docs/locked-notes.md): one row, "Add a locked note"
 * or "Locked", shown only while locked notes are on. Tapping it always asks
 * for the password; nothing is remembered between openings.
 */
@Composable
fun LockedNoteSection(app: AppModel, date: String, modifier: Modifier = Modifier) {
    val t = tokens
    var enabled by remember { mutableStateOf(false) }
    var has by remember(date) { mutableStateOf(false) }
    var opening by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(date, app.revision) {
        runCatching {
            app.run { store ->
                val on = store.lockStatus().enabled
                on to (on && store.hasLockedNote(null, date))
            }
        }.onSuccess { (on, note) ->
            enabled = on
            has = note
        }
    }

    if (!enabled) return
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable { opening = true }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (has) "Locked note. Open with the lock password" else "Add a locked note"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (has) Icons.Filled.Lock else Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = if (has) t.ink else t.muted)
        Text(
            if (has) "Locked" else "Add a locked note",
            style = Typeface.small,
            color = if (has) t.ink else t.muted,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    // A draft whose save failed reopens the sheet, edits and all.
    if (opening || date in app.lockedNotes) LockedNoteSheet(app, date) { opening = false }
}

/**
 * An open locked note: the password being typed, then the text as it was,
 * the text now and the password that opened it. Held in [AppModel.lockedNotes],
 * in memory only and never in saved state, so it outlives the sheet only
 * across a rotation or when saving it failed.
 */
class LockedNoteDraft(val date: String) {
    var password by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var original by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
    var key: String? = null
    val opened: Boolean get() = original != null
}

/**
 * Closes [draft], saving it when changed. If the save fails the draft goes
 * back, with the error, so the edits are still there when the sheet reopens
 * (at once, or when the day is next shown) rather than being dropped.
 */
private fun AppModel.closeLockedNote(draft: LockedNoteDraft) {
    if (lockedNotes[draft.date] !== draft) return
    lockedNotes.remove(draft.date)
    val pw = draft.key
    val before = draft.original
    val after = draft.text
    if (pw == null || before == null || after == before) return
    scope.launch {
        try {
            this@closeLockedNote.run { store -> store.setLockedNote(null, draft.date, pw, after) }
            changed()
            sync.schedulePush()
        } catch (e: Exception) {
            draft.error = "Couldn't save the note: ${e.message ?: "unknown error"}. Your changes are still here."
            lastError = draft.error
            lockedNotes.putIfAbsent(draft.date, draft)
        }
    }
}

/**
 * Password, then the note in a plain editor. The text and the password live
 * only in a [LockedNoteDraft]: closing the sheet saves (when changed) and
 * forgets both. While the note is open the window is kept out of screenshots
 * and the recents thumbnail, and the keyboard is asked not to learn from it.
 */
@Composable
private fun LockedNoteSheet(app: AppModel, date: String, onDismiss: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val activity = LocalContext.current.activity()
    val draft = remember(date) { app.lockedNotes.getOrPut(date) { LockedNoteDraft(date) } }
    var busy by remember { mutableStateOf(false) }
    val opened = draft.opened

    fun open() {
        if (busy || draft.password.isEmpty()) return
        val tried = draft.password
        busy = true
        scope.launch {
            try {
                val note = app.run { it.lockedNote(null, date, tried) }.orEmpty()
                draft.text = note
                draft.original = note
                draft.key = tried
                draft.password = ""
                draft.error = null
            } catch (e: Exception) {
                Haptics.reject(haptics)
                draft.error = LockText.openFailure(e)
                draft.password = ""
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        onDismiss()
        app.closeLockedNote(draft)
    }

    // Saves on the way out however the sheet goes: dismissed, Done, or the
    // screen under it closing. Not when the activity is only being recreated
    // (rotation, dark mode, resizing): the new sheet picks the draft up.
    DisposableEffect(draft) {
        onDispose { if (activity?.isChangingConfigurations != true) app.closeLockedNote(draft) }
    }

    // Leaving the app closes the note rather than leaving it open behind.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) close()
    }

    // No screenshots or recents preview of the open note.
    if (opened) SecureWindow()

    Sheet(::close, secure = true) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicroLabel("Locked note · ${Day.title(date)}")
            Text(if (opened) "Locked note" else "Enter the lock password", style = Typeface.headline, color = t.ink)
        }
        if (!opened) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            InputField(
                draft.password,
                { draft.password = it; draft.error = null },
                "Lock password",
                modifier = Modifier.focusRequester(focus),
                secret = true,
                imeAction = ImeAction.Go,
                onDone = ::open,
                error = draft.error != null,
            )
            draft.error?.let { Text(it, style = Typeface.small, color = t.danger) }
            PrimaryButton(if (busy) "Opening…" else "Open", ::open, enabled = !busy && draft.password.isNotEmpty())
        } else {
            val shape = RoundedCornerShape(Radius.control)
            NoPersonalisedLearning {
                BasicTextField(
                    value = draft.text,
                    onValueChange = { draft.text = it },
                    textStyle = Typeface.body.copy(color = t.ink),
                    cursorBrush = SolidColor(t.primary),
                    minLines = 8,
                    // No suggestions from what's typed here, and (below) no
                    // learning from it either. Not KeyboardType.Password: that
                    // would make it a single masked line on many keyboards.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .background(t.raised, shape)
                        .border(1.dp, t.input, shape)
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (draft.text.isEmpty()) Text("Only you can open this.", style = Typeface.body, color = t.dim)
                        inner()
                    },
                )
            }
            draft.error?.let { Text(it, style = Typeface.small, color = t.danger) }
            Text(
                "Saved when you close it. Clear all the text to remove the note.",
                style = Typeface.small.copy(fontSize = 12.sp),
                color = t.muted,
            )
            PrimaryButton("Done", ::close)
        }
    }
}

/**
 * Asks the keyboard not to learn from what's typed inside
 * (IME_FLAG_NO_PERSONALIZED_LEARNING, "incognito" on Gboard). Compose's
 * KeyboardOptions has no switch for it, so the flag is added to the editor's
 * EditorInfo on the way to the keyboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoPersonalisedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val private = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            }
            nextHandler.startInputMethod(private)
        },
        content = content,
    )
}
