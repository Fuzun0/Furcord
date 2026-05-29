package com.furcord.platform

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin

actual object SoundEffect {
    private val fmt = AudioFormat(44100f, 16, 1, true, false)

    /** Çıkış sesi: yumuşak alçalan ding (880 Hz → 660 Hz, 180 ms). */
    actual fun playJoin() = playAsync(
        startHz = 880.0, endHz = 1100.0, durationMs = 180, volumeScale = 0.175f
    )

    /** Giriş sesi: yumuşak yükselen ding (660 Hz → 880 Hz, 180 ms). */
    actual fun playLeave() = playAsync(
        startHz = 660.0, endHz = 440.0, durationMs = 180, volumeScale = 0.14f
    )

    private fun playAsync(startHz: Double, endHz: Double, durationMs: Int, volumeScale: Float) {
        Thread {
            try {
                val sampleRate = 44100
                val totalSamples = sampleRate * durationMs / 1000
                val buf = ShortArray(totalSamples)
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    val progress = i.toDouble() / totalSamples
                    val hz = startHz + (endHz - startHz) * progress
                    // Fade-in + fade-out zarf
                    val fadeIn  = if (progress < 0.1) progress / 0.1 else 1.0
                    val fadeOut = if (progress > 0.8) (1.0 - progress) / 0.2 else 1.0
                    val envelope = fadeIn * fadeOut
                    buf[i] = (sin(2 * PI * hz * t) * envelope * volumeScale * Short.MAX_VALUE).toInt().toShort()
                }
                val bytes = ByteArray(totalSamples * 2)
                for (i in buf.indices) {
                    val v = buf[i].toInt()
                    bytes[i * 2]     = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
                }
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(fmt)
                line.start()
                line.write(bytes, 0, bytes.size)
                line.drain()
                line.close()
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
    }
}
