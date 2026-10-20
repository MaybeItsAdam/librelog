package uk.co.maybeitssoftware.librelog.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
    var opening by remember { mutableStateOf(false) }

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

    if (opening) LockedNoteSheet(app, date) { opening = false }
}

/**
 * Password, then the note in a plain editor. The text and the password live
 * only in this sheet's state: closing it saves (when changed) and forgets
 * both. While the note is open the window is kept out of screenshots and
 * the recents thumbnail.
 */
@Composable
private fun LockedNoteSheet(app: AppModel, date: String, onDismiss: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Set once opened: the text as it was, the text now, and the password that opened it.
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<String?>(null) }
    val opened = original != null

    fun open() {
        if (busy || password.isEmpty()) return
        val tried = password
        busy = true
        scope.launch {
            try {
                val note = app.run { it.lockedNote(null, date, tried) }.orEmpty()
                text = note
                original = note
                key = tried
                password = ""
                error = null
            } catch (e: Exception) {
                Haptics.reject(haptics)
                error = LockText.openFailure(e)
                password = ""
            } finally {
                busy = false
            }
        }
    }

    // Saves on the way out however the sheet goes: dismissed, Done, or the
    // screen under it closing.
    DisposableEffect(Unit) {
        onDispose {
            val pw = key
            val before = original
            val after = text
            if (pw != null && before != null && after != before) {
                app.scope.launch {
                    try {
                        app.run { it.setLockedNote(null, date, pw, after) }
                        app.changed()
                        app.sync.schedulePush()
                    } catch (e: Exception) {
                        app.lastError = e.message
                    }
                }
            }
            key = null
            original = null
            text = ""
            password = ""
        }
    }

    // Leaving the app closes the note rather than leaving it open behind.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onDismiss() }

    // No screenshots or recents preview of the open note.
    if (opened) {
        DisposableEffect(Unit) {
            val window = context.activity()?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    Sheet(onDismiss, secure = true) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicroLabel("Locked note · ${Day.title(date)}")
            Text(if (opened) "Locked note" else "Enter the lock password", style = Typeface.headline, color = t.ink)
        }
        if (!opened) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            InputField(
                password,
                { password = it; error = null },
                "Lock password",
                modifier = Modifier.focusRequester(focus),
                secret = true,
                imeAction = ImeAction.Go,
                onDone = ::open,
                error = error != null,
            )
            error?.let { Text(it, style = Typeface.small, color = t.danger) }
            PrimaryButton(if (busy) "Opening…" else "Open", ::open, enabled = !busy && password.isNotEmpty())
        } else {
            val shape = RoundedCornerShape(Radius.control)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Typeface.body.copy(color = t.ink),
                cursorBrush = SolidColor(t.primary),
                minLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .background(t.raised, shape)
                    .border(1.dp, t.input, shape)
                    .padding(12.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Only you can open this.", style = Typeface.body, color = t.dim)
                    inner()
                },
            )
            Text(
                "Saved when you close it. Clear all the text to remove the note.",
                style = Typeface.small.copy(fontSize = 12.sp),
                color = t.muted,
            )
            PrimaryButton("Done", onDismiss)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
