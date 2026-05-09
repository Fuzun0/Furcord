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
 * Key safety measures:
 *  - `timeout` / `rw_timeout` (3 s) prevent infinite blocking inside native code.
 *  - `CancellationException` is re-thrown so coroutine cooperative cancellation works.
 *  - `finally` block always calls `grabber.stop()/release()` — prevents JVM crashes
 *    from orphaned native memory when the coroutine scope is cancelled externally.
 *  - null `grabImage()` return (stream end / timeout) breaks the loop gracefully.
 */
class ScreenReceiver(private val port: Int) {

    // ── FFmpeg grabber ────────────────────────────────────────────────────────

    private val grabber = FFmpegFrameGrabber(
        "udp://0.0.0.0:$port?fifo_size=500000&overrun_nonfatal=1"
    ).apply {
        format = "mpegts"
        // Prevent probe from blocking forever when no data arrives
        setOption("timeout",         "3000000")  // 3 s open timeout (µs)
        setOption("rw_timeout",      "3000000")  // 3 s read/write timeout (µs)
        setOption("fflags",          "nobuffer+discardcorrupt")
        setOption("flags",           "low_delay")
        setOption("analyzeduration", "500000")   // 500 ms probe
        setOption("probesize",       "65536")    // 64 KB probe size
    }

    private val converter = Java2DFrameConverter()

    // ── Compose state ────────────────────────────────────────────────────────

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val decodedFps: StateFlow<Float> = _fps.asStateFlow()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Volatile var isRunning = false; private set

    /**
     * Dedicated IO scope — NEVER cancelled externally.
     * We drive shutdown via [isRunning] + [grabber] native timeout so the
     * JVM thread completes its native call before the scope ends.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        println("[ScreenReceiver] start() — UDP port $port")
        scope.launch { decodeLoop() }
    }

    /**
     * Signals the decode loop to stop, then waits up to [timeoutMs] for
     * the native thread to exit cleanly before force-cancelling the scope.
     */
    fun stop(timeoutMs: Long = 5_000) {
        println("[ScreenReceiver] stop() requested")
        isRunning = false
        // Give the native FFmpeg call up to timeoutMs to return on its own
        // (it will, because rw_timeout = 3 s). Then cancel scope.
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(timeoutMs) { scope.coroutineContext[Job]?.join() }
        }
        scope.cancel()
        println("[ScreenReceiver] stop() complete")
    }

    // ── Decode loop ───────────────────────────────────────────────────────────

    private suspend fun decodeLoop() = withContext(Dispatchers.IO) {
        println("[ScreenReceiver] Opening grabber on udp://0.0.0.0:$port ...")

        // grabber.start() MUST be called before the try-finally so that
        // grabber.stop() is only called when start() actually succeeded.
        val started: Boolean = try {
            grabber.start()
            println("[ScreenReceiver] grabber.start() OK — format=${grabber.format}")
            true
        } catch (e: CancellationException) {
            println("[ScreenReceiver] cancelled during grabber.start()")
            throw e   // propagate — coroutine machinery needs this
        } catch (e: FrameGrabber.Exception) {
            println("[ScreenReceiver] grabber.start() FAILED (FrameGrabber.Exception): ${e.message}")
            false
        } catch (e: Exception) {
            println("[ScreenReceiver] grabber.start() FAILED (${e::class.simpleName}): ${e.message}")
            false
        }

        if (!started) return@withContext

        var frameCount  = 0
        var totalFrames = 0
        var fpsWindowMs = System.currentTimeMillis()
        println("[ScreenReceiver] decode loop running...")

        try {
            while (isRunning) {
                val javacvFrame: org.bytedeco.javacv.Frame?
                try {
                    javacvFrame = grabber.grabImage()
                } catch (e: CancellationException) {
                    // Coroutine was cancelled — exit cleanly
                    println("[ScreenReceiver] CancellationException inside grabImage(), exiting loop")
                    break
                } catch (e: FrameGrabber.Exception) {
                    // Timeout or network error — could be transient
                    if (!isRunning) break
                    println("[ScreenReceiver] grabImage FrameGrabber.Exception: ${e.message}")
                    delay(200)
                    continue
                } catch (e: Exception) {
                    if (!isRunning) break
                    println("[ScreenReceiver] grabImage exception (${e::class.simpleName}): ${e.message}")
                    delay(100)
                    continue
                }

                if (javacvFrame == null) {
                    // null = stream ended or timeout fired — don't loop forever
                    println("[ScreenReceiver] grabImage() returned null — stream ended or timed out")
                    break
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
            }
        } finally {
            // Always release native resources — prevents JVM crash on scope cancel
            println("[ScreenReceiver] finally: releasing grabber (totalFrames=$totalFrames)")
            runCatching { grabber.stop()    }.onFailure { println("[ScreenReceiver] grabber.stop() error: ${it.message}") }
            runCatching { grabber.release() }.onFailure { println("[ScreenReceiver] grabber.release() error: ${it.message}") }
            _frame.value = null
            println("[ScreenReceiver] decodeLoop ended — totalFrames=$totalFrames")
        }
    }
}
