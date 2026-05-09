package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter

/**
 * Receives an MPEG-TS/H264 stream directly via FFmpeg''s native UDP input.
 *
 * `FFmpegFrameGrabber` opens `udp://0.0.0.0:<port>?fifo_size=500000&overrun_nonfatal=1`
 * and calls `grabImage()` in a loop, converting each decoded frame to a Compose
 * [ImageBitmap] emitted via [frame].
 *
 * No manual DatagramSocket, no chunk reassembly, no StreamBuffer — FFmpeg handles
 * all UDP receiving, MPEG-TS demuxing, and H264 decoding internally.
 */
class ScreenReceiver(private val port: Int) {

    // ── FFmpeg grabber ────────────────────────────────────────────────────────

    private val grabber = FFmpegFrameGrabber("udp://0.0.0.0:$port?fifo_size=500000&overrun_nonfatal=1").apply {
        format = "mpegts"
        setOption("fflags",          "nobuffer+discardcorrupt")
        setOption("flags",           "low_delay")
        setOption("analyzeduration", "500000")  // 500 ms probe
        setOption("probesize",       "65536")   // 64 KB
    }

    private val converter = Java2DFrameConverter()

    // ── Compose state ────────────────────────────────────────────────────────

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val decodedFps: StateFlow<Float> = _fps.asStateFlow()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Volatile var isRunning = false; private set
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        isRunning = true
        println("[ScreenReceiver] start() — will listen on UDP port $port")
        scope.launch { decodeLoop() }
    }

    fun stop() {
        println("[ScreenReceiver] stop()")
        isRunning = false
        scope.cancel()
        runCatching { grabber.stop(); grabber.release() }
    }

    // ── Decode loop ───────────────────────────────────────────────────────────

    private suspend fun decodeLoop() = withContext(Dispatchers.IO) {
        println("[ScreenReceiver] Opening FFmpegFrameGrabber on udp://0.0.0.0:$port ...")
        try {
            grabber.start()
            println("[ScreenReceiver] grabber.start() succeeded — format=${grabber.format}")
        } catch (e: FrameGrabber.Exception) {
            println("[ScreenReceiver] grabber.start() FAILED (FrameGrabber.Exception): ${e.message}")
            return@withContext
        } catch (e: Exception) {
            println("[ScreenReceiver] grabber.start() FAILED (${e::class.simpleName}): ${e.message}")
            return@withContext
        }

        var frameCount  = 0
        var totalFrames = 0
        var fpsWindowMs = System.currentTimeMillis()

        println("[ScreenReceiver] decode loop running — waiting for frames...")
        while (isRunning) {
            try {
                val javacvFrame = grabber.grabImage()
                if (javacvFrame == null) {
                    delay(2)
                    continue
                }

                val bi = converter.getBufferedImage(javacvFrame)
                if (bi == null) {
                    println("[ScreenReceiver] WARN: converter returned null for frame $totalFrames")
                    continue
                }

                _frame.value = bi.toComposeImageBitmap()
                totalFrames++
                if (totalFrames == 1) println("[ScreenReceiver] First frame decoded!")

                frameCount++
                val now = System.currentTimeMillis()
                if (now - fpsWindowMs >= 1_000L) {
                    _fps.value = frameCount * 1_000f / (now - fpsWindowMs)
                    println("[ScreenReceiver] FPS=${_fps.value} totalFrames=$totalFrames")
                    frameCount  = 0
                    fpsWindowMs = now
                }

            } catch (e: FrameGrabber.Exception) {
                println("[ScreenReceiver] grabImage FrameGrabber.Exception: ${e.message}")
                delay(10)
            } catch (e: Exception) {
                println("[ScreenReceiver] grabImage exception (${e::class.simpleName}): ${e.message}")
                delay(5)
            }
        }
        println("[ScreenReceiver] decodeLoop ended — totalFrames=$totalFrames")
    }
}
