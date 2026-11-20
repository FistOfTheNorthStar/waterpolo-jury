package io.github.fistofthenorthstar.waterpolojury.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fistofthenorthstar.waterpolojury.BluetoothGate
import io.github.fistofthenorthstar.waterpolojury.ControllerViewModel
import io.github.fistofthenorthstar.waterpolojury.bt.LinkInfo
import io.github.fistofthenorthstar.waterpolojury.core.EventType
import io.github.fistofthenorthstar.waterpolojury.core.MatchConfig
import io.github.fistofthenorthstar.waterpolojury.core.MatchEvent
import io.github.fistofthenorthstar.waterpolojury.core.MatchState
import io.github.fistofthenorthstar.waterpolojury.core.Team
import io.github.fistofthenorthstar.waterpolojury.core.parseClock
import java.util.Locale

private val NumberKeys = KeyboardOptions(keyboardType = KeyboardType.Number)
private val DecimalKeys = KeyboardOptions(keyboardType = KeyboardType.Decimal)

private fun secondsText(ms: Long): String {
    val tenths = (ms % 60_000) / 100
    return if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.US, "%.1f", tenths / 10.0)
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One tap on a cap number logs the event at the time the dialog result arrives. */
@Composable
fun CapPickerDialog(
    state: MatchState,
    type: EventType,
    team: Team,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${type.label} — ${state.config.name(team)}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cap number", color = Color.Gray, fontSize = 12.sp)
                for (row in (1..state.config.caps).chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (n in row) {
                            val fouls = state.fouls(team, n)
                            val out = state.isOut(team, n)
                            Button(
                                onClick = { onPick(n) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = when {
                                    out -> ButtonDefaults.buttonColors(containerColor = Color(0xFF7A1C1C), contentColor = Color.White)
                                    team == Team.WHITE -> ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                                    else -> ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White)
                                },
                            ) {
                                Text(
                                    if (fouls > 0) "$n\n" + "•".repeat(fouls) else "$n",
                                    fontSize = 18.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                                )
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("• = personal fouls, red = out of the match", color = Color.Gray, fontSize = 11.sp)
                OutlinedButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("No number / bench / coach")
                }
            }
        },
    )
}

@Composable
fun SetClockDialog(title: String, currentMs: Long, withMinutes: Boolean, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    var minutes by remember { mutableStateOf(if (withMinutes) (currentMs / 60_000).toString() else "") }
    var seconds by remember {
        mutableStateOf(if (withMinutes) secondsText(currentMs) else ((currentMs + 999) / 1000).toString())
    }
    val parsed = parseClock(minutes, seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (withMinutes) {
                        OutlinedTextField(
                            minutes, { minutes = it }, label = { Text("min") }, singleLine = true,
                            keyboardOptions = NumberKeys, modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        seconds, { seconds = it }, label = { Text("sec") }, singleLine = true,
                        keyboardOptions = DecimalKeys, modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Applies immediately, also while the clock is running.",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditEventDialog(
    state: MatchState,
    event: MatchEvent,
    onDismiss: () -> Unit,
    onSave: (MatchEvent) -> Unit,
    onDelete: () -> Unit,
) {
    var type by remember { mutableStateOf(event.type) }
    var team by remember { mutableStateOf(event.team) }
    var cap by remember { mutableStateOf(event.cap?.toString() ?: "") }
    var period by remember { mutableStateOf(event.period.toString()) }
    var minutes by remember { mutableStateOf((event.gameMs / 60_000).toString()) }
    var seconds by remember { mutableStateOf(secondsText(event.gameMs)) }
    var note by remember { mutableStateOf(event.note) }
    var typeMenu by remember { mutableStateOf(false) }

    val ms = parseClock(minutes, seconds)
    val periodNumber = period.trim().toIntOrNull()
    val capNumber = cap.trim().toIntOrNull()
    val valid = ms != null && periodNumber != null && periodNumber >= 1 && (cap.isBlank() || capNumber != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit log entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${type.label} ▾")
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        for (t in EventType.values()) {
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; typeMenu = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in Team.values()) {
                        val selected = team == t
                        if (selected) {
                            Button(onClick = { team = t }, modifier = Modifier.weight(1f)) { Text(state.config.name(t), maxLines = 1) }
                        } else {
                            OutlinedButton(onClick = { team = t }, modifier = Modifier.weight(1f)) { Text(state.config.name(t), maxLines = 1) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        cap, { cap = it }, label = { Text("Cap") }, singleLine = true,
                        keyboardOptions = NumberKeys, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        period, { period = it }, label = { Text("Period") }, singleLine = true,
                        keyboardOptions = NumberKeys, modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        minutes, { minutes = it }, label = { Text("Clock min") }, singleLine = true,
                        keyboardOptions = NumberKeys, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        seconds, { seconds = it }, label = { Text("sec") }, singleLine = true,
                        keyboardOptions = DecimalKeys, modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onDelete) { Text("Delete this entry", color = AlertRed) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (ms != null && periodNumber != null) {
                        onSave(
                            event.copy(
                                type = type, team = team, cap = capNumber,
                                period = periodNumber, gameMs = ms, note = note.trim(),
                            )
                        )
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(config: MatchConfig, onDismiss: () -> Unit, onSave: (MatchConfig) -> Unit) {
    var white by remember { mutableStateOf(config.whiteName) }
    var blue by remember { mutableStateOf(config.blueName) }
    var periods by remember { mutableStateOf(config.periods.toString()) }
    var periodMin by remember { mutableStateOf(secondsOrMinutes(config.periodMs, 60_000)) }
    var shot by remember { mutableStateOf(secondsOrMinutes(config.shotMs, 1000)) }
    var shotShort by remember { mutableStateOf(secondsOrMinutes(config.shotShortMs, 1000)) }
    var exclusion by remember { mutableStateOf(secondsOrMinutes(config.exclusionMs, 1000)) }
    var timeout by remember { mutableStateOf(secondsOrMinutes(config.timeoutMs, 1000)) }
    var timeouts by remember { mutableStateOf(config.timeoutsPerTeam.toString()) }
    var interval by remember { mutableStateOf(secondsOrMinutes(config.intervalMs, 1000)) }
    var halftime by remember { mutableStateOf(secondsOrMinutes(config.halftimeMs, 1000)) }
    var foulLimit by remember { mutableStateOf(config.foulLimit.toString()) }
    var caps by remember { mutableStateOf(config.caps.toString()) }

    fun millis(text: String, unit: Long, old: Long): Long =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Math.round(it * unit) } ?: old

    fun count(text: String, old: Int, range: IntRange): Int = text.trim().toIntOrNull()?.coerceIn(range) ?: old

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Match settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(white, { white = it }, label = { Text("White caps team name") }, singleLine = true)
                OutlinedTextField(blue, { blue = it }, label = { Text("Blue caps team name") }, singleLine = true)
                NumberRow("Periods", periods, { periods = it }, "Period (min)", periodMin, { periodMin = it })
                NumberRow("Shot clock (s)", shot, { shot = it }, "Short shot clock (s)", shotShort, { shotShort = it })
                NumberRow("Exclusion (s)", exclusion, { exclusion = it }, "Fouls to foul out", foulLimit, { foulLimit = it })
                NumberRow("Timeouts / team", timeouts, { timeouts = it }, "Timeout (s)", timeout, { timeout = it })
                NumberRow("Interval (s)", interval, { interval = it }, "Half time (s)", halftime, { halftime = it })
                NumberRow("Caps per team", caps, { caps = it }, null, "", {})
                Text(
                    "Clock lengths apply from the next reset; the running period is not changed.",
                    color = Color.Gray, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    config.copy(
                        whiteName = white.trim().ifBlank { config.whiteName },
                        blueName = blue.trim().ifBlank { config.blueName },
                        periods = count(periods, config.periods, 1..9),
                        periodMs = millis(periodMin, 60_000, config.periodMs),
                        shotMs = millis(shot, 1000, config.shotMs),
                        shotShortMs = millis(shotShort, 1000, config.shotShortMs),
                        exclusionMs = millis(exclusion, 1000, config.exclusionMs),
                        timeoutMs = millis(timeout, 1000, config.timeoutMs),
                        timeoutsPerTeam = count(timeouts, config.timeoutsPerTeam, 0..9),
                        intervalMs = millis(interval, 1000, config.intervalMs),
                        halftimeMs = millis(halftime, 1000, config.halftimeMs),
                        foulLimit = count(foulLimit, config.foulLimit, 1..9),
                        caps = count(caps, config.caps, 1..30),
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun secondsOrMinutes(ms: Long, unit: Long): String =
    if (ms % unit == 0L) (ms / unit).toString() else String.format(Locale.US, "%.1f", ms.toDouble() / unit)

@Composable
private fun NumberRow(
    label1: String, value1: String, on1: (String) -> Unit,
    label2: String?, value2: String, on2: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value1, on1, label = { Text(label1, fontSize = 11.sp, maxLines = 1) }, singleLine = true,
            keyboardOptions = DecimalKeys, modifier = Modifier.weight(1f),
        )
        if (label2 != null) {
            OutlinedTextField(
                value2, on2, label = { Text(label2, fontSize = 11.sp, maxLines = 1) }, singleLine = true,
                keyboardOptions = DecimalKeys, modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun DevicesDialog(vm: ControllerViewModel, links: List<LinkInfo>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display tablets") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }) { Text("Bluetooth settings") }
        },
        text = {
            BluetoothGate {
                var selected by remember { mutableStateOf(vm.selectedDevices()) }
                val paired = remember { vm.pairedDevices() }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "1. Pair each tablet with this device in Android Bluetooth settings.\n" +
                            "2. Open this app on the tablet and choose Main scoreboard or Shot clock.\n" +
                            "3. Tick the tablets below. They reconnect automatically.",
                        color = Color.Gray, fontSize = 12.sp,
                    )
                    if (paired.isEmpty()) {
                        Text("No paired Bluetooth devices yet.", modifier = Modifier.padding(top = 12.dp))
                    }
                    for ((address, name) in paired) {
                        val link = links.firstOrNull { it.address == address }
                        val checked = address in selected
                        val toggle = {
                            selected = if (checked) selected - address else selected + address
                            vm.setSelectedDevices(selected)
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Column {
                                Text(name)
                                if (checked) {
                                    val status = when {
                                        link == null -> "…"
                                        !link.paired -> "Not paired any more — pair again in Bluetooth settings"
                                        link.connected -> "Connected" + (link.role?.let { " · $it" } ?: "")
                                        else -> "Searching…"
                                    }
                                    Text(
                                        status, fontSize = 12.sp,
                                        color = if (link?.connected == true) BreakGreen else AlertRed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
