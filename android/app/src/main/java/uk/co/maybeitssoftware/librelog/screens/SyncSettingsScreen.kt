package uk.co.maybeitssoftware.librelog.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.sync.GoogleSignIn
import uk.co.maybeitssoftware.librelog.sync.SyncEngine
import uk.co.maybeitssoftware.librelog.sync.SyncError
import uk.co.maybeitssoftware.librelog.ui.GoogleSignInButton
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SecondaryButton
import uk.co.maybeitssoftware.librelog.ui.StatusBanner
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.card
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.PairingSession

/**
 * Settings, a tab of its own: appearance, then sync setup and status
 * (SPEC.md §12), then import and export. Everything here is optional: the
 * journal works fully without an account. Mirrors
 * apple/App/Screens/SyncSettingsView.swift, with Google (when the build has a
 * client id) where Apple has Sign in with Apple.
 */
@Composable
fun SettingsScreen(app: AppModel) {
    val t = tokens
    val sync = app.sync
    Column(
        Modifier
            .fillMaxSize()
            .background(t.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp)
            .widthIn(max = 720.dp),
    ) {
        Text("Settings", style = Typeface.title, color = t.ink, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        AppearanceSection(app)
        PrivacySection(app)
        when (sync.phase) {
            SyncEngine.Phase.Off -> SignIn(app)
            SyncEngine.Phase.NeedsKey -> KeySetup(app)
            SyncEngine.Phase.On -> Status(app)
        }
        ImportExportSection(app)
        Text(
            "Entries are encrypted on this device before they're uploaded. The server only ever stores ciphertext; " +
                "without your recovery phrase nobody, including us, can read them. Photos and other files stay on " +
                "the device they were added on.",
            style = Typeface.small.copy(fontSize = 12.sp),
            color = t.muted,
            modifier = Modifier.padding(top = 24.dp),
        )
    }

    sync.newRecoveryPhrase?.let { phrase ->
        RecoveryPhraseSheet(phrase) { sync.newRecoveryPhrase = null }
    }
}

// MARK: Signed out

@Composable
private fun SignIn(app: AppModel) {
    val t = tokens
    val scope = rememberCoroutineScope()
    // Saved through rotation; the password isn't, as saved state can be
    // written to disk.
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var sentTo by rememberSaveable { mutableStateOf<String?>(null) }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Google first when this build has a client id, as Apple's button is on
    // iPhone and Mac; email follows under its own heading.
    if (GoogleSignIn.isConfigured) {
        Section("Sync across your devices") {
            GoogleSignInButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        message = runCatching {
                            GoogleSignIn.request(context)?.let { app.sync.signInWithGoogle(it.idToken, it.rawNonce) }
                        }.exceptionOrNull()?.message
                        busy = false
                    }
                },
            )
        }
    }

    Section(if (GoogleSignIn.isConfigured) "Or with email" else "Sync across your devices") {
        val address = sentTo
        if (address != null) {
            Text(
                "We sent a sign-in email to $address. Open the link on this device, or type the code if the email has one.",
                style = Typeface.small,
                color = t.muted,
            )
            Field(code, { code = it }, "6-digit code", keyboard = KeyboardType.Number, mono = true)
            PrimaryButton(
                "Sign in",
                onClick = {
                    scope.launch {
                        busy = true
                        message = runCatching { app.sync.verify(code) }.exceptionOrNull()?.message
                        busy = false
                    }
                },
                enabled = code.count(Char::isDigit) >= 6 && !busy,
            )
            SecondaryButton("Use a different email", { sentTo = null; code = "" }, color = t.muted)
        } else {
            Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            if (usePassword) {
                Field(password, { password = it }, "Password", keyboard = KeyboardType.Password, secret = true)
                PrimaryButton(
                    "Sign in",
                    onClick = {
                        scope.launch {
                            busy = true
                            val error = runCatching { app.sync.signIn(email, password) }.exceptionOrNull()
                            if (error == null) password = ""
                            message = error?.message
                            busy = false
                        }
                    },
                    enabled = email.contains('@') && password.isNotEmpty() && !busy,
                )
                SecondaryButton("Email me a sign-in link instead", { usePassword = false; password = "" }, color = t.muted)
            } else {
                PrimaryButton(
                    "Email me a sign-in link",
                    onClick = {
                        scope.launch {
                            busy = true
                            val error = runCatching { app.sync.sendSignInEmail(email) }.exceptionOrNull()
                            if (error == null) sentTo = email.trim()
                            message = error?.message
                            busy = false
                        }
                    },
                    enabled = email.contains('@') && !busy,
                )
                SecondaryButton("Use a password instead", { usePassword = true }, color = t.muted)
            }
        }
        // Signing in can get a session and still fail setting up the account
        // after it (offline, or the server refusing); and the email's link
        // signs in from outside this screen. Those land in sync.problem.
        (message ?: app.sync.problem?.let(::signInProblem))?.let { StatusBanner(it, t.danger) }
    }
}

private fun signInProblem(error: SyncError): String = when (error) {
    SyncError.SignedOut -> "You were signed out. Sign in again to keep syncing."
    SyncError.Offline -> "Couldn't reach the server. Check your connection and try again."
    else -> error.message ?: "Couldn't finish signing in."
}

// MARK: Signed in without the key

@Composable
private fun KeySetup(app: AppModel) {
    val t = tokens
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf<PairingSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Section("Unlock your journal") {
        Text(
            "This account already has a journal. To read it here, use another signed-in device, or type your 24-word recovery phrase.",
            style = Typeface.small,
            color = t.muted,
        )
        val session = pairing
        if (session != null) {
            val payload = remember(session) { session.qrPayload() }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QrCode(payload, Modifier.size(240.dp))
                Text(
                    "On another device: Settings → Sync → Add another device, then scan this code.",
                    style = Typeface.small.copy(fontSize = 12.sp),
                    color = t.muted,
                    textAlign = TextAlign.Center,
                )
                SelectionContainer { Text(payload, style = Typeface.mono(9), color = t.dim, maxLines = 2) }
            }
        } else {
            SecondaryButton("Pair with another device", {
                val s = PairingSession()
                pairing = s
                scope.launch {
                    runCatching { app.sync.receiveKeyByPairing(s) }.onFailure {
                        error = it.message
                        pairing = null
                    }
                }
            })
        }
    }

    Section("Recovery phrase") {
        Field(phrase, { phrase = it }, "24 words", mono = true, minLines = 3)
        PrimaryButton(
            "Use recovery phrase",
            onClick = {
                scope.launch {
                    busy = true
                    error = runCatching { app.sync.useRecoveryPhrase(phrase) }.exceptionOrNull()?.message
                    busy = false
                }
            },
            enabled = phrase.trim().split(Regex("\\s+")).size >= 24 && !busy,
        )
        error?.let { StatusBanner(it, t.danger) }
    }
}

// MARK: Signed in and syncing

@Composable
private fun Status(app: AppModel) {
    val t = tokens
    val sync = app.sync
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var revealing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    Section("Sync") {
        Labeled("Account") { Text(sync.email ?: "—", style = Typeface.small, color = t.ink) }
        Labeled("Status") {
            when {
                sync.isSyncing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.primary)
                sync.problem != null -> StatusTag(if (sync.problem == SyncError.NotActive) "Inactive" else "Problem", t.danger)
                else -> StatusTag("Synced", t.success)
            }
        }
        sync.lastSynced?.let { last ->
            Labeled("Last synced") {
                Text(DateUtils.getRelativeTimeSpanString(last).toString(), style = Typeface.mono(13), color = t.muted)
            }
        }
        if (sync.pending > 0u) Labeled("Waiting to upload") { Text(sync.pending.toString(), style = Typeface.mono(13), color = t.ink) }
        sync.problem?.let {
            val text = if (it == SyncError.NotActive) "Sync isn't active for this account. Subscribe below to switch it on; " +
                "everything stays on this device meanwhile." else it.message ?: "Problem"
            StatusBanner(text, t.danger)
        }
        SecondaryButton("Sync now", { scope.launch { sync.syncNow() } })
    }

    SubscriptionSection(app)

    Section("Devices") {
        SecondaryButton("Add another device", { adding = true })
        SecondaryButton("Show recovery phrase", { revealing = true })
    }

    Section("Account") {
        SecondaryButton("Sign out", { scope.launch { sync.signOut() } }, color = t.danger)
        SecondaryButton("Delete account", { if (!deleting) confirmDelete = true }, color = t.danger)
        deleteError?.let { StatusBanner(it, t.danger) }
        Text(
            "Signing out keeps everything on this device. It stops syncing until you sign in again. " +
                "Deleting the account also removes everything synced to it from the server.",
            style = Typeface.small.copy(fontSize = 12.sp),
            color = t.muted,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "Your account and everything synced to it are removed from the server for good, and your " +
                        "other devices stop syncing. The journal on each device stays where it is.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        deleteError = runCatching { sync.deleteAccount() }.exceptionOrNull()
                            ?.let { "Couldn't delete the account: ${it.message}" }
                        deleting = false
                    }
                }) { Text("Delete account", color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(Radius.card),
            containerColor = t.raised,
        )
    }

    if (adding) PairingApproveSheet(app) { adding = false }
    if (revealing) {
        val phrase = remember { runCatching { app.store.recoveryPhrase() }.getOrNull() }
        if (phrase != null) RecoveryPhraseSheet(phrase, reveal = true) { revealing = false } else revealing = false
    }
}

/** On an existing device: answer a new device's pairing code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingApproveSheet(app: AppModel, onDismiss: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<String?>(null) }

    fun approve(qr: String) = scope.launch {
        state = runCatching { app.sync.approvePairing(qr) }
            .fold({ "Sent. The new device will finish setting up in a few seconds." }, { it.message })
    }

    Sheet(onDismiss) {
        Text("Add a device", style = Typeface.headline, color = t.ink)
        Text(
            "On the new device, sign in to the same account and choose “Pair with another device”. Then scan its code here, or paste the text under it.",
            style = Typeface.small,
            color = t.muted,
        )
        PrimaryButton("Scan code", {
            // Google's code scanner: no camera permission, the UI is Play services'.
            val scanner = GmsBarcodeScanning.getClient(
                context,
                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue.orEmpty()
                    if (raw.startsWith("librelog-pair:")) {
                        code = raw
                        approve(raw)
                    } else {
                        state = "That isn't a LibreLog pairing code."
                    }
                }
                .addOnFailureListener { state = it.message }
        })
        Field(code, { code = it }, "librelog-pair:…", mono = true, minLines = 2)
        SecondaryButton("Send journal key", { if (code.startsWith("librelog-pair:")) approve(code.trim()) })
        state?.let { Text(it, style = Typeface.small, color = t.muted) }
    }
}

/** Shown once after sync creates the key, and on request from Devices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryPhraseSheet(phrase: String, reveal: Boolean = false, onDone: () -> Unit) {
    val t = tokens
    var confirmed by remember { mutableStateOf(reveal) }
    // The words are the journal key: kept out of screenshots, screen sharing
    // and the recents thumbnail, as an open locked note is.
    SecureWindow()
    Sheet(onDismiss = { if (confirmed) onDone() }, secure = true) {
        Text(if (reveal) "Recovery phrase" else "Write these 24 words down", style = Typeface.title, color = t.ink)
        Text(
            if (reveal) "Anyone with these words and your account can read your journal. Keep them somewhere safe and offline."
            else "They're the only way to read your synced journal on a new device if you lose this one. Nobody can reset them for you, including us.",
            style = Typeface.body.copy(fontSize = 15.sp),
            color = t.muted,
        )
        PhraseGrid(phrase)
        if (!reveal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    confirmed, { confirmed = it },
                    colors = CheckboxDefaults.colors(checkedColor = t.primary, uncheckedColor = t.input),
                )
                Text("I've written them down", style = Typeface.ui, color = t.ink)
            }
        }
        PrimaryButton("Done", onDone, enabled = confirmed)
    }
}

@Composable
private fun PhraseGrid(phrase: String) {
    val t = tokens
    val words = phrase.split(' ')
    SelectionContainer {
        Column(Modifier.card(androidx.compose.foundation.layout.PaddingValues(12.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.chunked(3).forEachIndexed { row, three ->
                Row {
                    three.forEachIndexed { col, w ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("${row * 3 + col + 1}", style = Typeface.mono(10), color = t.dim, modifier = Modifier.width(18.dp), textAlign = TextAlign.End)
                            Spacer(Modifier.width(6.dp))
                            Text(w, style = Typeface.mono(14), color = t.ink)
                        }
                    }
                }
            }
        }
    }
}

// MARK: Pieces

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MicroLabel(title)
        Hairline()
        content()
    }
}

@Composable
internal fun Labeled(label: String, value: @Composable () -> Unit) {
    val t = tokens
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Typeface.small, color = t.muted, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
internal fun StatusTag(text: String, color: Color) {
    val shape = RoundedCornerShape(Radius.control)
    Text(
        text,
        style = Typeface.micro,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), shape)
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A bordered text field; 16sp so it reads at a glance on a phone. */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    minLines: Int = 1,
    secret: Boolean = false,
) {
    val t = tokens
    val style = (if (mono) Typeface.mono(16) else Typeface.ui.copy(fontSize = 16.sp)).copy(color = t.ink)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = style,
        minLines = minLines,
        singleLine = minLines == 1,
        cursorBrush = SolidColor(t.primary),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(t.raised, RoundedCornerShape(Radius.control))
            .border(1.dp, t.input, RoundedCornerShape(Radius.control))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = t.dim))
            inner()
        },
    )
}

/**
 * Keeps the activity's window out of screenshots and the recents thumbnail
 * while this is composed. A secure [Sheet] only covers the sheet's own window.
 */
@Composable
internal fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.activity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Dialogs are bottom sheets on phones (house touch rules). A [secure] sheet
 * is left out of screenshots, screen sharing and the recents thumbnail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(onDismiss: () -> Unit, secure: Boolean = false, content: @Composable () -> Unit) {
    val t = tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = if (secure) ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn) else ModalBottomSheetProperties(),
        shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card),
        containerColor = t.raised,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val bitmap = remember(text) { renderQr(text) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = "Pairing code",
        filterQuality = FilterQuality.None,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(Radius.card))
            .padding(12.dp),
    )
}

private fun renderQr(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
    )
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp
}
