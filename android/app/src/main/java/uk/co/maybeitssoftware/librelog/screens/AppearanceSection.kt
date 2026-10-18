package uk.co.maybeitssoftware.librelog.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.ui.Appearance
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.MicroLabel
import uk.co.maybeitssoftware.librelog.ui.Palette
import uk.co.maybeitssoftware.librelog.ui.Radius
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.tokens

/**
 * Settings → Appearance (docs/appearance.md): theme, accent, reading face
 * and text size. Each choice applies at once and syncs to the user's other
 * devices.
 */
@Composable
fun AppearanceSection(app: AppModel) {
    val a = app.appearance
    Section("Appearance") {
        Choice("Theme") {
            Appearance.Theme.entries.forEach { th ->
                Option(th.title, th == a.theme, Modifier.weight(1f)) { app.setAppearance(Appearance.THEME, th.key) }
            }
        }
        Choice("Accent") {
            Appearance.Accent.entries.forEach { ac ->
                Swatch(ac, ac == a.accent, Modifier.weight(1f)) { app.setAppearance(Appearance.ACCENT, ac.key) }
            }
        }
        Choice("Typeface") {
            Appearance.ReadingFont.entries.forEach { f ->
                Option(
                    f.title, f == a.font, Modifier.weight(1f),
                    style = TextStyle(fontFamily = Typeface.family(f), fontSize = 15.sp),
                ) { app.setAppearance(Appearance.FONT, f.key) }
            }
        }
        Choice("Text size") {
            Appearance.TextSize.entries.forEach { size ->
                Option(
                    "Aa", size == a.textSize, Modifier.weight(1f),
                    style = Typeface.body.copy(fontSize = 15.sp * size.scale, lineHeight = 20.sp),
                    label = size.title,
                ) { app.setAppearance(Appearance.TEXT_SIZE, size.key) }
            }
        }
        Text(
            "The typeface and size are for what you write: entries, titles and the editor. " +
                "They follow your other devices, as do the theme and accent.",
            style = Typeface.small.copy(fontSize = 12.sp),
            color = tokens.muted,
        )
    }
}

@Composable
private fun Choice(title: String, options: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MicroLabel(title)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = options)
    }
}

/** A squarish 6dp option: selection is a border, never elevation. */
@Composable
private fun Option(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    style: TextStyle = Typeface.small,
    label: String? = null,
    onClick: () -> Unit,
) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(Radius.control)
    val border by animateColorAsState(if (selected) t.primary else t.input, motion.quick(), label = "option")
    Column(
        modifier
            .heightIn(min = 48.dp)
            .background(if (selected) t.primary.copy(alpha = 0.1f) else t.raised, shape)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .selectable(selected, role = Role.RadioButton) {
                if (!selected) Haptics.tick(haptics)
                onClick()
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = style.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = if (selected) t.ink else t.muted, maxLines = 1)
        if (label != null) Text(label.uppercase(), style = Typeface.micro.copy(fontSize = 9.sp), color = t.muted, maxLines = 1)
    }
}

@Composable
private fun Swatch(accent: Appearance.Accent, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(Radius.control)
    val hue = Color(accent.hex)
    val ring by animateColorAsState(if (selected) t.ink else Color.Transparent, motion.quick(), label = "swatch")
    Box(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton) {
                if (!selected) Haptics.tick(haptics)
                onClick()
            }
            .semantics { contentDescription = accent.title },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .aspectRatio(1f)
                .border(1.5.dp, ring, shape)
                .padding(4.dp)
                .background(hue, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = if (accent.inkOnFill) Color(Palette.GRAPE) else Color.White)
            }
        }
    }
}
