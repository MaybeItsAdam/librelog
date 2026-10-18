package uk.co.maybeitssoftware.librelog.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.librelog.Day
import uniffi.librelog_core.thumbhashImage

/** A photo in the device's own library. */
data class LibraryPhoto(val uri: Uri, val takenAt: Long)

/**
 * The device's photo library through MediaStore: only the photos taken on
 * one local day, for the chooser (docs/photos.md).
 */
object PhotoLibrary {
    enum class Access { Full, Partial, None }

    /**
     * What to ask for. On Android 14 asking for both image permissions lets
     * the user share just some photos; ACCESS_MEDIA_LOCATION keeps their
     * place in the copy we make.
     */
    val permissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
            Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun access(context: Context): Access {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_IMAGES) -> Access.Full
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
            else -> Access.None
        }
    }

    /** Photos whose DATE_TAKEN falls within the local day [date], earliest first. Blocking. */
    fun photosTaken(context: Context, date: String): List<LibraryPhoto> {
        val (start, end) = Day.millis(date) ?: return emptyList()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val found = mutableListOf<LibraryPhoto>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
            arrayOf(start.toString(), end.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                found += LibraryPhoto(ContentUris.withAppendedId(collection, c.getLong(id)), c.getLong(taken))
            }
        }
        return found
    }
}

/**
 * Decoded pictures for tiles and the viewer, sized for where they're drawn
 * and decoded off the main thread. Recent ones are kept in memory, so
 * scrolling back or reopening a day doesn't decode again.
 */
object PhotoImages {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** A file on this device, at most [maxPx] on its long side. */
    suspend fun file(path: String, maxPx: Int): Bitmap? = load("file:$path@$maxPx") {
        if (!File(path).exists()) return@load null
        decode(ImageDecoder.createSource(File(path)), maxPx)
    }

    /** A library photo's thumbnail, for the chooser. */
    suspend fun library(context: Context, uri: Uri, px: Int): Bitmap? = load("lib:$uri@$px") {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else {
            decode(ImageDecoder.createSource(context.contentResolver, uri), px)
        }
    }

    /**
     * The blurred stand-in drawn from a ThumbHash, for a photo kept on
     * another device. Decoded at its tiny size, then scaled up with
     * filtering so it reads as a soft blur rather than blocks.
     */
    suspend fun thumbhash(hash: String): Bitmap? = load("hash:$hash") {
        val img = thumbhashImage(hash)
        val w = img.width.toInt()
        val h = img.height.toInt()
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        small.copyPixelsFromBuffer(ByteBuffer.wrap(img.rgba))
        Bitmap.createScaledBitmap(small, w * 8, h * 8, true)
    }

    private fun decode(source: ImageDecoder.Source, maxPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = PhotoImport.fit(info.size.width, info.size.height, maxPx)
            decoder.setTargetSize(w, h)
        }

    private suspend fun load(key: String, make: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { runCatching(make).getOrNull() }?.also { cache.put(key, it) }
    }
}

/** A picture loaded by [load] off the main thread; null until it arrives. */
@Composable
fun rememberPicture(key: Any, load: suspend () -> Bitmap?): ImageBitmap? {
    val bitmap by produceState<Bitmap?>(null, key) { value = load() }
    return bitmap?.asImageBitmap()
}
