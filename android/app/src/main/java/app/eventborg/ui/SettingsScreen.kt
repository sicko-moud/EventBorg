package app.eventborg.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.BuildConfig
import app.eventborg.R
import app.eventborg.data.NOTIFY_ALL
import app.eventborg.data.NOTIFY_FAV
import app.eventborg.data.NOTIFY_OFF
import app.eventborg.graph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    var lang by remember { mutableStateOf(Lang.current(ctx)) }
    val dk = isDanish(uiLocale())
    val pageLang = if (dk) "da" else "en"

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Heading(stringResource(R.string.set_events))
            Radios(
                prefs.notify,
                listOf(NOTIFY_ALL to R.string.set_events_all, NOTIFY_FAV to R.string.set_events_fav, NOTIFY_OFF to R.string.off),
            ) { g.prefs.setNotify(it) }
            Hint0(stringResource(R.string.set_events_hint))

            Heading(stringResource(R.string.set_stories))
            Radios(
                prefs.stories,
                listOf(NOTIFY_OFF to R.string.off, NOTIFY_FAV to R.string.set_stories_fav, NOTIFY_ALL to R.string.set_stories_all),
            ) { g.prefs.setStories(it) }
            Hint0(stringResource(R.string.set_stories_hint))
            if (!BuildConfig.FIREBASE) Hint0(stringResource(R.string.set_no_push))

            Heading(stringResource(R.string.set_language))
            Radios(lang, listOf("" to R.string.lang_system, "en" to R.string.lang_en, "da" to R.string.lang_da)) {
                lang = it
                (ctx as? Activity)?.let { a -> Lang.set(a, it) }
            }

            Heading(stringResource(R.string.set_about))
            Hint0(stringResource(R.string.about_body))
            Link(stringResource(R.string.privacy)) { ctx.openWeb("${BuildConfig.WORKER_URL}/privacy?lang=$pageLang") }
            Link(stringResource(R.string.organizers)) { ctx.openWeb("${BuildConfig.WORKER_URL}/organizers?lang=$pageLang") }
            Link(stringResource(R.string.source_code)) { ctx.openWeb("https://github.com/sicko-moud/EventBorg") }
            Hint0(stringResource(R.string.version, BuildConfig.VERSION_NAME))
            Spacer(Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun Radios(value: String, options: List<Pair<String, Int>>, set: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { (k, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(value == k, role = Role.RadioButton) { set(k) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == k, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Hint0(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(Ic.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
