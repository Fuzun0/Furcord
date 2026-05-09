package com.furcord.livekit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.video.*
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.image.BufferedImage

object LiveKitRoom {

    val serverUrl:  String = System.getProperty("furcord.livekit.url",       "")
    val apiKey:     String = System.getProperty("furcord.livekit.apiKey",     "")
    val apiSecret:  String = System.getProperty("furcord.livekit.apiSecret",  "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _localFrame  = MutableStateFlow<ImageBitmap?>(null)
    private val _remoteFrame = MutableStateFlow<ImageBitmap?>(null)
    val localFrame:  StateFlow<ImageBitmap?> = _localFrame.asStateFlow()
    val remoteFrame: StateFlow<ImageBitmap?> = _remoteFrame.asStateFlow()

    @Volatile var isPublishing: Boolean = false
        private set

    // Single PeerConnectionFactory (JNI native — not thread-safe to create multiple)
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

    private var pubSig:    LiveKitSignalingClient? = null
    private var subSig:    LiveKitSignalingClient? = null
    private var pubPc:     RTCPeerConnection?      = null
    private var subPc:     RTCPeerConnection?      = null
    private var desktopSrc: VideoDesktopSource?   = null
    private var iceServers: List<RTCIceServer>     = emptyList()

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

            sig.events.collect { event ->
                when (event) {
                    is LiveKitSignalingClient.Event.Connected    -> { /* wait for Join */ }
                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        iceServers = event.response.iceServers.map { it.toRTC() }
                        createPublisherPc(sig)
                    }
                    is LiveKitSignalingClient.Event.AnswerReceived -> {
                        pubPc?.setRemoteDescription(
                            RTCSessionDescription(RTCSdpType.ANSWER, event.sdp.sdp),
                            noopSdpObserver()
                        )
                    }
                    is LiveKitSignalingClient.Event.TrickleReceived -> {
                        if (event.msg.target == 0) { // PUBLISHER
                            val (sdp, mid, idx) = parseCandidateJson(event.msg.candidateInit)
                            pubPc?.addIceCandidate(RTCIceCandidate(mid, idx, sdp))
                        }
                    }
                    is LiveKitSignalingClient.Event.Disconnected -> stopPublisher()
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

            sig.events.collect { event ->
                when (event) {
                    is LiveKitSignalingClient.Event.Connected    -> { /* wait for Join */ }
                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        iceServers = event.response.iceServers.map { it.toRTC() }
                        createSubscriberPc(sig)
                    }
                    is LiveKitSignalingClient.Event.OfferReceived -> {
                        subPc?.setRemoteDescription(
                            RTCSessionDescription(RTCSdpType.OFFER, event.sdp.sdp),
                            object : SetSessionDescriptionObserver {
                                override fun onSuccess() { scope.launch { subscriberAnswer(sig) } }
                                override fun onFailure(e: String) {}
                            }
                        )
                    }
                    is LiveKitSignalingClient.Event.TrickleReceived -> {
                        if (event.msg.target == 1) { // SUBSCRIBER
                            val (sdp, mid, idx) = parseCandidateJson(event.msg.candidateInit)
                            subPc?.addIceCandidate(RTCIceCandidate(mid, idx, sdp))
                        }
                    }
                    is LiveKitSignalingClient.Event.Disconnected -> stopSubscriber()
                    else -> {}
                }
            }
        }
    }

    fun stopPublisher() {
        desktopSrc?.stop()
        desktopSrc?.dispose()
        desktopSrc = null
        pubPc?.close();     pubPc  = null
        pubSig?.disconnect(); pubSig = null
        isPublishing = false
        _localFrame.value = null
    }

    fun stopSubscriber() {
        subPc?.close();     subPc  = null
        subSig?.disconnect(); subSig = null
        _remoteFrame.value = null
    }

    fun leave() {
        stopPublisher()
        stopSubscriber()
    }

    // =========================================================================
    // PUBLISHER
    // =========================================================================

    private fun createPublisherPc(sig: LiveKitSignalingClient) {
        val config = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }

        // PeerConnectionObserver is a Java interface — use object expression (no parens)
        pubPc = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                sig.sendTrickle(candidateToJson(candidate), 0) // 0 = PUBLISHER
            }
            override fun onRenegotiationNeeded() {
                scope.launch { publisherNegotiate(sig) }
            }
        }) ?: return

        attachDesktopSource(sig)
        isPublishing = true
    }

    private fun attachDesktopSource(sig: LiveKitSignalingClient) {
        // ScreenCapturer lists available screens; use first one
        val capturer = ScreenCapturer()
        val sources  = capturer.getDesktopSources()
        val source   = sources.firstOrNull() ?: run { capturer.dispose(); return }
        capturer.dispose() // We only needed the source ID

        val src = VideoDesktopSource().also { desktopSrc = it }
        src.setSourceId(source.id, false) // false = screen (not window)
        src.setFrameRate(30)
        src.setMaxFrameSize(1280, 720)

        val videoTrack = factory.createVideoTrack("screen_video", src)

        // Local preview via VideoTrackSink
        videoTrack.addSink(object : VideoTrackSink {
            override fun onVideoFrame(frame: VideoFrame) {
                _localFrame.value = frame.toImageBitmap()
            }
        })

        pubPc?.addTrack(videoTrack, listOf("screen"))
        sig.sendAddTrack(videoTrack.id, "screen", type = 1, source = 3)
        src.start()
    }

    private suspend fun publisherNegotiate(sig: LiveKitSignalingClient) {
        val pc    = pubPc ?: return
        val offer = createOffer(pc) ?: return
        pc.setLocalDescription(offer, noopSdpObserver())
        sig.sendOffer(offer.sdp)
    }

    // =========================================================================
    // SUBSCRIBER
    // =========================================================================

    private fun createSubscriberPc(sig: LiveKitSignalingClient) {
        val config = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }

        subPc = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                sig.sendTrickle(candidateToJson(candidate), 1) // 1 = SUBSCRIBER
            }
            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver?.track ?: return
                if (track is VideoTrack) {
                    track.addSink(object : VideoTrackSink {
                        override fun onVideoFrame(frame: VideoFrame) {
                            _remoteFrame.value = frame.toImageBitmap()
                        }
                    })
                }
            }
        }) ?: return
    }

    private suspend fun subscriberAnswer(sig: LiveKitSignalingClient) {
        val pc     = subPc ?: return
        val answer = createAnswer(pc) ?: return
        pc.setLocalDescription(answer, noopSdpObserver())
        sig.sendAnswer(answer.sdp)
    }

    // =========================================================================
    // SUSPEND HELPERS
    // =========================================================================

    private suspend fun createOffer(pc: RTCPeerConnection): RTCSessionDescription? =
        suspendCancellableCoroutine { cont ->
            pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(d: RTCSessionDescription) =
                    cont.resumeWith(Result.success(d))
                override fun onFailure(e: String) =
                    cont.resumeWith(Result.success(null))
            })
        }

    private suspend fun createAnswer(pc: RTCPeerConnection): RTCSessionDescription? =
        suspendCancellableCoroutine { cont ->
            pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(d: RTCSessionDescription) =
                    cont.resumeWith(Result.success(d))
                override fun onFailure(e: String) =
                    cont.resumeWith(Result.success(null))
            })
        }

    private fun noopSdpObserver() = object : SetSessionDescriptionObserver {
        override fun onSuccess() {}
        override fun onFailure(e: String) {}
    }

    // =========================================================================
    // IMAGE CONVERSION
    // =========================================================================

    /** VideoFrame (I420) → Compose ImageBitmap */
    private fun VideoFrame.toImageBitmap(): ImageBitmap? = runCatching {
        val buf = buffer as? I420Buffer ?: return@runCatching null
        val w = buf.width; val h = buf.height
        val argb = IntArray(w * h)
        val dataY = buf.dataY;   val dataU = buf.dataU;   val dataV = buf.dataV
        val strY  = buf.strideY; val strU  = buf.strideU; val strV  = buf.strideV
        for (row in 0 until h) {
            for (col in 0 until w) {
                val Y = (dataY.get(row * strY + col).toInt() and 0xFF) - 16
                val U = (dataU.get((row / 2) * strU + (col / 2)).toInt() and 0xFF) - 128
                val V = (dataV.get((row / 2) * strV + (col / 2)).toInt() and 0xFF) - 128
                val r = (1.164 * Y + 1.596 * V).toInt().coerceIn(0, 255)
                val g = (1.164 * Y - 0.391 * U - 0.813 * V).toInt().coerceIn(0, 255)
                val b = (1.164 * Y + 2.018 * U).toInt().coerceIn(0, 255)
                argb[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, w, h, argb, 0, w)
        bi.toComposeImageBitmap()
    }.getOrNull()

    // =========================================================================
    // ICE SERVER HELPER
    // =========================================================================

    /** LKICEServer (proto) → RTCIceServer (webrtc-java) */
    private fun LKICEServer.toRTC(): RTCIceServer {
        val server = RTCIceServer()
        server.urls     = urls          // List<String> — matches RTCIceServer.urls type
        server.username = username
        server.password = credential    // webrtc-java uses 'password', our proto has 'credential'
        return server
    }
}
