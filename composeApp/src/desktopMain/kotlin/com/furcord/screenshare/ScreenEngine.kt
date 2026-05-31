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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * P2P ekran paylaşımı motoru — VoiceEngine'in UDP soketi üzerinde çalışır.
 *
 * Paket başlığı (12 byte):
 *   [0-3]  magic    = 0x53435200 ("SCR\0")
 *   [4-7]  frameSeq (Int)
 *   [8]    fragIdx  (Byte, 0-based)
 *   [9]    totalFrags (Byte)
 *   [10-11] reserved
 *
 * Çözünürlük: 854×480 (480p), JPEG kalite: 0.40, hedef FPS: 30
 */
object ScreenEngine {

    const val MAGIC            = 0x53435200.toInt()  // VoiceEngine bu sabiti okur
    private const val TARGET_W      = 854
    private const val TARGET_H      = 480
    private const val JPEG_QUALITY  = 0.40f
    private const val TARGET_FPS    = 30
    private const val INTERVAL_MS   = 1000L / TARGET_FPS   // ~33ms
    private const val HEADER_SIZE   = 12
    private const val CHUNK_SIZE    = 1_400  // MTU altı — VoiceEngine.SCREEN_CHUNK_SIZE ile eşleşmeli
    private const val MAX_PENDING_FRAMES = 32  // fragment reassembly için bellek limiti

    // ── Herkese açık durum ────────────────────────────────────────────────────

    private val _localFrame    = MutableStateFlow<ImageBitmap?>(null)
    /** Yayıncının kendi önizleme karesi */
    val localFrame: StateFlow<ImageBitmap?> = _localFrame

    private val _receiverFrame = MutableStateFlow<ImageBitmap?>(null)
    /** Uzaktaki yayıncıdan gelen kare */
    val receiverFrame: StateFlow<ImageBitmap?> = _receiverFrame

    private val _broadcastingUidHash = MutableStateFlow<Int>(0)
    /** Ekran paylaşan kullanıcının uid.hashCode()'u; 0 = kimse paylaşmıyor */
    val broadcastingUidHash: StateFlow<Int> = _broadcastingUidHash

    @Volatile var isActive    = false; private set
    @Volatile var isReceiving = false; private set

    // ── Dahili ───────────────────────────────────────────────────────────────

    private val seqCounter  = AtomicInteger(0)
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var robot:      Robot? = null
    /** Yayıncıdan son paket alındığı zaman (ms). 0 = hiç alınmadı. */
    @Volatile private var lastFrameMs = 0L

    /**
     * VoiceEngine receive thread'inden non-blocking olarak beslenir.
     * CONFLATED: decoder yetişemezse eski kare düşer, en yeni kare işlenir.
     * Bu sayede receiveLoop hiç bloklanmaz → ses kaybı olmaz.
     */
    @Suppress("DEPRECATION")
    private val decodeChannel = Channel<ByteArray>(Channel.CONFLATED)

    init {
        // Kare decode eden ayrı coroutine — VoiceEngine thread'inden bağımsız
        scope.launch {
            for (jpeg in decodeChannel) {
                runCatching {
                    ImageIO.read(ByteArrayInputStream(jpeg))?.toComposeImageBitmap()
                }.getOrNull()?.let { _receiverFrame.value = it }
            }
        }
        // Yayın zaman aşımı: 5 saniye paket gelmezse broadcastingUidHash sıfırla
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

    // Fragment birleştirme: frameSeq → [totalFrags] nullable dilimler
    private val fragments   = ConcurrentHashMap<Int, Array<ByteArray?>>()
    private val pendingSeqs = ArrayDeque<Int>()

    // ── Yayıncı API ───────────────────────────────────────────────────────────

    /**
     * Ekran yakalamayı ve UDP gönderimini başlatır.
     * Zaten aktifse tekrar çağrılması zararsızdır.
     */
    fun start() {
        if (isActive) return
        val r = runCatching { Robot() }.getOrNull() ?: return
        robot    = r
        isActive = true
        VoiceEngine.onScreenFrame = ::onPacketReceived
        captureJob = scope.launch { captureLoop() }
    }

    /** Yayını durdurur ve kaynakları serbest bırakır. */
    fun stop() {
        isActive = false
        captureJob?.cancel(); captureJob = null
        robot = null
        _localFrame.value = null
        if (!isReceiving) VoiceEngine.onScreenFrame = null
    }

    // ── İzleyici API ─────────────────────────────────────────────────────────

    fun startReceiver() {
        if (isReceiving) return
        isReceiving = true
        VoiceEngine.onScreenFrame = ::onPacketReceived
    }

    fun stopReceiver() {
        isReceiving = false
        _receiverFrame.value = null
        // broadcastingUidHash ve VoiceEngine.onScreenFrame kasıtlı olarak temizlenmez:
        // Yayın bitince zaman aşımı (5s) devreye girer; bu sayede yayıncı
        // yayını yeniden başlatırsa diğer kullanıcılar otomatik fark eder.
    }

    /** Ses kanalından tamamen ayrılırken çağrılır — tüm alım durumunu sıfırlar. */
    fun stopReceiverFull() {
        isReceiving = false
        _receiverFrame.value = null
        _broadcastingUidHash.value = 0
        lastFrameMs = 0
        if (!isActive) VoiceEngine.onScreenFrame = null
    }

    // ── Yakalama döngüsü ─────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        while (isActive) {
            val t0 = System.currentTimeMillis()
            try {
                val jpeg = captureAndEncode()
                if (jpeg != null) {
                    // Kendi önizlemesini göster
                    runCatching {
                        ImageIO.read(ByteArrayInputStream(jpeg))?.toComposeImageBitmap()
                    }.getOrNull()?.let { _localFrame.value = it }

                    // Peer'lara gönder
                    VoiceEngine.sendScreenPackets(jpeg, seqCounter.incrementAndGet())
                }
            } catch (_: Exception) {}
            val elapsed = System.currentTimeMillis() - t0
            val wait    = INTERVAL_MS - elapsed
            if (wait > 0) delay(wait)
        }
    }

    /** Ekranı yakalar, 854×480'e küçültür, JPEG olarak sıkıştırır. */
    private fun captureAndEncode(): ByteArray? {
        val rb = robot ?: return null
        return try {
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val full       = rb.createScreenCapture(Rectangle(screenSize))

            // Hızlı bilinear ölçeklendirme
            val scaled = BufferedImage(TARGET_W, TARGET_H, BufferedImage.TYPE_INT_RGB)
            val g      = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(full, 0, 0, TARGET_W, TARGET_H, null)
            g.dispose()

            // JPEG sıkıştırma — kalite 0.40 = iyi-yeterli bant genişliği dengesi
            val baos   = ByteArrayOutputStream(32_000)
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param  = writer.defaultWriteParam.apply {
                compressionMode    = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = JPEG_QUALITY
            }
            val ios = ImageIO.createImageOutputStream(baos)
            writer.output = ios
            writer.write(null, IIOImage(scaled, null, null), param)
            writer.dispose()
            ios.close()

            baos.toByteArray()
        } catch (_: Exception) { null }
    }

    // ── Paket alma ───────────────────────────────────────────────────────────

    /**
     * VoiceEngine.receiveLoop() ekran paylaşımı paketini tespit ettiğinde çağırır.
     * Ham paket baytları verilir (başından sona, magic dahil).
     * senderUidHash: gönderici kullanıcının uid.hashCode()'u (VoiceEngine peer listesinden türetilir)
     */
    fun onPacketReceived(raw: ByteArray, senderUidHash: Int = 0) {
        lastFrameMs = System.currentTimeMillis()
        if (senderUidHash != 0) _broadcastingUidHash.value = senderUidHash
        // Badge-only mode: isReceiving=false olduğunda sadece hash güncellenir, decode atlanır
        if (!isReceiving) return
        if (raw.size < HEADER_SIZE + 1) return
        val bb         = ByteBuffer.wrap(raw)
        val seq        = bb.getInt(4)
        val fragIdx    = raw[8].toInt() and 0xFF
        val totalFrags = raw[9].toInt() and 0xFF
        val payload    = raw.copyOfRange(HEADER_SIZE, raw.size)

        if (totalFrags <= 1) {
            // Tek parça — direkt işle
            processFrame(payload)
            return
        }

        // Fragmentli kare: birleştir
        val frags = fragments.getOrPut(seq) { arrayOfNulls(totalFrags) }
        frags[fragIdx] = payload

        if (frags.none { it == null }) {
            // Tüm parçalar geldi
            fragments.remove(seq)
            synchronized(pendingSeqs) { pendingSeqs.remove(seq) }
            val complete = frags.filterNotNull().reduce { a, b -> a + b }
            processFrame(complete)
        } else {
            // Eksik parça var — bellek temizliği izle
            synchronized(pendingSeqs) {
                if (seq !in pendingSeqs) {
                    while (pendingSeqs.size >= MAX_PENDING_FRAMES) {
                        fragments.remove(pendingSeqs.removeFirst())
                    }
                    pendingSeqs.addLast(seq)
                }
            }
        }
    }

    private fun processFrame(jpeg: ByteArray) {
        // Non-blocking: receive thread'ini asla bloklamaz.
        // ImageIO.read() decode işi decodeChannel worker'a devredilir.
        decodeChannel.trySend(jpeg)
    }
}
