package com.furcord.screenshare

import com.furcord.voice.VoiceEngine
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Yayın sesi (bilgisayarın sesi). Yayıncı: WASAPI loopback → Opus (stereo, 48 kHz) → yalnızca izleyenlere UDP.
 * İzleyici: Opus çöz → ayrı bir hoparlör hattında çal; seviye [StreamAudioPrefs.volume] ile ayarlanır.
 * Sesli sohbetten (mikrofon/konuşmalar) bağımsızdır.
 */
internal object StreamAudio {
    private const val RATE = 48_000
    private const val FRAME = 960            // 20 ms
    private const val BITRATE = 96_000

    // ── Yayıncı ───────────────────────────────────────────────────────────────
    private var loopback: WasapiLoopback? = null
    private var encoder: OpusEncoder? = null
    private var seq = 0

    /**
     * Discord gibi: tüm ekran paylaşılırken Furcord HARİÇ sistem sesi (sesli sohbet karışmaz),
     * pencere paylaşılırken yalnızca o pencerenin uygulamasının sesi. İşlem bazlı yakalama
     * desteklenmiyorsa (eski Windows) ekran paylaşımında tüm sistem sesine düşer; pencerede ses verilmez.
     */
    fun startCapture(source: ShareSource?, activeViewers: () -> Set<Int>) {
        stopCapture()
        val enc = runCatching {
            OpusEncoder(RATE, 2, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
                setBitrate(BITRATE); setUseVBR(true); setComplexity(5)
            }
        }.getOrNull() ?: return
        encoder = enc
        val out = ByteArray(4000)
        val sink: (ShortArray) -> Unit = { pcm ->
            val targets = activeViewers()
            if (targets.isNotEmpty()) {                          // izleyen yoksa kodlama/gönderme yok
                runCatching {
                    val n = enc.encode(pcm, 0, FRAME, out, 0, out.size)
                    if (n > 0) VoiceEngine.sendStreamAudio(out, n, ++seq, targets)
                }
            }
        }
        val isWindow = source != null && !source.isScreen
        val mode: WasapiLoopback.Mode = if (isWindow) {
            val pid = windowPid(source!!.id)
            if (pid != 0) WasapiLoopback.Mode.Include(pid) else return
        } else {
            WasapiLoopback.Mode.Exclude(ProcessHandle.current().pid().toInt())
        }
        val lb = WasapiLoopback(mode, sink).also { it.start() }
        loopback = lb
        // İşlem bazlı yakalama başlatılamadıysa (eski Windows): ekran paylaşımında tüm sese düş
        if (!isWindow) Thread({
            Thread.sleep(1_500)
            if (loopback === lb && lb.failed) {
                lb.stop()
                loopback = WasapiLoopback(WasapiLoopback.Mode.Device, sink).also { it.start() }
            }
        }, "furcord-loopback-fallback").apply { isDaemon = true; start() }
    }

    private fun windowPid(hwnd: Long): Int = runCatching {
        val pid = com.sun.jna.ptr.IntByReference()
        com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(
            com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(hwnd)), pid)
        pid.value
    }.getOrDefault(0)

    fun stopCapture() {
        loopback?.stop(); loopback = null
        encoder = null
    }

    // ── İzleyici ──────────────────────────────────────────────────────────────
    private val queue = ArrayBlockingQueue<ShortArray>(40)
    @Volatile private var playing = false
    private var playThread: Thread? = null
    private var decoder: OpusDecoder? = null
    private var lastSeq = Int.MIN_VALUE

    fun startPlayback() {
        if (playing) return
        playing = true
        queue.clear(); lastSeq = Int.MIN_VALUE
        decoder = runCatching { OpusDecoder(RATE, 2) }.getOrNull()
        VoiceEngine.onStreamAudio = ::onPacket
        playThread = Thread({ runCatching { playLoop() }; playing = false }, "furcord-stream-audio").apply { isDaemon = true; start() }
    }

    fun stopPlayback() {
        playing = false
        VoiceEngine.onStreamAudio = null
        playThread?.let { if (it !== Thread.currentThread()) it.interrupt() }
        playThread = null
        queue.clear()
    }

    private fun onPacket(opus: ByteArray, seqNo: Int, senderHash: Int) {
        if (!playing) return
        // Yalnızca izlediğimiz yayıncıdan gelen sesi çal
        val b = ScreenEngine.broadcastingUidHash.value
        if (b == 0 || senderHash != b) return
        val dec = decoder ?: return
        if (lastSeq != Int.MIN_VALUE && seqNo - lastSeq <= 0 && seqNo - lastSeq > -200) return   // eski/yinelenen
        lastSeq = seqNo
        val pcm = ShortArray(FRAME * 2)
        val n = runCatching { dec.decode(opus, 0, opus.size, pcm, 0, FRAME, false) }.getOrDefault(0)
        if (n <= 0) return
        while (!queue.offer(pcm)) queue.poll()          // dolduysa en eskiyi at (gecikme birikmesin)
    }

    private fun playLoop() {
        val fmt = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        val line: SourceDataLine = AudioSystem.getSourceDataLine(fmt)
        line.open(fmt, FRAME * 4 * 10)                     // ~200 ms tampon
        line.start()
        val bytes = ByteArray(FRAME * 4)
        try {
            // Önce birkaç kare biriksin (kesilmeyi azaltır)
            while (playing && queue.size < 3) Thread.sleep(10)
            while (playing) {
                val pcm = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (queue.size > 12) { while (queue.size > 4) queue.poll() }   // gecikme sınırı (~80 ms'ye indir)
                val vol = StreamAudioPrefs.volume.value
                for (i in pcm.indices) {
                    val v = (pcm[i] * vol).toInt().coerceIn(-32768, 32767)
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = (v shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { line.stop(); line.flush(); line.close() }
        }
    }
}
