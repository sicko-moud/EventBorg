package app.eventborg.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Star
import app.eventborg.ui.theme.G
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.ui.theme.TopBar
import app.eventborg.ui.theme.SecondaryButton
import app.eventborg.ui.theme.PrimaryButton
import app.eventborg.R
import app.eventborg.data.Ev
import app.eventborg.graph
import coil3.compose.AsyncImage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, back: () -> Unit, openSource: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val data by g.repo.data.collectAsStateWithLifecycle()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val locale = uiLocale()
    val ev = data.byId[id]
    val saved = id in prefs.saved

    Scaffold(
        topBar = {
            TopBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (ev != null) {
                        IconButton(onClick = { ctx.share(ev, locale) }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                        StarButton(saved, R.string.unsave, R.string.save) { g.prefs.toggleSaved(id) }
                    }
                },
            )
        },
        bottomBar = {
            val link = ev?.e?.url ?: ev?.e?.src?.firstOrNull()?.url
            if (link != null) {
                Column(Modifier.background(G.Bg)) {
                    HorizontalDivider(color = G.Line)
                    PrimaryButton(
                        onClick = { ctx.openWeb(link) },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.tickets)) }
                }
            }
        },
    ) { pad ->
        if (ev == null) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                Message("🗓️", stringResource(R.string.gone_title), stringResource(R.string.gone_body), stringResource(R.string.back), back)
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (ev.e.img != null) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    CategoryArt(ev.e.c, Modifier.fillMaxSize(), 48.dp)
                    AsyncImage(model = ev.e.img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Body(ev, locale, prefs.favCats, prefs.favSrcs, saved, openSource)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Body(ev: Ev, locale: Locale, favCats: Set<String>, favSrcs: Set<String>, saved: Boolean, openSource: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val data by g.repo.data.collectAsStateWithLifecycle()
    val e = ev.e
    Column(Modifier.padding(16.dp)) {
        StatusTag(ev)
        Text(
            e.t,
            style = MaterialTheme.typography.headlineSmall,
            textDecoration = if (ev.cancelled) TextDecoration.LineThrough else null,
        )
        Spacer(Modifier.height(12.dp))
        Info(Icons.Outlined.DateRange, detailWhen(ctx, ev, locale))
        if (e.v != null) {
            val place = listOfNotNull(e.v.n, listOfNotNull(e.v.addr, e.v.town).joinToString(", ").ifEmpty { null }).joinToString("\n")
            Info(Icons.Outlined.Place, place, stringResource(R.string.open_map)) { ctx.openMap(e) }
        }
        priceText(ctx, e)?.let { Info(Icons.Outlined.ShoppingCart, it) }
        e.org?.let { Info(Icons.Outlined.Person, stringResource(R.string.organizer, it)) }
        e.age?.let { Info(Icons.Outlined.Face, stringResource(R.string.age, it)) }
        e.lang?.let { l -> Info(Icons.Outlined.Info, stringResource(R.string.language, Locale.forLanguageTag(l).getDisplayLanguage(locale))) }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val fav = e.c in favCats
            FilterChip(
                fav,
                { g.prefs.toggleCat(e.c) },
                { Text(stringResource(catLabel(e.c))) },
                trailingIcon = { Icon(if (fav) Icons.Filled.Star else Ic.StarBorder, stringResource(if (fav) R.string.unfav_cat else R.string.fav_cat), Modifier.size(18.dp)) },
            )
            e.tags.take(4).forEach { Tag("#$it") }
        }

        FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(onClick = { ctx.addToCalendar(ev) }) {
                Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_calendar))
            }
            SecondaryButton(onClick = { g.prefs.toggleSaved(e.id) }) {
                Icon(if (saved) Icons.Filled.Star else Ic.StarBorder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (saved) R.string.saved else R.string.save))
            }
        }

        val sum = summary(e, locale)
        if (sum != null) Text(sum, style = MaterialTheme.typography.bodyLarge)
        val desc = e.desc?.trim()
        if (!desc.isNullOrEmpty() && desc != sum) {
            var open by rememberSaveable(e.id) { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) {
                Text(stringResource(if (open) R.string.hide_original else R.string.show_original))
                Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
            }
            if (open) {
                Text(desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.animateContentSize())
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Heading(stringResource(R.string.sources))
        e.src.forEach { s ->
            val src = data.srcById[s.id]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    if (src != null) openSource(s.id) else s.url?.let { ctx.openWeb(it) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tag(stringResource(kindLabel(s.kind)))
                Spacer(Modifier.width(8.dp))
                Text(src?.n ?: s.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (s.url != null) {
                    IconButton(onClick = { ctx.openWeb(s.url) }) { Icon(Ic.OpenInNew, stringResource(R.string.open_source_page)) }
                }
                if (src != null) {
                    StarButton(s.id in favSrcs, R.string.unfav_source, R.string.fav_source) { g.prefs.toggleSource(s.id) }
                }
            }
        }
    }
}

@Composable
private fun Info(icon: ImageVector, text: String, actionLabel: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).let { m ->
            if (onClick != null) m.clickable(onClickLabel = actionLabel, onClick = onClick) else m
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = G.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
