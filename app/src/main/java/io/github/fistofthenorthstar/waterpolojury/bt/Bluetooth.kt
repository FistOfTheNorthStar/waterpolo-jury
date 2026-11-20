package io.github.fistofthenorthstar.waterpolojury.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

/** The controller sends at least once a second; silence this long means a dead peer. */
private const val SILENCE_TIMEOUT_MS = 6_000L
private const val HANDSHAKE_TIMEOUT_MS = 5_000L

private fun nowMs(): Long = System.nanoTime() / 1_000_000

/**
 * Reads one '\n'-terminated line (a trailing '\r' is dropped). Null at end of stream.
 * An oversized line is skipped and returned as "" so one bad message cannot kill the link.
 */
private fun InputStream.readBoundedLine(): String? {
    val buffer = ByteArrayOutputStream(512)
    var oversized = false
    var sawAny = false
    while (true) {
        val b = read()
        if (b < 0) {
            if (!sawAny) return null
            break
        }
        sawAny = true
        if (b == '\n'.code) break
        if (buffer.size() >= MAX_LINE_BYTES) oversized = true else buffer.write(b)
    }
    return if (oversized) "" else buffer.toString("UTF-8").removeSuffix("\r")
}

data class LinkInfo(
    val address: String,
    val name: String,
    val connected: Boolean,
    val role: String?,
    /** False when Android no longer has this device paired. */
    val paired: Boolean = true,
)

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
                serve(sock)
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

    private fun serve(sock: BluetoothSocket) {
        if (!awaitBonded(sock.remoteDevice)) throw IOException("controller is not a paired device")
        val alive = AtomicBoolean(true)
        val lastRx = java.util.concurrent.atomic.AtomicLong(nowMs())
        // Bluetooth sockets have no read timeout, so a watchdog drops a silent peer
        // and lets the real controller back in.
        thread(name = "display-watchdog", isDaemon = true) {
            while (alive.get() && !closed) {
                runCatching { Thread.sleep(1000) }
                if (alive.get() && nowMs() - lastRx.get() > SILENCE_TIMEOUT_MS) {
                    runCatching { sock.close() }
                    break
                }
            }
        }
        try {
            onStatus(true)
            sock.outputStream.write((ROLE_PREFIX + role + "\n").toByteArray())
            sock.outputStream.flush()
            val input = sock.inputStream.buffered()
            while (!closed) {
                val line = input.readBoundedLine() ?: break
                lastRx.set(nowMs())
                if (line.isNotEmpty()) onLine(line)
            }
        } finally {
            alive.set(false)
        }
    }

    /** Right after first-time pairing the bond state can still be "bonding" for a moment. */
    private fun awaitBonded(device: BluetoothDevice): Boolean {
        repeat(15) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return true
                BluetoothDevice.BOND_NONE -> return false
                else -> runCatching { Thread.sleep(200) }
            }
        }
        return false
    }
}

/** Controller side: one self-reconnecting link per display. Only the newest snapshot is kept. */
@SuppressLint("MissingPermission")
class DisplayLink(private val device: BluetoothDevice, private val onChange: () -> Unit) {
    val address: String = device.address

    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var paired = true
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
        LinkInfo(address, runCatching { device.name }.getOrNull() ?: address, connected, role, paired)

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun setPaired(value: Boolean) {
        if (paired != value) {
            paired = value
            onChange()
        }
    }

    private fun run() {
        var failures = 0
        while (!closed) {
            // Never start a connection to an unpaired device: that would pop up
            // pairing dialogs on both ends every few seconds.
            val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            setPaired(bonded)
            if (!bonded) {
                runCatching { Thread.sleep(3000) }
                continue
            }
            var handshook = false
            try {
                val sock = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = sock
                // Parallel connection attempts disturb each other on cheap radios.
                synchronized(connectLock) { sock.connect() }
                queue.clear()
                val since = nowMs()
                thread(name = "link-read-$address", isDaemon = true) {
                    try {
                        val input = sock.inputStream.buffered()
                        while (true) {
                            val line = input.readBoundedLine() ?: break
                            // Ignore a reader left over from an earlier connection.
                            if (line.startsWith(ROLE_PREFIX) && socket === sock && !closed) {
                                role = line.removePrefix(ROLE_PREFIX).trim().take(16)
                                connected = true
                                onChange()
                            }
                        }
                    } catch (_: Exception) {
                    }
                    runCatching { sock.close() }
                }
                val out = sock.outputStream
                while (!closed) {
                    // The link only counts as connected once the display has answered;
                    // a display that accepts and then rejects us never gets that far.
                    if (connected) {
                        handshook = true
                    } else if (nowMs() - since > HANDSHAKE_TIMEOUT_MS) {
                        throw IOException("display did not answer")
                    }
                    val line = queue.poll(if (connected) 1000L else 100L, TimeUnit.MILLISECONDS) ?: continue
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }
            } catch (_: Exception) {
                // Display not reachable yet or link dropped: retry below.
            } finally {
                runCatching { socket?.close() }
                socket = null
                val was = connected
                connected = false
                role = null
                if (was) onChange()
            }
            failures = if (handshook) 0 else failures + 1
            // Quick retries at first, then back off so a stale pairing does not hammer the radio.
            if (!closed) runCatching { Thread.sleep(minOf(2000L + failures * 1000L, 10_000L)) }
        }
    }

    private companion object {
        val connectLock = Any()
    }
}
