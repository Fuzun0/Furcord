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
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures the primary monitor, encodes to H264 inside an MPEG-TS container,
 * and streams the compressed data over UDP using a simple chunked packet protocol.
 *
 * ── Packet layout (HEADER_BYTES = 10 bytes) ───────────────────────────────────
 *  Offset  Size  Field
 *  0       4B    segId       – monotonically increasing segment counter
 *  4       2B    totalChunks – how many chunks this segment was split into
 *  6       2B    chunkIndex  – 0-based index of this chunk
 *  8       2B    flags       – reserved (always 0x0000)
 *  10      ≤1400B payload    – raw mpegts bytes for this chunk
 *
 * ── Why MPEG-TS? ──────────────────────────────────────────────────────────────
 * MPEG-TS is a streaming container designed for packet-switched networks.
 * Unlike MP4/MKV, it does not require seeking during write, making it
 * compatible with an OutputStream and suitable for live transmission.
 *
 * ── Cross-platform capture backend ───────────────────────────────────────────
 *  Windows  → gdigrab   ("desktop")
 *  macOS    → avfoundation ("1:none")
 *  Linux    → x11grab   (":0.0+0,0")
 */
class ScreenBroadcaster(
    /** DatagramSocket already bound (shared with VoiceEngine on a separate port). */
    private val socket: DatagramSocket,
    /** Returns the current list of peers to send to. Called per-segment. */
    private val peers: () -> List<InetSocketAddress>,
) {
    companion object {
        /** Max payload bytes per UDP packet — safely under 1500-byte Ethernet MTU. */
        const val CHUNK_SIZE   = 1400
        const val HEADER_BYTES = 10

        const val WIDTH   = 1280
        const val HEIGHT  = 720
        const val FPS     = 30.0
        /** 2 Mbps is sufficient for 720p H264 ultrafast. */
        const val BITRATE = 2_000_000
    }

    // ── FFmpeg objects ────────────────────────────────────────────────────────

    private val grabber: FFmpegFrameGrabber
    /** Per-frame ring buffer — recorder writes here, we drain it after each record(). */
    private val baos     = ByteArrayOutputStream(256 * 1024)
    private val recorder: FFmpegFrameRecorder

    // ── State ─────────────────────────────────────────────────────────────────

    private val segId   = AtomicInteger(0)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isRunning  = false; private set
    @Volatile var bytesSent  = 0L;    private set // diagnostic counter
    /** Local preview — the broadcaster can see their own stream quality. */
    private val _localFrame = MutableStateFlow<ImageBitmap?>(null)
    val localFrame: StateFlow<ImageBitmap?> = _localFrame.asStateFlow()
    private val localConverter = Java2DFrameConverter()
    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        val osName = System.getProperty("os.name").lowercase()
        val (fmt, input) = when {
            "win"  in osName -> "gdigrab"      to "desktop"
            "mac"  in osName -> "avfoundation" to "1:none"
            else             -> "x11grab"      to ":0.0+0,0"
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

        recorder = FFmpegFrameRecorder(baos as java.io.OutputStream, WIDTH, HEIGHT).apply {
            videoCodec   = avcodec.AV_CODEC_ID_H264
            format       = "mpegts"
            frameRate    = FPS
            videoBitrate = BITRATE
            // One keyframe per second so receivers can join mid-stream
            gopSize      = FPS.toInt()
            // H264 options — minimise encoder latency
            setVideoOption("tune",   "zerolatency")
            setVideoOption("preset", "ultrafast")
            setVideoOption("crf",    "30")          // quality vs size trade-off
            // MPEG-TS muxer options — flush every packet immediately
            setOption("fflags",   "flush_packets")
            setOption("muxdelay", "0")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens the screen grabber and H264 recorder, then begins the capture loop.
     * Runs non-blocking — returns immediately after launching the coroutine.
     */
    fun start() {
        grabber.start()
        recorder.start()
        isRunning = true

        scope.launch {
            while (isRunning) {
                try {
                    // 1. Grab the next video frame from the screen
                    val frame = grabber.grabImage() ?: continue

                    // 1b. Publish a local preview frame (self-view)
                    runCatching {
                        localConverter.getBufferedImage(frame)?.toComposeImageBitmap()
                            ?.let { _localFrame.value = it }
                    }

                    // 2. Encode to H264/mpegts — writes TS packets into baos
                    recorder.record(frame)

                    // 3. Drain baos atomically — everything written since last drain
                    val data = baos.toByteArray()
                    baos.reset()

                    // 4. Packetise and send over UDP
                    if (data.isNotEmpty()) {
                        sendChunked(segId.incrementAndGet(), data)
                        bytesSent += data.size
                    }
                } catch (e: Exception) {
                    if (isRunning) delay(16L) // ~1 frame period before retry
                }
            }
        }
    }

    /** Stops capture, releases FFmpeg resources, and cancels the coroutine scope. */
    fun stop() {
        isRunning = false
        scope.cancel()
        runCatching { recorder.stop(); recorder.release() }
        runCatching { grabber.stop();  grabber.release() }
    }

    // ── UDP chunking ──────────────────────────────────────────────────────────

    private fun sendChunked(id: Int, data: ByteArray) {
        val total   = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        val targets = peers()
        if (targets.isEmpty()) return

        for (i in 0 until total) {
            val start   = i * CHUNK_SIZE
            val end     = minOf(start + CHUNK_SIZE, data.size)
            val payload = data.sliceArray(start until end)

            // Build packet: header + payload
            val pkt = ByteBuffer.allocate(HEADER_BYTES + payload.size).apply {
                putInt(id)               // 4B segId
                putShort(total.toShort())  // 2B totalChunks
                putShort(i.toShort())      // 2B chunkIndex
                putShort(0.toShort())      // 2B flags (reserved)
                put(payload)
            }.array()

            for (peer in targets) {
                runCatching {
                    socket.send(DatagramPacket(pkt, pkt.size, peer.address, peer.port))
                }
            }
        }
    }
}
