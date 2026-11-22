package app.eventborg.ui

import androidx.compose.foundation.border
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import app.eventborg.ui.theme.G
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.ui.theme.TopBar
import app.eventborg.ui.theme.Field
import app.eventborg.R
import app.eventborg.data.N_EVENTS
import app.eventborg.data.N_POSTS
import app.eventborg.data.N_STORIES
import app.eventborg.data.Problem
import app.eventborg.data.Sent
import app.eventborg.data.Source
import app.eventborg.data.ZONE
import app.eventborg.graph
import app.eventborg.watch.storyEligible
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@Composable
fun sourceStatus(s: Source): String {
    val ctx = LocalContext.current
    val now = rememberNow()
    val ok = s.ok?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZONE) }.getOrNull() }
    val count = ctx.resources.getQuantityString(R.plurals.src_events, s.cnt, s.cnt)
    return when (s.st) {
        "ok" -> listOfNotNull(ago(ctx, ok, now)?.let { stringResource(R.string.src_updated, it) }, count).joinToString(" · ")
        "social" -> stringResource(R.string.src_social)
        "new" -> stringResource(R.string.src_new)
        "disabled", "no_key" -> stringResource(R.string.src_paused)
        else -> listOfNotNull(stringResource(R.string.src_problem), ago(ctx, ok, now)?.let { stringResource(R.string.src_last_ok, it) })
            .joinToString(" · ")
    }
}

@Composable
fun SourceRow(s: Source, fav: Boolean, onFav: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(s.n, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(stringResource(sourceKindLabel(s.kind)), s.town).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(sourceStatus(s), style = MaterialTheme.typography.bodySmall, color = G.Faint)
        }
        StarButton(fav, R.string.unfav_source, R.string.fav_source, onFav)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = G.Line)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(openSource: (String) -> Unit, openAddEvent: () -> Unit) {
    val g = LocalContext.current.graph
    val data by g.repo.data.collectAsStateWithLifecycle()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val status by g.repo.status.collectAsStateWithLifecycle()
    var suggest by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopBar(title = { Text(stringResource(R.string.tab_sources)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { suggest = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.suggest_source)) },
                shape = G.Radius,
                containerColor = G.Raised,
                contentColor = G.Text,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier.border(1.dp, G.Line, G.Radius),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item("add_event") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = openAddEvent).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ae_link),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = G.Muted)
                }
            }
            val problem = status.problem
            when {
                data.sources.isNotEmpty() -> item("intro") { Hint(stringResource(R.string.sources_intro, data.sources.size)) }
                problem != null && !status.refreshing -> item("error") {
                    Message(
                        "📡",
                        stringResource(if (problem == Problem.OFFLINE) R.string.offline_title else R.string.server_title),
                        stringResource(R.string.error_body),
                        stringResource(R.string.retry),
                    ) { g.repo.refresh() }
                }
                else -> item("loading") { Loading() }
            }
            items(data.sources, key = { it.id }) { s ->
                SourceRow(s, s.id in prefs.favSrcs, { g.prefs.toggleSource(s.id) }) { openSource(s.id) }
            }
        }
    }
    if (suggest) SuggestDialog { suggest = false }
}

@Composable
private fun SuggestDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val valid = link.trim().length in 4..300 && name.length <= 100 && note.length <= 500

    AlertDialog(
        onDismissRequest = { if (!sending) close() },
        title = { Text(stringResource(R.string.suggest_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.suggest_body), style = MaterialTheme.typography.bodyMedium)
                Field(name, { name = it.take(100) }, label = { Text(stringResource(R.string.suggest_name)) }, singleLine = true)
                Field(
                    link,
                    { link = it.take(300) },
                    label = { Text(stringResource(R.string.suggest_link)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Field(note, { note = it.take(500) }, label = { Text(stringResource(R.string.suggest_note)) }, minLines = 2)
                Text(stringResource(R.string.suggest_public), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val r = g.api.suggest(name, link, note)
                        sending = false
                        val msg = when (r) {
                            Sent.OK -> R.string.suggest_ok
                            Sent.LIMITED -> R.string.suggest_limited
                            Sent.INVALID -> R.string.suggest_invalid
                            Sent.FAILED -> R.string.suggest_failed
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (r == Sent.OK) close()
                    }
                },
            ) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = close, enabled = !sending) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourcePage(id: String, back: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val data by g.repo.data.collectAsStateWithLifecycle()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val s = data.srcById[id]
    val events = remember(data, id, now) { data.events.filter { ev -> ev.end.isAfter(now) && ev.e.src.any { it.id == id } } }

    Scaffold(
        topBar = {
            TopBar(
                title = { Text(s?.n ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (s != null) StarButton(id in prefs.favSrcs, R.string.unfav_source, R.string.fav_source) { g.prefs.toggleSource(id) }
                },
            )
        },
    ) { pad ->
        if (s == null) {
            Column(Modifier.padding(pad)) { Message("🏛️", stringResource(R.string.source_gone)) }
            return@Scaffold
        }
        EventList(
            header = {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        listOfNotNull(stringResource(sourceKindLabel(s.kind)), s.town).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(sourceStatus(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.url?.let { u -> AssistChip(onClick = { ctx.openWeb(u) }, label = { Text(stringResource(R.string.website)) }) }
                        s.ig?.let { h -> AssistChip(onClick = { ctx.openApp(instagramUrl(h)) }, label = { Text("Instagram") }) }
                        s.fb?.let { u -> AssistChip(onClick = { ctx.openApp(u) }, label = { Text("Facebook") }) }
                    }
                }
                SectionHeader(stringResource(R.string.src_notify))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (s.pri) Box(Modifier.padding(top = 12.dp)) { StatusLabel(stringResource(R.string.src_priority)) }
                    SwitchRow(stringResource(R.string.src_n_events), prefs.on(N_EVENTS, id)) { g.prefs.setSourceOn(N_EVENTS, id, it) }
                    if (storyEligible(s)) {
                        SwitchRow(stringResource(R.string.src_n_stories), prefs.on(N_STORIES, id)) { g.prefs.setSourceOn(N_STORIES, id, it) }
                        SwitchRow(stringResource(R.string.src_n_posts), prefs.on(N_POSTS, id)) { g.prefs.setSourceOn(N_POSTS, id, it) }
                    }
                    Text(stringResource(R.string.src_n_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionHeader(ctx.resources.getQuantityString(R.plurals.upcoming, events.size, events.size))
                if (events.isEmpty()) Hint(stringResource(R.string.source_no_events))
            },
            events = events,
            saved = prefs.saved,
            onSave = { g.prefs.toggleSaved(it) },
            open = open,
            modifier = Modifier.padding(pad),
        )
    }
}
