package app.eventborg.ui

import androidx.compose.material3.HorizontalDivider
import app.eventborg.ui.theme.G
import app.eventborg.ui.theme.switchColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eventborg.ui.theme.SecondaryButton
import app.eventborg.ui.theme.PrimaryButton
import app.eventborg.R
import app.eventborg.data.CATEGORIES
import app.eventborg.data.DATE_ANY
import app.eventborg.data.DATE_CUSTOM
import app.eventborg.data.DATE_NOW
import app.eventborg.data.DATE_TODAY
import app.eventborg.data.DATE_TOMORROW
import app.eventborg.data.DATE_TONIGHT
import app.eventborg.data.DATE_WEEK
import app.eventborg.data.DATE_WEEKEND
import app.eventborg.data.Data
import app.eventborg.data.Filter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val DAY_MS = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(f: Filter, data: Data, onChange: (Filter) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val locale = uiLocale()
    val towns = remember(data) {
        data.events.mapNotNull { it.e.v?.town }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Heading(stringResource(R.string.f_when))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DATE_ANY to R.string.d_any, DATE_NOW to R.string.q_now, DATE_TODAY to R.string.sec_today,
                    DATE_TONIGHT to R.string.q_tonight, DATE_TOMORROW to R.string.sec_tomorrow,
                    DATE_WEEKEND to R.string.q_weekend, DATE_WEEK to R.string.d_week,
                ).forEach { (d, label) ->
                    FilterChip(f.date == d, { onChange(f.copy(date = d, from = null, to = null)) }, { Text(stringResource(label)) })
                }
                val custom = f.date == DATE_CUSTOM && f.from != null
                val fmt = DateTimeFormatter.ofPattern("d MMM", locale)
                FilterChip(custom, { picking = true }, {
                    Text(
                        if (custom) listOfNotNull(f.from, f.to).distinct().joinToString(" – ") { LocalDate.ofEpochDay(it).format(fmt) }
                        else stringResource(R.string.d_custom),
                    )
                })
            }

            Heading(stringResource(R.string.f_categories))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORIES.forEach { c ->
                    FilterChip(
                        c in f.cats,
                        { onChange(f.copy(cats = if (c in f.cats) f.cats - c else f.cats + c)) },
                        { Text(stringResource(catLabel(c))) },
                    )
                }
            }

            Heading(stringResource(R.string.f_more))
            SwitchRow(stringResource(R.string.f_free), f.free) { onChange(f.copy(free = it)) }
            SwitchRow(stringResource(R.string.f_fav), f.fav) { onChange(f.copy(fav = it)) }
            SwitchRow(stringResource(R.string.f_students), f.stu) { onChange(f.copy(stu = it)) }

            Heading(stringResource(R.string.f_town))
            Choice(f.town, towns.associateWith { it }, stringResource(R.string.f_all_towns)) { onChange(f.copy(town = it)) }

            Heading(stringResource(R.string.f_source))
            Choice(f.source, data.sources.filter { it.cnt > 0 }.associate { it.id to it.n }, stringResource(R.string.f_all_sources)) {
                onChange(f.copy(source = it))
            }

            Spacer(Modifier.padding(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(onClick = { onChange(Filter()) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.f_reset)) }
                PrimaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.done)) }
            }
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = f.from?.let { it * DAY_MS },
            initialSelectedEndDateMillis = f.to?.let { it * DAY_MS },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val a = state.selectedStartDateMillis!! / DAY_MS
                        val b = state.selectedEndDateMillis?.div(DAY_MS) ?: a
                        onChange(f.copy(date = DATE_CUSTOM, from = a, to = b))
                        picking = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun Heading(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = G.Faint, modifier = Modifier.semantics { heading() })
        HorizontalDivider(Modifier.padding(top = 8.dp), color = G.Line)
    }
}

@Composable
fun SwitchRow(text: String, on: Boolean, subtitle: String? = null, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = set),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = null, colors = switchColors())
    }
}

@Composable
private fun Choice(value: String?, options: Map<String, String>, allLabel: String, set: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value?.let { options[it] ?: it } ?: allLabel, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { set(null); open = false })
            options.forEach { (k, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { set(k); open = false })
            }
        }
    }
}
