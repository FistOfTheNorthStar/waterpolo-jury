package io.github.fistofthenorthstar.waterpolojury.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fistofthenorthstar.waterpolojury.ControllerViewModel
import io.github.fistofthenorthstar.waterpolojury.bt.LinkInfo
import io.github.fistofthenorthstar.waterpolojury.core.EventType
import io.github.fistofthenorthstar.waterpolojury.core.MatchEvent
import io.github.fistofthenorthstar.waterpolojury.core.MatchState
import io.github.fistofthenorthstar.waterpolojury.core.Team
import io.github.fistofthenorthstar.waterpolojury.core.ceilSeconds
import io.github.fistofthenorthstar.waterpolojury.core.formatGame
import io.github.fistofthenorthstar.waterpolojury.core.formatMinSec
import io.github.fistofthenorthstar.waterpolojury.core.formatShot

sealed interface Dlg {
    data class CapPick(val type: EventType, val team: Team) : Dlg
    data class Edit(val event: MatchEvent) : Dlg
    data object SetGame : Dlg
    data object SetShot : Dlg
    data object Settings : Dlg
    data object Devices : Dlg
    data object ConfirmNew : Dlg
}

private val BlueButton = Color(0xFF1565C0)
private val GoGreen = Color(0xFF2E7D32)
private val StopRed = Color(0xFFC62828)
private val Panel = Color(0xFF1C1C1E)

@Composable
fun ControllerScreen(onExit: () -> Unit, vm: ControllerViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val links by vm.links.collectAsState()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(horizontal = 8.dp)) {
        TopBar(
            links = links,
            state = state,
            onDevices = { dlg = Dlg.Devices },
            onSettings = { dlg = Dlg.Settings },
            onNewMatch = { dlg = Dlg.ConfirmNew },
            onNextPeriod = { vm.act { nextPeriod(it) } },
            onPreviousPeriod = { vm.act { previousPeriod(it) } },
            onInterval = { vm.act { startBreak(it, config.intervalMs, "INTERVAL") } },
            onHorn = { vm.soundHorn() },
            onExport = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Water polo match log")
                    .putExtra(Intent.EXTRA_TEXT, state.exportText())
                context.startActivity(Intent.createChooser(send, "Export match log"))
            },
            onExit = onExit,
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                ScoreHeader(
                    state = state,
                    onSetGame = { dlg = Dlg.SetGame },
                    onAdjust = { delta -> vm.act { adjustGame(it, delta) } },
                )
            }
            if (state.breakMs > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${state.breakLabel}  ${formatMinSec(state.breakMs)}",
                            color = BreakGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.act { cancelBreak(it) } }) { Text("Cancel") }
                    }
                }
            }
            if (state.gameMs <= 0 && !state.isShootout) {
                item {
                    Button(
                        onClick = { vm.act { nextPeriod(it) } },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            if (state.period < state.config.periods) "Period ${state.period} over — set up period ${state.period + 1}"
                            else "Full time — go to penalty shootout",
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (team in Team.values()) {
                        TeamActions(
                            team = team,
                            state = state,
                            modifier = Modifier.weight(1f),
                            onAction = { type ->
                                if (type == EventType.TIMEOUT) vm.act { addEvent(it, type, team, null) }
                                else dlg = Dlg.CapPick(type, team)
                            },
                        )
                    }
                }
            }
            if (state.exclusions.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (e in state.exclusions) {
                            Text(
                                "${e.team.short}${e.cap?.let { " #$it" } ?: ""}  ${ceilSeconds(e.remainingMs)}s  ✕",
                                color = teamColor(e.team),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0xFF5A1010), RoundedCornerShape(16.dp))
                                    .clickable { vm.act { clearExclusion(it, e.eventId) } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Match log (tap to edit) — ${state.events.size} entries",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(state.events.asReversed(), key = { it.id }) { ev ->
                EventRow(ev, state) { dlg = Dlg.Edit(ev) }
            }
        }

        BottomControls(
            state = state,
            onToggle = { vm.act { toggleRun(it) } },
            onSetShot = { dlg = Dlg.SetShot },
            onShot = { ms -> vm.act { setShot(it, ms) } },
            onAdjustShot = { delta -> vm.act { adjustShot(it, delta) } },
        )
    }

    when (val d = dlg) {
        null -> {}
        is Dlg.CapPick -> CapPickerDialog(state, d.type, d.team, onDismiss = { dlg = null }) { cap ->
            vm.act { addEvent(it, d.type, d.team, cap) }
            dlg = null
        }
        is Dlg.Edit -> EditEventDialog(
            state, d.event,
            onDismiss = { dlg = null },
            onSave = { ev -> vm.act { updateEvent(it, ev) }; dlg = null },
            onDelete = { vm.act { deleteEvent(it, d.event.id) }; dlg = null },
        )
        Dlg.SetGame -> SetClockDialog("Set game clock", state.gameMs, withMinutes = true, onDismiss = { dlg = null }) { ms ->
            vm.act { setGame(it, ms) }
            dlg = null
        }
        Dlg.SetShot -> SetClockDialog("Set shot clock", state.shotMs, withMinutes = false, onDismiss = { dlg = null }) { ms ->
            vm.act { setShot(it, ms) }
            dlg = null
        }
        Dlg.Settings -> SettingsDialog(state.config, onDismiss = { dlg = null }) { c ->
            vm.act { withConfig(it, c) }
            dlg = null
        }
        Dlg.Devices -> DevicesDialog(vm, links, onDismiss = { dlg = null })
        Dlg.ConfirmNew -> ConfirmDialog(
            title = "Start a new match?",
            text = "The current score and match log will be cleared. Export the log first if you need it.",
            confirm = "New match",
            onDismiss = { dlg = null },
        ) {
            vm.act { newMatch(it) }
            dlg = null
        }
    }
}

@Composable
private fun TopBar(
    links: List<LinkInfo>,
    state: MatchState,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    onNewMatch: () -> Unit,
    onNextPeriod: () -> Unit,
    onPreviousPeriod: () -> Unit,
    onInterval: () -> Unit,
    onHorn: () -> Unit,
    onExport: () -> Unit,
    onExit: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val up = links.count { it.connected }
        TextButton(onClick = onDevices) {
            Text(
                if (links.isEmpty()) "Displays: none selected" else "Displays: $up/${links.size} connected",
                color = if (links.isNotEmpty() && up == links.size) BreakGreen else AlertRed,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) { Text("Menu ☰") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val entries = listOf(
                    "Displays (Bluetooth)" to onDevices,
                    "Match settings" to onSettings,
                    "Next period" to onNextPeriod,
                    "Previous period" to onPreviousPeriod,
                    "Start interval timer" to onInterval,
                    "Sound horn" to onHorn,
                    "Export match log" to onExport,
                    "New match" to onNewMatch,
                    "Change device role" to onExit,
                )
                for ((label, action) in entries) {
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(state: MatchState, onSetGame: () -> Unit, onAdjust: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamScore(state, Team.WHITE, Modifier.weight(1f))
        Column(Modifier.weight(1.5f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state.isShootout) "PENALTY SHOOTOUT" else "PERIOD ${state.period} / ${state.config.periods}",
                color = Color.LightGray, fontSize = 13.sp,
            )
            Text(
                formatGame(state.gameMs),
                color = ClockColor, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onSetGame),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("−1s") { onAdjust(-1000) }
                SmallButton("Set") { onSetGame() }
                SmallButton("+1s") { onAdjust(1000) }
            }
        }
        TeamScore(state, Team.BLUE, Modifier.weight(1f))
    }
}

@Composable
private fun TeamScore(state: MatchState, team: Team, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.config.name(team), color = teamColor(team), fontSize = 14.sp, maxLines = 1)
        Text(
            state.score(team).toString(),
            color = teamColor(team), fontSize = 52.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        )
        Text("Timeouts left: ${state.timeoutsLeft(team)}", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun TeamActions(team: Team, state: MatchState, modifier: Modifier, onAction: (EventType) -> Unit) {
    val colors = if (team == Team.WHITE) {
        ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
    } else {
        ButtonDefaults.buttonColors(containerColor = BlueButton, contentColor = Color.White)
    }
    var more by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = { onAction(EventType.GOAL) },
            colors = colors,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) { Text("GOAL  ${state.config.name(team)}", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { onAction(EventType.EXCLUSION) },
                colors = colors, contentPadding = PaddingValues(2.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Exclusion", maxLines = 1) }
            Button(
                onClick = { onAction(EventType.PENALTY) },
                colors = colors, contentPadding = PaddingValues(2.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Penalty", maxLines = 1) }
        }
        Box {
            OutlinedButton(onClick = { more = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Timeout / cards / more ▾", color = teamColor(team), fontSize = 13.sp, maxLines = 1)
            }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                val types = listOf(
                    EventType.TIMEOUT, EventType.MISCONDUCT, EventType.BRUTALITY,
                    EventType.YELLOW_CARD, EventType.RED_CARD,
                )
                for (type in types) {
                    DropdownMenuItem(
                        text = { Text(type.label + if (type == EventType.TIMEOUT) " (${state.timeoutsLeft(team)} left)" else "") },
                        onClick = { more = false; onAction(type) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(ev: MatchEvent, state: MatchState, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (ev.period > state.config.periods) "PSO" else "P${ev.period}") + " " + formatGame(ev.gameMs),
                color = Color.LightGray, fontFamily = FontFamily.Monospace, modifier = Modifier.width(92.dp),
            )
            val who = ev.team?.let { t -> state.config.name(t) + (ev.cap?.let { " #$it" } ?: "") } ?: ""
            Text(
                who, color = ev.team?.let { teamColor(it) } ?: Color.Gray,
                fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f),
            )
            val team = ev.team
            val cap = ev.cap
            val extra = when {
                team != null && cap != null && ev.type.personalFoul ->
                    "  (${state.fouls(team, cap)}/${state.config.foulLimit})" + if (state.isOut(team, cap)) " OUT" else ""
                else -> ""
            }
            Text(
                ev.type.label + extra + if (ev.note.isNotBlank()) " · ${ev.note}" else "",
                color = if (ev.type == EventType.GOAL) BreakGreen else Color.White,
                textAlign = TextAlign.End, modifier = Modifier.weight(1.4f),
            )
        }
        HorizontalDivider(color = Color(0xFF2C2C2E))
    }
}

@Composable
private fun BottomControls(
    state: MatchState,
    onToggle: () -> Unit,
    onSetShot: () -> Unit,
    onShot: (Long) -> Unit,
    onAdjustShot: (Long) -> Unit,
) {
    val full = state.config.shotMs
    val short = state.config.shotShortMs
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(118.dp)) {
                Text(
                    formatShot(state.shotMs),
                    color = if (ceilSeconds(state.shotMs) <= 5) AlertRed else ClockColor,
                    fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable(onClick = onSetShot),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("−1") { onAdjustShot(-1000) }
                    SmallButton("+1") { onAdjustShot(1000) }
                }
            }
            Button(
                onClick = { onShot(full) },
                modifier = Modifier.weight(1f).height(84.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242), contentColor = ClockColor),
            ) { Text(formatShot(full), fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { onShot(short) },
                modifier = Modifier.weight(1f).height(84.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242), contentColor = ClockColor),
            ) { Text(formatShot(short), fontSize = 34.sp, fontWeight = FontWeight.Bold) }
        }
        Button(
            onClick = onToggle,
            enabled = state.running || state.gameMs > 0,
            modifier = Modifier.fillMaxWidth().height(92.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.running) StopRed else GoGreen,
                contentColor = Color.White,
            ),
        ) {
            Text(if (state.running) "STOP" else "START", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
    }
}
