package com.furcord.voice

import com.furcord.auth.VoicePeer
import com.furcord.auth.AppPrefs
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Gerçek zamanlı çok kullanıcılı ses motoru.
 *
 *  • Codec : Opus (48 kHz, mono, VBR ~48 kbps, in-band FEC + paket kaybı gizleme).
 *            Eski sürümdeki ham 16 kHz PCM'e göre hem çok daha net hem ~5 kat daha az bant genişliği.
 *  • Ağ    : UDP (STUN + hole punching); simetrik NAT'ta TCP relay.
 *  • Alım  : sıra numarasıyla yeniden sıralama, kayıp kareyi FEC/PLC ile kurtarma,
 *            kısa jitter tamponu; hoparlör saatine kilitli mixer (20 ms'lik Thread.sleep kayması yok).
 * ⚠ Eko önleme yok — kulaklık zorunludur.
 */
object VoiceEngine {

    private val FORMAT              = AudioFormat(48000f, 16, 1, true, false)
    private const val SAMPLE_RATE         = 48_000
    private const val SAMPLES_FRAME       = 960                 // 20 ms @ 48 kHz
    private const val FRAME_BYTES         = SAMPLES_FRAME * 2
    private const val HEADER_BYTES        = 8                   // [seq:4][uidHash:4]
    private const val MAX_OPUS_BYTES      = 400
    private const val BUFFER_FRAMES       = 12                  // en fazla 240 ms jitter tamponu
    private const val PRIME_FRAMES        = 2                   // tampon boşaldıktan sonra 40 ms birikince çal
    private const val MAX_PLC_FRAMES      = 5                   // mixer: veri yoksa en fazla ~100 ms tekrar
    private const val SPEAKER_QUEUE_FRAMES = 3                  // hoparlör hattında tutulacak ~60 ms
    private const val OPUS_BITRATE        = 48_000
    private const val VAD_MIN_RMS         = 300.0               // bunun altı asla konuşma sayılmaz
    private const val VAD_ONSET_FRAMES    = 3                   // 60 ms sürekli yüksek enerji → konuşma (klavye tıkı elenir)
    private const val VAD_HANGOVER_FRAMES = 15                  // konuşma bitince 300 ms daha gönder
    private const val RELAY_SEQ           = Int.MIN_VALUE       // relay (TCP, sıralı) paketlerinde seq yok

    private const val SCREEN_CHUNK_SIZE   = 1_400  // MTU altı (1500B) — IP fragmantasyonu önlenir
    private const val SCREEN_MAGIC        = 0x53435200.toInt() // "SCR\0" — ScreenEngine ile eşleşmeli
    private const val WATCH_MAGIC         = 0x57415443         // "WATC" — izleyici → yayıncı isteği
    private const val MAX_UDP_PAYLOAD     = 65_507 // RFC 768 maks. UDP yükü

    private val STUN_SERVERS = listOf(
        "stun.l.google.com"   to 19302,
        "stun1.l.google.com"  to 19302,
        "stun.cloudflare.com" to 3478,
    )

    /**
     * Relay sunucusu adresi. null ise relay devre dışı.
     * relay-server/RelayServer.kt dosyasını bir VPS'e deploy edin.
     */
    var relayServer: Pair<String, Int>? = null

    /** Ekran paylaşımı paketi geldiğinde ScreenEngine tarafından atanır. Bloklayan iş yapma. */
    var onScreenFrame: ((ByteArray, Int) -> Unit)? = null

    /** Bir izleyici "izliyorum" isteği gönderdiğinde (yayıncı tarafında) çağrılır. Parametre: izleyici uidHash. */
    var onWatchRequest: ((Int) -> Unit)? = null

    @Volatile var isMuted      = false
    @Volatile var isDeafened   = false
    @Volatile var isActive     = false; private set
    @Volatile var isRelayMode  = false; private set
    /** Mikrofon giriş kazanç: 0.0 = sessiz, 1.0 = normal, 2.0 = 2x. Disk'e kaydedilir. */
    @Volatile var micGain: Float = AppPrefs.micGain

    var localPublicIp: String = ""; private set
    var localPort: Int = 0;         private set

    private var socket:        DatagramSocket? = null
    private var micLine:       TargetDataLine? = null
    private var speakerLine:   SourceDataLine? = null
    private var engineScope:   CoroutineScope? = null
    private var relayClient:   RelayClient?    = null
    private var shutdownLatch: CountDownLatch? = null
    private var channelIdHash: Int = 0
    private var encoder:       OpusEncoder?    = null

    private val peers        = ConcurrentHashMap<String, VoicePeer>()
    private val peerAddrs    = ConcurrentHashMap<String, InetSocketAddress>()   // uid → önceden çözülmüş adres
    private val peerBuffers  = ConcurrentHashMap<Int, PeerBuffer>()
    private val decoders     = ConcurrentHashMap<Int, OpusDecoder>()            // yalnızca receive thread kullanır
    private val lastSeqs     = ConcurrentHashMap<Int, Int>()
    private val peerVolumes  = ConcurrentHashMap<Int, Float>()      // uidHash → 0.0-2.0 (1.0 = normal)
    private val prevPeerVols = ConcurrentHashMap<Int, Float>()      // smooth ramp: previous-frame volume
    private val seqCounter        = AtomicInteger(0)
    private val speakingTimestamps = ConcurrentHashMap<Int, Long>()       // uidHash → son ms
    private val lastGoodFrames    = ConcurrentHashMap<Int, IntArray>()    // PLC: son iyi frame
    private val plcCountMap       = ConcurrentHashMap<Int, Int>()         // PLC: tekrar sayısı

    // ── Per-peer jitter buffer ────────────────────────────────────────────────

    private class PeerBuffer(capacity: Int = SAMPLES_FRAME * BUFFER_FRAMES) {
        private val ring = ShortArray(capacity)
        private var wPos  = 0
        private var count = 0
        private var primed = false

        @Synchronized
        fun writeShorts(src: ShortArray, off: Int, n: Int) {
            for (i in 0 until n) {
                if (count < ring.size) { ring[wPos] = src[off + i]; wPos = (wPos + 1) % ring.size; count++ }
            }
        }

        @Synchronized
        fun read(dst: IntArray, dstOffset: Int, length: Int) {
            val rPos  = (wPos - count + ring.size * 2) % ring.size
            var p     = rPos
            val avail = minOf(count, length)
            for (i in 0 until avail) { dst[dstOffset + i] += ring[p].toInt(); p = (p + 1) % ring.size }
            count -= avail
            if (count < SAMPLES_FRAME) primed = false   // boşaldı → tekrar tampon biriktir
        }

        /** Tampon boşaldıktan sonra PRIME_FRAMES kare birikmeden çalmaya başlama (kesik kesik ses önleme). */
        @Synchronized
        fun hasData(): Boolean {
            if (!primed) {
                if (count >= SAMPLES_FRAME * PRIME_FRAMES) primed = true else return false
            }
            return count >= SAMPLES_FRAME
        }
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    suspend fun start(selfUidHash: Int, channelId: String = ""): Boolean = withContext(Dispatchers.IO) {
        // Kanal değiştirirken önceki oturum tamamen kapatılır.
        if (isActive) stop()
        channelIdHash = if (channelId.isNotBlank()) channelId.hashCode() else selfUidHash
        try {
            socket = DatagramSocket().apply {
                // Büyük tamponlar: ekran paylaşımı kareleri onlarca paket halinde patlar; varsayılan
                // 64 KB tampon bu patlamada paket düşürür (hem ekran hem ses bozulur).
                runCatching { receiveBufferSize = 4 * 1024 * 1024 }
                runCatching { sendBufferSize    = 2 * 1024 * 1024 }
            }
            val stun = stunDiscover()
            if (stun != null) {
                localPublicIp = stun.first; localPort = stun.second
            } else {
                localPublicIp = runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("127.0.0.1")
                localPort     = socket!!.localPort
            }

            // Simetrik NAT tespiti: ikinci STUN farklı port veriyorsa simetrik NAT
            val isSymmetricNat = detectSymmetricNat()
            if (isSymmetricNat) {
                isRelayMode = true
                val rs = relayServer
                if (rs != null) {
                    val rc = RelayClient(
                        host          = rs.first,
                        port          = rs.second,
                        channelIdHash = channelIdHash,
                        selfUidHash   = selfUidHash,
                        onFrame       = { fromUidHash, data ->
                            handleVoicePacket(fromUidHash, RELAY_SEQ, data, 0, data.size)
                        },
                    )
                    rc.connect()
                    relayClient = rc
                }
            }

            encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                setBitrate(OPUS_BITRATE)
                setUseVBR(true)
                setComplexity(5)
                setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
                setUseInbandFEC(true)        // kayıp paketi bir sonraki paketten kurtar
                setPacketLossPercent(8)
            }

            val micInfo = DataLine.Info(TargetDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(micInfo)) { stop(); return@withContext false }
            micLine = (AudioSystem.getLine(micInfo) as TargetDataLine).apply { open(FORMAT, FRAME_BYTES * 4); start() }

            val spkInfo = DataLine.Info(SourceDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(spkInfo)) { stop(); return@withContext false }
            // Hat tamponu küçük tutulur → gecikme düşük; mixer hattın doluluğuna göre üretir.
            speakerLine = (AudioSystem.getLine(spkInfo) as SourceDataLine).apply { open(FORMAT, FRAME_BYTES * 8); start() }

            isActive    = true
            val latch   = CountDownLatch(3)
            shutdownLatch = latch
            engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            engineScope!!.launch { try { captureLoop(selfUidHash) } finally { latch.countDown() } }
            engineScope!!.launch { try { receiveLoop()            } finally { latch.countDown() } }
            engineScope!!.launch { try { mixerLoop()              } finally { latch.countDown() } }
            true
        } catch (_: Exception) { stop(); false }
    }

    fun stop() {
        isActive = false

        // 1) Soketi kapat → receiveLoop'taki bloklu receive() SocketException ile çıkar.
        val sock = socket; socket = null
        runCatching { sock?.close() }

        // 2) Hoparlör hattı: stop → flush → close (drain() asla — mixer durmuşsa sonsuza dek bloklar).
        val sl = speakerLine; speakerLine = null
        runCatching { sl?.stop(); sl?.flush(); sl?.close() }

        // 3) Mikrofon hattı: close() captureLoop'taki bloklu read()'i serbest bırakır.
        val ml = micLine; micLine = null
        runCatching { ml?.stop(); ml?.flush(); ml?.close() }

        // 4) Üç döngünün bitmesini bekle (donanımın serbest kaldığından emin ol).
        runCatching { shutdownLatch?.await(300, TimeUnit.MILLISECONDS) }
        shutdownLatch = null

        // 5) Coroutine scope'u iptal et.
        engineScope?.cancel(); engineScope = null

        relayClient?.disconnect(); relayClient = null
        encoder = null
        peers.clear(); peerAddrs.clear(); peerBuffers.clear(); decoders.clear(); lastSeqs.clear()
        peerVolumes.clear(); prevPeerVols.clear()
        speakingTimestamps.clear(); lastGoodFrames.clear(); plcCountMap.clear()
        seqCounter.set(0)
        localPort = 0; localPublicIp = ""
        isMuted = false; isDeafened = false; isRelayMode = false
    }

    /** Belirli bir kullanıcının ses seviyesini ayarla. 0.0 = sessiz, 1.0 = normal, 2.0 = 2x */
    fun setPeerVolume(uid: String, volume: Float) {
        peerVolumes[uid.hashCode()] = volume.coerceIn(0f, 2f)
    }

    fun getPeerVolume(uid: String): Float = peerVolumes[uid.hashCode()] ?: 1f

    // ── Ekran paylaşımı taşıma (ses soketini paylaşır) ───────────────────────

    /**
     * JPEG karesini parçalara bölerek [targetUidHashes] içindeki peer'lara gönderir.
     * @param seq artan kare numarası (parça birleştirme için)
     */
    fun sendScreenPackets(jpegBytes: ByteArray, seq: Int, targetUidHashes: Set<Int>) {
        if (!isActive) return
        val targets = peers.values.filter { it.uid.hashCode() in targetUidHashes }
            .mapNotNull { peerAddrs[it.uid] }
        if (targets.isEmpty()) return
        val sock = socket ?: return
        val totalFrags = ((jpegBytes.size - 1) / SCREEN_CHUNK_SIZE + 1)
        if (totalFrags > 255) return
        var offset = 0
        var fragIdx = 0
        while (offset < jpegBytes.size) {
            val chunkLen = minOf(SCREEN_CHUNK_SIZE, jpegBytes.size - offset)
            val pkt = ByteArray(12 + chunkLen)
            ByteBuffer.wrap(pkt).apply {
                putInt(SCREEN_MAGIC)
                putInt(seq)
                put(fragIdx.toByte())
                put(totalFrags.toByte())
                put(0); put(0)
            }
            System.arraycopy(jpegBytes, offset, pkt, 12, chunkLen)
            for (addr in targets) runCatching { sock.send(DatagramPacket(pkt, pkt.size, addr)) }
            offset  += chunkLen
            fragIdx++
        }
    }

    /** "Yayın var" duyurusu (veri yok) — izlemeyenlerin de yayını görebilmesi için tüm peer'lara. */
    fun sendScreenAnnounce(seq: Int) {
        if (!isActive) return
        val sock = socket ?: return
        val pkt = ByteBuffer.allocate(12).apply {
            putInt(SCREEN_MAGIC); putInt(seq); put(0); put(0); put(0); put(0)
        }.array()
        for (addr in peerAddrs.values) runCatching { sock.send(DatagramPacket(pkt, pkt.size, addr)) }
    }

    /** Yayıncıya "bu yayını izliyorum" isteği gönderir (yayıncı sadece izleyenlere kare yollar). */
    fun sendWatchRequest(broadcasterUidHash: Int) {
        if (!isActive) return
        val sock = socket ?: return
        val peer = peers.values.find { it.uid.hashCode() == broadcasterUidHash } ?: return
        val addr = peerAddrs[peer.uid] ?: return
        val pkt = ByteBuffer.allocate(8).apply { putInt(WATCH_MAGIC); putInt(seqCounter.get()) }.array()
        runCatching { sock.send(DatagramPacket(pkt, pkt.size, addr)) }
    }

    /** uidHash bazlı konuşma göstergesi — son 500ms içinde ses alındıysa true */
    fun isSpeaking(uidHash: Int): Boolean =
        (System.currentTimeMillis() - (speakingTimestamps[uidHash] ?: 0L)) < 500L

    // ── Peer management ───────────────────────────────────────────────────────

    fun updatePeers(newPeers: List<VoicePeer>) {
        val newHashSet = newPeers.map { it.uid.hashCode() }.toSet()
        peerBuffers.keys.filter        { it !in newHashSet }.forEach { peerBuffers.remove(it) }
        decoders.keys.filter           { it !in newHashSet }.forEach { decoders.remove(it) }
        lastSeqs.keys.filter           { it !in newHashSet }.forEach { lastSeqs.remove(it) }
        prevPeerVols.keys.filter       { it !in newHashSet }.forEach { prevPeerVols.remove(it) }
        lastGoodFrames.keys.filter     { it !in newHashSet }.forEach { lastGoodFrames.remove(it) }
        plcCountMap.keys.filter        { it !in newHashSet }.forEach { plcCountMap.remove(it) }
        speakingTimestamps.keys.filter { it !in newHashSet }.forEach { speakingTimestamps.remove(it) }
        val added = newPeers.filter { !peers.containsKey(it.uid) }
        peers.clear(); peerAddrs.clear()
        newPeers.forEach {
            peers[it.uid] = it
            peerBuffers.getOrPut(it.uid.hashCode()) { PeerBuffer() }
            runCatching { peerAddrs[it.uid] = InetSocketAddress(InetAddress.getByName(it.ip), it.port) }
        }
        for (peer in added) {
            val addr = peerAddrs[peer.uid] ?: continue
            runCatching { socket?.send(DatagramPacket(ByteArray(1), 1, addr)) }   // hole punching
        }
    }

    // ── Loops ─────────────────────────────────────────────────────────────────

    private fun captureLoop(selfUidHash: Int) {
        val pcmBuf  = ByteArray(FRAME_BYTES)
        val pcm     = ShortArray(SAMPLES_FRAME)
        val pktBuf  = ByteArray(HEADER_BYTES + MAX_OPUS_BYTES)
        val pcmBB   = ByteBuffer.wrap(pcmBuf).order(ByteOrder.LITTLE_ENDIAN)
        selfHashForVad = selfUidHash
        noiseFloor = 100.0; aboveCount = 0; hangover = 0; vadOpen = false; preRoll.clear()
        while (isActive) {
            val read = try { micLine?.read(pcmBuf, 0, FRAME_BYTES) ?: break } catch (_: Exception) { break }
            if (read < FRAME_BYTES) continue
            if (isMuted) { vadOpen = false; aboveCount = 0; hangover = 0; preRoll.clear(); continue }

            // Mikrofon kazancı + yumuşak sınırlayıcı (hard clip yerine tepe normalizasyonu)
            val gain = micGain
            var peak = 0
            for (i in 0 until SAMPLES_FRAME) {
                val v = (pcmBB.getShort(i * 2) * (if (gain != 1f) gain else 1f)).toInt()
                pcm[i] = v.coerceIn(-32768, 32767).toShort()
                if (gain != 1f) {
                    val av = if (v < 0) -v else v
                    if (av > peak) peak = av
                }
            }
            if (gain != 1f && peak > 32767) {
                val scale = 32767f / peak.toFloat()
                for (i in 0 until SAMPLES_FRAME) {
                    pcm[i] = (pcmBB.getShort(i * 2) * gain * scale).toInt().coerceIn(-32768, 32767).toShort()
                }
            }

            // ── VAD: yalnızca konuşma varken gönder ─────────────────────────────
            // Ham (kazançsız) kare enerjisi; gürültü tabanı uyarlanır → sabit fan/uğultu konuşma sayılmaz.
            var sumSq = 0.0
            for (i in 0 until SAMPLES_FRAME) { val s = pcmBB.getShort(i * 2).toDouble(); sumSq += s * s }
            val rms = Math.sqrt(sumSq / SAMPLES_FRAME)
            if (rms < noiseFloor) noiseFloor = noiseFloor * 0.9 + rms * 0.1
            else if (!vadOpen)    noiseFloor += (rms - noiseFloor) * 0.003
            val threshold = maxOf(noiseFloor * 3.0, VAD_MIN_RMS)
            val loud = rms > threshold

            if (loud) { aboveCount++; hangover = VAD_HANGOVER_FRAMES } else { aboveCount = 0; if (hangover > 0) hangover-- }

            if (!vadOpen) {
                // Kapalıyken son kareleri ön-tamponda tut; konuşma başlayınca baştan gönder (ilk hece kesilmesin)
                preRoll.addLast(pcm.copyOf())
                while (preRoll.size > VAD_ONSET_FRAMES) preRoll.removeFirst()
                if (aboveCount >= VAD_ONSET_FRAMES) {
                    vadOpen = true
                    while (preRoll.isNotEmpty()) { if (!encodeAndSend(preRoll.removeFirst())) return }
                }
                continue
            }
            // Açık: konuşma + hangover süresince gönder
            if (!loud && hangover == 0) { vadOpen = false; preRoll.clear(); continue }
            if (!encodeAndSend(pcm)) return
        }
    }

    // VAD durumu (capture thread'ine özel)
    private var noiseFloor = 100.0
    private var aboveCount = 0
    private var hangover   = 0
    private var vadOpen    = false
    private val preRoll    = java.util.ArrayDeque<ShortArray>()
    private val pktBufV    = ByteArray(HEADER_BYTES + MAX_OPUS_BYTES)

    /** Bir PCM karesini Opus'a çevirip eşlere (veya relay'e) yollar; kalıcı hata olursa false. */
    private fun encodeAndSend(frame: ShortArray): Boolean {
        val enc  = encoder ?: return false
        val seq  = seqCounter.incrementAndGet()
        val n = try { enc.encode(frame, 0, SAMPLES_FRAME, pktBufV, HEADER_BYTES, MAX_OPUS_BYTES) }
                catch (_: Exception) { return true }
        if (n <= 0) return true
        speakingTimestamps[selfHashForVad] = System.currentTimeMillis()

        val rc = relayClient
        if (isRelayMode && rc != null) {
            rc.sendFrame(pktBufV.copyOfRange(HEADER_BYTES, HEADER_BYTES + n))
        } else {
            ByteBuffer.wrap(pktBufV, 0, HEADER_BYTES).putInt(seq).putInt(selfHashForVad)
            val sock = socket ?: return false
            val len = HEADER_BYTES + n
            for (addr in peerAddrs.values) {
                runCatching { sock.send(DatagramPacket(pktBufV, len, addr)) }
            }
        }
        return true
    }
    @Volatile private var selfHashForVad = 0

    private fun receiveLoop() {
        // Ses paketleri küçük, ekran paylaşımı parçaları ≤ ~1.4 KB; yine de tam UDP boyutunda tampon
        val buf = ByteArray(MAX_UDP_PAYLOAD)
        while (isActive) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket?.receive(dp) ?: break
            } catch (_: SocketException) {
                break   // stop() soketi kapattı — normal çıkış
            } catch (_: Exception) {
                if (!isActive) break
                continue
            }
            if (dp.length >= 8) {
                val magic = ByteBuffer.wrap(buf, 0, 4).int
                // Ekran paylaşımı → ScreenEngine
                if (dp.length >= 12 && magic == SCREEN_MAGIC) {
                    onScreenFrame?.invoke(buf.copyOfRange(0, dp.length), senderHashOf(dp))
                    continue
                }
                // İzleyici isteği → yayıncı tarafı
                if (magic == WATCH_MAGIC && dp.length == 8) {
                    val h = senderHashOf(dp)
                    if (h != 0) onWatchRequest?.invoke(h)
                    continue
                }
            }
            if (dp.length <= HEADER_BYTES) continue
            val uidHash = ByteBuffer.wrap(buf, 4, 4).int
            speakingTimestamps[uidHash] = System.currentTimeMillis()
            if (isDeafened) continue
            val seq = ByteBuffer.wrap(buf, 0, 4).int
            handleVoicePacket(uidHash, seq, buf, HEADER_BYTES, dp.length - HEADER_BYTES)
        }
    }

    private fun senderHashOf(dp: DatagramPacket): Int {
        val ip = dp.address.hostAddress; val port = dp.port
        return peers.values.find { it.ip == ip && it.port == port }?.uid?.hashCode() ?: 0
    }

    /**
     * Bir Opus paketini çözüp peer'ın jitter tamponuna yazar.
     * Sıra dışı/yinelenen paketler atılır; 1-3 paketlik kayıplar FEC/PLC ile doldurulur.
     */
    private fun handleVoicePacket(uidHash: Int, seq: Int, data: ByteArray, off: Int, len: Int) {
        val pbuf = peerBuffers[uidHash] ?: return
        if (len <= 0) return
        val dec = decoders.getOrPut(uidHash) { OpusDecoder(SAMPLE_RATE, 1) }
        val pcm = ShortArray(SAMPLES_FRAME)
        try {
            val last = lastSeqs[uidHash]
            if (seq != RELAY_SEQ && last != null) {
                val diff = seq - last
                if (diff <= 0 && diff > -200) return          // eski ya da yinelenen paket
                if (diff in 3..4) {                           // 2+ kare kayıp: önce saf PLC
                    repeat(diff - 2) {
                        val n = dec.decode(null, 0, 0, pcm, 0, SAMPLES_FRAME, false)
                        pbuf.writeShorts(pcm, 0, n)
                    }
                }
                if (diff in 2..4) {                           // bir önceki kare: bu paketteki FEC'ten kurtar
                    val n = dec.decode(data, off, len, pcm, 0, SAMPLES_FRAME, true)
                    pbuf.writeShorts(pcm, 0, n)
                }
            }
            if (seq != RELAY_SEQ) lastSeqs[uidHash] = seq
            val n = dec.decode(data, off, len, pcm, 0, SAMPLES_FRAME, false)
            pbuf.writeShorts(pcm, 0, n)
        } catch (_: Exception) {
            decoders.remove(uidHash)    // bozuk durum → decoder'ı yeniden oluştur
            lastSeqs.remove(uidHash)
        }
    }

    private fun mixerLoop() {
        val mixBuf  = IntArray(SAMPLES_FRAME)
        val tempBuf = IntArray(SAMPLES_FRAME)
        val outBuf  = ByteArray(FRAME_BYTES)
        val outBB   = ByteBuffer.wrap(outBuf).order(ByteOrder.LITTLE_ENDIAN)
        var outputFade = 1f
        while (isActive) {
            // Hoparlör saatine kilitli üretim: hat boşalmaya yaklaşınca bir kare üret.
            val sl = speakerLine ?: break
            val queued = try { sl.bufferSize - sl.available() } catch (_: Exception) { break }
            if (queued >= FRAME_BYTES * SPEAKER_QUEUE_FRAMES) {
                try { Thread.sleep(3) } catch (_: InterruptedException) { break }
                continue
            }

            // Smooth deafen fade (≈40ms ramp)
            val targetFade = if (isDeafened) 0f else 1f
            outputFade     = outputFade + (targetFade - outputFade) * 0.5f

            if (outputFade < 0.005f) {
                outBuf.fill(0)
                runCatching { sl.write(outBuf, 0, FRAME_BYTES) }
                continue
            }

            // PLC: veri varsa al+kaydet, yoksa son kareyi yavaşça tekrar et
            var hasVoice = false
            mixBuf.fill(0)
            for ((hash, pbuf) in peerBuffers) {
                tempBuf.fill(0)
                if (pbuf.hasData()) {
                    pbuf.read(tempBuf, 0, SAMPLES_FRAME)
                    lastGoodFrames[hash] = tempBuf.copyOf()
                    plcCountMap[hash] = 0
                    hasVoice = true
                } else {
                    val lf = lastGoodFrames[hash]
                    val pc = plcCountMap.getOrDefault(hash, MAX_PLC_FRAMES)
                    if (lf != null && pc < MAX_PLC_FRAMES) {
                        val fade = 1f - pc.toFloat() / MAX_PLC_FRAMES
                        for (i in 0 until SAMPLES_FRAME) tempBuf[i] = (lf[i] * fade).toInt()
                        plcCountMap[hash] = pc + 1
                        hasVoice = true
                    } else {
                        continue
                    }
                }

                // Peer başına yumuşak ses seviyesi geçişi (ses ayarı değişince "pop" olmasın)
                val targetVol = peerVolumes[hash] ?: 1f
                val prevVol   = prevPeerVols.getOrDefault(hash, targetVol)
                prevPeerVols[hash] = targetVol

                if (prevVol == targetVol) {
                    if (targetVol == 1f) {
                        for (i in 0 until SAMPLES_FRAME) mixBuf[i] += tempBuf[i]
                    } else {
                        for (i in 0 until SAMPLES_FRAME) mixBuf[i] += (tempBuf[i] * targetVol).toInt()
                    }
                } else {
                    val last = SAMPLES_FRAME - 1
                    for (i in 0 until SAMPLES_FRAME) {
                        val t   = i.toFloat() / last
                        val vol = prevVol + (targetVol - prevVol) * t
                        mixBuf[i] += (tempBuf[i] * vol).toInt()
                    }
                }
            }

            if (!hasVoice) {
                outBuf.fill(0)
                runCatching { sl.write(outBuf, 0, FRAME_BYTES) }
                continue
            }

            // Birden çok konuşmacı / yüksek ses seviyesinde patlamayı önlemek için tepe normalizasyonu
            var peak = 0
            for (v in mixBuf) { val av = if (v < 0) -v else v; if (av > peak) peak = av }
            val normScale = if (peak > 32767) 32767f / peak.toFloat() else 1f

            outBB.rewind()
            for (i in 0 until SAMPLES_FRAME) {
                val s = (mixBuf[i] * outputFade * normScale).toInt().coerceIn(-32768, 32767)
                outBB.putShort(s.toShort())
            }
            runCatching { sl.write(outBuf, 0, FRAME_BYTES) }
        }
    }

    // ── NAT tespiti ───────────────────────────────────────────────────────────

    /**
     * İki farklı STUN sunucusundan alınan harici port farklıysa simetrik NAT.
     * Simetrik NAT'ta UDP hole punching başarısız olur; relay gerekir.
     */
    private fun detectSymmetricNat(): Boolean {
        if (STUN_SERVERS.size < 2) return false
        val sock = socket ?: return false
        val r1 = stunDiscoverFrom(sock, STUN_SERVERS[0])
        val r2 = stunDiscoverFrom(sock, STUN_SERVERS[1])
        if (r1 == null || r2 == null) return false
        return r1.second != r2.second // farklı port → simetrik NAT
    }

    // ── STUN (RFC 5389) ───────────────────────────────────────────────────────

    private fun stunDiscover(): Pair<String, Int>? {
        val sock = socket ?: return null
        for (server in STUN_SERVERS) {
            stunDiscoverFrom(sock, server)?.let { return it }
        }
        return null
    }

    private fun stunDiscoverFrom(sock: DatagramSocket, server: Pair<String, Int>): Pair<String, Int>? {
        val (host, stunPort) = server
        val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val req  = ByteBuffer.allocate(20).apply {
            putShort(0x0001); putShort(0); putInt(0x2112A442.toInt()); put(txId)
        }.array()
        return try {
            val addr  = InetAddress.getByName(host)
            val saved = sock.soTimeout; sock.soTimeout = 3_000
            sock.send(DatagramPacket(req, req.size, addr, stunPort))
            val resp = ByteArray(512); val dp = DatagramPacket(resp, resp.size)
            sock.receive(dp); sock.soTimeout = saved
            parseStunResponse(resp, dp.length)
        } catch (_: Exception) { null }
    }

    private fun parseStunResponse(resp: ByteArray, len: Int): Pair<String, Int>? {
        if (len < 20) return null
        val buf = ByteBuffer.wrap(resp)
        if ((buf.short.toInt() and 0xFFFF) != 0x0101) return null
        val msgLen = buf.short.toInt() and 0xFFFF
        buf.int; buf.get(ByteArray(12))
        var pos = 20; val end = minOf(20 + msgLen, len)
        while (pos + 4 <= end) {
            val aType = ((resp[pos].toInt() and 0xFF) shl 8) or (resp[pos + 1].toInt() and 0xFF)
            val aLen  = ((resp[pos + 2].toInt() and 0xFF) shl 8) or (resp[pos + 3].toInt() and 0xFF)
            pos += 4
            if (aType == 0x0020 && pos + 8 <= end) {
                val port = (((resp[pos+2].toInt() and 0xFF) shl 8) or (resp[pos+3].toInt() and 0xFF)) xor 0x2112
                val ip   = buildString {
                    append((resp[pos+4].toInt() and 0xFF) xor 0x21); append('.')
                    append((resp[pos+5].toInt() and 0xFF) xor 0x12); append('.')
                    append((resp[pos+6].toInt() and 0xFF) xor 0xA4); append('.')
                    append((resp[pos+7].toInt() and 0xFF) xor 0x42)
                }
                return ip to port
            }
            pos += aLen + if (aLen % 4 != 0) 4 - aLen % 4 else 0
        }
        return null
    }
}
