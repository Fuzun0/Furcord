package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives UDP packets from a [ScreenBroadcaster], reassembles the chunked
 * segments, and decodes the H264/mpegts stream into Compose [ImageBitmap]s
 * exposed via a [StateFlow].
 *
 * ── Threading model ───────────────────────────────────────────────────────────
 *  • receiveLoop  — one IO coroutine: reads UDP, validates headers, reassembles
 *  • decodeLoop   — one IO coroutine: feeds StreamBuffer → FFmpeg → ImageBitmap
 *
 * ── Packet loss handling ──────────────────────────────────────────────────────
 * Incomplete segments (missing chunks) are discarded after [STALE_WINDOW] newer
 * segments arrive. Because H264 with GOP=30 sends a keyframe every second, the
 * decoder recovers automatically on the next keyframe.
 */
class ScreenReceiver(
    /** DatagramSocket already bound to the screen-share port. */
    private val socket: DatagramSocket,
) {
    companion object {
        private const val HEADER_BYTES = ScreenBroadcaster.HEADER_BYTES
        private const val MAX_PKT_SIZE = ScreenBroadcaster.CHUNK_SIZE + HEADER_BYTES + 32

        /** Segments more than this many behind the latest are discarded as stale. */
        private const val STALE_WINDOW = 120

        /** Minimum bytes in StreamBuffer before starting the FFmpeg decoder probe. */
        private const val PROBE_BYTES = 16_384 // 16 KB — covers PAT+PMT + first keyframe
    }

    // ── Chunk reassembly ─────────────────────────────────────────────────────

    /** segId → sparse array of chunk payloads (null = not yet received). */
    private val chunkStore = ConcurrentHashMap<Int, Array<ByteArray?>>()
    private val chunkCount = ConcurrentHashMap<Int, AtomicInteger>()
    private val chunkTotal = ConcurrentHashMap<Int, Int>()
    private val latestSeg  = AtomicInteger(-1)

    // ── H264/mpegts streaming decoder ────────────────────────────────────────

    private val streamBuf = StreamBuffer()

    /**
     * FFmpegFrameGrabber reading from a live [StreamBuffer].
     * Key options to minimise startup latency:
     *   fflags=nobuffer      — do not buffer input
     *   fflags=discardcorrupt — skip damaged TS packets (from packet loss)
     *   flags=low_delay      — prefer low latency over accuracy
     *   analyzeduration      — how long FFmpeg probes before declaring streams found
     *   probesize            — max bytes to read during format detection
     */
    private val decoder = FFmpegFrameGrabber(streamBuf as java.io.InputStream).apply {
        format = "mpegts"
        setOption("fflags",          "nobuffer+discardcorrupt")
        setOption("flags",           "low_delay")
        setOption("analyzeduration", "500000") // 500 ms
        setOption("probesize",       "65536")  // 64 KB
    }

    private val converter = Java2DFrameConverter()

    // ── Compose state ────────────────────────────────────────────────────────

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    /** Collect this in a Composable via [collectAsState] to display the stream. */
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Frames decoded per second — updated every second for diagnostic display. */
    private val _fps = MutableStateFlow(0f)
    val decodedFps: StateFlow<Float> = _fps.asStateFlow()

    @Volatile var isRunning = false; private set
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        isRunning = true
        scope.launch { receiveLoop() }
        scope.launch { decodeLoop()  }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        runCatching { decoder.stop(); decoder.release() }
        runCatching { streamBuf.close() }
    }

    // ── Receive & reassemble ─────────────────────────────────────────────────

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(MAX_PKT_SIZE)
        while (isRunning) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                socket.receive(dp)
                if (dp.length < HEADER_BYTES) continue

                val bb = ByteBuffer.wrap(buf, 0, dp.length)

                val segId       = bb.int
                val totalChunks = bb.short.toInt() and 0xFFFF
                val chunkIndex  = bb.short.toInt() and 0xFFFF
                bb.short // skip flags

                // Sanity bounds
                if (totalChunks <= 0 || totalChunks > 5_000) continue
                if (chunkIndex < 0 || chunkIndex >= totalChunks) continue

                // Discard stale segments
                val latest = latestSeg.get()
                if (latest >= 0 && latest - segId > STALE_WINDOW) continue
                if (segId > latest) latestSeg.set(segId)

                // Store chunk
                val payload = ByteArray(dp.length - HEADER_BYTES)
                bb.get(payload)

                val arr = chunkStore.getOrPut(segId) { arrayOfNulls(totalChunks) }
                arr[chunkIndex] = payload
                chunkTotal[segId] = totalChunks

                val received = chunkCount
                    .getOrPut(segId) { AtomicInteger(0) }
                    .incrementAndGet()

                if (received == totalChunks) {
                    reassembleAndFeed(segId, arr)
                }

                // Prune segments that will never complete
                val cur = latestSeg.get()
                chunkStore.keys
                    .filter { cur - it > STALE_WINDOW }
                    .forEach { k ->
                        chunkStore.remove(k)
                        chunkCount.remove(k)
                        chunkTotal.remove(k)
                    }

            } catch (_: java.net.SocketException) {
                break // socket closed by stop()
            } catch (e: Exception) {
                if (isRunning) delay(1)
            }
        }
    }

    private fun reassembleAndFeed(segId: Int, arr: Array<ByteArray?>) {
        chunkStore.remove(segId)
        chunkCount.remove(segId)
        chunkTotal.remove(segId)

        // Calculate total size and copy chunks in order
        val totalBytes = arr.sumOf { it?.size ?: 0 }
        if (totalBytes == 0) return

        val assembled = ByteArray(totalBytes)
        var pos = 0
        for (chunk in arr) {
            if (chunk != null) {
                chunk.copyInto(assembled, pos)
                pos += chunk.size
            }
        }

        streamBuf.offer(assembled)
    }

    // ── Decode & emit ─────────────────────────────────────────────────────────

    private suspend fun decodeLoop() = withContext(Dispatchers.IO) {
        // Wait for enough mpegts data so FFmpeg's format probe succeeds.
        // The first data in the stream contains PAT + PMT + first keyframe.
        while (isRunning && streamBuf.bufferedBytes() < PROBE_BYTES) {
            delay(50)
        }
        if (!isRunning) return@withContext

        try {
            decoder.start()
        } catch (e: Exception) {
            // Format detection failed — usually means no data arrived yet.
            // The caller can call start() again after checking isRunning.
            return@withContext
        }

        // FPS counter
        var frameCount  = 0
        var fpsWindowMs = System.currentTimeMillis()

        while (isRunning) {
            try {
                val javacvFrame  = decoder.grabImage() ?: continue
                val bufferedImage = converter.getBufferedImage(javacvFrame) ?: continue

                // Convert java.awt.image.BufferedImage → Compose ImageBitmap
                _frame.value = bufferedImage.toComposeImageBitmap()

                // Update FPS counter every second
                frameCount++
                val now = System.currentTimeMillis()
                if (now - fpsWindowMs >= 1_000L) {
                    _fps.value = frameCount * 1_000f / (now - fpsWindowMs)
                    frameCount  = 0
                    fpsWindowMs = now
                }
            } catch (_: Exception) {
                delay(5)
            }
        }
    }
}
