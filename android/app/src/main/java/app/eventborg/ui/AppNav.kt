package app.eventborg.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
    Tab(FeedR, FeedR::class, R.string.tab_feed, Icons.Filled.Home),
    Tab(SavedR, SavedR::class, R.string.tab_saved, Icons.Filled.Star),
    Tab(WatchR(), WatchR::class, R.string.tab_watch, Icons.Filled.Notifications),
    Tab(SourcesR, SourcesR::class, R.string.tab_sources, Icons.AutoMirrored.Filled.List),
    Tab(SettingsR, SettingsR::class, R.string.tab_settings, Icons.Filled.Settings),
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
                NavigationBar {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = dest?.hierarchy?.any { it.hasRoute(t.cls) } == true,
                            onClick = { nav.tab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = FeedR, modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
            composable<FeedR> { FeedScreen(openEvent) }
            composable<SavedR> { SavedScreen(openEvent, openSource) }
            composable<WatchR> { WatchScreen(it.toRoute<WatchR>().link, { go(ListenerR) }, { go(GroupPostsR) }) }
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
