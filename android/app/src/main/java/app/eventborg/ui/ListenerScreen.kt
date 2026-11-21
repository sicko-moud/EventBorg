package app.eventborg.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eventborg.R
import app.eventborg.graph
import app.eventborg.notify.Listen
import app.eventborg.watch.IG_PKG

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenerScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val granted = rememberOnResume { Listen.granted(ctx) }
    val on = prefs.listen && granted
    val agree = rememberNotifyRequest { Listen.openSettings(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.l_title)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (on) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.l_on), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Heading(stringResource(R.string.l_setup))
                Step("1", stringResource(R.string.l_step_silent))
                OutlinedButton(onClick = {
                    if (!Listen.openAppNotifications(ctx, IG_PKG)) Toast.makeText(ctx, R.string.no_app, Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)) { Text(stringResource(R.string.l_open_ig_settings)) }
                Step("2", stringResource(R.string.l_step_bell))
                Step("3", stringResource(R.string.l_step_group))
                Step("4", stringResource(R.string.l_step_account))
                Heading(stringResource(R.string.l_what_h))
                Para(stringResource(R.string.l_reads))
                Para(stringResource(R.string.l_never))
                Para(stringResource(R.string.l_posts))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    g.prefs.setListen(false)
                    Listen.setEnabled(ctx, false)
                }) { Text(stringResource(R.string.l_turn_off)) }
                Para(stringResource(R.string.l_turn_off_hint))
            } else {
                Text(stringResource(R.string.l_intro), style = MaterialTheme.typography.bodyLarge)
                Heading(stringResource(R.string.l_what_h))
                Para(stringResource(R.string.l_reads))
                Heading(stringResource(R.string.l_never_h))
                Para(stringResource(R.string.l_never))
                Heading(stringResource(R.string.l_posts_h))
                Para(stringResource(R.string.l_posts))
                Heading(stringResource(R.string.l_groups_h))
                Para(stringResource(R.string.l_groups))
                Heading(stringResource(R.string.l_hide_h))
                Para(stringResource(R.string.l_hide))
                Para(stringResource(R.string.l_share))
                Para(stringResource(R.string.l_optional))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        g.prefs.setListen(true)
                        Listen.setEnabled(ctx, true)
                        agree()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.l_agree)) }
                if (prefs.listen && !granted) {
                    Para(stringResource(R.string.l_waiting))
                }
                Para(stringResource(R.string.l_restricted))
                TextButton(onClick = { Listen.openAppInfo(ctx) }) { Text(stringResource(R.string.l_app_info)) }
                if (prefs.listen) {
                    TextButton(onClick = {
                        g.prefs.setListen(false)
                        Listen.setEnabled(ctx, false)
                    }) { Text(stringResource(R.string.cancel)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(n, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(32.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
