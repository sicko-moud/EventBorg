package app.eventborg.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.eventborg.ui.theme.G
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.eventborg.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object FeedR
@Serializable object SavedR
@Serializable object SourcesR
@Serializable object SettingsR
@Serializable data class WatchR(val link: String? = null)
@Serializable object GroupPostsR
@Serializable object ListenerR
@Serializable object AddEventR
@Serializable data class DetailR(val id: String)
@Serializable data class SourceR(val id: String)
@Serializable data class DraftR(val text: String = "", val img: String? = null, val post: String? = null)

private class Tab(val route: Any, val cls: KClass<*>, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(FeedR, FeedR::class, R.string.tab_feed, Icons.Outlined.Home),
    Tab(SavedR, SavedR::class, R.string.tab_saved, Icons.Outlined.Star),
    Tab(WatchR(), WatchR::class, R.string.tab_watch, Icons.Outlined.Notifications),
    Tab(SourcesR, SourcesR::class, R.string.tab_sources, Icons.AutoMirrored.Outlined.List),
    Tab(SettingsR, SettingsR::class, R.string.tab_settings, Icons.Outlined.Settings),
)

private fun NavHostController.tab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppNav(links: MutableStateFlow<Any?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val topLevel = TABS.any { dest?.hasRoute(it.cls) == true }
    val openEvent: (String) -> Unit = { nav.navigate(DetailR(it)) }
    val openSource: (String) -> Unit = { nav.navigate(SourceR(it)) }
    val go: (Any) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }

    val link by links.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        links.value = null
        when {
            l == FeedR -> nav.tab(FeedR)
            l is WatchR && l.link == null -> nav.tab(WatchR())
            else -> go(l)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (topLevel) {
                Column(Modifier.background(G.Surface)) {
                    HorizontalDivider(color = G.Line)
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
                        TABS.forEach { t ->
                            val selected = dest?.hierarchy?.any { it.hasRoute(t.cls) } == true
                            val tint by animateColorAsState(if (selected) G.Text else G.Muted, tween(150), label = "tab")
                            Column(
                                Modifier.weight(1f).heightIn(min = 64.dp)
                                    .selectable(selected, role = Role.Tab, onClick = { nav.tab(t.route) })
                                    .padding(top = 10.dp, bottom = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(4.dp))
                                BasicText(
                                    stringResource(t.label),
                                    style = MaterialTheme.typography.labelMedium.copy(color = tint),
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                                Spacer(Modifier.height(5.dp))
                                Box(Modifier.size(width = 16.dp, height = 2.dp).background(if (selected) G.Accent else Color.Transparent, G.Radius))
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = FeedR, modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
            composable<FeedR> { FeedScreen(openEvent) }
            composable<SavedR> { SavedScreen(openEvent, openSource) }
            composable<WatchR> { WatchScreen(it.toRoute<WatchR>().link, { go(ListenerR) }, { go(GroupPostsR) }, openSource) }
            composable<SourcesR> { SourcesScreen(openSource) { go(AddEventR) } }
            composable<SettingsR> { SettingsScreen({ go(ListenerR) }, { go(AddEventR) }) }
            composable<DetailR> { DetailScreen(it.toRoute<DetailR>().id, back, openSource) }
            composable<SourceR> { SourcePage(it.toRoute<SourceR>().id, back, openEvent) }
            composable<GroupPostsR> { GroupPostsScreen(back, go) }
            composable<ListenerR> { ListenerScreen(back) }
            composable<AddEventR> { AddEventScreen(back, go) }
            composable<DraftR> {
                val r = it.toRoute<DraftR>()
                DraftScreen(r, back)
            }
        }
    }
}
