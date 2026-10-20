package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.lock.LockText
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.InputField
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SecondaryButton
import uk.co.maybeitssoftware.librelog.ui.StatusBanner
import uk.co.maybeitssoftware.librelog.ui.ToggleRow
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.LockStatus

/**
 * Settings → Privacy (docs/locked-notes.md): the Locked notes switch, and
 * once there's a lock password, changing it or starting over without it.
 */
@Composable
fun PrivacySection(app: AppModel) {
    val t = tokens
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(LockStatus(enabled = false, hasPassword = false)) }
    var setting by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Again after a sync, since the password and the switch follow the account.
    LaunchedEffect(app.revision) {
        status = runCatching { app.run { it.lockStatus() } }.getOrNull() ?: return@LaunchedEffect
    }

    Section("Privacy") {
        ToggleRow(
            "Locked notes",
            status.enabled,
            onChange = { on ->
                error = null
                if (on && !status.hasPassword) {
                    setting = true
                } else {
                    status = status.copy(enabled = on)
                    scope.launch {
                        runCatching { app.run { it.setLockEnabled(on) } }
                            .onSuccess { app.changed(); app.sync.schedulePush() }
                            .onFailure { e -> error = e.message; status = status.copy(enabled = !on) }
                    }
                }
            },
            detail = "A private note on any day that opens only with a password, every time.",
        )
        if (status.hasPassword) {
            SecondaryButton("Change password", { changing = true })
            SecondaryButton("Reset locked notes", { confirmReset = true }, color = t.danger)
        }
        error?.let { StatusBanner(it, t.danger) }
        Text(
            "There's no way to recover the password: forget it and the only way on is to reset, " +
                "which deletes every locked note. Turning locked notes off only hides them.",
            style = Typeface.small.copy(fontSize = 12.sp),
            color = t.muted,
        )
    }

    if (setting) {
        LockPasswordSheet(app, change = false, onDone = { setting = false })
    }
    if (changing) {
        LockPasswordSheet(app, change = true, onDone = { changing = false })
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset locked notes?") },
            text = {
                Text(
                    "Every locked note, on every day and every device you sync with, is deleted along with the " +
                        "password. There's no way to get them back. The rest of your journal stays as it is.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { app.write { it.resetLock() } }
                }) { Text("Delete locked notes", color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(Radius.card),
            containerColor = t.raised,
            tonalElevation = 0.dp,
        )
    }
}

/**
 * Sets the lock password the first time, or changes it ([change]): the
 * current one, then the new one twice. Changing re-seals every locked note,
 * which the core does in one step.
 */
@Composable
private fun LockPasswordSheet(app: AppModel, change: Boolean, onDone: () -> Unit) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val problem = LockText.newPasswordProblem(new, again)

    fun save() {
        if (busy) return
        if (problem != null) {
            error = problem
            return
        }
        busy = true
        scope.launch {
            try {
                app.run { it.setLockPassword(if (change) current else null, new) }
                Haptics.confirm(haptics)
                app.changed()
                app.sync.schedulePush()
                onDone()
            } catch (e: Exception) {
                Haptics.reject(haptics)
                if (LockText.isWrongPassword(e)) {
                    error = LockText.WRONG
                    current = ""
                } else {
                    error = e.message
                }
            } finally {
                busy = false
            }
        }
    }

    Sheet(onDone, secure = true) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicroLabel("Locked notes")
            Text(if (change) "Change password" else "Set a lock password", style = Typeface.headline, color = t.ink)
        }
        Text(
            if (change) "Every locked note is re-locked under the new password."
            else "At least ${LockText.MIN_LENGTH} characters. It can't be recovered, so choose one you'll remember.",
            style = Typeface.small,
            color = t.muted,
        )
        if (change) {
            InputField(current, { current = it; error = null }, "Current password", secret = true, imeAction = ImeAction.Next)
        }
        InputField(new, { new = it; error = null }, "New password", secret = true, imeAction = ImeAction.Next)
        InputField(again, { again = it; error = null }, "New password again", secret = true, imeAction = ImeAction.Done, onDone = ::save)
        error?.let { Text(it, style = Typeface.small, color = t.danger) }
        PrimaryButton(
            if (busy) "Saving…" else if (change) "Change password" else "Set password",
            ::save,
            enabled = !busy && new.isNotEmpty() && again.isNotEmpty() && (!change || current.isNotEmpty()),
        )
    }
}
