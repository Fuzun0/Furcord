package com.furcord.livekit

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Audio-only LiveKit room â€” v1.0.59 tam yeniden yazÄ±m.
 *
 * TEMEL KURALLAR:
 *  - JNI wrappers ASLA dispose/close edilmez â†’ C++ native thread'leri async Ã§alÄ±ÅŸÄ±r,
 *    Java-side teardown yarÄ±ÅŸ yaratÄ±r â†’ crash (jvm.dll+0x369b04).
 *  - C++'Ä±n raw pointer tuttuÄŸu tÃ¼m nesneler [gcJail]'de kalÄ±r.
 *  - SDP munge YOK â€” WebRTC'nin default Opus ayarlarÄ± (useinbandfec, uygun bitrate)
 *    zaten iyidir; elle mÃ¼dahale geÃ§miÅŸte sorun yarattÄ±.
 *  - AudioOptions default â€” WebRTC'nin dengeli AEC/NS/AGC'si en iyi starting point.
 */
object LiveKitRoom {

    val serverUrl: String = System.getProperty("furcord.livekit.url",      "")
    val apiKey:    String = System.getProperty("furcord.livekit.apiKey",    "")
    val apiSecret: String = System.getProperty("furcord.livekit.apiSecret", "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // â”€â”€ GC Jail â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // C++'Ä±n raw pointer tuttuÄŸu her JNI wrapper nesnesi burada Ã¶mÃ¼r boyu yaÅŸar.
    // gcJail.clear() ASLA Ã§aÄŸrÄ±lmaz.
    private val gcJail = CopyOnWriteArrayList<Any>()

    // â”€â”€ WebRTC Factory â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

    // â”€â”€ Session state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private var sig: LiveKitSignalingClient?   = null
    private var pubPc: RTCPeerConnection?      = null
    private var subPc: RTCPeerConnection?      = null
    private var iceServers: List<RTCIceServer> = emptyList()

    // RTCConfiguration class-level olmalÄ± â€” createPeerConnection sonrasÄ± da
    // C++ ICE listesini okur; local var GC'ye yenik dÃ¼ÅŸer.
    private var pubCfg: RTCConfiguration? = null
    private var subCfg: RTCConfiguration? = null

    // Ses nesneleri
    private var audioSource: AudioTrackSource? = null
    private var audioTrack:  AudioTrack?       = null

    // PeerConnectionObserver'lar class-level: C++ raw pointer tutar
    private var pubObs: PeerConnectionObserver? = null
    private var subObs: PeerConnectionObserver? = null

    // ICE aday tamponlarÄ± â€” setRemoteDescription tamamlanmadan Ã¶nce gelen adaylar
    @Volatile private var pubRemoteSet = false
    @Volatile private var subRemoteSet = false
    private val pubCandBuf = mutableListOf<RTCIceCandidate>()
    private val subCandBuf = mutableListOf<RTCIceCandidate>()

    // Renegotiation guard: ilk offer gÃ¶nderildikten sonra tekrar gÃ¶nderme
    @Volatile private var offerSent = false

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun join(roomName: String, identity: String, displayName: String) {
        scope.launch {
            val token = LiveKitTokenGenerator.generateToken(
                apiKey, apiSecret, roomName, identity, displayName,
                canPublish = true, canSubscribe = true
            )
            sig = LiveKitSignalingClient(serverUrl, token).also { it.connect() }
            println("[LK] BaÄŸlanÄ±yor â†’ room=$roomName  id=$identity")

            sig!!.events.collect { ev ->
                when (ev) {
                    is LiveKitSignalingClient.Event.Connected ->
                        println("[LK] WS baÄŸlandÄ±")

                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        println("[LK] Join â€” ${ev.response.iceServers.size} ICE sunucu")
                        iceServers = ev.response.iceServers.map { it.toRTC() }
                        setupPub()
                        setupSub()
                    }

                    // Server bizim offer'Ä±mÄ±za cevap verdi (publisher yolu)
                    is LiveKitSignalingClient.Event.AnswerReceived ->
                        applyPubAnswer(ev.sdp.sdp)

                    // Server bize offer gÃ¶nderdi (subscriber yolu)
                    is LiveKitSignalingClient.Event.OfferReceived ->
                        applySubOffer(ev.sdp.sdp)

                    is LiveKitSignalingClient.Event.TrickleReceived ->
                        handleTrickle(ev.msg)

                    is LiveKitSignalingClient.Event.Disconnected ->
                        println("[LK] WS baÄŸlantÄ±sÄ± kesildi")

                    else -> {}
                }
            }
        }
    }

    /**
     * Odadan ayrÄ±l â€” YALNIZCA WebSocket kapatÄ±lÄ±r.
     * Native nesneler kasÄ±tlÄ± olarak serbest bÄ±rakÄ±lmaz.
     */
    fun leave() {
        pubRemoteSet = false
        subRemoteSet = false
        offerSent    = false
        synchronized(pubCandBuf) { pubCandBuf.clear() }
        synchronized(subCandBuf) { subCandBuf.clear() }
        try { sig?.disconnect() } catch (_: Exception) {}
        println("[LK] leave() â€” WS kapatÄ±ldÄ±")
    }

    // â”€â”€ Publisher PC â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun setupPub() {
        pubCfg = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }
        pubObs = object : PeerConnectionObserver {
            override fun onSignalingChange(s: RTCSignalingState)          = println("[LK][PUB] sig=$s")
            override fun onIceGatheringChange(s: RTCIceGatheringState)    = println("[LK][PUB] gather=$s")
            override fun onIceCandidateError(e: RTCPeerConnectionIceErrorEvent) =
                println("[LK][PUB] ICE hata: ${e.errorText} (${e.errorCode})")

            override fun onIceCandidate(c: RTCIceCandidate) {
                gcJail.add(c)
                scope.launch { sig?.sendTrickle(candidateToJson(c), 0) }
            }

            override fun onIceConnectionChange(s: RTCIceConnectionState) {
                println("[LK][PUB] ICE=$s")
                if (s == RTCIceConnectionState.FAILED) {
                    println("[LK][PUB] ICE FAILED â†’ yeniden baÅŸlatÄ±lÄ±yor")
                    offerSent = false
                    pubPc?.restartIce()
                }
            }

            override fun onConnectionChange(s: RTCPeerConnectionState) = println("[LK][PUB] PC=$s")

            override fun onRenegotiationNeeded() {
                if (offerSent) {
                    println("[LK][PUB] onRenegotiationNeeded engellendi")
                    return
                }
                offerSent = true
                scope.launch { sendPubOffer() }
            }

            override fun onDataChannel(dc: RTCDataChannel) {
                gcJail.add(dc)
                val obs = object : RTCDataChannelObserver {
                    override fun onBufferedAmountChange(p: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(b: RTCDataChannelBuffer) {}
                }
                gcJail.add(obs)
                dc.registerObserver(obs)
            }
        }

        pubPc = factory.createPeerConnection(pubCfg!!, pubObs!!) ?: run {
            println("[LK] HATA: pubPc null")
            return
        }

        // Mikrofon â€” WebRTC varsayÄ±lan AudioOptions (AEC+NS+AGC dengeli)
        audioSource = factory.createAudioSource(AudioOptions())
        audioTrack  = factory.createAudioTrack("audio", audioSource!!)
        pubPc!!.addTrack(audioTrack!!, listOf("audio"))?.also { gcJail.add(it) }
        println("[LK] Publisher PC hazÄ±r")
    }

    private suspend fun sendPubOffer() {
        val pc    = pubPc ?: return
        val offer = awaitCreateSdp { obs -> pc.createOffer(RTCOfferOptions(), obs) } ?: return
        val setLocalObs = jailedSetObs("[LK][PUB] setLocal")
        pc.setLocalDescription(offer, setLocalObs)
        sig?.sendOffer(offer.sdp)
        println("[LK] Offer gÃ¶nderildi")
    }

    private fun applyPubAnswer(sdp: String) {
        val pc = pubPc ?: return
        val obs = object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                println("[LK][PUB] setRemote OK")
                pubRemoteSet = true
                synchronized(pubCandBuf) {
                    if (pubCandBuf.isNotEmpty()) {
                        println("[LK][PUB] ${pubCandBuf.size} bekleyen ICE adayÄ± ekleniyor")
                        pubCandBuf.forEach { pc.addIceCandidate(it) }
                        pubCandBuf.clear()
                    }
                }
            }
            override fun onFailure(e: String) = println("[LK][PUB] setRemote HATA: $e")
        }
        gcJail.add(obs)
        pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, sdp).also { gcJail.add(it) }, obs)
    }

    // â”€â”€ Subscriber PC â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun setupSub() {
        subCfg = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }
        subObs = object : PeerConnectionObserver {
            override fun onSignalingChange(s: RTCSignalingState)          = println("[LK][SUB] sig=$s")
            override fun onIceGatheringChange(s: RTCIceGatheringState)    = println("[LK][SUB] gather=$s")
            override fun onIceCandidateError(e: RTCPeerConnectionIceErrorEvent) =
                println("[LK][SUB] ICE hata: ${e.errorText} (${e.errorCode})")

            override fun onIceCandidate(c: RTCIceCandidate) {
                gcJail.add(c)
                scope.launch { sig?.sendTrickle(candidateToJson(c), 1) }
            }

            override fun onIceConnectionChange(s: RTCIceConnectionState) = println("[LK][SUB] ICE=$s")
            override fun onConnectionChange(s: RTCPeerConnectionState)   = println("[LK][SUB] PC=$s")

            override fun onTrack(t: RTCRtpTransceiver) {
                gcJail.add(t)
                t.receiver?.also { r ->
                    gcJail.add(r)
                    try { r.getTrack()?.also { gcJail.add(it) } } catch (_: Exception) {}
                }
                println("[LK][SUB] onTrack")
            }

            override fun onAddTrack(r: RTCRtpReceiver, s: Array<dev.onvoid.webrtc.media.MediaStream>) {
                gcJail.add(r)
                s.forEach { gcJail.add(it) }
                try { r.getTrack()?.also { gcJail.add(it) } } catch (_: Exception) {}
                println("[LK][SUB] onAddTrack")
            }

            override fun onRemoveTrack(r: RTCRtpReceiver) {
                gcJail.add(r)
                println("[LK][SUB] onRemoveTrack")
            }

            override fun onDataChannel(dc: RTCDataChannel) {
                gcJail.add(dc)
                val obs = object : RTCDataChannelObserver {
                    override fun onBufferedAmountChange(p: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(b: RTCDataChannelBuffer) {}
                }
                gcJail.add(obs)
                dc.registerObserver(obs)
            }
        }

        subPc = factory.createPeerConnection(subCfg!!, subObs!!) ?: run {
            println("[LK] HATA: subPc null")
            return
        }
        println("[LK] Subscriber PC hazÄ±r")
    }

    private fun applySubOffer(sdp: String) {
        val pc = subPc ?: return
        val obs = object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                println("[LK][SUB] setRemote OK â†’ answer oluÅŸturuluyor")
                subRemoteSet = true
                synchronized(subCandBuf) {
                    if (subCandBuf.isNotEmpty()) {
                        println("[LK][SUB] ${subCandBuf.size} bekleyen ICE adayÄ± ekleniyor")
                        subCandBuf.forEach { pc.addIceCandidate(it) }
                        subCandBuf.clear()
                    }
                }
                scope.launch { sendSubAnswer() }
            }
            override fun onFailure(e: String) = println("[LK][SUB] setRemote HATA: $e")
        }
        gcJail.add(obs)
        pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, sdp).also { gcJail.add(it) }, obs)
    }

    private suspend fun sendSubAnswer() {
        val pc     = subPc ?: return
        val answer = awaitCreateSdp { obs -> pc.createAnswer(RTCAnswerOptions(), obs) } ?: return
        val setLocalObs = jailedSetObs("[LK][SUB] setLocal")
        pc.setLocalDescription(answer, setLocalObs)
        sig?.sendAnswer(answer.sdp)
        println("[LK] Answer gÃ¶nderildi")
    }

    // â”€â”€ ICE trickle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun handleTrickle(msg: LKTrickleRequest) {
        val (sdp, mid, idx) = parseCandidateJson(msg.candidateInit)
        val c = RTCIceCandidate(mid, idx, sdp).also { gcJail.add(it) }
        when (msg.target) {
            0 -> if (pubRemoteSet) {
                pubPc?.addIceCandidate(c)
                println("[LK] ICE â†’ pubPc mid=$mid")
            } else {
                synchronized(pubCandBuf) { pubCandBuf.add(c) }
                println("[LK] ICE â†’ pubPc mid=$mid (tamponlandÄ±)")
            }
            1 -> if (subRemoteSet) {
                subPc?.addIceCandidate(c)
                println("[LK] ICE â†’ subPc mid=$mid")
            } else {
                synchronized(subCandBuf) { subCandBuf.add(c) }
                println("[LK] ICE â†’ subPc mid=$mid (tamponlandÄ±)")
            }
            else -> println("[LK] ICE bilinmeyen target=${msg.target}")
        }
    }

    // â”€â”€ YardÄ±mcÄ±lar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** createOffer/createAnswer iÃ§in coroutine wrapper. Observer gcJail'e eklenir. */
    private suspend fun awaitCreateSdp(
        create: (CreateSessionDescriptionObserver) -> Unit
    ): RTCSessionDescription? = suspendCancellableCoroutine { cont ->
        val obs = object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) {
                gcJail.add(d)
                cont.resumeWith(Result.success(d))
            }
            override fun onFailure(e: String) {
                println("[LK] SDP oluÅŸturma HATA: $e")
                cont.resumeWith(Result.success(null))
            }
        }
        gcJail.add(obs)
        create(obs)
    }

    /** setLocalDescription/setRemoteDescription iÃ§in gcJail'e alÄ±nmÄ±ÅŸ observer. */
    private fun jailedSetObs(tag: String): SetSessionDescriptionObserver =
        object : SetSessionDescriptionObserver {
            override fun onSuccess() = println("$tag OK")
            override fun onFailure(e: String) = println("$tag HATA: $e")
        }.also { gcJail.add(it) }

    private fun LKICEServer.toRTC(): RTCIceServer {
        val s = RTCIceServer()
        s.urls     = urls
        s.username = username
        s.password = credential
        return s
    }
}

