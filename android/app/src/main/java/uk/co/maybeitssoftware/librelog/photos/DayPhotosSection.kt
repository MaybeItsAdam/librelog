package uk.co.maybeitssoftware.librelog.photos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.Day
import uk.co.maybeitssoftware.librelog.screens.Sheet
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.SecondaryButton
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.DayPhoto

/** Photos sit three to a row on a phone. */
private const val COLUMNS = 3

/**
 * The media surfaces behind and over a photo are fixed black and white
 * whatever the theme: the picture underneath isn't ours to theme.
 */
private val Letterbox = Color.Black
private val Scrim = Color.Black.copy(alpha = 0.45f)
private val OnScrim = Color.White

/** One photo to add: where it is, MediaStore's date for it, and whether it's from the library. */
private data class Chosen(val uri: Uri, val takenHint: Long?, val fromLibrary: Boolean)

/**
 * A day's photos, in the editor and under a day in Calendar and Journal
 * (docs/photos.md). Each tile is this device's preview, or the blurred
 * ThumbHash when the photo was added on another device. Tap to view, hold
 * for caption and remove. Mirrors calendar/DayCalendarSection.kt.
 */
@Composable
fun DayPhotosSection(app: AppModel, date: String, modifier: Modifier = Modifier) {
    val t = tokens
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var photos by remember(date) { mutableStateOf<List<DayPhoto>>(emptyList()) }
    var choosing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<DayPhoto?>(null) }
    var captioning by remember { mutableStateOf<DayPhoto?>(null) }
    var adding by remember { mutableIntStateOf(0) }
    // Where the camera writes; saved so it survives the camera app's turn.
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(date, app.revision) {
        photos = runCatching { app.run { it.dayPhotos(null, date) } }.getOrNull() ?: return@LaunchedEffect
    }

    fun add(chosen: List<Chosen>, cleanup: () -> Unit = {}) {
        if (chosen.isEmpty()) return cleanup()
        val appContext = context.applicationContext
        adding += chosen.size
        // The app scope, so leaving the day doesn't stop photos half added.
        app.scope.launch {
            for (c in chosen) {
                try {
                    val photo = withContext(Dispatchers.IO) { PhotoImport.prepare(appContext, c.uri, c.takenHint, c.fromLibrary) }
                    try {
                        app.run { it.addDayPhoto(null, date, photo) }
                    } catch (e: Exception) {
                        PhotoImport.discard(photo)
                        throw e
                    }
                } catch (e: Exception) {
                    app.lastError = e.message
                } finally {
                    adding--
                }
            }
            cleanup()
            Haptics.confirm(haptics)
            app.changed()
            app.sync.schedulePush()
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        add(uris.map { Chosen(it, null, fromLibrary = false) })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile?.let(::File)
        cameraFile = null
        if (taken && file != null && file.length() > 0) {
            add(listOf(Chosen(Uri.fromFile(file), System.currentTimeMillis(), fromLibrary = false))) { file.delete() }
        } else {
            file?.delete()
        }
    }

    Column(modifier.fillMaxWidth().animateContentSize(motion.glide())) {
        if (photos.isNotEmpty() || adding > 0) {
            Row(Modifier.padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MicroLabel("Photos", Modifier.weight(1f))
                if (adding > 0) Text("Adding $adding…", style = Typeface.mono(11), color = t.muted)
            }
        }
        photos.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { photo ->
                    PhotoTile(
                        photo,
                        Modifier.weight(1f),
                        onOpen = { viewing = photo },
                        onCaption = { captioning = photo },
                        onRemove = {
                            Haptics.confirm(haptics)
                            photos = photos.filterNot { it.id == photo.id }
                            app.scope.launch { app.write { it.removeDayPhoto(photo.id) } }
                        },
                    )
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressable { choosing = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AddPhotoAlternate, null, Modifier.size(16.dp), tint = t.muted)
            Text("Add photo", style = Typeface.small, color = t.muted, modifier = Modifier.padding(start = 8.dp))
        }
    }

    if (choosing) {
        PhotoChooser(
            date = date,
            onDismiss = { choosing = false },
            onAdd = { uris ->
                choosing = false
                add(uris)
            },
            onChooseAnother = {
                choosing = false
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = if (hasCamera(context)) {
                {
                    choosing = false
                    val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
                    cameraFile = file.path
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    try {
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        cameraFile = null
                        app.lastError = "No camera app"
                    }
                }
            } else null,
        )
    }
    viewing?.let { PhotoViewer(it) { viewing = null } }
    captioning?.let { photo ->
        CaptionSheet(photo.caption.orEmpty(), onDismiss = { captioning = null }) { text ->
            captioning = null
            val caption = text.trim().ifEmpty { null }
            photos = photos.map { if (it.id == photo.id) it.copy(caption = caption) else it }
            app.scope.launch { app.write { it.setPhotoCaption(photo.id, caption) } }
        }
    }
}

private fun hasCamera(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** A tile's picture: the preview here, else the blurred ThumbHash. */
@Composable
private fun rememberTile(photo: DayPhoto, px: Int) = rememberPicture(photo.previewPath to photo.thumbhash) {
    photo.previewPath?.let { PhotoImages.file(it, px) } ?: photo.thumbhash?.let { PhotoImages.thumbhash(it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(photo: DayPhoto, modifier: Modifier, onOpen: () -> Unit, onCaption: () -> Unit, onRemove: () -> Unit) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.control)
    val px = with(LocalDensity.current) { 160.dp.roundToPx() }
    val picture = rememberTile(photo, px)
    val elsewhere = photo.previewPath == null
    val shown by animateFloatAsState(if (picture != null) 1f else 0f, motion.standard(), label = "tile")
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(t.well)
                .border(1.dp, t.hairline, shape)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        Haptics.tick(haptics)
                        menuOpen = true
                    },
                    onLongClickLabel = "Caption or remove",
                )
                .semantics { contentDescription = photo.caption ?: "Photo" },
        ) {
            // Fades in once decoded, over the well colour.
            picture?.let {
                Image(it, null, Modifier.fillMaxSize().graphicsLayer { this.alpha = shown }, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            }
            if (elsewhere) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Scrim).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    MicroLabel("On another device", color = OnScrim)
                }
            }
            // Flat like everything else: a hairline, not a shadow.
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(Radius.card),
                containerColor = t.raised,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, t.hairline),
            ) {
                DropdownMenuItem(
                    text = { Text(if (photo.caption.isNullOrBlank()) "Add caption" else "Edit caption") },
                    onClick = {
                        menuOpen = false
                        onCaption()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove from this day", color = t.danger) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
        photo.caption?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Typeface.small.copy(fontSize = 12.sp), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * Full screen on black. Shows whatever's quickest first (preview or blur),
 * then the original once it's decoded, sized to the screen.
 */
@Composable
private fun PhotoViewer(photo: DayPhoto, onClose: () -> Unit) {
    val metrics = LocalContext.current.resources.displayMetrics
    val screen = maxOf(metrics.widthPixels, metrics.heightPixels)
    val quick = rememberTile(photo, 1024)
    val full = rememberPicture(photo.originalPath ?: "") {
        photo.originalPath?.let { PhotoImages.file(it, screen) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Box(
            Modifier
                .fillMaxSize()
                .background(Letterbox)
                .combinedClickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            (full ?: quick)?.let {
                Image(it, photo.caption ?: "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
            }
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                IconAction(Icons.Filled.Close, "Close", onClose, tint = OnScrim)
            }
            val caption = photo.caption?.takeIf { it.isNotBlank() }
            val line = listOfNotNull(caption, photo.takenAt?.let { takenLabel(it) }, if (photo.originalPath == null && photo.previewPath == null) "On another device" else null)
            if (line.isNotEmpty()) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Scrim)
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    caption?.let { Text(it, style = Typeface.body, color = OnScrim) }
                    Text(line.drop(if (caption != null) 1 else 0).joinToString(" · "), style = Typeface.mono(11), color = OnScrim.copy(alpha = 0.8f))
                }
            }
        }
    }
}

/** "09:41" from an RFC 3339 time, in the photo's own offset. */
private fun takenLabel(rfc: String): String? =
    runCatching { java.time.OffsetDateTime.parse(rfc).let { "%02d:%02d".format(Locale.ROOT, it.hour, it.minute) } }.getOrNull()

@Composable
private fun CaptionSheet(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val t = tokens
    var text by remember { mutableStateOf(initial) }
    Sheet(onDismiss) {
        MicroLabel("Caption")
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = Typeface.ui.copy(fontSize = 16.sp, color = t.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(t.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(t.raised, RoundedCornerShape(Radius.control))
                .border(1.dp, t.input, RoundedCornerShape(Radius.control))
                .padding(12.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Say something about it", style = Typeface.ui.copy(fontSize = 16.sp), color = t.dim)
                inner()
            },
        )
        PrimaryButton("Save", { onSave(text) })
    }
}

/**
 * Add photo (docs/photos.md): the photos this device's library has from
 * that local day, several at once, then "Choose another photo" for anything
 * else and "Take photo" where there's a camera. Without library access it
 * says so and still offers the other two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoChooser(
    date: String,
    onDismiss: () -> Unit,
    onAdd: (List<Chosen>) -> Unit,
    onChooseAnother: () -> Unit,
    onTakePhoto: (() -> Unit)?,
) {
    val t = tokens
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var access by remember { mutableStateOf(PhotoLibrary.access(context)) }
    var asked by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<List<LibraryPhoto>?>(null) }
    var selected by remember { mutableStateOf<List<LibraryPhoto>>(emptyList()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        access = PhotoLibrary.access(context)
    }
    // Asks the first time; after a refusal the sheet explains instead.
    LaunchedEffect(Unit) { if (access == PhotoLibrary.Access.None) ask.launch(PhotoLibrary.permissions) }
    LaunchedEffect(access) {
        if (access == PhotoLibrary.Access.None) return@LaunchedEffect
        library = withContext(Dispatchers.IO) { runCatching { PhotoLibrary.photosTaken(context, date) }.getOrDefault(emptyList()) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card),
        containerColor = t.raised,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MicroLabel("Taken ${Day.title(date).let { if (it == "Today" || it == "Yesterday") it.lowercase() else "on $it" }}")
                Text("Add photos", style = Typeface.headline, color = t.ink)
            }
            when {
                access == PhotoLibrary.Access.None && asked -> Text(
                    "LibreLog can't see your photo library, so it can't show the photos you took this day. " +
                        "You can still choose any photo, or allow access in Android's settings for LibreLog.",
                    style = Typeface.small,
                    color = t.muted,
                )
                access == PhotoLibrary.Access.None -> {}
                library == null -> Text("Looking…", style = Typeface.small, color = t.muted)
                library!!.isEmpty() -> Text("No photos in your library from this day.", style = Typeface.small, color = t.muted)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    items(library!!, key = { it.uri.toString() }) { photo ->
                        val on = photo in selected
                        LibraryTile(photo, on) {
                            Haptics.tick(haptics)
                            selected = if (on) selected - photo else selected + photo
                        }
                    }
                }
            }
            if (access == PhotoLibrary.Access.Partial) {
                Text(
                    "Showing the photos you've shared with LibreLog. Share more",
                    style = Typeface.small,
                    color = t.primary,
                    modifier = Modifier.heightIn(min = 48.dp).pressable { ask.launch(PhotoLibrary.permissions) }.padding(vertical = 14.dp),
                )
            }
            if (selected.isNotEmpty()) {
                PrimaryButton(
                    if (selected.size == 1) "Add 1 photo" else "Add ${selected.size} photos",
                    { onAdd(selected.map { Chosen(it.uri, it.takenAt, fromLibrary = true) }) },
                )
            }
            SecondaryButton("Choose another photo", onChooseAnother)
            onTakePhoto?.let { SecondaryButton("Take photo", it) }
        }
    }
}

@Composable
private fun LibraryTile(photo: LibraryPhoto, selected: Boolean, onToggle: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 128.dp.roundToPx() }
    val picture = rememberPicture(photo.uri) { PhotoImages.library(context, photo.uri, px) }
    val shape = RoundedCornerShape(Radius.control)
    val ring by animateColorAsState(if (selected) t.primary else t.hairline, motion.quick(), label = "ring")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(t.well)
            .border(if (selected) 2.dp else 1.dp, ring, shape)
            .pressable(pressedScale = 0.95f, onClick = onToggle)
            .semantics { contentDescription = if (selected) "Selected photo" else "Photo" },
    ) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(t.primary, RoundedCornerShape(Radius.control)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = t.onPrimary)
            }
        }
    }
}
