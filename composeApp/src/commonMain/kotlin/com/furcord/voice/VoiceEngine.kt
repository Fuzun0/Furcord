package com.furcord.voice

import com.furcord.auth.VoicePeer
import com.furcord.auth.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.math.sqrt
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Gerçek zamanlı çok kullanıcılı ses motoru.
 * Mixer thread her 20 ms'de tüm buffer'ları toplar → 7-8 kişilik kanal desteklenir.
 * NAT traversal: STUN (RFC 5389) + simültane UDP hole punching.
 * Simetrik NAT tespit edilirse TCP relay moduna geçer.
 * ⚠ Eko önleme yok — kulaklık zorunludur.
 */
object VoiceEngine {

    private val FORMAT              = AudioFormat(16000f, 16, 1, true, false)
    private const val SAMPLES_FRAME       = 320
    private const val FRAME_BYTES         = SAMPLES_FRAME * 2
    private const val HEADER_BYTES        = 8
    private const val PACKET_BYTES        = HEADER_BYTES + FRAME_BYTES
    private const val BUFFER_FRAMES       = 20   // max jitter buffer kapasitesi (400ms)
    private const val VAD_THRESHOLD       = 300f // RMS eşiği — altında sessiz (iletim durdurulur)
    private const val VAD_HOLD_FRAMES     = 10   // ~200ms hold timer — kelime sonu kesilmesin
    private const val MAX_PLC_FRAMES      = 3    // PLC: en fazla 3 frame (~60ms) tekrar

    private val STUN_SERVERS = listOf(
        "stun.l.google.com"   to 19302,
        "stun1.l.google.com"  to 19302,
        "stun.cloudflare.com" to 3478,
    )

    /**
     * Relay sunucusu adresi. null ise relay devre dışı.
     * Örnek: "relay.example.com" to 8080
     * relay-server/RelayServer.kt dosyasını bir VPS'e deploy edin.
     */
    var relayServer: Pair<String, Int>? = null

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

    private val peers        = ConcurrentHashMap<String, VoicePeer>()
    private val peerBuffers  = ConcurrentHashMap<Int, PeerBuffer>()
    private val peerVolumes  = ConcurrentHashMap<Int, Float>()      // uidHash → 0.0-2.0 (1.0 = normal)
    private val prevPeerVols = ConcurrentHashMap<Int, Float>()      // smooth ramp: previous-frame volume
    private val seqCounter        = AtomicInteger(0)
    private var vadHoldCounter    = 0                                     // VAD hold sayacı
    private val speakingTimestamps = ConcurrentHashMap<Int, Long>()       // uidHash → son ms
    private val lastGoodFrames    = ConcurrentHashMap<Int, IntArray>()    // PLC: son iyi frame
    private val plcCountMap       = ConcurrentHashMap<Int, Int>()         // PLC: tekrar sayısı

    // ── Per-peer jitter buffer ────────────────────────────────────────────────

    private class PeerBuffer(capacity: Int = SAMPLES_FRAME * BUFFER_FRAMES) {
        private val ring = ShortArray(capacity)
        private var wPos  = 0
        private var count = 0

        @Synchronized
        fun write(src: ByteArray, byteOffset: Int, byteLen: Int) {
            // AudioFormat(16000, 16, 1, signed, little-endian) — PCM byte pair is LE.
            // ByteBuffer.asShortBuffer() defaults to BIG-endian which corrupts samples.
            // Must explicitly use LITTLE_ENDIAN to reconstruct shorts correctly.
            val bb = ByteBuffer.wrap(src, byteOffset, byteLen)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val frames = byteLen / 2
            repeat(frames) {
                if (count < ring.size) { ring[wPos] = bb.get(); wPos = (wPos + 1) % ring.size; count++ }
            }
        }

        @Synchronized
        fun read(dst: IntArray, dstOffset: Int, length: Int) {
            val rPos  = (wPos - count + ring.size * 2) % ring.size
            var p     = rPos
            val avail = minOf(count, length)
            for (i in 0 until avail) { dst[dstOffset + i] += ring[p].toInt(); p = (p + 1) % ring.size }
            count -= avail
        }

        @Synchronized fun hasData() = count >= SAMPLES_FRAME
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    suspend fun start(selfUidHash: Int, channelId: String = ""): Boolean = withContext(Dispatchers.IO) {
        // Channel switching: fully tear down the previous session before re-opening hardware.
        // Simply returning true when isActive would leave a zombie session when switching channels.
        if (isActive) stop()
        channelIdHash = if (channelId.isNotBlank()) channelId.hashCode() else selfUidHash
        try {
            socket = DatagramSocket()
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
                            peerBuffers[fromUidHash]?.write(data, 0, data.size)
                        },
                    )
                    rc.connect()
                    relayClient = rc
                }
            }

            val micInfo = DataLine.Info(TargetDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(micInfo)) { stop(); return@withContext false }
            micLine = (AudioSystem.getLine(micInfo) as TargetDataLine).apply { open(FORMAT); start() }

            val spkInfo = DataLine.Info(SourceDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(spkInfo)) { stop(); return@withContext false }
            speakerLine = (AudioSystem.getLine(spkInfo) as SourceDataLine).apply { open(FORMAT); start() }

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

        // ── Step 1: Close the DatagramSocket FIRST. ────────────────────────────
        // This immediately throws SocketException inside receiveLoop's blocking
        // socket.receive() call, allowing that thread to exit cleanly.
        val sock = socket; socket = null
        runCatching { sock?.close() }

        // ── Step 2: Shut down SourceDataLine (speaker). ────────────────────────
        // Order: stop() → flush() → close(). Do NOT call drain() — it blocks
        // indefinitely when the mixerLoop has already stopped feeding data.
        val sl = speakerLine; speakerLine = null
        runCatching { sl?.stop(); sl?.flush(); sl?.close() }

        // ── Step 3: Shut down TargetDataLine (microphone). ────────────────────
        // close() forcefully unblocks captureLoop's blocking read() call,
        // which then catches the exception and exits the while loop.
        val ml = micLine; micLine = null
        runCatching { ml?.stop(); ml?.flush(); ml?.close() }

        // ── Step 4: Wait for all three loop coroutines to actually finish. ─────
        // Hardware lines are now closed; loops will exit within one iteration.
        // The 300 ms timeout is a safety net — in practice this completes in < 50 ms.
        // This guarantees the OS has released the hardware before start() can
        // re-open it (prevents the 'mic works on 2nd PC sometimes' resource leak).
        runCatching { shutdownLatch?.await(300, TimeUnit.MILLISECONDS) }
        shutdownLatch = null

        // ── Step 5: Cancel the coroutine scope (loops are already done). ───────
        engineScope?.cancel(); engineScope = null

        relayClient?.disconnect(); relayClient = null
        peers.clear(); peerBuffers.clear(); peerVolumes.clear(); prevPeerVols.clear()
        speakingTimestamps.clear(); lastGoodFrames.clear(); plcCountMap.clear()
        seqCounter.set(0); vadHoldCounter = 0
        localPort = 0; localPublicIp = ""
        isMuted = false; isDeafened = false; isRelayMode = false
    }

    /** Belirli bir kullanıcının ses seviyesini ayarla. 0.0 = sessiz, 1.0 = normal, 2.0 = 2x */
    fun setPeerVolume(uid: String, volume: Float) {
        peerVolumes[uid.hashCode()] = volume.coerceIn(0f, 2f)
    }

    fun getPeerVolume(uid: String): Float = peerVolumes[uid.hashCode()] ?: 1f

    /** uidHash bazlı konuşma göstergesi — son 500ms içinde ses alındıysa true */
    fun isSpeaking(uidHash: Int): Boolean =
        (System.currentTimeMillis() - (speakingTimestamps[uidHash] ?: 0L)) < 500L

    // ── Peer management ───────────────────────────────────────────────────────

    fun updatePeers(newPeers: List<VoicePeer>) {
        val newHashSet = newPeers.map { it.uid.hashCode() }.toSet()
        peerBuffers.keys.filter        { it !in newHashSet }.forEach { peerBuffers.remove(it) }
        prevPeerVols.keys.filter       { it !in newHashSet }.forEach { prevPeerVols.remove(it) }
        lastGoodFrames.keys.filter     { it !in newHashSet }.forEach { lastGoodFrames.remove(it) }
        plcCountMap.keys.filter        { it !in newHashSet }.forEach { plcCountMap.remove(it) }
        speakingTimestamps.keys.filter { it !in newHashSet }.forEach { speakingTimestamps.remove(it) }
        val added = newPeers.filter { !peers.containsKey(it.uid) }
        peers.clear()
        newPeers.forEach { peers[it.uid] = it; peerBuffers.getOrPut(it.uid.hashCode()) { PeerBuffer() } }
        for (peer in added) {
            runCatching { socket?.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName(peer.ip), peer.port)) }
        }
    }

    // ── Loops ─────────────────────────────────────────────────────────────────

    private fun captureLoop(selfUidHash: Int) {
        val pcmBuf = ByteArray(FRAME_BYTES); val pktBuf = ByteArray(PACKET_BYTES)
        while (isActive) {
            val read = try { micLine?.read(pcmBuf, 0, FRAME_BYTES) ?: break } catch (_: Exception) { break }
            if (read < FRAME_BYTES || isMuted) continue

            // Mikrofon kazanç: kullanıcı ayarı, varsayılan 1.0 = saf iletim (bypass)
            // Soft limiter: hard clip yerine peak normalization — gain > 1.0'da waveform bozulmaz.
            val gain = micGain
            if (gain != 1f) {
                val bb = java.nio.ByteBuffer.wrap(pcmBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val tmp = IntArray(SAMPLES_FRAME)
                var peak = 0
                for (i in 0 until SAMPLES_FRAME) {
                    val v = (bb.getShort(i * 2).toInt() * gain).toInt()
                    tmp[i] = v
                    val av = if (v < 0) -v else v
                    if (av > peak) peak = av
                }
                val scale = if (peak > 32767) 32767f / peak.toFloat() else 1f
                for (i in 0 until SAMPLES_FRAME) {
                    bb.putShort(i * 2, (tmp[i] * scale).toInt().coerceIn(-32768, 32767).toShort())
                }
            }

            // VAD: RMS gate + hold timer — kelime ortasında kesmesin
            val rmsVal = run {
                val bb = java.nio.ByteBuffer.wrap(pcmBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until SAMPLES_FRAME) { val s = bb.getShort(i * 2).toDouble(); sum += s * s }
                sqrt(sum / SAMPLES_FRAME).toFloat()
            }
            if (rmsVal > VAD_THRESHOLD) {
                vadHoldCounter = VAD_HOLD_FRAMES
                speakingTimestamps[selfUidHash] = System.currentTimeMillis()
            } else {
                if (vadHoldCounter <= 0) continue
                vadHoldCounter--
            }

            val seq = seqCounter.incrementAndGet()

            if (isRelayMode) {
                // Relay modu: ses verisini TCP relay üzerinden gönder
                relayClient?.sendFrame(pcmBuf)
            } else {
                // Direkt UDP modu
                ByteBuffer.wrap(pktBuf, 0, HEADER_BYTES).putInt(seq).putInt(selfUidHash)
                System.arraycopy(pcmBuf, 0, pktBuf, HEADER_BYTES, FRAME_BYTES)
                for (peer in peers.values.toList()) {
                    runCatching { socket?.send(DatagramPacket(pktBuf, PACKET_BYTES, InetAddress.getByName(peer.ip), peer.port)) }
                }
            }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(PACKET_BYTES + 64)
        while (isActive) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket?.receive(dp) ?: break
            } catch (_: SocketException) {
                // Socket was closed by stop() — normal, clean shutdown path.
                break
            } catch (_: Exception) {
                if (!isActive) break
                continue
            }
            if (dp.length <= HEADER_BYTES || isDeafened) continue
            val uidHash = ByteBuffer.wrap(buf, 4, 4).int
            speakingTimestamps[uidHash] = System.currentTimeMillis()
            peerBuffers[uidHash]?.write(buf, HEADER_BYTES, dp.length - HEADER_BYTES)
        }
    }

    private fun mixerLoop() {
        val mixBuf  = IntArray(SAMPLES_FRAME)
        val tempBuf = IntArray(SAMPLES_FRAME)
        val outBuf  = ByteArray(FRAME_BYTES)
        // Wrap with LITTLE_ENDIAN once — matches AudioFormat(16000, 16, 1, signed, little-endian).
        // Using default BE ByteBuffer + manual byte swap is error-prone; LE wrap is canonical.
        val outBB   = ByteBuffer.wrap(outBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var outputFade = 1f
        while (isActive) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }

            // ── Smooth deafen fade (≈40ms ramp at 50% per frame) ─────────────
            val targetFade = if (isDeafened) 0f else 1f
            outputFade     = outputFade + (targetFade - outputFade) * 0.5f

            // Once fully faded out, write silence to keep the line fed (no underrun pop)
            if (outputFade < 0.005f) {
                outBuf.fill(0)
                runCatching { speakerLine?.write(outBuf, 0, FRAME_BYTES) }
                continue
            }

            // PLC: tüm peer'ları gez; veri varsa al+kaydet, yoksa son frame'i yavaşça tekrar et
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

                // ── Per-peer smooth volume ramp ───────────────────────────────
                // Linearly interpolate between previous and target volume over the
                // frame (320 samples ≈ 20ms). Eliminates pops when setPeerVolume
                // jumps discontinuously (e.g. muting a specific user).
                val targetVol = peerVolumes[hash] ?: 1f
                val prevVol   = prevPeerVols.getOrDefault(hash, targetVol)
                prevPeerVols[hash] = targetVol

                if (prevVol == targetVol) {
                    // No change — fast path, no interpolation needed
                    if (targetVol == 1f) {
                        for (i in 0 until SAMPLES_FRAME) mixBuf[i] += tempBuf[i]
                    } else {
                        for (i in 0 until SAMPLES_FRAME) mixBuf[i] += (tempBuf[i] * targetVol).toInt()
                    }
                } else {
                    // Volume changed this frame — ramp to avoid discontinuity
                    val last = SAMPLES_FRAME - 1
                    for (i in 0 until SAMPLES_FRAME) {
                        val t   = i.toFloat() / last
                        val vol = prevVol + (targetVol - prevVol) * t
                        mixBuf[i] += (tempBuf[i] * vol).toInt()
                    }
                }
            }

            // Veri yoksa sessizlik yaz (buffer underrun önleme)
            if (!hasVoice) {
                outBuf.fill(0)
                runCatching { speakerLine?.write(outBuf, 0, FRAME_BYTES) }
                continue
            }

            // ── Write to speaker with deafen fade + peak normalization ─────────
            // Normalize the mixed frame to prevent hard-clipping distortion when
            // multiple peers or high volumes would overflow Short range.
            var peak = 0
            for (v in mixBuf) { val av = if (v < 0) -v else v; if (av > peak) peak = av }
            val normScale = if (peak > 32767) 32767f / peak.toFloat() else 1f

            outBB.rewind()
            for (i in 0 until SAMPLES_FRAME) {
                val s = (mixBuf[i] * outputFade * normScale).toInt().coerceIn(-32768, 32767)
                outBB.putShort(s.toShort())
            }
            runCatching { speakerLine?.write(outBuf, 0, FRAME_BYTES) }
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

