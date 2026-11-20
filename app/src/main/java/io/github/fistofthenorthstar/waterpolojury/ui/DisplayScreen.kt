package io.github.fistofthenorthstar.waterpolojury.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.fistofthenorthstar.waterpolojury.AppJson
import io.github.fistofthenorthstar.waterpolojury.BluetoothGate
import io.github.fistofthenorthstar.waterpolojury.Horn
import io.github.fistofthenorthstar.waterpolojury.Role
import io.github.fistofthenorthstar.waterpolojury.bt.DisplayServer
import io.github.fistofthenorthstar.waterpolojury.core.DisplayState
import io.github.fistofthenorthstar.waterpolojury.core.Team
import io.github.fistofthenorthstar.waterpolojury.core.ceilSeconds
import io.github.fistofthenorthstar.waterpolojury.core.formatGame
import io.github.fistofthenorthstar.waterpolojury.core.formatMinSec
import io.github.fistofthenorthstar.waterpolojury.core.formatShot
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlin.math.max
import kotlin.math.min

val WhiteTeam = Color(0xFFFFFFFF)
val BlueTeam = Color(0xFF4DA3FF)
val ClockColor = Color(0xFFFFD600)
val AlertRed = Color(0xFFFF3B30)
val BreakGreen = Color(0xFF4CD964)

fun teamColor(team: Team): Color = if (team == Team.WHITE) WhiteTeam else BlueTeam

private class Received(val state: DisplayState, val at: Long)

@SuppressLint("MissingPermission")
@Composable
fun DisplayScreen(role: Role, onExit: () -> Unit) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave display mode?") },
            confirmButton = { TextButton(onClick = onExit) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BluetoothGate { adapter ->
            var received by remember { mutableStateOf<Received?>(null) }
            var connected by remember { mutableStateOf(false) }
            DisposableEffect(adapter, role) {
                val server = DisplayServer(
                    adapter = adapter,
                    role = role.name,
                    onLine = { line ->
                        runCatching { AppJson.decodeFromString<DisplayState>(line) }.getOrNull()?.let {
                            received = Received(it, SystemClock.elapsedRealtime())
                        }
                    },
                    onStatus = { connected = it },
                )
                server.start()
                onDispose { server.close() }
            }

            // Horn follows the controller's counter; the first snapshot only sets the baseline.
            var lastHorn by remember { mutableStateOf<Int?>(null) }
            val horn = received?.state?.hornSeq
            LaunchedEffect(horn) {
                if (horn != null) {
                    if (lastHorn != null && lastHorn != horn) Horn.play()
                    lastHorn = horn
                }
            }

            val now by produceState(SystemClock.elapsedRealtime()) {
                while (true) {
                    delay(50)
                    value = SystemClock.elapsedRealtime()
                }
            }

            val r = received
            if (r == null) {
                val name = remember { runCatching { adapter.name }.getOrNull() ?: "this tablet" }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(role.title, color = Color.White, fontSize = 34.sp)
                    Text("Waiting for the jury table…", color = Color.LightGray, fontSize = 22.sp)
                    Text(
                        "Pair this tablet with the controller in Android Bluetooth settings,\n" +
                            "then tick \"$name\" under Displays in the controller.",
                        color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                // Clocks keep counting locally between snapshots (and through short link drops).
                val live = r.state.extrapolate(now - r.at)
                if (role == Role.MAIN) MainBoard(live) else ShotBoard(live)
                if (!connected) {
                    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopEnd) {
                        Box(Modifier.size(18.dp).background(AlertRed, CircleShape))
                    }
                }
            }
        }
    }
}

/** Single line of text scaled to fill the space it is given. */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    chars: Int = text.length,
    mono: Boolean = true,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val sizeDp = min(maxHeight.value * 0.78f, maxWidth.value / (max(chars, 1) * 0.62f))
        val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
        Text(
            text,
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MainBoard(s: DisplayState) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.weight(1.1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FitText(s.whiteName, Modifier.weight(1f).fillMaxHeight(), WhiteTeam, chars = max(s.whiteName.length, 6), mono = false)
            FitText(
                if (s.period == "PSO") "PENALTIES" else "PERIOD ${s.period}",
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp), Color.LightGray, chars = 9, mono = false,
            )
            FitText(s.blueName, Modifier.weight(1f).fillMaxHeight(), BlueTeam, chars = max(s.blueName.length, 6), mono = false)
        }
        Row(Modifier.weight(3.2f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FitText(s.whiteScore.toString(), Modifier.weight(1f).fillMaxHeight(), WhiteTeam, chars = 2)
            Column(Modifier.weight(1.8f).fillMaxHeight()) {
                if (s.breakMs > 0) {
                    FitText(s.breakLabel, Modifier.weight(1f).fillMaxWidth(), BreakGreen, mono = false)
                    FitText(formatMinSec(s.breakMs), Modifier.weight(3f).fillMaxWidth(), BreakGreen, chars = 4)
                } else {
                    FitText(formatGame(s.gameMs), Modifier.fillMaxSize(), ClockColor, chars = 4)
                }
            }
            FitText(s.blueScore.toString(), Modifier.weight(1f).fillMaxHeight(), BlueTeam, chars = 2)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ExclusionList(s, Team.WHITE, Modifier.weight(1f).fillMaxHeight())
            FitText(
                "T.O.  ${s.whiteTimeouts} : ${s.blueTimeouts}",
                Modifier.weight(0.8f).fillMaxHeight().padding(vertical = 14.dp), Color.Gray, mono = false,
            )
            ExclusionList(s, Team.BLUE, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ExclusionList(s: DisplayState, team: Team, modifier: Modifier) {
    val list = s.exclusions.filter { it.team == team }.take(3)
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (e in list) {
            FitText(
                "${e.cap ?: "-"}·${ceilSeconds(e.ms)}",
                Modifier.weight(1f).fillMaxHeight().padding(4.dp), AlertRed, chars = 6,
            )
        }
    }
}

@Composable
private fun ShotBoard(s: DisplayState) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        val seconds = ceilSeconds(s.shotMs)
        FitText(
            formatShot(s.shotMs),
            Modifier.weight(5f).fillMaxWidth(),
            if (seconds <= 5) AlertRed else ClockColor,
            chars = 2,
        )
        FitText(
            if (s.breakMs > 0) "${s.breakLabel} ${formatMinSec(s.breakMs)}"
            else "P${s.period}   ${formatGame(s.gameMs)}   ${s.whiteScore}-${s.blueScore}",
            Modifier.weight(1f).fillMaxWidth(),
            Color.LightGray,
        )
    }
}
