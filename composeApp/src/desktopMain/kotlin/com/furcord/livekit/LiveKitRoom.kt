package com.furcord.livekit

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.logging.LogSink
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.logging.Logging.Severity
import dev.onvoid.webrtc.media.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * Audio-only LiveKit room.
 *
 * VIDEO / SCREEN-SHARE INTENTIONALLY REMOVED.
 *
 * All webrtc-java JNI wrappers (AudioSource, AudioTrack, PeerConnection,
 * RTCDataChannel, observers, ICE candidates, session descriptions) are kept in
 * [gcJail] for their entire lifetime.  They are NEVER disposed or cleared.
 * Explicitly calling .close() or .dispose() on native objects while the C++
 * engine is still running races the GC thread → 0xc0000005 (jvm.dll+0x369b04).
 * The only safe teardown is disconnecting the OkHttp WebSocket.
 */
object LiveKitRoom {

    // =========================================================================
    // NATIVE WEBRTC LOGGING — must run before any native object is created
    // =========================================================================
    init {
        try {
            val logFile = java.io.File("livekit-trace.log").also { it.createNewFile() }
            val fos     = FileOutputStream(logFile, false)
            val tee     = object : java.io.OutputStream() {
                val original = System.out
                val file     = PrintStream(fos, true)
                override fun write(b: Int)                           { original.write(b);            file.write(b)            }
                override fun write(b: ByteArray)                     { original.write(b);            file.write(b)            }
                override fun write(b: ByteArray, off: Int, len: Int) { original.write(b, off, len);  file.write(b, off, len)  }
                override fun flush() { original.flush(); file.flush() }
            }
            System.setOut(PrintStream(tee, true))
            println("[LiveKit] TRACE FILE: ${logFile.absolutePath}")
        } catch (e: Exception) {
            println("[LiveKit] WARNING: Could not open trace log: ${e.message}")
        }

        Logging.logToDebug(Severity.INFO)
        Logging.logTimestamps(false)
        Logging.logThreads(false)
        Logging.addLogSink(Severity.WARNING, object : LogSink {
            override fun onLogMessage(severity: Severity, message: String) {
                println("[WebRTC-C++/${severity.name}] ${message.trimEnd()}")
            }
        })
        println("[LiveKit] Native WebRTC logging enabled (WARNING+ forwarded to stdout)")
    }

    val serverUrl: String = System.getProperty("furcord.livekit.url",       "")
    val apiKey:    String = System.getProperty("furcord.livekit.apiKey",     "")
    val apiSecret: String = System.getProperty("furcord.livekit.apiSecret",  "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // =========================================================================
    // GC JAIL
    // Every JNI wrapper object that C++ holds a raw pointer to MUST live here.
    // Never call gcJail.clear() — do not dispose these objects.
    // =========================================================================
    private val gcJail = java.util.concurrent.CopyOnWriteArrayList<Any>()

    // =========================================================================
    // PEER CONNECTION FACTORY
    // Single shared factory — default native ADM+APM (WASAPI on Windows).
    // Do NOT inject custom AudioDeviceModule; JNI bridge cannot validate lifetime
    // of Java-constructed objects across native thread boundaries.
    // =========================================================================
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory().also {
            println("[LiveKit] PeerConnectionFactory created (native default ADM+APM)")
        }
    }

    // =========================================================================
    // SESSION STATE
    // =========================================================================
    private var pubSig: LiveKitSignalingClient? = null
    private var subSig: LiveKitSignalingClient? = null
    private var pubPc:  RTCPeerConnection?      = null
    private var subPc:  RTCPeerConnection?      = null
    private var iceServers: List<RTCIceServer>  = emptyList()

    // RTCConfiguration must be class-level — local var becomes GC-eligible the
    // moment createPeerConnection() returns, while C++ still reads the ICE list.
    private var pubRtcConfig: RTCConfiguration? = null
    private var subRtcConfig: RTCConfiguration? = null

    // =========================================================================
    // AUDIO OBJECTS — class-level for full session lifetime
    // =========================================================================
    private var pubAudioSource: AudioTrackSource? = null
    private var pubAudioTrack:  AudioTrack?       = null

    // =========================================================================
    // SDP / PC OBSERVER STRONG REFERENCES
    // C++ holds raw pointers to these observers and fires callbacks on native
    // threads.  GC-collecting an observer mid-callback → 0xc0000005.
    // =========================================================================
    private var pubPcObserver:      PeerConnectionObserver?           = null
    private var subPcObserver:      PeerConnectionObserver?           = null
    private var pubCreateOfferObs:  CreateSessionDescriptionObserver? = null
    private var subCreateAnswerObs: CreateSessionDescriptionObserver? = null
    private var pubSetLocalDescObs: SetSessionDescriptionObserver?    = null
    private var pubSetRemoteObs:    SetSessionDescriptionObserver?    = null
    private var subSetLocalDescObs: SetSessionDescriptionObserver?    = null
    private var subSetRemoteObs:    SetSessionDescriptionObserver?    = null

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    fun joinAndPublish(roomName: String, identity: String, displayName: String) {
        scope.launch {
            val token = LiveKitTokenGenerator.generateToken(
                apiKey, apiSecret, roomName, identity, displayName,
                canPublish = true, canSubscribe = false
            )
            val sig = LiveKitSignalingClient(serverUrl, token).also { pubSig = it }
            sig.connect()
            println("[LiveKit] Publisher: connecting to $serverUrl")

            sig.events.collect { event ->
                when (event) {
                    is LiveKitSignalingClient.Event.Connected -> {
                        println("[LiveKit] Publisher: WebSocket connected")
                    }
                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        println("[LiveKit] Publisher: Join ack — ${event.response.iceServers.size} ICE server(s)")
                        iceServers = event.response.iceServers.map { it.toRTC() }
                        createPublisherPc(sig)
                    }
                    is LiveKitSignalingClient.Event.AnswerReceived -> {
                        println("[LiveKit] Publisher: Answer received")
                        pubSetRemoteObs = object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                try { println("[LiveKit] pub setRemoteDescription — OK") }
                                catch (e: Exception) { e.printStackTrace() }
                            }
                            override fun onFailure(e: String) {
                                try { println("[LiveKit] pub setRemoteDescription — FAILED: $e") }
                                catch (ex: Exception) { ex.printStackTrace() }
                            }
                        }
                        val answerSdp = RTCSessionDescription(RTCSdpType.ANSWER, event.sdp.sdp)
                        gcJail.add(answerSdp)
                        pubPc?.setRemoteDescription(answerSdp, pubSetRemoteObs!!)
                    }
                    is LiveKitSignalingClient.Event.TrickleReceived -> {
                        if (event.msg.target == 0) {
                            val (sdp, mid, idx) = parseCandidateJson(event.msg.candidateInit)
                            val candidate = RTCIceCandidate(mid, idx, sdp)
                            gcJail.add(candidate)
                            pubPc?.addIceCandidate(candidate)
                        }
                    }
                    is LiveKitSignalingClient.Event.Disconnected -> {
                        println("[LiveKit] Publisher: disconnected")
                    }
                    else -> {}
                }
            }
        }
    }

    fun joinAndSubscribe(roomName: String, identity: String, displayName: String) {
        scope.launch {
            val token = LiveKitTokenGenerator.generateToken(
                apiKey, apiSecret, roomName, identity, displayName,
                canPublish = false, canSubscribe = true
            )
            val sig = LiveKitSignalingClient(serverUrl, token).also { subSig = it }
            sig.connect()
            println("[LiveKit] Subscriber: connecting to $serverUrl")

            sig.events.collect { event ->
                when (event) {
                    is LiveKitSignalingClient.Event.Connected -> {
                        println("[LiveKit] Subscriber: WebSocket connected")
                    }
                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        println("[LiveKit] Subscriber: Join ack — ${event.response.iceServers.size} ICE server(s)")
                        iceServers = event.response.iceServers.map { it.toRTC() }
                        createSubscriberPc(sig)
                    }
                    is LiveKitSignalingClient.Event.OfferReceived -> {
                        println("[LiveKit] Subscriber: Offer received (${event.sdp.sdp.length} bytes)")
                        subSetRemoteObs = object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                try {
                                    println("[LiveKit] sub setRemoteDescription — OK")
                                    scope.launch { subscriberAnswer(sig) }
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            override fun onFailure(e: String) {
                                try { println("[LiveKit] sub setRemoteDescription FAILED: $e") }
                                catch (ex: Exception) { ex.printStackTrace() }
                            }
                        }
                        val offerSdp = RTCSessionDescription(RTCSdpType.OFFER, event.sdp.sdp)
                        gcJail.add(offerSdp)
                        subPc?.setRemoteDescription(offerSdp, subSetRemoteObs!!)
                    }
                    is LiveKitSignalingClient.Event.TrickleReceived -> {
                        if (event.msg.target == 1) {
                            val (sdp, mid, idx) = parseCandidateJson(event.msg.candidateInit)
                            val candidate = RTCIceCandidate(mid, idx, sdp)
                            gcJail.add(candidate)
                            subPc?.addIceCandidate(candidate)
                        }
                    }
                    is LiveKitSignalingClient.Event.Disconnected -> {
                        println("[LiveKit] Subscriber: disconnected")
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Soft disconnect — ONLY closes the OkHttp WebSockets.
     *
     * NEVER call .close() or .dispose() on PeerConnections, factory, audio
     * sources/tracks, or gcJail contents.  The C++ engine runs async cleanup
     * threads; racing them with Java-side teardown → GC thread segfault
     * (jvm.dll+0x369b04).  Native objects are intentionally leaked; the JVM
     * process will clean them on exit.
     */
    fun leave() {
        try { pubSig?.disconnect() } catch (_: Exception) {}
        try { subSig?.disconnect() } catch (_: Exception) {}
        println("[LiveKit] leave(): WebSockets closed — native objects intentionally retained")
    }

    // =========================================================================
    // PUBLISHER PEER CONNECTION
    // =========================================================================

    private fun createPublisherPc(sig: LiveKitSignalingClient) {
        pubRtcConfig = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }

        pubPcObserver = object : PeerConnectionObserver {
            override fun onSignalingChange(s: RTCSignalingState) {
                try { println("[LiveKit] [PUB] onSignalingChange: $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onIceCandidate(c: RTCIceCandidate) {
                try {
                    gcJail.add(c)
                    scope.launch { sig.sendTrickle(candidateToJson(c), 0) }
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onIceCandidateError(e: RTCPeerConnectionIceErrorEvent) {
                try { println("[LiveKit] [PUB] onIceCandidateError: ${e.errorText} (${e.errorCode})") } catch (ex: Exception) { ex.printStackTrace() }
            }
            override fun onIceGatheringChange(s: RTCIceGatheringState) {
                try { println("[LiveKit] [PUB] onIceGatheringChange: $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onRenegotiationNeeded() {
                try {
                    println("[LiveKit] Publisher: onRenegotiationNeeded → sending offer")
                    scope.launch { publisherNegotiate(sig) }
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onIceConnectionChange(s: RTCIceConnectionState) {
                try { println("[LiveKit] Publisher ICE → $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onConnectionChange(s: RTCPeerConnectionState) {
                try { println("[LiveKit] Publisher PC  → $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onDataChannel(dc: RTCDataChannel) {
                try {
                    gcJail.add(dc)
                    val obs = object : RTCDataChannelObserver {
                        override fun onBufferedAmountChange(p: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(b: RTCDataChannelBuffer) {}
                    }
                    gcJail.add(obs)
                    dc.registerObserver(obs)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        pubPc = factory.createPeerConnection(pubRtcConfig!!, pubPcObserver!!) ?: run {
            println("[LiveKit] FATAL: createPeerConnection returned null (publisher)")
            return
        }

        attachMicrophone()
        println("[LiveKit] Publisher PC created")
    }

    // ── Microphone ───────────────────────────────────────────────────────────
    // "Studio Quality" Windows Desktop Audio Profile
    //
    // echoCancellation     ON  — AEC prevents speaker bleed into mic
    // noiseSuppression     ON  — light background noise reduction
    // autoGainControl      OFF — Windows hardware/driver AGC is superior; WebRTC
    //                            software AGC causes pumping/distortion on desktop
    // highpassFilter       OFF — HPF makes desktop mics sound thin, metallic,
    //                            robotic; low-frequency warmth preserved
    // typingDetection      OFF — causes extreme volume ducking on keystrokes,
    //                            leading to choppy/cut-out audio
    // residualEchoDetector ON  — secondary AEC pass, low overhead
    // ─────────────────────────────────────────────────────────────────────────
    private fun attachMicrophone() {
        try {
            val opts = AudioOptions().apply {
                echoCancellation     = true
                noiseSuppression     = true
                autoGainControl      = false
                highpassFilter       = false
                typingDetection      = false
                residualEchoDetector = true
            }
            pubAudioSource = factory.createAudioSource(opts)
            pubAudioTrack  = factory.createAudioTrack("mic_audio", pubAudioSource!!)
            // addTrack returns an RTCRtpSender — jail immediately; GC of sender
            // finalizer calls native delete while the RTP pipeline is active.
            pubPc?.addTrack(pubAudioTrack!!, listOf("microphone"))?.also { gcJail.add(it) }
            println("[LiveKit] Microphone attached (AEC+NS | AGC=off HPF=off Typing=off — Studio Profile)")
        } catch (e: Exception) {
            println("[LiveKit] attachMicrophone FAILED:")
            e.printStackTrace()
        }
    }

    // ── Offer with Opus SDP munge ────────────────────────────────────────────
    // DTX off → no silent gaps; 64 kbps → Discord-level quality; FEC → packet loss resilience
    private suspend fun publisherNegotiate(sig: LiveKitSignalingClient) {
        val pc    = pubPc ?: return
        val offer = createOffer(pc) ?: run { println("[LiveKit] createOffer returned null"); return }
        val mungedSdp   = mungeOpusSdp(offer.sdp)
        val mungedOffer = RTCSessionDescription(RTCSdpType.OFFER, mungedSdp)
        gcJail.add(mungedOffer)
        pubSetLocalDescObs = object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                try { println("[LiveKit] pub setLocalDescription — OK") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(e: String) {
                try { println("[LiveKit] pub setLocalDescription — FAILED: $e") } catch (ex: Exception) { ex.printStackTrace() }
            }
        }
        pc.setLocalDescription(mungedOffer, pubSetLocalDescObs!!)
        sig.sendOffer(mungedSdp)
        println("[LiveKit] Offer sent (Opus DTX=off, 64 kbps, FEC=on)")
    }

    // =========================================================================
    // SUBSCRIBER PEER CONNECTION
    // =========================================================================

    private fun createSubscriberPc(sig: LiveKitSignalingClient) {
        subRtcConfig = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }

        subPcObserver = object : PeerConnectionObserver {
            override fun onSignalingChange(s: RTCSignalingState) {
                try { println("[LiveKit] [SUB] onSignalingChange: $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onIceCandidate(c: RTCIceCandidate) {
                try {
                    gcJail.add(c)
                    scope.launch { sig.sendTrickle(candidateToJson(c), 1) }
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onIceCandidateError(e: RTCPeerConnectionIceErrorEvent) {
                try { println("[LiveKit] [SUB] onIceCandidateError: ${e.errorText} (${e.errorCode})") } catch (ex: Exception) { ex.printStackTrace() }
            }
            override fun onIceGatheringChange(s: RTCIceGatheringState) {
                try { println("[LiveKit] [SUB] onIceGatheringChange: $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onIceConnectionChange(s: RTCIceConnectionState) {
                try { println("[LiveKit] Subscriber ICE → $s") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onConnectionChange(s: RTCPeerConnectionState) {
                try { println("[LiveKit] Subscriber PC  → $s") } catch (e: Exception) { e.printStackTrace() }
            }

            // Jail all JNI wrappers delivered by C++ callbacks — they have no
            // existing strong ref on the Java side and are immediately GC-eligible
            // unless we retain them here.
            override fun onTrack(transceiver: RTCRtpTransceiver) {
                try {
                    gcJail.add(transceiver)
                    val receiver = transceiver.receiver ?: return
                    gcJail.add(receiver)
                    val track = try { receiver.getTrack() } catch (_: Exception) { null } ?: return
                    gcJail.add(track)
                    println("[LiveKit] onTrack: ${track.javaClass.simpleName} id=${track.id} (audio-only mode, no sink attached)")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<dev.onvoid.webrtc.media.MediaStream>) {
                try {
                    gcJail.add(receiver)
                    streams.forEach { gcJail.add(it) }
                    val track = try { receiver.getTrack() } catch (_: Exception) { null } ?: return
                    gcJail.add(track)
                    println("[LiveKit] onAddTrack: ${track.javaClass.simpleName} id=${track.id} (audio-only mode, no sink attached)")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onRemoveTrack(receiver: RTCRtpReceiver) {
                try {
                    // No video sinks to detach in audio-only mode.
                    // Jail the receiver so GC doesn't race C++ teardown.
                    gcJail.add(receiver)
                    println("[LiveKit] onRemoveTrack (audio-only, no action needed)")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onDataChannel(dc: RTCDataChannel) {
                try {
                    gcJail.add(dc)
                    val obs = object : RTCDataChannelObserver {
                        override fun onBufferedAmountChange(p: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(b: RTCDataChannelBuffer) {}
                    }
                    gcJail.add(obs)
                    dc.registerObserver(obs)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        subPc = factory.createPeerConnection(subRtcConfig!!, subPcObserver!!) ?: run {
            println("[LiveKit] FATAL: createPeerConnection returned null (subscriber)")
            return
        }
        println("[LiveKit] Subscriber PC created")
    }

    private suspend fun subscriberAnswer(sig: LiveKitSignalingClient) {
        val pc     = subPc ?: return
        val answer = createAnswer(pc) ?: run { println("[LiveKit] createAnswer returned null"); return }
        subSetLocalDescObs = object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                try { println("[LiveKit] sub setLocalDescription — OK") } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(e: String) {
                try { println("[LiveKit] sub setLocalDescription — FAILED: $e") } catch (ex: Exception) { ex.printStackTrace() }
            }
        }
        pc.setLocalDescription(answer, subSetLocalDescObs!!)
        sig.sendAnswer(answer.sdp)
        println("[LiveKit] Subscriber answer sent")
    }

    // =========================================================================
    // SUSPEND HELPERS
    // =========================================================================

    private suspend fun createOffer(pc: RTCPeerConnection): RTCSessionDescription? =
        suspendCancellableCoroutine { cont ->
            pubCreateOfferObs = object : CreateSessionDescriptionObserver {
                override fun onSuccess(d: RTCSessionDescription) {
                    try {
                        gcJail.add(d)
                        pubCreateOfferObs = null
                        cont.resumeWith(Result.success(d))
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(e: String) {
                    try {
                        println("[LiveKit] createOffer FAILED: $e")
                        pubCreateOfferObs = null
                        cont.resumeWith(Result.success(null))
                    } catch (ex: Exception) { ex.printStackTrace() }
                }
            }
            pc.createOffer(RTCOfferOptions(), pubCreateOfferObs!!)
        }

    private suspend fun createAnswer(pc: RTCPeerConnection): RTCSessionDescription? =
        suspendCancellableCoroutine { cont ->
            subCreateAnswerObs = object : CreateSessionDescriptionObserver {
                override fun onSuccess(d: RTCSessionDescription) {
                    try {
                        gcJail.add(d)
                        subCreateAnswerObs = null
                        cont.resumeWith(Result.success(d))
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(e: String) {
                    try {
                        println("[LiveKit] createAnswer FAILED: $e")
                        subCreateAnswerObs = null
                        cont.resumeWith(Result.success(null))
                    } catch (ex: Exception) { ex.printStackTrace() }
                }
            }
            pc.createAnswer(RTCAnswerOptions(), subCreateAnswerObs!!)
        }

    // =========================================================================
    // SDP MUNGE — Opus voice quality tuning
    // DTX=off    → no packet gaps during silence (removes choppiness)
    // 64 kbps    → Discord-level audio quality
    // FEC=on     → single-packet-loss correction
    // minptime=10→ 10 ms packet duration (Discord default)
    // =========================================================================
    private fun mungeOpusSdp(sdp: String): String {
        val pt = Regex("""a=rtpmap:(\d+) opus/48000""").find(sdp)
            ?.groupValues?.get(1)
            ?: return sdp.also { println("[LiveKit] SDP munge: Opus PT not found") }
        val newFmtp   = "a=fmtp:$pt minptime=10;useinbandfec=1;usedtx=0;maxaveragebitrate=64000"
        val fmtpRegex = Regex("""a=fmtp:$pt [^\r\n]*""")
        return if (fmtpRegex.containsMatchIn(sdp))
            fmtpRegex.replace(sdp, newFmtp)
        else
            Regex("""(a=rtpmap:$pt [^\r\n]*)""").replace(sdp) { "${it.value}\r\n$newFmtp" }
    }

    // =========================================================================
    // ICE SERVER HELPER
    // =========================================================================
    private fun LKICEServer.toRTC(): RTCIceServer {
        val s = RTCIceServer()
        s.urls     = urls
        s.username = username
        s.password = credential
        return s
    }
}
