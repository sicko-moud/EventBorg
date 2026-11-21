package app.eventborg.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eventborg.R
import app.eventborg.data.CATEGORIES
import app.eventborg.data.Draft
import app.eventborg.data.Extract
import app.eventborg.data.Images
import app.eventborg.data.Sent
import app.eventborg.data.Why
import app.eventborg.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val DAY = 86_400_000L
private val DATE_RX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val TIME_RX = Regex("^([01]?\\d|2[0-3])(?:[:.]([0-5]\\d))?$")

private fun normTime(t: String): String? {
    val m = TIME_RX.matchEntire(t.trim()) ?: return null
    return "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "00" })
}

// Only the shared text/picture (or a group post's preview) is sent to /extract, after the user's tap.
private suspend fun loadInput(ctx: Context, r: DraftR): Pair<String, String?> = withContext(Dispatchers.IO) {
    val g = ctx.graph
    if (r.post != null) {
        val p = g.watch.state.value.posts.firstOrNull { it.id == r.post } ?: return@withContext "" to null
        val bmp = if (p.pic) runCatching { BitmapFactory.decodeFile(g.watch.pic(p.id).path) }.getOrNull() else null
        p.text to bmp?.let { Images.base64(Images.jpeg(Images.scale(it, 1280))) }
    } else {
        r.text to r.img?.let { Images.fromUri(ctx, Uri.parse(it))?.let { b -> Images.base64(Images.jpeg(b)) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(r: DraftR, back: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val key = remember(r) { r.toString() }
    var result by remember { mutableStateOf(g.drafts[key]) }
    var attempt by remember { mutableIntStateOf(0) }
    var sent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(key, attempt) {
        if (result != null) return@LaunchedEffect
        val (text, img) = loadInput(ctx, r)
        val res = if (text.isBlank() && img == null) Extract.Failed(Why.FAILED) else g.api.extract(text, img)
        if (res !is Extract.Failed) g.drafts[key] = res
        result = res
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.d_title)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val res = result
            val manual = { result = Extract.Ok(Draft(description = r.text.take(1500).ifBlank { null }), null) }
            when {
                sent -> Message("🎉", stringResource(R.string.d_sent), stringResource(R.string.d_sent_body), stringResource(R.string.done), back)
                res == null -> Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.d_reading), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                res is Extract.Ok -> DraftForm(res.draft, res.tok) { sent = true }
                res is Extract.NotEvent -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Message("🤔", stringResource(R.string.d_not_event), res.reason, stringResource(R.string.d_manual), manual)
                }
                res is Extract.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val msg = when (res.why) {
                        Why.LIMITED -> R.string.d_limited
                        Why.BLOCKED_URL -> R.string.d_blocked
                        Why.UNREADABLE_URL -> R.string.d_unreadable
                        Why.BUSY -> R.string.d_busy
                        Why.OFFLINE -> R.string.offline_title
                        Why.FAILED -> R.string.d_failed
                    }
                    Message("⚠️", stringResource(msg), null, stringResource(R.string.retry)) {
                        result = null
                        attempt++
                    }
                    TextButton(onClick = manual) { Text(stringResource(R.string.d_manual)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftForm(d: Draft, tok: String?, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val locale = uiLocale()
    var title by rememberSaveable(d) { mutableStateOf(d.title.orEmpty()) }
    var date by rememberSaveable(d) { mutableStateOf(d.start?.take(10).orEmpty()) }
    var time by rememberSaveable(d) { mutableStateOf(d.start?.drop(11)?.take(5).orEmpty()) }
    var endDate by rememberSaveable(d) { mutableStateOf(d.end?.take(10)?.takeIf { it != d.start?.take(10) }.orEmpty()) }
    var endTime by rememberSaveable(d) { mutableStateOf(d.end?.drop(11)?.take(5).orEmpty()) }
    var venue by rememberSaveable(d) { mutableStateOf(d.venue.orEmpty()) }
    var address by rememberSaveable(d) { mutableStateOf(d.address.orEmpty()) }
    var town by rememberSaveable(d) { mutableStateOf(d.town.orEmpty()) }
    var price by rememberSaveable(d) { mutableStateOf(d.price.orEmpty()) }
    var url by rememberSaveable(d) { mutableStateOf(d.url.orEmpty()) }
    var org by rememberSaveable(d) { mutableStateOf(d.organizer.orEmpty()) }
    var desc by rememberSaveable(d) { mutableStateOf(d.description.orEmpty()) }
    var cat by rememberSaveable(d) { mutableStateOf(d.category ?: "other") }
    var picking by remember { mutableStateOf<Int?>(null) }
    var catMenu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val timeOk = time.isBlank() || normTime(time) != null
    val endOk = endTime.isBlank() || normTime(endTime) != null
    val urlOk = url.isBlank() || url.trim().startsWith("http")
    val valid = title.isNotBlank() && DATE_RX.matches(date) && timeOk && endOk && urlOk
    val dayFmt = remember(locale) { DateTimeFormatter.ofPattern(if (isDanish(locale)) "EEE d. MMM yyyy" else "EEE d MMM yyyy", locale) }
    fun show(s: String) = runCatching { LocalDate.parse(s).format(dayFmt) }.getOrNull()

    fun build(): Draft {
        val start = date + (normTime(time)?.let { "T$it" } ?: "")
        val end = normTime(endTime)?.let { "${endDate.ifBlank { date }}T$it" } ?: endDate.ifBlank { null }
        fun v(s: String) = s.trim().ifEmpty { null }
        return d.copy(
            title = v(title), start = start, end = end, venue = v(venue), address = v(address), town = v(town),
            price = v(price), url = v(url), organizer = v(org), description = v(desc), category = cat,
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.d_check), style = MaterialTheme.typography.bodyMedium)
        Field(title, { title = it.take(200) }, stringResource(R.string.d_f_title) + " *")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picking = 0 }, modifier = Modifier.weight(1.4f).heightIn(min = 56.dp)) {
                Icon(Icons.Filled.DateRange, null)
                Spacer(Modifier.padding(3.dp))
                Text(show(date) ?: (stringResource(R.string.d_f_date) + " *"))
            }
            OutlinedTextField(
                time, { time = it.take(5) }, Modifier.weight(1f), label = { Text(stringResource(R.string.d_f_time)) },
                placeholder = { Text("19:30") }, singleLine = true, isError = !timeOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.weight(1.4f).heightIn(min = 56.dp)) {
                Text(show(endDate) ?: stringResource(R.string.d_f_end_date))
            }
            OutlinedTextField(
                endTime, { endTime = it.take(5) }, Modifier.weight(1f), label = { Text(stringResource(R.string.d_f_end_time)) },
                singleLine = true, isError = !endOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Field(venue, { venue = it.take(120) }, stringResource(R.string.d_f_venue))
        Field(address, { address = it.take(200) }, stringResource(R.string.d_f_address))
        Field(town, { town = it.take(60) }, stringResource(R.string.d_f_town))
        Field(price, { price = it.take(120) }, stringResource(R.string.d_f_price))
        Field(url, { url = it.take(500) }, stringResource(R.string.d_f_link), KeyboardType.Uri, isError = !urlOk)
        Field(org, { org = it.take(120) }, stringResource(R.string.d_f_org))
        Box {
            OutlinedButton(onClick = { catMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(catEmoji(cat) + "  " + stringResource(catLabel(cat)), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                CATEGORIES.forEach { c ->
                    DropdownMenuItem(text = { Text(catEmoji(c) + "  " + stringResource(catLabel(c))) }, onClick = { cat = c; catMenu = false })
                }
            }
        }
        OutlinedTextField(desc, { desc = it.take(1500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.d_f_desc)) }, minLines = 3)
        Text(stringResource(R.string.d_public), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = {
                sending = true
                scope.launch {
                    val res = g.api.submit(build(), tok)
                    sending = false
                    when (res) {
                        Sent.OK -> onSent()
                        Sent.LIMITED -> Toast.makeText(ctx, R.string.suggest_limited, Toast.LENGTH_LONG).show()
                        Sent.INVALID -> Toast.makeText(ctx, R.string.d_invalid, Toast.LENGTH_LONG).show()
                        Sent.FAILED -> Toast.makeText(ctx, R.string.suggest_failed, Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = valid && !sending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.send)) }
        Spacer(Modifier.height(24.dp))
    }

    picking?.let { which ->
        val initial = (if (which == 0) date else endDate.ifBlank { date }).let { s -> runCatching { LocalDate.parse(s).toEpochDay() * DAY }.getOrNull() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = LocalDate.ofEpochDay(ms / DAY).toString()
                        if (which == 0) date = day else endDate = if (day == date) "" else day
                    }
                    picking = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (which == 1) endDate = ""
                    picking = null
                }) { Text(stringResource(if (which == 1) R.string.d_clear else R.string.cancel)) }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun Field(value: String, set: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text, isError: Boolean = false) {
    OutlinedTextField(
        value, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = type),
    )
}
