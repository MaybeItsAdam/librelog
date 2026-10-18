package uk.co.maybeitssoftware.librelog.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt
import uniffi.librelog_core.NewPhoto

/**
 * Turns a picked or taken photo into what the core keeps (docs/photos.md):
 * the original's bytes, a JPEG preview at most 1024 px on its long side, and
 * the photo scaled to fit 100 × 100 as RGBA for the ThumbHash. The date and
 * place come from the photo's EXIF, or MediaStore's date when it has none.
 */
object PhotoImport {
    const val PREVIEW_MAX = 1024
    const val HASH_MAX = 100
    private const val PREVIEW_QUALITY = 85

    /** [w] × [h] scaled to fit [max] on its long side; never enlarged, never zero. */
    fun fit(w: Int, h: Int, max: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val scale = max.toDouble() / long
        return maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
    }

    /** RFC 3339 with the offset [zone] had at that moment, to the second. */
    fun rfc3339(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * Copies [uri] into the cache and prepares it. Blocking: run on the IO
     * dispatcher. [takenHint] is MediaStore's DATE_TAKEN, used when the file
     * has no EXIF date. The core moves both files it's given, so they are
     * only the caller's to delete if adding fails.
     */
    fun prepare(context: Context, uri: Uri, takenHint: Long? = null, fromLibrary: Boolean = false): NewPhoto {
        val resolver = context.contentResolver
        val dir = File(context.cacheDir, "photo-import").apply { mkdirs() }
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val id = UUID.randomUUID().toString()
        val original = File(dir, "$id.$ext")
        val preview = File(dir, "$id-preview.jpg")
        try {
            // Library photos have their location redacted unless asked for
            // with ACCESS_MEDIA_LOCATION; the Photo Picker always strips it.
            val source = if (fromLibrary && Build.VERSION.SDK_INT >= 29 && hasMediaLocation(context)) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }
            val input = runCatching { resolver.openInputStream(source) }.getOrNull() ?: resolver.openInputStream(uri)
            (input ?: error("Couldn't open the photo")).use { i -> original.outputStream().use { i.copyTo(it) } }

            val exif = runCatching { ExifInterface(original.path) }.getOrNull()
            val taken = exif?.dateTimeOriginal ?: exif?.dateTime ?: takenHint
            val latLng = exif?.latLong

            // ImageDecoder reads HEIC and turns the picture upright from its
            // EXIF orientation, so its size is the size as seen.
            var width = 0
            var height = 0
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                val (w, h) = fit(width, height, PREVIEW_MAX)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val rgb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            preview.outputStream().use { rgb.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }

            val (hw, hh) = fit(rgb.width, rgb.height, HASH_MAX)
            val small = Bitmap.createScaledBitmap(rgb, hw, hh, true)
            // ARGB_8888 is laid out R, G, B, A in memory: the RGBA the core wants.
            val rgba = ByteBuffer.allocate(hw * hh * 4).also { small.copyPixelsToBuffer(it) }.array()

            return NewPhoto(
                path = original.path,
                mime = mime,
                width = width.toUInt(),
                height = height.toUInt(),
                takenAt = taken?.let { rfc3339(it) },
                lat = latLng?.get(0),
                lng = latLng?.get(1),
                previewPath = preview.path,
                hashRgba = rgba,
                hashWidth = hw.toUInt(),
                hashHeight = hh.toUInt(),
                sourceRef = null,
                localAsset = null,
            )
        } catch (e: Exception) {
            original.delete()
            preview.delete()
            throw e
        }
    }

    /** Removes what [prepare] made, if the core didn't take it. */
    fun discard(photo: NewPhoto) {
        File(photo.path).delete()
        File(photo.previewPath).delete()
    }

    private fun hasMediaLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
}
