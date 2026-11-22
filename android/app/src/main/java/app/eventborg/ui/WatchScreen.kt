package app.eventborg.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import app.eventborg.ui.theme.G
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.ui.theme.TopBar
import app.eventborg.ui.theme.Field
import app.eventborg.ui.theme.PrimaryButton
import app.eventborg.R
import app.eventborg.data.Source
import app.eventborg.graph
import app.eventborg.notify.Listen
import app.eventborg.watch.FB
import app.eventborg.watch.Group
import app.eventborg.watch.GroupLink
import app.eventborg.watch.IG
import app.eventborg.watch.Link
import app.eventborg.watch.Profile
import app.eventborg.watch.ProfileLink
import app.eventborg.watch.parseLink
import app.eventborg.watch.profileFor
import app.eventborg.watch.storyEligible

private fun listedFor(l: ProfileLink, sources: List<Source>): Source? = sources.firstOrNull { s ->
    when (l.net) {
        IG -> l.handle != null && s.ig?.trimStart('@')?.lowercase() == l.handle
        else -> s.fb != null && (parseLink(s.fb) as? ProfileLink)?.let { it.handle != null && it.handle == l.handle } == true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(link: String?, openListener: () -> Unit, openPosts: () -> Unit, openSource: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val watch by g.watch.state.collectAsStateWithLifecycle()
    val data by g.repo.data.collectAsStateWithLifecycle()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val granted = rememberOnResume { Listen.granted(ctx) }
    var input by rememberSaveable { mutableStateOf("") }
    var bad by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Link?>(null) }
    var handled by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(link) {
        if (link != null && !handled) {
            handled = true
            adding = parseLink(link)
        }
    }
    val suggestions = remember(data, watch, prefs.favSrcs) {
        data.sources.filter { storyEligible(it) && it.id !in watch.watchedSources }.sortedByDescending { it.id in prefs.favSrcs }
    }
    val mutedSources = remember(data, watch) {
        watch.muted.filter { it !in watch.watchedSources }.mapNotNull { data.srcById[it] }
    }
    fun submit() {
        val l = parseLink(input)
        bad = l == null
        if (l != null) adding = l
    }

    Scaffold(topBar = { TopBar(title = { Text(stringResource(R.string.tab_watch)) }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("detect") { DetectCard(prefs.listen && granted, openListener) }
            item("add") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Field(
                        input,
                        { input = it.take(500); bad = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.w_paste)) },
                        singleLine = true,
                        isError = bad,
                        supportingText = { Text(stringResource(if (bad) R.string.w_bad_link else R.string.w_paste_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        trailingIcon = { TextButton(onClick = ::submit, enabled = input.isNotBlank()) { Text(stringResource(R.string.add)) } },
                    )
                }
            }
            item("h_profiles") { Heading2(stringResource(R.string.w_profiles)) }
            if (watch.profiles.isEmpty()) item("p_empty") { Hint(stringResource(R.string.w_profiles_empty)) }
            items(watch.profiles.sortedByDescending { it.src != null && data.srcById[it.src]?.pri == true }, key = { "p_" + it.key }) { p ->
                WatchRow(
                    title = p.name,
                    subtitle = listOfNotNull(p.handle?.takeIf { p.net == IG }?.let { "@$it" }, if (p.net == IG) "Instagram" else "Facebook").joinToString(" · "),
                    tip = if (p.caught) null else stringResource(if (p.net == IG) R.string.w_tip_ig else R.string.w_tip_fb),
                    caught = p.caught,
                    muted = p.key in watch.muted,
                    openLabel = stringResource(R.string.w_open_profile),
                    onOpen = { ctx.openApp(p.url) },
                    onMute = { g.watch.setMuted(p.key, it) },
                    onRemove = { g.watch.removeProfile(p.key) },
                    onSettings = p.src?.takeIf { it in data.srcById }?.let { id -> { openSource(id) } },
                )
            }
            item("h_groups") { Heading2(stringResource(R.string.w_groups)) }
            if (watch.groups.isEmpty()) item("g_empty") { Hint(stringResource(R.string.w_groups_empty)) }
            items(watch.groups, key = { "g_" + it.id }) { gr ->
                WatchRow(
                    title = gr.name,
                    subtitle = stringResource(R.string.w_fb_group),
                    tip = stringResource(R.string.w_tip_group),
                    caught = false,
                    muted = gr.key in watch.muted,
                    openLabel = stringResource(R.string.w_open_group),
                    onOpen = { ctx.openApp(gr.url) },
                    onMute = { g.watch.setMuted(gr.key, it) },
                    onRemove = { g.watch.removeGroup(gr.id) },
                )
            }
            item("posts") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = openPosts).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.gp_screen) + if (watch.posts.isNotEmpty()) "  ·  ${watch.posts.size}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = G.Muted)
                }
            }
            if (suggestions.isNotEmpty()) {
                item("h_sugg") {
                    Heading2(stringResource(R.string.w_suggestions))
                    Hint(stringResource(R.string.w_suggestions_hint))
                }
                items(if (showAll) suggestions else suggestions.take(6), key = { "s_" + it.id }) { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.n, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(s.ig?.let { "@" + it.trimStart('@') }, s.fb?.let { "Facebook" }, s.town).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { g.watch.addProfile(profileFor(s)) }) { Text(stringResource(R.string.w_watch)) }
                    }
                }
                if (!showAll && suggestions.size > 6) {
                    item("more") {
                        TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.w_show_all, suggestions.size))
                        }
                    }
                }
            }
            if (mutedSources.isNotEmpty()) {
                item("h_muted") { Heading2(stringResource(R.string.w_muted)) }
                items(mutedSources, key = { "m_" + it.id }) { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.n, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { g.watch.setMuted(s.id, false) }) { Text(stringResource(R.string.unmute)) }
                    }
                }
            }
        }
    }

    adding?.let { l ->
        AddDialog(l, l is ProfileLink && listedFor(l, data.sources) != null, { adding = null }) { name ->
            when (l) {
                is ProfileLink -> {
                    val src = listedFor(l, data.sources)
                    g.watch.addProfile(if (src != null) profileFor(src).copy(name = name) else Profile(l.net, l.handle, name, l.url))
                }
                is GroupLink -> g.watch.addGroup(Group(l.id, name, l.url))
            }
            adding = null
            input = ""
        }
    }
}

@Composable
private fun Heading2(text: String) {
    Box(Modifier.padding(horizontal = 16.dp)) { Heading(text) }
}

@Composable
private fun DetectCard(on: Boolean, open: () -> Unit) {
    Surface(
        color = G.Surface,
        shape = G.Radius,
        border = G.Border,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(G.Radius).clickable(onClick = open),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (on) R.string.w_detect_on else R.string.w_detect_off), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (on) R.string.w_detect_on_body else R.string.w_detect_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (on) StatusLabel(stringResource(R.string.on))
            else PrimaryButton(onClick = open) { Text(stringResource(R.string.set_up)) }
        }
    }
}

@Composable
private fun WatchRow(
    title: String,
    subtitle: String,
    tip: String?,
    caught: Boolean,
    muted: Boolean,
    openLabel: String,
    onOpen: () -> Unit,
    onMute: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (caught) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.CheckCircle, stringResource(R.string.w_caught), tint = G.Accent, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                subtitle + if (muted) " · " + stringResource(R.string.muted) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tip != null) Text(tip, style = MaterialTheme.typography.bodySmall, color = G.Faint)
        }
        TextButton(onClick = onOpen) { Text(openLabel) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options, title)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (muted) R.string.unmute else R.string.mute)) },
                    onClick = { onMute(!muted); menu = false },
                )
                if (onSettings != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.w_notifications)) }, onClick = { onSettings(); menu = false })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { onRemove(); menu = false })
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = G.Line)
}

@Composable
private fun AddDialog(l: Link, listed: Boolean, close: () -> Unit, add: (String) -> Unit) {
    val group = l is GroupLink
    val fb = group || (l as ProfileLink).net == FB
    val initial = when (l) {
        is ProfileLink -> l.name ?: l.handle?.takeIf { l.net == IG }.orEmpty()
        is GroupLink -> l.name.orEmpty()
    }
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = name.trim().length >= if (fb) 4 else 2
    AlertDialog(
        onDismissRequest = close,
        title = {
            Text(stringResource(when { group -> R.string.w_add_group; fb -> R.string.w_add_fb; else -> R.string.w_add_ig }))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (l) {
                        is ProfileLink -> l.url
                        is GroupLink -> l.url
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Field(
                    name,
                    { name = it.take(80) },
                    label = { Text(stringResource(R.string.w_name)) },
                    singleLine = true,
                )
                Text(
                    stringResource(if (fb) R.string.w_name_hint_fb else R.string.w_name_hint_ig),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (listed) Text(stringResource(R.string.w_listed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.w_local_only), style = MaterialTheme.typography.bodySmall, color = G.Faint)
            }
        },
        confirmButton = { TextButton(onClick = { add(name.trim()) }, enabled = valid) { Text(stringResource(R.string.w_watch)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}
