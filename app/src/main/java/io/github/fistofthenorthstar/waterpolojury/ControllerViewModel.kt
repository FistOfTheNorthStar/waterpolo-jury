package io.github.fistofthenorthstar.waterpolojury

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fistofthenorthstar.waterpolojury.bt.DisplayLink
import io.github.fistofthenorthstar.waterpolojury.bt.LinkInfo
import io.github.fistofthenorthstar.waterpolojury.core.DisplayState
import io.github.fistofthenorthstar.waterpolojury.core.MatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Owns the match (the controller is the single source of truth) and the display links. */
class ControllerViewModel(app: Application) : AndroidViewModel(app) {
    private val file = File(app.filesDir, "match.json")
    private val prefs = app.getSharedPreferences("controller", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(load())
    val state: StateFlow<MatchState> = _state.asStateFlow()

    private val _links = MutableStateFlow<List<LinkInfo>>(emptyList())
    val links: StateFlow<List<LinkInfo>> = _links.asStateFlow()

    @Volatile
    private var active: List<DisplayLink> = emptyList()

    init {
        refreshLinks()
        viewModelScope.launch {
            var n = 0
            while (true) {
                delay(100)
                n++
                val old = _state.value
                val s = old.advance(now())
                if (s !== old) _state.value = s
                val ticking = s.running || s.breakMs > 0
                if (s.hornSeq != old.hornSeq) {
                    Horn.play()
                    broadcast()
                    save()
                } else if (n % (if (ticking) 3 else 10) == 0) {
                    broadcast()
                }
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** Apply a match operation at the exact current time, then push it to the displays. */
    fun act(op: MatchState.(Long) -> MatchState) {
        val old = _state.value
        val s = old.op(now())
        _state.value = s
        if (s.hornSeq != old.hornSeq) Horn.play()
        broadcast()
        save()
    }

    fun soundHorn() = act { copy(hornSeq = hornSeq + 1) }

    private fun broadcast() {
        val links = active
        if (links.isEmpty()) return
        val line = AppJson.encodeToString<DisplayState>(_state.value.toDisplay())
        links.forEach { it.send(line) }
    }

    private fun load(): MatchState = try {
        // Clock timestamps do not survive a restart, so always come back stopped.
        AppJson.decodeFromString<MatchState>(file.readText())
            .copy(running = false, anchor = 0, breakMs = 0, breakLabel = "")
    } catch (_: Exception) {
        MatchState()
    }

    private fun save() {
        val text = AppJson.encodeToString<MatchState>(_state.value)
        io.execute {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            } catch (_: Exception) {
            }
        }
    }

    fun selectedDevices(): Set<String> = prefs.getStringSet("devices", emptySet())?.toSet() ?: emptySet()

    fun setSelectedDevices(addresses: Set<String>) {
        prefs.edit().putStringSet("devices", addresses).apply()
        refreshLinks()
    }

    /** Paired devices as (address, name). Empty when Bluetooth permission is missing. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<Pair<String, String>> = try {
        adapter?.bondedDevices.orEmpty().map { it.address to (it.name ?: it.address) }.sortedBy { it.second }
    } catch (_: Exception) {
        emptyList()
    }

    fun refreshLinks() {
        val want = selectedDevices()
        val (keep, drop) = active.partition { it.address in want }
        drop.forEach { it.close() }
        val have = keep.map { it.address }.toSet()
        val added = (want - have).mapNotNull { address ->
            try {
                adapter?.getRemoteDevice(address)?.let { device ->
                    DisplayLink(device, ::publishLinks).also { it.start() }
                }
            } catch (_: Exception) {
                null
            }
        }
        active = keep + added
        publishLinks()
    }

    private fun publishLinks() {
        _links.value = active.map { it.info() }
    }

    override fun onCleared() {
        active.forEach { it.close() }
        io.shutdown()
    }
}
