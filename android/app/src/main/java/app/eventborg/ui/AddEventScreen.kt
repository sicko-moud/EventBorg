package app.eventborg.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eventborg.ui.theme.TopBar
import app.eventborg.ui.theme.Field
import app.eventborg.ui.theme.SecondaryButton
import app.eventborg.ui.theme.PrimaryButton
import app.eventborg.BuildConfig
import app.eventborg.R
import app.eventborg.data.Channels
import app.eventborg.graph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventScreen(back: () -> Unit, go: (Any) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    var text by rememberSaveable { mutableStateOf("") }
    var channels by remember { mutableStateOf<Channels?>(null) }
    LaunchedEffect(Unit) { channels = g.api.channels() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) go(DraftR(img = uri.toString()))
    }
    val lang = if (isDanish(uiLocale())) "da" else "en"

    Scaffold(
        topBar = {
            TopBar(
                title = { Text(stringResource(R.string.ae_title)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.ae_intro), style = MaterialTheme.typography.bodyLarge)

            Heading(stringResource(R.string.ae_share_h))
            Text(stringResource(R.string.ae_share), style = MaterialTheme.typography.bodyMedium)

            Heading(stringResource(R.string.ae_paste_h))
            Field(
                text,
                { text = it.take(4000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ae_paste)) },
                minLines = 3,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(onClick = { go(DraftR(text = text.trim())) }, enabled = text.trim().length >= 10) { Text(stringResource(R.string.ae_check)) }
                SecondaryButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.ae_pick))
                }
            }

            channels?.ig?.let { h ->
                Heading("Instagram")
                Text(stringResource(R.string.ae_ig, h), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { ctx.openApp(instagramUrl(h)) }) { Text(stringResource(R.string.ae_open_ig)) }
            }
            channels?.wa?.let { n ->
                Heading("WhatsApp")
                Text(stringResource(R.string.ae_wa, n), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { ctx.openApp("https://wa.me/" + n.filter { it.isDigit() }) }) { Text(stringResource(R.string.ae_open_wa)) }
            }

            Heading(stringResource(R.string.ae_org_h))
            Text(stringResource(R.string.ae_org), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { ctx.openWeb("${BuildConfig.WORKER_URL}/organizers?lang=$lang") }) { Text(stringResource(R.string.organizers)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
