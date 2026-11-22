package app.eventborg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.ui.theme.TopBar
import app.eventborg.R
import app.eventborg.data.CATEGORIES
import app.eventborg.graph

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SavedScreen(open: (String) -> Unit, openSource: (String) -> Unit) {
    val g = LocalContext.current.graph
    val data by g.repo.data.collectAsStateWithLifecycle()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val saved = remember(data, prefs.saved, now) { data.events.filter { it.id in prefs.saved && it.end.isAfter(now) } }
    val sources = remember(data, prefs.favSrcs) { data.sources.filter { it.id in prefs.favSrcs } }

    Scaffold(topBar = { TopBar(title = { Text(stringResource(R.string.tab_saved)) }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("h_events") { SectionHeader(stringResource(R.string.saved_events)) }
            if (saved.isEmpty()) {
                item("e_events") { Hint(stringResource(R.string.saved_events_empty)) }
            } else {
                items(saved, key = { it.id }) { ev ->
                    EventCard(ev, null, true, { g.prefs.toggleSaved(ev.id) }) { open(ev.id) }
                }
            }
            item("h_cats") { SectionHeader(stringResource(R.string.fav_categories)) }
            item("cats") {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORIES.forEach { c ->
                        FilterChip(c in prefs.favCats, { g.prefs.toggleCat(c) }, { Text(stringResource(catLabel(c))) })
                    }
                }
            }
            item("h_srcs") { SectionHeader(stringResource(R.string.fav_sources)) }
            if (sources.isEmpty()) {
                item("e_srcs") { Hint(stringResource(R.string.fav_sources_empty)) }
            } else {
                items(sources, key = { "s_" + it.id }) { s ->
                    SourceRow(s, true, { g.prefs.toggleSource(s.id) }) { openSource(s.id) }
                }
            }
            item("note") { Hint(stringResource(R.string.fav_note)) }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
