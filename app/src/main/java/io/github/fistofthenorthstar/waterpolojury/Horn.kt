package io.github.fistofthenorthstar.waterpolojury

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Synthesised buzzer, so no audio asset is needed. Plays on the media volume. */
object Horn {
    private const val RATE = 22050
    private const val MILLIS = 1300

    private val samples: ShortArray by lazy {
        val n = RATE * MILLIS / 1000
        ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val a = if (sin(2 * PI * 233 * t) >= 0) 1.0 else -1.0
            val b = if (sin(2 * PI * 311 * t) >= 0) 1.0 else -1.0
            ((a * 0.45 + b * 0.35) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun play() {
        thread(name = "horn", isDaemon = true) {
            try {
                val data = samples
                @Suppress("DEPRECATION")
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, data.size * 2, AudioTrack.MODE_STATIC,
                )
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep(MILLIS + 200L)
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
