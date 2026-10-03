package uk.co.maybeitssoftware.librelog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import uk.co.maybeitssoftware.librelog.screens.TimelineScreen
import uk.co.maybeitssoftware.librelog.screens.TodayScreen
import uk.co.maybeitssoftware.librelog.ui.Hairline
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.VerticalHairline
import uk.co.maybeitssoftware.librelog.ui.tokens

enum class Tab(val title: String, val icon: ImageVector) {
    Today("Today", Icons.Outlined.WbSunny),
    Calendar("Calendar", Icons.Outlined.CalendarMonth),
    Timeline("Timeline", Icons.AutoMirrored.Outlined.ViewList),
    Search("Search", Icons.Outlined.Search),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Screens pushed over the tabs. */
sealed interface Route {
    data class Entry(val id: String) : Route
    data class History(val entryId: String) : Route
}

/**
 * The app's frame (SPEC.md §5): a bottom bar on phones, a side rail on wide
 * screens, with entries and their history pushed on top.
 */
@Composable
fun RootScreen(app: AppModel) {
    val t = tokens
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    val stack = remember { mutableStateListOf<Route>() }
    val open: (String) -> Unit = { stack += Route.Entry(it) }

    BoxWithConstraints(Modifier.fillMaxSize().background(t.paper)) {
        val wide = maxWidth >= 720.dp
        when (val top = stack.lastOrNull()) {
            is Route.Entry -> EntryEditorScreen(
                app, top.id,
                onClose = { stack.removeLastOrNull() },
                onShowHistory = { stack += Route.History(it) },
            )
            is Route.History -> ConflictsScreen(app, top.entryId, onClose = { stack.removeLastOrNull() })
            null -> if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(88.dp).fillMaxHeight().background(t.raised).statusBarsPadding().padding(top = 12.dp)) {
                        Tab.entries.forEach { TabItem(it, it == tab) { tab = it } }
                    }
                    VerticalHairline()
                    Box(Modifier.weight(1f).statusBarsPadding(), contentAlignment = Alignment.TopCenter) { TabContent(app, tab, open) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).statusBarsPadding()) { TabContent(app, tab, open) }
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

@Composable
private fun TabContent(app: AppModel, tab: Tab, open: (String) -> Unit) {
    when (tab) {
        Tab.Today -> TodayScreen(app, open)
        Tab.Calendar -> CalendarScreen(app, open)
        Tab.Timeline -> TimelineScreen(app, open)
        Tab.Search -> SearchScreen(app, open)
        Tab.Settings -> SyncSettingsScreen(app)
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val t = tokens
    val color = if (selected) t.primary else t.muted
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(tab.icon, null, Modifier.size(22.dp), tint = color)
        Text(tab.title, style = Typeface.small.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = color)
    }
}
