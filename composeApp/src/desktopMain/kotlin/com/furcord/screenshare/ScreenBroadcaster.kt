package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures the primary monitor, encodes to H264/MPEG-TS, and transmits to
 * each peer via FFmpeg''s native UDP output (`udp://ip:port?pkt_size=1316`).
 *
 * No manual chunking or DatagramSocket is used — FFmpeg handles all network I/O.
 *
 * Peers are dynamically managed: every [PEER_SYNC_FRAMES] frames the broadcaster
 * reconciles the live peer list, starting recorders for new peers and stopping
 * recorders for peers that left.
 */
class ScreenBroadcaster(
    /** Returns the current list of peers to send to. Polled periodically. */
    private val getPeers: () -> List<InetSocketAddress>,
) {
    companion object {
        const val WIDTH   = 1280
        const val HEIGHT  = 720
        const val FPS     = 30.0
        const val BITRATE = 2_000_000

        /** Sync the peer/recorder map every N frames (~1 s at 30 fps). */
        private const val PEER_SYNC_FRAMES = 30
    }

    // ── Desktop screen grabber ────────────────────────────────────────────────

    private val grabber: FFmpegFrameGrabber

    // ── Per-peer UDP recorders ────────────────────────────────────────────────

    /** peer address → active FFmpegFrameRecorder */
    private val recorderMap = ConcurrentHashMap<InetSocketAddress, FFmpegFrameRecorder>()

    // ── State ─────────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isRunning  = false; private set
    @Volatile var framesSent = 0L;    private set

    /** Local preview — broadcaster can monitor their own stream. */
    private val _localFrame = MutableStateFlow<ImageBitmap?>(null)
    val localFrame: StateFlow<ImageBitmap?> = _localFrame.asStateFlow()
    private val localConverter = Java2DFrameConverter()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val osName = System.getProperty("os.name").lowercase()
        val (fmt, input) = when {
            "win" in osName -> "gdigrab"      to "desktop"
            "mac" in osName -> "avfoundation" to "1:none"
            else            -> "x11grab"      to ":0.0+0,0"
        }
        grabber = FFmpegFrameGrabber(input).apply {
            format      = fmt
            imageWidth  = WIDTH
            imageHeight = HEIGHT
            frameRate   = FPS
            setOption("framerate", "30")
            if ("win" in osName) {
                setOption("video_size", "${WIDTH}x${HEIGHT}")
                setOption("offset_x",   "0")
                setOption("offset_y",   "0")
                setOption("draw_mouse", "1")
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        println("[ScreenBroadcaster] start() — launching grabber + capture loop")
        try {
            grabber.start()
            println("[ScreenBroadcaster] grabber started (${grabber.format})")
        } catch (e: Exception) {
            println("[ScreenBroadcaster] grabber.start() FAILED: ${e.message}")
            return
        }
        isRunning = true
        scope.launch { captureLoop() }
    }

    fun stop() {
        println("[ScreenBroadcaster] stop() — framesSent=$framesSent")
        isRunning = false
        scope.cancel()
        stopAllRecorders()
        runCatching { grabber.stop(); grabber.release() }
    }

    // ── Capture loop ──────────────────────────────────────────────────────────

    private suspend fun captureLoop() = withContext(Dispatchers.IO) {
        var frameN = 0
        while (isRunning) {
            // Sync recorders with current peer list every second
            if (frameN % PEER_SYNC_FRAMES == 0) {
                syncRecorders(getPeers())
            }
            frameN++

            try {
                val frame = grabber.grabImage() ?: continue

                // Local preview (self-view)
                runCatching {
                    localConverter.getBufferedImage(frame)?.toComposeImageBitmap()
                        ?.let { _localFrame.value = it }
                }

                // Encode + send to all active peers
                val snapshot = recorderMap.values.toList()
                for (rec in snapshot) {
                    runCatching { rec.record(frame) }
                }
                if (snapshot.isNotEmpty()) framesSent++

            } catch (e: Exception) {
                if (isRunning) {
                    println("[ScreenBroadcaster] capture error: ${e::class.simpleName}: ${e.message}")
                    delay(16L)
                }
            }
        }
        println("[ScreenBroadcaster] captureLoop ended")
    }

    // ── Recorder management ───────────────────────────────────────────────────

    /** Starts a recorder targeting `udp://ip:port?pkt_size=1316`. */
    private fun makeRecorder(addr: InetSocketAddress): FFmpegFrameRecorder? {
        val url = "udp://${addr.hostString}:${addr.port}?pkt_size=1316"
        return runCatching {
            FFmpegFrameRecorder(url, WIDTH, HEIGHT).apply {
                videoCodec   = avcodec.AV_CODEC_ID_H264
                format       = "mpegts"
                frameRate    = FPS
                videoBitrate = BITRATE
                gopSize      = 30
                setVideoOption("preset", "ultrafast")
                setVideoOption("tune",   "zerolatency")
                setVideoOption("crf",    "30")
                setOption("fflags",   "flush_packets")
                setOption("muxdelay", "0")
            }.also {
                it.start()
                println("[ScreenBroadcaster] recorder started -> $url")
            }
        }.onFailure { e ->
            println("[ScreenBroadcaster] recorder.start() FAILED for $url: ${e.message}")
        }.getOrNull()
    }

    /** Adds recorders for new peers, removes recorders for departed peers. */
    private fun syncRecorders(currentPeers: List<InetSocketAddress>) {
        val currentSet = currentPeers.toSet()

        // Remove departed peers
        val toRemove = recorderMap.keys.filter { it !in currentSet }
        for (addr in toRemove) {
            recorderMap.remove(addr)?.let { rec ->
                runCatching { rec.stop(); rec.release() }
                println("[ScreenBroadcaster] recorder stopped for $addr")
            }
        }

        // Add new peers
        for (addr in currentSet) {
            if (!recorderMap.containsKey(addr)) {
                makeRecorder(addr)?.let { recorderMap[addr] = it }
            }
        }

        if (recorderMap.isNotEmpty() || toRemove.isNotEmpty()) {
            println("[ScreenBroadcaster] syncRecorders: active=${recorderMap.size} peers=${currentSet.size}")
        }
    }

    private fun stopAllRecorders() {
        for ((addr, rec) in recorderMap) {
            runCatching { rec.stop(); rec.release() }
            println("[ScreenBroadcaster] recorder released for $addr")
        }
        recorderMap.clear()
    }
}
