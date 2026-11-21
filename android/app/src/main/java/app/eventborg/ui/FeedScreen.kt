package app.eventborg.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.R
import app.eventborg.data.CATEGORIES
import app.eventborg.data.DATE_NOW
import app.eventborg.data.DATE_TONIGHT
import app.eventborg.data.DATE_WEEKEND
import app.eventborg.data.DATE_ANY
import app.eventborg.data.Filter
import app.eventborg.data.Problem
import app.eventborg.data.dateRange
import app.eventborg.data.fold
import app.eventborg.data.matchesFilter
import app.eventborg.data.matchesQuery
import app.eventborg.data.queryTokens
import app.eventborg.data.sectioned
import app.eventborg.data.studentPicks
import app.eventborg.data.StoryItem
import app.eventborg.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun categoryNames(): Map<String, String> {
    val ctx = LocalContext.current
    val locale = uiLocale()
    return remember(locale) { CATEGORIES.associateWith { fold(ctx.getString(catLabel(it))) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val data by g.repo.data.collectAsStateWithLifecycle()
    val status by g.repo.status.collectAsStateWithLifecycle()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(status.refreshing) { if (!status.refreshing) pulled = false }

    val now = rememberNow()
    val catNames = categoryNames()
    val tokens = remember(query) { queryTokens(query) }
    val f = prefs.filter
    val sections = remember(data, prefs, tokens, now, catNames) {
        val range = dateRange(f, now)
        val faves = prefs.faves
        val list = data.events.filter {
            it.end.isAfter(now) && matchesFilter(it, f, faves, range) && (tokens.isEmpty() || matchesQuery(it, tokens, catNames))
        }
        sectioned(list, now.toLocalDate())
    }
    val picks = remember(data, now, prefs.studentsFirst) { if (prefs.studentsFirst) studentPicks(data.events, now) else emptyList() }
    val askNotify = notifyAskNeeded(prefs)
    val stories by g.stories.items.collectAsStateWithLifecycle()
    val watch by g.watch.state.collectAsStateWithLifecycle()
    val strip = remember(stories, watch, prefs.favSrcs, data.sources, now) {
        val cut = now.toInstant().toEpochMilli() - 24 * 3600_000L
        val mine = prefs.favSrcs + watch.profiles.map { it.key }
        val pri = data.sources.filter { it.pri }.map { it.id }.toSet()
        stories.filter { it.ts > cut && it.key !in watch.muted }
            .sortedWith(compareByDescending<StoryItem> { it.key in pri }.thenByDescending { it.key in mine }.thenByDescending { it.ts })
    }

    Scaffold(
        topBar = {
            SearchBar(query, { query = it }, f.active) { sheet = true }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = pulled && status.refreshing,
            onRefresh = { pulled = true; g.repo.refresh() },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                // Always present (possibly empty): an item inserted above the first visible one later would open off-screen.
                item("top") {
                    Column {
                        if (strip.isNotEmpty()) {
                            StoryStrip(
                                strip,
                                onOpen = { s ->
                                    g.scope.launch(Dispatchers.IO) { g.stories.seen(s.key) }
                                    ctx.openApp(s.url)
                                },
                                onMute = { s ->
                                    g.watch.setMuted(s.key, true)
                                    Toast.makeText(ctx, ctx.getString(R.string.muted_toast, s.name), Toast.LENGTH_LONG).show()
                                },
                            )
                        }
                        if (askNotify) NotifyAsk { g.prefs.setNotifAsked() }
                    }
                }
                item("quick") { QuickChips(f, prefs.studentsFirst) { g.prefs.setFilter(it) } }
                val problem = status.problem
                if (problem != null && data.events.isNotEmpty()) {
                    item("banner") {
                        val updated = ago(ctx, data.gen, now)
                        Banner(
                            if (problem == Problem.OFFLINE) stringResource(R.string.offline_banner, updated ?: "–")
                            else stringResource(R.string.server_banner),
                            stringResource(R.string.retry),
                        ) { g.repo.refresh() }
                    }
                }
                when {
                    data.events.isEmpty() && (!status.ready || status.refreshing) -> item("loading") { Loading() }
                    data.events.isEmpty() && problem != null -> item("error") {
                        Message(
                            "📡",
                            stringResource(if (problem == Problem.OFFLINE) R.string.offline_title else R.string.server_title),
                            stringResource(R.string.error_body),
                            stringResource(R.string.retry),
                        ) { g.repo.refresh() }
                    }
                    sections.isEmpty() -> item("empty") {
                        val filtered = f.active > 0 || tokens.isNotEmpty()
                        Message(
                            "🔍",
                            stringResource(if (filtered) R.string.empty_filtered else R.string.empty_all),
                            if (filtered) stringResource(R.string.empty_filtered_body) else null,
                            if (filtered) stringResource(R.string.clear_filters) else null,
                        ) {
                            query = ""
                            g.prefs.setFilter(Filter())
                        }
                    }
                    else -> {
                        if (picks.isNotEmpty() && f.active == 0 && tokens.isEmpty()) {
                            item("students", contentType = "students") {
                                StudentRow(picks, open) { g.prefs.setFilter(Filter(stu = true)) }
                            }
                        }
                        sections.forEach { (sec, list) ->
                            stickyHeader(key = "h_${sec.name}", contentType = "header") {
                                SectionHeader(sectionLabel(sec) + "  ·  " + list.size)
                            }
                            items(list, key = { it.id }, contentType = { "event" }) { ev ->
                                EventCard(ev, sec, ev.id in prefs.saved, { g.prefs.toggleSaved(ev.id) }) { open(ev.id) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet) FilterSheet(f, data, onChange = { g.prefs.setFilter(it) }) { sheet = false }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, active: Int, onFilter: () -> Unit) {
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear_search)) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                IconButton(onClick = onFilter) { Icon(Ic.Filter, stringResource(R.string.filters)) }
            }
        }
    }
}

@Composable
private fun QuickChips(f: Filter, studentsFirst: Boolean, set: (Filter) -> Unit) {
    fun date(d: String) = set(f.copy(date = if (f.date == d) DATE_ANY else d, from = null, to = null))
    val students = @Composable { FilterChip(f.stu, { set(f.copy(stu = !f.stu)) }, { Text(stringResource(R.string.q_students)) }) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (studentsFirst) students()
        FilterChip(f.date == DATE_NOW, { date(DATE_NOW) }, { Text(stringResource(R.string.q_now)) })
        FilterChip(f.date == DATE_TONIGHT, { date(DATE_TONIGHT) }, { Text(stringResource(R.string.q_tonight)) })
        FilterChip(f.date == DATE_WEEKEND, { date(DATE_WEEKEND) }, { Text(stringResource(R.string.q_weekend)) })
        FilterChip(f.free, { set(f.copy(free = !f.free)) }, { Text(stringResource(R.string.free)) })
        FilterChip(f.fav, { set(f.copy(fav = !f.fav)) }, { Text(stringResource(R.string.q_fav)) })
        if (!studentsFirst) students()
    }
}

@Composable
fun EventList(header: (@Composable () -> Unit)?, events: List<app.eventborg.data.Ev>, saved: Set<String>, onSave: (String) -> Unit, open: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (header != null) item("header") { Column { header() } }
        items(events, key = { it.id }) { ev -> EventCard(ev, null, ev.id in saved, { onSave(ev.id) }) { open(ev.id) } }
    }
}
