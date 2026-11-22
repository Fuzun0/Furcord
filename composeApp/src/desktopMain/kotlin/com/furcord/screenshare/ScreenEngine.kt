package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.furcord.voice.VoiceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.concurrent.withLock

/**
 * Ekran / pencere paylaşımı motoru — VoiceEngine'in UDP soketi üzerinde çalışır.
 *
 *  • Yakalama: libwebrtc DesktopCapturer (monitör veya tek pencere). Başarısız olursa Robot ile ana ekran.
 *  • Kodlama : JPEG. Kalite, seçilen profilin bit hızı bütçesine göre kare kare ayarlanır (hız kontrolü).
 *  • Gönderim: yalnızca izlemek isteyen kişilere (izleyici "watch" isteği yollar); diğerlerine sadece duyuru.
 *
 * Ekran paketi başlığı (12 byte):
 *   [0-3]  magic    = 0x53435200 ("SCR\0")
 *   [4-7]  frameSeq (Int)
 *   [8]    fragIdx  (Byte, 0-based)
 *   [9]    totalFrags (Byte)   — 0 ise "duyuru" paketidir (veri yok)
 *   [10-11] reserved
 */
object ScreenEngine {

    const val MAGIC = 0x53435200.toInt()
    private const val HEADER_SIZE        = 12
    private const val MAX_PENDING_FRAMES = 16
    private const val VIEWER_TTL_MS      = 6_000L
    private const val MAX_FRAME_BYTES    = 300_000   // 255 parça × 1400 B'ın altında

    // ── Herkese açık durum ────────────────────────────────────────────────────

    private val _quality = MutableStateFlow(StreamQuality.MEDIUM)
    val quality: StateFlow<StreamQuality> = _quality
    fun setQuality(q: StreamQuality) { _quality.value = q }   // yayın sırasında da etkili

    private val _sourceTitle = MutableStateFlow("")
    val sourceTitle: StateFlow<String> = _sourceTitle

    private val _localFrame = MutableStateFlow<ImageBitmap?>(null)
    val localFrame: StateFlow<ImageBitmap?> = _localFrame

    private val _receiverFrame = MutableStateFlow<ImageBitmap?>(null)
    val receiverFrame: StateFlow<ImageBitmap?> = _receiverFrame

    private val _broadcastingUidHash = MutableStateFlow(0)
    val broadcastingUidHash: StateFlow<Int> = _broadcastingUidHash

    @Volatile var isActive    = false; private set
    @Volatile var isReceiving = false; private set

    // ── Dahili ───────────────────────────────────────────────────────────────

    private val seqCounter = AtomicInteger(0)
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureThread: Thread? = null
    private var watchJob: Job? = null
    @Volatile private var lastFrameMs = 0L

    /** İzleyici uidHash → son "watch" isteği zamanı */
    private val viewers = ConcurrentHashMap<Int, Long>()

    @Suppress("DEPRECATION")
    private val decodeChannel = Channel<ByteArray>(Channel.CONFLATED)

    private val fragments   = ConcurrentHashMap<Int, Array<ByteArray?>>()
    private val pendingSeqs = ArrayDeque<Int>()
    @Volatile private var lastCompletedSeq = -1

    private val sourceLock = ReentrantLock()   // listSources / thumbnail aynı anda çalışmasın

    init {
        scope.launch {
            for (jpeg in decodeChannel) {
                runCatching { ImageIO.read(ByteArrayInputStream(jpeg))?.toComposeImageBitmap() }
                    .getOrNull()?.let { _receiverFrame.value = it }
            }
        }
        // Yayın zaman aşımı: 5 sn paket/duyuru gelmezse yayın bitti say
        scope.launch {
            while (true) {
                delay(1_000)
                val last = lastFrameMs
                if (last > 0 && System.currentTimeMillis() - last > 5_000) {
                    _broadcastingUidHash.value = 0
                    lastFrameMs = 0
                }
            }
        }
    }

    // ── Kaynak listesi / önizleme ────────────────────────────────────────────

    /** Monitörler + açık pencereler. Bloklar — arka plan thread'inden çağır. */
    fun listSources(): List<ShareSource> = sourceLock.withLock {
        val screens = runCatching { WinCapture.listScreens() }.getOrDefault(emptyList())
        val windows = runCatching { WinCapture.listWindows() }.getOrDefault(emptyList())
            .filter { it.title != "Furcord — Ekran Önizleme" }
        screens.ifEmpty { listOf(ShareSource(0, "Ana ekran", isScreen = true)) } + windows
    }

    /** Kaynaktan tek kare alıp küçük önizleme üretir. */
    fun thumbnail(source: ShareSource): ImageBitmap? = sourceLock.withLock {
        runCatching {
            val grabber = WinCapture.Grabber.open(source) ?: return@runCatching null
            try {
                grabber.grab(320, 180)?.toComposeImageBitmap()
            } finally { grabber.close() }
        }.getOrNull()
    }

    // ── Yayıncı API ───────────────────────────────────────────────────────────

    /** [source] null ise ana ekran. Zaten aktifse tekrar çağrılması zararsızdır. */
    fun start(source: ShareSource?, q: StreamQuality) {
        if (isActive) return
        _quality.value = q
        _sourceTitle.value = source?.title ?: "Ana ekran"
        isActive = true
        viewers.clear()
        VoiceEngine.onScreenFrame = ::onPacketReceived
        VoiceEngine.onWatchRequest = { hash -> viewers[hash] = System.currentTimeMillis() }
        captureThread = Thread({ runCapture(source) }, "furcord-screen-capture").apply {
            isDaemon = true; start()
        }
    }

    /** Yayını durdurur ve kaynakları serbest bırakır. */
    fun stop() {
        if (!isActive && captureThread == null) return
        isActive = false
        val t = captureThread; captureThread = null
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(2_000) }
        _localFrame.value = null
        _sourceTitle.value = ""
        viewers.clear()
        VoiceEngine.onWatchRequest = null
        if (!isReceiving) VoiceEngine.onScreenFrame = null
    }

    // ── Yakalama döngüsü (özel thread) ───────────────────────────────────────

    /** Yakalama thread'inden kodlayıcı thread'ine devredilen kare. */
    private class EncodeJob(val img: BufferedImage, val q: StreamQuality, val targets: Set<Int>)

    private fun runCapture(source: ShareSource?) {
        val grabber: WinCapture.Grabber? = WinCapture.Grabber.open(source)
        val robot: Robot? = if (grabber == null) runCatching { Robot() }.getOrNull() else null
        if (grabber == null && robot == null) { isActive = false; return }

        // Yakalama ve JPEG kodlama ayrı thread'lerde → ikisi üst üste biner (hız ~2×).
        val pool = java.util.concurrent.ArrayBlockingQueue<BufferedImage>(2)        // yeniden kullanılan kare tamponları
        val jobs = java.util.concurrent.ArrayBlockingQueue<EncodeJob>(1)            // en yeni kare kazanır
        val encoder = Thread({ runEncoder(jobs, pool) }, "furcord-screen-encode").apply { isDaemon = true; start() }
        repeat(2) { pool.offer(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)) }

        var frameNo = 0
        var lastAnnounce = 0L
        var failures = 0

        try {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                val q = _quality.value

                val img: BufferedImage? = try {
                    if (grabber != null) grabber.grab(q.maxWidth, q.maxHeight)
                    else robotGrab(robot!!, q.maxWidth, q.maxHeight)
                } catch (_: Exception) { null }

                if (img == null) {
                    // Pencere kapandı / küçültüldü / yakalanamıyor → yaklaşık 3 sn sonra yayını bitir
                    if (++failures > q.fps * 3) { isActive = false; break }
                } else {
                    failures = 0
                    frameNo++

                    // Yerel önizleme ~10 fps
                    if (frameNo % maxOf(1, q.fps / 10) == 0) {
                        runCatching { _localFrame.value = img.toComposeImageBitmap() }
                    }

                    val now = System.currentTimeMillis()
                    // Duyuru: izlemeyenler de "yayın var" görsün
                    if (now - lastAnnounce >= 1_000) {
                        VoiceEngine.sendScreenAnnounce(seqCounter.get())
                        lastAnnounce = now
                    }

                    // Sadece izleyen varsa kodlayıcıya ver
                    val targets = viewers.filterValues { now - it < VIEWER_TTL_MS }.keys
                    if (targets.isNotEmpty()) {
                        val buf = pool.poll()          // kodlayıcı meşgulse boş: bu kareyi atla
                        if (buf != null) {
                            val copy = if (buf.width == img.width && buf.height == img.height) buf
                                       else BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
                            val src = (img.raster.dataBuffer as DataBufferInt).data
                            val dst = (copy.raster.dataBuffer as DataBufferInt).data
                            System.arraycopy(src, 0, dst, 0, minOf(src.size, dst.size))
                            jobs.poll()?.let { pool.offer(it.img) }   // eski kareyi at, tamponu geri ver
                            jobs.offer(EncodeJob(copy, q, targets))
                        }
                    }
                }

                val wait = 1000L / q.fps - (System.currentTimeMillis() - t0)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
        } finally {
            encoder.interrupt()
            runCatching { grabber?.close() }
            isActive = false
            _localFrame.value = null
        }
    }

    /** JPEG kodlama + gönderme döngüsü. Hız kontrolü: kare boyutu bit hızı bütçesine göre kalite ayarlar. */
    private fun runEncoder(
        jobs: java.util.concurrent.ArrayBlockingQueue<EncodeJob>,
        pool: java.util.concurrent.ArrayBlockingQueue<BufferedImage>,
    ) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        var jpegQ = 0.6f
        try {
            while (isActive) {
                val job = jobs.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                try {
                    val q = job.q
                    val budget = (q.kbps * 1000L / 8 / q.fps).toInt().coerceAtMost(MAX_FRAME_BYTES)
                    val jpeg = encodeJpeg(writer, job.img, jpegQ)
                    jpegQ = when {
                        jpeg.size > budget * 1.15f -> (jpegQ - 0.06f).coerceAtLeast(0.25f)
                        jpeg.size < budget * 0.6f  -> (jpegQ + 0.03f).coerceAtMost(0.9f)
                        else -> jpegQ
                    }
                    if (jpeg.size <= MAX_FRAME_BYTES) {
                        VoiceEngine.sendScreenPackets(jpeg, seqCounter.incrementAndGet(), job.targets)
                    }
                } catch (_: Exception) {
                } finally {
                    pool.offer(job.img)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { writer.dispose() }
        }
    }

    private fun encodeJpeg(writer: javax.imageio.ImageWriter, img: BufferedImage, quality: Float): ByteArray {
        val baos = ByteArrayOutputStream(64_000)
        val param = writer.defaultWriteParam.apply {
            compressionMode    = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        ImageIO.createImageOutputStream(baos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        return baos.toByteArray()
    }

    /** DesktopCapturer çalışmazsa kullanılan yedek: ana ekranı Robot ile yakala. */
    private fun robotGrab(robot: Robot, maxW: Int, maxH: Int): BufferedImage? {
        val size = Toolkit.getDefaultToolkit().screenSize
        val full = robot.createScreenCapture(Rectangle(size))
        val (w, h) = fit(full.width, full.height, maxW, maxH)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(full, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun fit(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (w <= maxW && h <= maxH) return (w and 1.inv()).coerceAtLeast(2) to (h and 1.inv()).coerceAtLeast(2)
        val r = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
        return ((w * r).toInt() and 1.inv()).coerceAtLeast(2) to ((h * r).toInt() and 1.inv()).coerceAtLeast(2)
    }


    // ── İzleyici API ─────────────────────────────────────────────────────────

    fun startReceiver() {
        if (isReceiving) return
        isReceiving = true
        VoiceEngine.onScreenFrame = ::onPacketReceived
        // Yayıncıya "izliyorum" isteğini 2 sn'de bir gönder (yayıncı sadece izleyenlere yollar)
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive && isReceiving) {
                val hash = _broadcastingUidHash.value
                if (hash != 0) VoiceEngine.sendWatchRequest(hash)
                delay(if (hash != 0) 2_000 else 500)
            }
        }
    }

    fun stopReceiver() {
        isReceiving = false
        watchJob?.cancel(); watchJob = null
        _receiverFrame.value = null
        // broadcastingUidHash bilerek temizlenmez: yayın bitince 5 sn zaman aşımı devreye girer.
    }

    /** Ses kanalından tamamen ayrılırken çağrılır — tüm alım durumunu sıfırlar. */
    fun stopReceiverFull() {
        isReceiving = false
        watchJob?.cancel(); watchJob = null
        _receiverFrame.value = null
        _broadcastingUidHash.value = 0
        lastFrameMs = 0
        fragments.clear(); synchronized(pendingSeqs) { pendingSeqs.clear() }
        lastCompletedSeq = -1
        if (!isActive) VoiceEngine.onScreenFrame = null
    }

    // ── Paket alma ───────────────────────────────────────────────────────────

    /** VoiceEngine ekran paketi tespit ettiğinde çağırır (başından sona, magic dahil). */
    fun onPacketReceived(raw: ByteArray, senderUidHash: Int = 0) {
        lastFrameMs = System.currentTimeMillis()
        if (senderUidHash != 0 && senderUidHash != _broadcastingUidHash.value) {
            // Yayıncı değişti: eski parçaları ve sıra bilgisini at
            fragments.clear(); synchronized(pendingSeqs) { pendingSeqs.clear() }
            lastCompletedSeq = -1
            _broadcastingUidHash.value = senderUidHash
        }
        if (!isReceiving) return
        if (raw.size < HEADER_SIZE) return

        val seq        = ByteBuffer.wrap(raw).getInt(4)
        val fragIdx    = raw[8].toInt() and 0xFF
        val totalFrags = raw[9].toInt() and 0xFF
        if (totalFrags == 0) return                       // duyuru paketi
        if (seq <= lastCompletedSeq) return               // eski/yinelenen kare
        if (fragIdx >= totalFrags) return

        val payload = raw.copyOfRange(HEADER_SIZE, raw.size)
        if (totalFrags == 1) { complete(seq, payload); return }

        val frags = fragments.getOrPut(seq) { arrayOfNulls(totalFrags) }
        if (frags.size != totalFrags) return
        frags[fragIdx] = payload

        if (frags.none { it == null }) {
            fragments.remove(seq)
            synchronized(pendingSeqs) { pendingSeqs.remove(seq) }
            val bos = ByteArrayOutputStream(frags.sumOf { it!!.size })
            frags.forEach { bos.write(it!!) }
            complete(seq, bos.toByteArray())
        } else {
            synchronized(pendingSeqs) {
                if (seq !in pendingSeqs) {
                    while (pendingSeqs.size >= MAX_PENDING_FRAMES) fragments.remove(pendingSeqs.removeFirst())
                    pendingSeqs.addLast(seq)
                }
            }
        }
    }

    private fun complete(seq: Int, jpeg: ByteArray) {
        lastCompletedSeq = seq
        // Tamamlanan karedan eski, yarım kalmış kareleri temizle
        synchronized(pendingSeqs) {
            val it = pendingSeqs.iterator()
            while (it.hasNext()) { val s = it.next(); if (s < seq) { fragments.remove(s); it.remove() } }
        }
        decodeChannel.trySend(jpeg)   // non-blocking: receive thread'ini bloklamaz
    }
}
