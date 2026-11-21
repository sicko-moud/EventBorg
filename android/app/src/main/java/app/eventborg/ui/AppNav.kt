package app.eventborg.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.eventborg.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object FeedR
@Serializable object SavedR
@Serializable object SourcesR
@Serializable object SettingsR
@Serializable data class DetailR(val id: String)
@Serializable data class SourceR(val id: String)

private class Tab(val route: Any, val cls: KClass<*>, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(FeedR, FeedR::class, R.string.tab_feed, Icons.Filled.Home),
    Tab(SavedR, SavedR::class, R.string.tab_saved, Icons.Filled.Star),
    Tab(SourcesR, SourcesR::class, R.string.tab_sources, Icons.AutoMirrored.Filled.List),
    Tab(SettingsR, SettingsR::class, R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val topLevel = TABS.any { dest?.hasRoute(it.cls) == true }
    val openEvent: (String) -> Unit = { nav.navigate(DetailR(it)) }
    val openSource: (String) -> Unit = { nav.navigate(SourceR(it)) }
    val back: () -> Unit = { nav.popBackStack() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = dest?.hierarchy?.any { it.hasRoute(t.cls) } == true,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = FeedR, modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
            composable<FeedR> { FeedScreen(openEvent) }
            composable<SavedR> { SavedScreen(openEvent, openSource) }
            composable<SourcesR> { SourcesScreen(openSource) }
            composable<SettingsR> { SettingsScreen() }
            composable<DetailR> { DetailScreen(it.toRoute<DetailR>().id, back, openSource) }
            composable<SourceR> { SourcePage(it.toRoute<SourceR>().id, back, openEvent) }
        }
    }
}
