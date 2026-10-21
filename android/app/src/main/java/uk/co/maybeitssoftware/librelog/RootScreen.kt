package uk.co.maybeitssoftware.librelog

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
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
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import uk.co.maybeitssoftware.librelog.screens.OnThisDayScreen
import uk.co.maybeitssoftware.librelog.screens.SettingsScreen
import uk.co.maybeitssoftware.librelog.screens.JournalScreen
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Haptics
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.motion
import uk.co.maybeitssoftware.librelog.ui.pressable
import uk.co.maybeitssoftware.librelog.ui.VerticalHairline
import uk.co.maybeitssoftware.librelog.ui.tokens

enum class Tab(val title: String, val icon: ImageVector) {
    Journal("Journal", Icons.AutoMirrored.Outlined.ViewList),
    Calendar("Calendar", Icons.Outlined.CalendarMonth),
    Search("Search", Icons.Outlined.Search),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Screens pushed over the tabs. */
sealed interface Route {
    data class Entry(val id: String) : Route
    data class History(val entryId: String) : Route
    /** Opened from the bottom bar's left end. */
    data object OnThisDay : Route

    companion object {
        /** The stack as strings, so it survives rotation and process death. */
        val StackSaver: Saver<SnapshotStateList<Route>, Any> = listSaver(
            save = { stack -> stack.map(::encode) },
            restore = { saved -> saved.mapNotNull(::decode).toMutableStateList() },
        )

        fun encode(route: Route): String = when (route) {
            is Entry -> "entry:${route.id}"
            is History -> "history:${route.entryId}"
            OnThisDay -> "on-this-day"
        }

        fun decode(saved: String): Route? = when {
            saved.startsWith("entry:") -> Entry(saved.removePrefix("entry:"))
            saved.startsWith("history:") -> History(saved.removePrefix("history:"))
            saved == "on-this-day" -> OnThisDay
            else -> null
        }
    }
}

/**
 * The app's frame (SPEC.md §5): a bottom bar on phones, a side rail on wide
 * screens, with entries and their history pushed on top.
 */
@Composable
fun RootScreen(app: AppModel) {
    val t = tokens
    // The tabs are pages: swipe between them, or tap the bar. The pager's
    // state is saved, so the open tab survives rotation.
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    val m0 = motion
    val tab = Tab.entries[pager.currentPage]
    val select: (Tab) -> Unit = { to ->
        scope.launch {
            if (m0.reduced) pager.scrollToPage(to.ordinal)
            else pager.animateScrollToPage(to.ordinal, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    // Saved too, so rotation, dark mode or a resize leaves the entry open.
    val stack = rememberSaveable(saver = Route.StackSaver) { mutableStateListOf() }
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
                Route.OnThisDay -> OnThisDayScreen(app, open, onClose = pop)
                null -> if (wide) {
                    Row(Modifier.fillMaxSize().background(t.paper)) {
                        Column(Modifier.width(88.dp).fillMaxHeight().background(t.raised).statusBarsPadding().navigationBarsPadding().padding(top = 12.dp)) {
                            Tab.entries.forEach { TabItem(it.title, it.icon, it == tab) { select(it) } }
                            Spacer(Modifier.weight(1f))
                            Hairline()
                            TabItem("On this day", Icons.Outlined.History, false) { stack += Route.OnThisDay }
                        }
                        VerticalHairline()
                        Box(Modifier.weight(1f).statusBarsPadding(), contentAlignment = Alignment.TopCenter) { TabPages(tabStates, pager, app, open) }
                    }
                } else {
                    Column(Modifier.fillMaxSize().background(t.paper)) {
                        Box(Modifier.weight(1f).statusBarsPadding()) { TabPages(tabStates, pager, app, open) }
                        Column(Modifier.fillMaxWidth().background(t.raised).navigationBarsPadding()) {
                            Hairline()
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                // On This Day sits apart at the left end: it
                                // opens over the tabs rather than being one.
                                Box(Modifier.weight(1f)) {
                                    TabItem("On this day", Icons.Outlined.History, false) { stack += Route.OnThisDay }
                                }
                                VerticalHairline(Modifier.padding(vertical = 8.dp))
                                Tab.entries.forEach { Box(Modifier.weight(1f)) { TabItem(it.title, it.icon, it == tab) { select(it) } } }
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
private fun TabPages(holder: SaveableStateHolder, pager: PagerState, app: AppModel, open: (String) -> Unit) {
    // Each tab keeps its own saved state while it's off screen. Pages next
    // to the shown one are only composed while a swipe reveals them.
    HorizontalPager(pager, Modifier.fillMaxSize(), key = { Tab.entries[it].name }) { page ->
        val shown = Tab.entries[page]
        holder.SaveableStateProvider(shown.name) { when (shown) {
            Tab.Journal -> JournalScreen(app, open)
            Tab.Calendar -> CalendarScreen(app, open)
            Tab.Search -> SearchScreen(app, open)
            Tab.Settings -> SettingsScreen(app)
        } }
    }
}

@Composable
private fun TabItem(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
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
            .semantics(mergeDescendants = true) {}
            .pressable(role = Role.Tab, pressedScale = 0.92f) {
                if (!selected) Haptics.tick(haptics)
                onClick()
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            icon, null,
            Modifier.size(22.dp).graphicsLayer {
                translationY = -lift * 2.dp.toPx()
                scaleX = 1f + lift * 0.08f
                scaleY = 1f + lift * 0.08f
            },
            tint = color,
        )
        Text(title, maxLines = 1, softWrap = false, style = Typeface.small.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = color)
    }
}
