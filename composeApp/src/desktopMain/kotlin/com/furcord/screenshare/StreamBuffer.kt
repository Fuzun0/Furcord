package com.furcord.screenshare

import java.io.InputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Thread-safe, non-blocking-offer, blocking-read InputStream.
 *
 * The network receiver thread calls [offer] to push reassembled mpegts segments.
 * The decoder thread calls [read] (via FFmpegFrameGrabber), which blocks until
 * data is available — exactly the semantics FFmpeg's AVIO layer expects.
 *
 * When the deque reaches [capacity], the oldest chunk is silently dropped so the
 * decoder always works with the freshest data (live-stream semantics).
 */
class StreamBuffer(private val capacity: Int = 512) : InputStream() {

    private val deque  = LinkedBlockingDeque<ByteArray>(capacity)

    // Only ever touched by the single decoder thread that calls read()
    private var cur: ByteArray? = null
    private var pos  = 0

    @Volatile private var closed = false

    // ── Producer side (network / receiver coroutine) ──────────────────────────

    fun offer(data: ByteArray) {
        if (closed || data.isEmpty()) return
        // Drop the oldest segment if we're full — prefer freshness over completeness
        if (!deque.offerLast(data.copyOf())) {
            deque.pollFirst()
            deque.offerLast(data.copyOf())
        }
    }

    fun bufferedBytes(): Int =
        (cur?.size?.minus(pos) ?: 0) + deque.sumOf { it.size }

    // ── InputStream API (decoder thread) ─────────────────────────────────────

    override fun available(): Int = bufferedBytes()

    override fun read(): Int {
        val tmp = ByteArray(1)
        return if (read(tmp, 0, 1) == 1) tmp[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1

        while (true) {
            val c = cur
            if (c != null && pos < c.size) {
                val avail  = c.size - pos
                val toRead = minOf(len, avail)
                c.copyInto(b, off, pos, pos + toRead)
                pos += toRead
                if (pos >= c.size) { cur = null; pos = 0 }
                return toRead
            }

            // Block up to 100 ms waiting for the next chunk
            cur = deque.pollFirst(100, TimeUnit.MILLISECONDS)
                ?: if (closed) return -1 else continue
            pos = 0
        }
    }

    override fun close() {
        closed = true
    }
}
