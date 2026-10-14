package uk.co.maybeitssoftware.librelog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.co.maybeitssoftware.librelog.Day
import uniffi.librelog_core.EntrySummary

/**
 * One entry in Today, Timeline, Search and On This Day.
 *
 * [snippet] is a search snippet with matches wrapped in U+E000…U+E001 (see
 * the core's SearchHit). A multi-day entry always shows its range; listed
 * under one of the days it covers ([onDay]), it also says which day that is.
 */
@Composable
fun EntryRow(entry: EntrySummary, onClick: () -> Unit, showDate: Boolean = true, snippet: String? = null, onDay: String? = null) {
    val t = tokens
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val date = when {
                entry.endDate != null -> Day.range(entry.date, entry.endDate)
                showDate -> Day.short(entry.date)
                else -> null
            }
            onDay?.let { Day.spanDay(entry.date, entry.endDate, it) }?.let { (n, of) ->
                MicroLabel("Day $n of $of", Modifier.padding(end = 8.dp))
            }
            val meta = listOfNotNull(date, entry.time).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = Typeface.mono(11), color = t.muted)
            Spacer(Modifier.weight(1f))
            if (entry.isFavorite) Icon(Icons.Filled.Star, "Favourite", Modifier.size(12.dp), tint = t.warning)
            entry.rating?.let { Stars(it, size = 11) }
        }
        val title = entry.title
        if (!title.isNullOrBlank()) {
            Text(title, style = Typeface.headline, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val body = snippet?.let { highlighted(it, t) } ?: AnnotatedString(entry.excerpt.ifEmpty { "Empty entry" })
        Text(
            body,
            style = Typeface.body.copy(fontSize = Typeface.body.fontSize * 0.9f),
            color = if (entry.excerpt.isEmpty() && snippet == null) t.dim else t.ink,
            maxLines = if (title.isNullOrBlank()) 3 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.tagNames.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                entry.tagNames.forEach { TagChip(it) }
            }
        }
    }
}

private fun highlighted(snippet: String, t: Tokens): AnnotatedString = buildAnnotatedString {
    var inMatch = false
    // One line, like the excerpt: paragraph breaks become spaces.
    for (ch in snippet.replace(Regex("\\s+"), " ")) {
        when (ch) {
            '' -> inMatch = true
            '' -> inMatch = false
            else -> if (inMatch) {
                pushStyle(SpanStyle(background = t.warning.copy(alpha = 0.3f), fontWeight = FontWeight.Bold))
                append(ch)
                pop()
            } else {
                append(ch)
            }
        }
    }
}

/** A squarish status tag (6dp, not a pill). */
@Composable
fun TagChip(name: String, modifier: Modifier = Modifier) {
    val t = tokens
    Text(
        name,
        style = Typeface.small.copy(fontSize = Typeface.small.fontSize * 0.92f),
        color = t.ink,
        modifier = modifier
            .background(t.well, RoundedCornerShape(Radius.control))
            .border(1.dp, t.hairline, RoundedCornerShape(Radius.control))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Stars(rating: Double, size: Int = 12) {
    val t = tokens
    Row {
        for (i in 1..5) {
            val icon = when {
                rating >= i -> Icons.Filled.Star
                rating >= i - 0.5 -> Icons.Filled.StarHalf
                else -> Icons.Outlined.StarOutline
            }
            Icon(icon, null, Modifier.size(size.dp), tint = if (rating >= i - 0.5) t.warning else t.dim)
        }
    }
}

/**
 * Tappable stars; tapping the current rating clears it. 44dp tall targets.
 * Stars that light up pop in turn, left to right.
 */
@Composable
fun RatingPicker(rating: Double?, onSet: (Double?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val r = rating ?: 0.0
    // The rating before the last tap, so newly lit stars stagger from there.
    var from by remember { mutableStateOf(r) }
    Row {
        for (i in 1..5) {
            val value = i.toDouble()
            RatingStar(i, r = r, order = (i - 1 - from.toInt()).coerceAtLeast(0)) {
                Haptics.tick(haptics)
                from = r
                onSet(if (rating == value) null else value)
            }
        }
    }
}

@Composable
private fun RatingStar(i: Int, r: Double, order: Int, onClick: () -> Unit) {
    val t = tokens
    val m = motion
    val lit = r >= i - 0.5
    val tint by animateColorAsState(if (lit) t.warning else t.dim, m.standard(), label = "star")
    val scale = remember { Animatable(1f) }
    var wasLit by remember { mutableStateOf(lit) }
    LaunchedEffect(lit) {
        if (lit && !wasLit && !m.reduced) {
            delay(order * 45L)
            scale.animateTo(Motion.POP, tween(90, easing = FastOutSlowInEasing))
            scale.animateTo(1f, m.settle())
        }
        wasLit = lit
    }
    Box(
        Modifier
            .size(width = 30.dp, height = 44.dp)
            .pressable(pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when {
                r >= i -> Icons.Filled.Star
                lit -> Icons.Filled.StarHalf
                else -> Icons.Outlined.StarOutline
            },
            "$i stars",
            Modifier.size(18.dp).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            tint = tint,
        )
    }
}

/** An icon with a 44dp hit target around a smaller painted glyph. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = tokens.ink, enabled: Boolean = true) {
    Box(
        Modifier
            .size(44.dp)
            .pressable(enabled = enabled, pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color by animateColorAsState(if (enabled) tint else tokens.dim, motion.standard(), label = "icon")
        Icon(icon, label, Modifier.size(22.dp), tint = color)
    }
}

/** Tinted fill + border + text of one hue: the status convention. */
@Composable
fun StatusBanner(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(Radius.control)
    Text(
        text,
        style = Typeface.small.copy(fontWeight = FontWeight.Medium),
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f), shape)
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
    )
}

/** A full-width primary button: azure fill, 6dp corners, 44dp+ tall. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = tokens
    val fill by animateColorAsState(if (enabled) t.primary else t.dim, motion.standard(), label = "fill")
    Box(
        modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .background(fill, RoundedCornerShape(Radius.control))
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Typeface.ui.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = tokens.ink) {
    val t = tokens
    Box(
        modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .border(1.dp, t.input, RoundedCornerShape(Radius.control))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Typeface.ui.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/** The one primary action: a square-cornered azure "New entry" button, no shadow. */
@Composable
fun NewEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(20.dp)
            .size(56.dp)
            .pressable(pressedScale = Motion.PRESSED_PRIMARY, onClick = onClick)
            .background(tokens.primary, RoundedCornerShape(Radius.card)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, "New entry", Modifier.size(26.dp), tint = Color.White)
    }
}
