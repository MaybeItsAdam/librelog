package uk.co.maybeitssoftware.librelog.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Motion tokens: every duration and spring the app animates with. Quick and
 * tactile rather than bouncy; flat surfaces move, nothing gains elevation.
 *
 * With system animations off (developer options or accessibility "remove
 * animations" set the animator duration scale to 0) every spec collapses to
 * a snap, so state still changes but nothing moves.
 */
@Immutable
class Motion(val reduced: Boolean) {
    fun <T> quick(): FiniteAnimationSpec<T> = tweenOrSnap(QUICK)
    fun <T> standard(): FiniteAnimationSpec<T> = tweenOrSnap(STANDARD)
    fun <T> emphasized(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(EMPHASIZED, easing = LinearOutSlowInEasing)
    fun <T> tweenOrSnap(ms: Int): FiniteAnimationSpec<T> = if (reduced) snap() else tween(ms, easing = FastOutSlowInEasing)

    /** Pressing in and letting go: stiff, barely any overshoot. */
    fun <T> press(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh)

    /** Settling after a pop (a star being set). A little overshoot, no wobble. */
    fun <T> settle(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 700f)

    /** Things moving into place: list rows, the calendar's selection ring. */
    fun <T> glide(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    companion object {
        const val QUICK = 120
        const val STANDARD = 200
        const val EMPHASIZED = 280
        /** Delay between rows fading in on a screen's first load. */
        const val STAGGER = 28L
        const val PRESSED = 0.97f
        /** The one primary action presses a little deeper. */
        const val PRESSED_PRIMARY = 0.92f
        const val POP = 1.25f
    }
}

val LocalMotion = staticCompositionLocalOf { Motion(reduced = false) }

val motion: Motion
    @Composable get() = LocalMotion.current

/** Reads the system animator duration scale once per composition of the theme. */
@Composable
fun rememberMotion(): Motion {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        val scale = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        Motion(reduced = scale == 0f)
    }
}

// MARK: Haptics

/** The few kinds of haptic the app uses, so screens don't pick ad hoc. */
object Haptics {
    /** A small selection: a star, a day, a tab, a checkbox. */
    fun tick(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** Something was made or finished: a new entry, an import. */
    fun confirm(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.Confirm)

    fun reject(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.Reject)

    fun toggle(h: HapticFeedback, on: Boolean) =
        h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}

// MARK: Press feedback

/**
 * `clickable` that also shrinks slightly while pressed and springs back,
 * with an optional haptic tick on click.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = Motion.PRESSED,
    tick: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val m = motion
    val scale by animateFloatAsState(if (pressed && enabled) pressedScale else 1f, m.press(), label = "press")
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
        ) {
            if (tick) Haptics.tick(haptics)
            onClick()
        }
}

// MARK: First-load reveal

/**
 * Fades and lifts its content in, [index] rows after the first, when
 * [active]. Screens pass `active` only for the rows composed on their first
 * load, so rows scrolled into view later just appear.
 */
@Composable
fun Reveal(index: Int, active: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val m = motion
    val progress = remember { Animatable(if (active && !m.reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceIn(0, 12) * Motion.STAGGER)
            progress.animateTo(1f, m.emphasized())
        }
    }
    Column(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 10.dp.toPx()
        },
        content = content,
    )
}

// MARK: Lists

/** Rows fade in and out and glide to new positions as the list changes. */
fun LazyItemScope.rowMotion(m: Motion): Modifier =
    if (m.reduced) Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    else Modifier.animateItem(fadeInSpec = m.standard(), placementSpec = m.glide(), fadeOutSpec = m.quick())

/**
 * Hands out stagger positions for a screen's first load. Rows composed while
 * [active] get increasing indices; afterwards everything appears at once.
 */
class Stagger(val active: Boolean) {
    private var next = 0
    fun take(): Int = next++
}
