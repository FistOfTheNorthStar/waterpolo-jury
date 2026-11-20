package io.github.fistofthenorthstar.waterpolojury.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * Transport: classic Bluetooth RFCOMM (works on practically every Android tablet).
 * Each display listens; the controller connects out to every selected paired device
 * and pushes newline-delimited JSON snapshots. The display answers once with its role.
 */

val SERVICE_UUID: UUID = UUID.fromString("7d2f4c1e-5b0a-4e7b-9c3d-77a1c0de2801")
private const val ROLE_PREFIX = "ROLE "

data class LinkInfo(val address: String, val name: String, val connected: Boolean, val role: String?)

/** Display side: accepts one controller at a time and keeps listening after a drop. */
@SuppressLint("MissingPermission")
class DisplayServer(
    private val adapter: BluetoothAdapter,
    private val role: String,
    private val onLine: (String) -> Unit,
    private val onStatus: (Boolean) -> Unit,
) {
    @Volatile private var closed = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null

    fun start() {
        thread(name = "display-server", isDaemon = true) { run() }
    }

    fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { socket?.close() }
    }

    private fun run() {
        while (!closed) {
            try {
                val srv = adapter.listenUsingInsecureRfcommWithServiceRecord("WaterPoloJury", SERVICE_UUID)
                server = srv
                val sock = srv.accept()
                runCatching { srv.close() }
                socket = sock
                onStatus(true)
                sock.outputStream.write((ROLE_PREFIX + role + "\n").toByteArray())
                sock.outputStream.flush()
                val reader = sock.inputStream.bufferedReader()
                while (!closed) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            } catch (_: Exception) {
                // Bluetooth off, permission missing or link dropped: retry below.
            } finally {
                runCatching { server?.close() }
                runCatching { socket?.close() }
                onStatus(false)
            }
            if (!closed) runCatching { Thread.sleep(1000) }
        }
    }
}

/** Controller side: one self-reconnecting link per display. Only the newest snapshot is kept. */
@SuppressLint("MissingPermission")
class DisplayLink(private val device: BluetoothDevice, private val onChange: () -> Unit) {
    val address: String = device.address

    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var role: String? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val queue = LinkedBlockingQueue<String>()

    fun start() {
        thread(name = "link-$address", isDaemon = true) { run() }
    }

    fun send(line: String) {
        if (!connected) return
        queue.clear()
        queue.offer(line)
    }

    fun info(): LinkInfo =
        LinkInfo(address, runCatching { device.name }.getOrNull() ?: address, connected, role)

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun run() {
        while (!closed) {
            try {
                val sock = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = sock
                // Parallel connection attempts disturb each other on cheap radios.
                synchronized(connectLock) { sock.connect() }
                queue.clear()
                connected = true
                onChange()
                thread(name = "link-read-$address", isDaemon = true) {
                    try {
                        sock.inputStream.bufferedReader().forEachLine { line ->
                            if (line.startsWith(ROLE_PREFIX)) {
                                role = line.removePrefix(ROLE_PREFIX).trim()
                                onChange()
                            }
                        }
                    } catch (_: Exception) {
                    }
                    runCatching { sock.close() }
                }
                val out = sock.outputStream
                while (!closed) {
                    val line = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }
            } catch (_: Exception) {
                // Display not reachable yet or link dropped: retry below.
            } finally {
                runCatching { socket?.close() }
                val was = connected
                connected = false
                role = null
                if (was) onChange()
            }
            if (!closed) runCatching { Thread.sleep(2000) }
        }
    }

    private companion object {
        val connectLock = Any()
    }
}
