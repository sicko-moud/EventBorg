package app.eventborg.ui

import app.eventborg.ui.theme.G
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.ui.theme.TopBar
import app.eventborg.R
import app.eventborg.data.ZONE
import app.eventborg.graph
import app.eventborg.notify.openGroupPost
import coil3.compose.AsyncImage
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPostsScreen(back: () -> Unit, go: (Any) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val watch by g.watch.state.collectAsStateWithLifecycle()
    val now = rememberNow()

    Scaffold(
        topBar = {
            TopBar(
                title = { Text(stringResource(R.string.gp_screen)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("intro") { Hint(stringResource(R.string.gp_intro)) }
            if (watch.posts.isEmpty()) {
                item("empty") { Message("👥", stringResource(R.string.gp_empty), stringResource(R.string.gp_empty_body)) }
            }
            items(watch.posts, key = { it.id }) { p ->
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp)) {
                    Text(
                        p.groupName + " · " + (ago(ctx, Instant.ofEpochMilli(p.ts).atZone(ZONE), now) ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(Modifier.padding(top = 4.dp, end = 12.dp)) {
                        if (p.pic) {
                            AsyncImage(
                                model = g.watch.pic(p.id),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(G.Radius),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(p.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { ctx.findActivity()?.let { openGroupPost(it, p.id) } }) { Text(stringResource(R.string.open)) }
                        TextButton(onClick = { go(DraftR(post = p.id)) }) { Text(stringResource(R.string.add_as_event)) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { g.watch.removePost(p.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove)) }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
