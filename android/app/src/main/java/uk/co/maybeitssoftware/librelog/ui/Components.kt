package uk.co.maybeitssoftware.librelog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        Text(text, style = Typeface.ui.copy(fontWeight = FontWeight.SemiBold), color = t.onPrimary)
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

/**
 * The one primary action: a square-cornered azure "New entry" button, no
 * shadow. Held, it offers [onLongClick]: a new entry on another day.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "New entry", onLongClick: (() -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) Motion.PRESSED_PRIMARY else 1f, motion.press(), label = "press")
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .padding(20.dp)
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = onLongClick?.let { "New entry on another day" },
                onLongClick = onLongClick?.let {
                    {
                        Haptics.tick(haptics)
                        it()
                    }
                },
            )
            .background(tokens.primary, RoundedCornerShape(Radius.card)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, label, Modifier.size(26.dp), tint = tokens.onPrimary)
    }
}

/**
 * A switch in the house colours: azure track when on, a well with an input
 * border when off. The knob is round; that's the one thing that may be.
 */
@Composable
fun Toggle(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onChange?.let { change ->
            { now: Boolean ->
                Haptics.toggle(haptics, now)
                change(now)
            }
        },
        enabled = enabled,
        modifier = modifier,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = t.onPrimary,
            checkedTrackColor = t.primary,
            checkedBorderColor = t.primary,
            uncheckedThumbColor = t.muted,
            uncheckedTrackColor = t.well,
            uncheckedBorderColor = t.input,
            disabledCheckedTrackColor = t.dim,
            disabledUncheckedTrackColor = t.well,
            disabledUncheckedBorderColor = t.hairline,
            disabledUncheckedThumbColor = t.dim,
        ),
    )
}

/** A labelled row with a [Toggle] at the end; the whole row is the 48dp target. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch) {
                Haptics.toggle(haptics, it)
                onChange(it)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Typeface.ui, color = t.ink)
            if (detail != null) Text(detail, style = Typeface.small.copy(fontSize = 12.sp), color = t.muted)
        }
        // The row handles the tap, so the switch is only a picture of it.
        Toggle(checked, onChange = null, Modifier.padding(start = 12.dp).clearAndSetSemantics {})
    }
}

/**
 * A bordered single- or multi-line text field. 16sp, so phones don't zoom
 * in on it; [secret] hides what's typed.
 */
@Composable
fun InputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    minLines: Int = 1,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    error: Boolean = false,
) {
    val t = tokens
    val style = Typeface.ui.copy(fontSize = 16.sp, color = t.ink)
    val shape = RoundedCornerShape(Radius.control)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = style,
        minLines = minLines,
        singleLine = minLines == 1,
        cursorBrush = SolidColor(t.primary),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            capitalization = if (secret) KeyboardCapitalization.None else capitalization,
            autoCorrectEnabled = !secret,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(t.raised, shape)
            .border(1.dp, if (error) t.danger.copy(alpha = 0.6f) else t.input, shape)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = t.dim))
            inner()
        },
    )
}
