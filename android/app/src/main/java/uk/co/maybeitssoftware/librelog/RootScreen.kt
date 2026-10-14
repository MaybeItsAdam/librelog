package uk.co.maybeitssoftware.librelog

import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.maybeitssoftware.librelog.screens.CalendarScreen
import uk.co.maybeitssoftware.librelog.screens.ConflictsScreen
import uk.co.maybeitssoftware.librelog.screens.EntryEditorScreen
import uk.co.maybeitssoftware.librelog.screens.SearchScreen
import uk.co.maybeitssoftware.librelog.screens.SyncSettingsScreen
import uk.co.maybeitssoftware.librelog.screens.JournalScreen
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.IconAction
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.VerticalHairline
import uk.co.maybeitssoftware.librelog.ui.tokens

enum class Tab(val title: String, val icon: ImageVector) {
    Journal("Journal", Icons.AutoMirrored.Outlined.ViewList),
    Calendar("Calendar", Icons.Outlined.CalendarMonth),
    Search("Search", Icons.Outlined.Search),
}

/** Screens pushed over the tabs. */
sealed interface Route {
    data class Entry(val id: String) : Route
    data class History(val entryId: String) : Route
    /** Sync settings, opened from the gear in Journal. */
    data object Settings : Route
}

/**
 * The app's frame (SPEC.md §5): a bottom bar on phones, a side rail on wide
 * screens, with entries and their history pushed on top.
 */
@Composable
fun RootScreen(app: AppModel) {
    val t = tokens
    var tab by rememberSaveable { mutableStateOf(Tab.Journal) }
    val stack = remember { mutableStateListOf<Route>() }
    // Each tab keeps its saved state (scroll position, selected day, search)
    // while an entry is open over it and across tab switches, so Back
    // returns you to where you were.
    val tabStates = rememberSaveableStateHolder()
    val open: (String) -> Unit = { stack += Route.Entry(it) }
    // Leaving a pushed screen also puts the keyboard away; otherwise the
    // editor's keyboard stays up over the list you came back to.
    val keyboard = LocalSoftwareKeyboardController.current
    val pop: () -> Unit = {
        keyboard?.hide()
        stack.removeLastOrNull()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(t.paper)) {
        val wide = maxWidth >= 720.dp
        val m = motion
        AnimatedContent(
            targetState = Screen(stack.lastOrNull(), stack.size),
            transitionSpec = {
                if (m.reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (targetState.depth > initialState.depth) {
                    // Pushed: slides in from the right over a list that
                    // drifts back a little.
                    (slideInHorizontally(m.glide()) { it / 3 } + fadeIn(m.standard()))
                        .togetherWith(slideOutHorizontally(m.glide()) { -it / 12 } + fadeOut(m.standard()))
                        .apply { targetContentZIndex = 1f }
                } else {
                    // Popped: the screen leaves to the right, revealing the
                    // one underneath.
                    (slideInHorizontally(m.glide()) { -it / 12 } + fadeIn(m.standard()))
                        .togetherWith(slideOutHorizontally(m.glide()) { it / 3 } + fadeOut(m.quick()))
                        .apply { targetContentZIndex = -1f }
                }
            },
            label = "stack",
        ) { screen ->
            when (val top = screen.route) {
                is Route.Entry -> EntryEditorScreen(
                    app, top.id,
                    onClose = pop,
                    onShowHistory = { stack += Route.History(it) },
                )
                is Route.History -> ConflictsScreen(app, top.entryId, onClose = pop)
                Route.Settings -> SettingsFrame(app, onClose = pop)
                null -> if (wide) {
                    Row(Modifier.fillMaxSize().background(t.paper)) {
                        Column(Modifier.width(88.dp).fillMaxHeight().background(t.raised).statusBarsPadding().padding(top = 12.dp)) {
                            Tab.entries.forEach { TabItem(it, it == tab) { tab = it } }
                        }
                        VerticalHairline()
                        Box(Modifier.weight(1f).statusBarsPadding(), contentAlignment = Alignment.TopCenter) { TabContent(tabStates, app, tab, open) { stack += Route.Settings } }
                    }
                } else {
                    Column(Modifier.fillMaxSize().background(t.paper)) {
                        Box(Modifier.weight(1f).statusBarsPadding()) { TabContent(tabStates, app, tab, open) { stack += Route.Settings } }
                        Column(Modifier.fillMaxWidth().background(t.raised).navigationBarsPadding()) {
                            Hairline()
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Tab.entries.forEach { Box(Modifier.weight(1f)) { TabItem(it, it == tab) { tab = it } } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the stack shows, with its depth so transitions know push from pop. */
private data class Screen(val route: Route?, val depth: Int)

@Composable
private fun TabContent(holder: SaveableStateHolder, app: AppModel, tab: Tab, open: (String) -> Unit, openSettings: () -> Unit) {
    // A quick crossfade between tabs; each keeps its own saved state.
    Crossfade(tab, animationSpec = motion.quick(), label = "tab") { shown ->
        holder.SaveableStateProvider(shown.name) { when (shown) {
            Tab.Journal -> JournalScreen(app, open, openSettings)
            Tab.Calendar -> CalendarScreen(app, open)
            Tab.Search -> SearchScreen(app, open)
        } }
    }
}

/** Settings as a pushed screen: a back arrow over the sync settings. */
@Composable
private fun SettingsFrame(app: AppModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(tokens.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClose)
        }
        Box(Modifier.weight(1f)) { SyncSettingsScreen(app) }
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val t = tokens
    val m = motion
    val haptics = LocalHapticFeedback.current
    val color by animateColorAsState(if (selected) t.primary else t.muted, m.standard(), label = "tab")
    // The icon lifts a touch when its tab is chosen.
    val lift by animateFloatAsState(if (selected) 1f else 0f, m.settle(), label = "lift")
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(role = Role.Tab, pressedScale = 0.92f) {
                if (!selected) Haptics.tick(haptics)
                onClick()
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            tab.icon, null,
            Modifier.size(22.dp).graphicsLayer {
                translationY = -lift * 2.dp.toPx()
                scaleX = 1f + lift * 0.08f
                scaleY = 1f + lift * 0.08f
            },
            tint = color,
        )
        Text(tab.title, style = Typeface.small.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = color)
    }
}
