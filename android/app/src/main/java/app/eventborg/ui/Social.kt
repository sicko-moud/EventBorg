package app.eventborg.ui

import androidx.compose.foundation.layout.fillMaxSize
import app.eventborg.ui.theme.G
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.eventborg.ui.theme.PrimaryButton
import app.eventborg.BuildConfig
import app.eventborg.R
import app.eventborg.data.NOTIFY_OFF
import app.eventborg.data.StoryItem
import app.eventborg.data.UserPrefs

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// Re-evaluated whenever the screen resumes (e.g. back from Android settings).
@Composable
fun rememberOnResume(read: () -> Boolean): Boolean {
    val owner = LocalLifecycleOwner.current
    var v by remember { mutableStateOf(read()) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) v = read() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return v
}

fun notifyGranted(ctx: Context) =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
fun notifyAskNeeded(p: UserPrefs): Boolean {
    val ctx = LocalContext.current
    val granted = rememberOnResume { notifyGranted(ctx) }
    if (Build.VERSION.SDK_INT < 33 || !p.loaded || p.notifAsked || granted) return false
    return (BuildConfig.FIREBASE && (p.notify != NOTIFY_OFF || p.stories != NOTIFY_OFF)) || p.listen
}

@Composable
fun rememberNotifyRequest(then: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), then)
    return { if (notifyGranted(ctx)) then(true) else launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
fun NotifyAsk(done: () -> Unit) {
    val ask = rememberNotifyRequest { done() }
    Surface(
        color = G.Surface,
        shape = G.Radius,
        border = G.Border,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.ask_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = done) { Text(stringResource(R.string.not_now)) }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(onClick = ask) { Text(stringResource(R.string.allow)) }
            }
        }
    }
}

private fun initials(name: String) =
    name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoryStrip(items: List<StoryItem>, onOpen: (StoryItem) -> Unit, onMute: (StoryItem) -> Unit) {
    val openLabel = stringResource(R.string.open_story)
    val muteLabel = stringResource(R.string.mute_source)
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            stringResource(R.string.stories_heading).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = G.Faint,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(items, key = { it.key }) { s ->
                Column(
                    Modifier.width(76.dp).clip(G.Radius)
                        .combinedClickable(onClickLabel = openLabel, onLongClickLabel = muteLabel, onLongClick = { onMute(s) }) { onOpen(s) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(60.dp)) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape).background(G.Raised).border(1.dp, if (s.seen) G.Line else G.Muted, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initials(s.name), style = MaterialTheme.typography.titleMedium, color = if (s.seen) G.Muted else G.Text)
                        }
                        if (!s.seen) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(8.dp).background(G.Accent, CircleShape).border(1.5.dp, G.Bg, CircleShape))
                    }
                    Text(
                        s.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}
