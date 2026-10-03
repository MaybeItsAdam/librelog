package uk.co.maybeitsadam.dailylog.ui

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.co.maybeitsadam.dailylog.Day
import uniffi.dailylog_core.EntrySummary

/**
 * One entry in Today, Timeline, Search and On This Day.
 *
 * [snippet] is a search snippet with matches wrapped in U+E000…U+E001 (see
 * the core's SearchHit).
 */
@Composable
fun EntryRow(entry: EntrySummary, onClick: () -> Unit, showDate: Boolean = true, snippet: String? = null) {
    val t = tokens
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val meta = listOfNotNull(if (showDate) Day.short(entry.date) else null, entry.time).joinToString(" · ")
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

/** Tappable stars; tapping the current rating clears it. 44dp tall targets. */
@Composable
fun RatingPicker(rating: Double?, onSet: (Double?) -> Unit) {
    val t = tokens
    Row {
        for (i in 1..5) {
            val value = i.toDouble()
            val r = rating ?: 0.0
            Box(
                Modifier
                    .size(width = 30.dp, height = 44.dp)
                    .clickable(role = Role.Button) { onSet(if (rating == value) null else value) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        r >= value -> Icons.Filled.Star
                        r >= value - 0.5 -> Icons.Filled.StarHalf
                        else -> Icons.Outlined.StarOutline
                    },
                    "$i stars",
                    Modifier.size(18.dp),
                    tint = if (r >= value - 0.5) t.warning else t.dim,
                )
            }
        }
    }
}

/** An icon with a 44dp hit target around a smaller painted glyph. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = tokens.ink, enabled: Boolean = true) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (enabled) tint else tokens.dim)
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
    Box(
        modifier
            .fillMaxWidth()
            .background(if (enabled) t.primary else t.dim, RoundedCornerShape(Radius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
            .border(1.dp, t.input, RoundedCornerShape(Radius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Typeface.ui.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/**
 * Rotating reflection prompts (SPEC.md §6.1). Picked by date, so a day always
 * shows the same one; the same hash as the Apple app, so devices agree.
 */
object Prompts {
    val all = listOf(
        "What's on your mind today?",
        "What made today different from yesterday?",
        "What are you looking forward to?",
        "Three things you're grateful for.",
        "What took most of your energy today?",
        "Who did you spend time with?",
        "What would you tell yourself a year ago?",
        "What did you learn today?",
        "What's one small win from today?",
        "Where did the day go?",
    )

    fun today(date: String): String {
        var n = 0L
        for (c in date) n = (n * 31 + c.code) // wraps like Swift's &* and &+ on 64-bit Int
        return all[(Math.floorMod(kotlin.math.abs(n), all.size.toLong())).toInt()]
    }
}
