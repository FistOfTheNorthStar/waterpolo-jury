package io.github.fistofthenorthstar.waterpolojury.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * Transport: classic Bluetooth RFCOMM (works on practically every Android tablet).
 * Each display listens; the controller connects out to every selected paired device
 * and pushes newline-delimited JSON snapshots. The display answers once with its role.
 *
 * Only the secure socket variants are used: the link is encrypted and both ends must
 * already be paired, so a stranger at the pool cannot push a fake scoreboard.
 */

val SERVICE_UUID: UUID = UUID.fromString("7d2f4c1e-5b0a-4e7b-9c3d-77a1c0de2801")
private const val ROLE_PREFIX = "ROLE "

private const val MAX_LINE_BYTES = 16 * 1024

/** Reads one '\n'-terminated line, refusing oversized input. Null at end of stream. */
private fun InputStream.readBoundedLine(): String? {
    val buffer = java.io.ByteArrayOutputStream(512)
    while (true) {
        val b = read()
        if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
        if (b == '\n'.code) return buffer.toString("UTF-8")
        if (buffer.size() >= MAX_LINE_BYTES) throw IOException("line too long")
        buffer.write(b)
    }
}

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
                val srv = adapter.listenUsingRfcommWithServiceRecord("WaterPoloJury", SERVICE_UUID)
                server = srv
                val sock = srv.accept()
                runCatching { srv.close() }
                socket = sock
                if (sock.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
                    throw IOException("controller is not a paired device")
                }
                onStatus(true)
                sock.outputStream.write((ROLE_PREFIX + role + "\n").toByteArray())
                sock.outputStream.flush()
                val input = sock.inputStream.buffered()
                while (!closed) {
                    val line = input.readBoundedLine() ?: break
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
                val sock = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = sock
                // Parallel connection attempts disturb each other on cheap radios.
                synchronized(connectLock) { sock.connect() }
                queue.clear()
                connected = true
                onChange()
                thread(name = "link-read-$address", isDaemon = true) {
                    try {
                        val input = sock.inputStream.buffered()
                        while (true) {
                            val line = input.readBoundedLine() ?: break
                            if (line.startsWith(ROLE_PREFIX)) {
                                role = line.removePrefix(ROLE_PREFIX).trim().take(16)
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
