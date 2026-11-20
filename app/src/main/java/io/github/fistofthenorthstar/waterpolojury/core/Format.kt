package io.github.fistofthenorthstar.waterpolojury.core

import java.util.Locale
import kotlin.math.max

/** Game clock: m:ss, switching to seconds.tenths in the last minute. */
fun formatGame(ms: Long): String {
    val m: Long = max(ms, 0L)
    return if (m >= 60_000) {
        val s = m / 1000
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    } else {
        val t = m / 100
        String.format(Locale.US, "%d.%d", t / 10, t % 10)
    }
}

/** Whole seconds, rounded up, so 0 only shows when time has really expired. */
fun ceilSeconds(ms: Long): Long = (max(ms, 0L) + 999) / 1000

fun formatShot(ms: Long): String = ceilSeconds(ms).toString()

fun formatMinSec(ms: Long): String {
    val s = ceilSeconds(ms)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Minutes + seconds text fields (seconds may have decimals) to milliseconds. */
fun parseClock(minutes: String, seconds: String): Long? {
    val m = if (minutes.isBlank()) 0 else minutes.trim().toIntOrNull() ?: return null
    val s = if (seconds.isBlank()) 0.0 else seconds.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (m < 0 || s < 0) return null
    return m * 60_000L + Math.round(s * 1000)
}
