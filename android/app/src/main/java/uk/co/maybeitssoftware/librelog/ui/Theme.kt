package uk.co.maybeitssoftware.librelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import uk.co.maybeitssoftware.librelog.R

/**
 * The house palette (SPEC.md §14). Literal hexes live only here; screens use
 * the role colours in [Tokens], which flip between light and dark.
 */
object Palette {
    const val CHALK = 0xFFFAF8F4
    const val WHITE = 0xFFFFFFFF
    const val GRAPE = 0xFF444054
    const val AZURE = 0xFF007FFF
    const val EMERALD = 0xFF4CC38E
    const val RASPBERRY = 0xFFD62246
    const val AMBER = 0xFFFFBF00
    const val PURPLE = 0xFF7A4DE8
    const val PINK = 0xFFFF88DC
    const val ORANGE = 0xFFFF6B2B
}

/** Semantic role colours. Components only ever touch these. */
@Immutable
data class Tokens(
    val paper: Color,
    val raised: Color,
    val hover: Color,
    val well: Color,
    val hairline: Color,
    val input: Color,
    val ink: Color,
    val muted: Color,
    val dim: Color,
    val primary: Color = Color(Palette.AZURE),
    val success: Color = Color(Palette.EMERALD),
    val danger: Color = Color(Palette.RASPBERRY),
    val warning: Color = Color(Palette.AMBER),
)

val LightTokens = Tokens(
    paper = Color(Palette.CHALK),
    raised = Color(Palette.WHITE),
    hover = Color(0xFFF1EFF1),
    well = Color(0xFFEDEBEF),
    hairline = Color(0xFFE6E4EA),
    input = Color(0xFFD8D5DD),
    ink = Color(Palette.GRAPE),
    muted = Color(0xFF6E6B7C),
    dim = Color(0xFFB6B3BF),
)

val DarkTokens = Tokens(
    paper = Color(0xFF1C1A23),
    raised = Color(0xFF25232F),
    hover = Color(0xFF2D2B38),
    well = Color(0xFF2D2B38),
    hairline = Color(0xFF34313F),
    input = Color(0xFF3E3B4A),
    ink = Color(0xFFF5F4F7),
    muted = Color(0xFFB6B3BF),
    dim = Color(0xFF6E6B7C),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

/** One radius scale (SPEC.md §14): 8 for cards and sheets, 6 for controls. */
object Radius {
    val card: Dp = 8.dp
    val control: Dp = 6.dp
}

object Typeface {
    val serif = FontFamily(
        Font(R.font.arvo_regular, FontWeight.Normal),
        Font(R.font.arvo_bold, FontWeight.Bold),
        Font(R.font.arvo_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.arvo_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

    val mono = FontFamily(
        Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.geist_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.geist_mono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.geist_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

    val title = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
    val headline = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = serif, fontSize = 17.sp, lineHeight = 24.sp)
    val prompt = TextStyle(fontFamily = serif, fontSize = 19.sp, lineHeight = 26.sp)
    val ui = TextStyle(fontSize = 15.sp)
    val small = TextStyle(fontSize = 13.sp)

    fun mono(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = mono, fontWeight = weight, fontSize = size.sp)

    /** The signature micro-label: 10sp, bold, uppercase, 0.15em tracking. */
    val micro = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.15.em)
}

@Composable
fun LibrelogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkTokens else LightTokens
    // Material is only plumbing (text fields, sheets); map it onto the roles.
    val scheme: ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = t.primary,
        onPrimary = Color.White,
        background = t.paper,
        onBackground = t.ink,
        surface = t.raised,
        onSurface = t.ink,
        surfaceVariant = t.well,
        onSurfaceVariant = t.muted,
        surfaceContainer = t.raised,
        surfaceContainerLow = t.raised,
        surfaceContainerHigh = t.raised,
        surfaceContainerHighest = t.well,
        surfaceContainerLowest = t.paper,
        outline = t.input,
        outlineVariant = t.hairline,
        error = t.danger,
        secondaryContainer = t.well,
        onSecondaryContainer = t.ink,
    )
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val tokens: Tokens
    @Composable get() = LocalTokens.current

/** A raised panel: half a step of tone plus a hairline, no shadow. */
@Composable
fun Modifier.card(padding: PaddingValues = PaddingValues(16.dp)): Modifier {
    val t = tokens
    val shape = RoundedCornerShape(Radius.card)
    return this
        .fillMaxWidth()
        .background(t.raised, shape)
        .border(1.dp, t.hairline, shape)
        .padding(padding)
}

@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color = tokens.muted) {
    Text(text.uppercase(), style = Typeface.micro, color = color, modifier = modifier)
}

/** A section eyebrow over a hairline, as used down the Today screen. */
@Composable
fun SectionHeader(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    val t = tokens
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            MicroLabel(title, Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = Typeface.mono(11), color = t.muted)
        }
        Hairline()
        content()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().height(1.dp).background(tokens.hairline),
    )
}

@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxHeight().width(1.dp).background(tokens.hairline),
    )
}
