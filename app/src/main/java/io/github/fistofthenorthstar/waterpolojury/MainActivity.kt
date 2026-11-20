package io.github.fistofthenorthstar.waterpolojury

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.fistofthenorthstar.waterpolojury.ui.ControllerScreen
import io.github.fistofthenorthstar.waterpolojury.ui.DisplayScreen

enum class Role(val title: String, val hint: String) {
    CONTROLLER("Jury table (controller)", "Runs the clocks and keeps the match log"),
    MAIN("Main scoreboard", "Score, game clock and period"),
    SHOT("Shot clock", "Possession clock for one end of the pool"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { App() }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    // The chosen role is remembered so a tablet comes straight back as a display.
    var role by remember {
        mutableStateOf(prefs.getString("role", null)?.let { saved -> Role.values().firstOrNull { it.name == saved } })
    }
    val setRole: (Role?) -> Unit = { r ->
        role = r
        prefs.edit().putString("role", r?.name).apply()
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            val r = role
            if (r == null) {
                RoleChooser(setRole)
            } else if (r == Role.CONTROLLER) {
                ControllerScreen(onExit = { setRole(null) })
            } else {
                DisplayScreen(r, onExit = { setRole(null) })
            }
        }
    }
}

@Composable
private fun RoleChooser(onPick: (Role) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Water Polo Jury", fontSize = 30.sp, color = Color.White)
        Text("What is this device used for?", color = Color.LightGray)
        Spacer(Modifier.height(24.dp))
        for (r in Role.values()) {
            Button(
                onClick = { onPick(r) },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                    Text(r.title, fontSize = 20.sp)
                    Text(r.hint, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Shows `content` once Bluetooth exists, is permitted and is switched on. */
@Composable
fun BluetoothGate(content: @Composable (BluetoothAdapter) -> Unit) {
    val context = LocalContext.current
    val adapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    if (adapter == null) {
        GateMessage("This device has no Bluetooth.", null) {}
        return
    }
    val needsPermission = Build.VERSION.SDK_INT >= 31
    var granted by remember {
        mutableStateOf(
            !needsPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        GateMessage("Bluetooth permission is needed to link the jury devices.", "Allow Bluetooth") {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return
    }
    var enabled by remember { mutableStateOf(adapter.isEnabled) }
    val enableLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            enabled = adapter.isEnabled
        }
    if (!enabled) {
        GateMessage("Bluetooth is switched off.", "Turn on Bluetooth") {
            try {
                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                enabled = adapter.isEnabled
            }
        }
        return
    }
    content(adapter)
}

@Composable
private fun GateMessage(text: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}
