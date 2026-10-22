package uk.co.maybeitssoftware.librelog.photos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.tokens
import uniffi.librelog_core.DayPhoto

/**
 * The photo that stands for a day in the journal list (docs/photos.md): the
 * one chosen in the day's Photos section, else its first, or none. The choice
 * syncs; the pixels are whatever this device has.
 */
object DayPreview {
    /** The preview for each of [dates] that has one, by date: one core call for a page. */
    suspend fun load(app: AppModel, dates: Collection<String>): Map<String, DayPhoto> {
        if (dates.isEmpty()) return emptyMap()
        val photos = runCatching { app.run { it.dayPreviews(null, dates.distinct()) } }.getOrDefault(emptyList())
        return photos.associateBy { it.date }
    }

    /** What a photo's menu offers: "Use as preview" on any photo that isn't it, "No preview" while there is one. */
    data class Actions(val useAsPreview: Boolean, val noPreview: Boolean)

    fun actions(photoId: String, previewId: String?) = Actions(useAsPreview = previewId != photoId, noPreview = previewId != null)
}

/**
 * A small square of a day's preview photo, down the left of the journal.
 * Decoded small through [PhotoImages], whose cache is in memory only.
 */
@Composable
fun DayThumb(photo: DayPhoto, modifier: Modifier = Modifier, side: Dp = 52.dp) {
    val t = tokens
    val shape = RoundedCornerShape(Radius.control)
    val px = with(LocalDensity.current) { side.roundToPx() }
    val picture = rememberPicture(photo.previewPath to photo.thumbhash) {
        photo.previewPath?.let { PhotoImages.file(it, px) } ?: photo.thumbhash?.let { PhotoImages.thumbhash(it) }
    }
    val shown by animateFloatAsState(if (picture != null) 1f else 0f, motion.standard(), label = "thumb")
    Box(modifier.size(side).clip(shape).background(t.well).border(1.dp, t.hairline, shape)) {
        picture?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown }, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        }
    }
}
