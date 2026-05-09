package com.furcord.livekit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.video.*
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.image.BufferedImage

object LiveKitRoom {

    val serverUrl:  String = System.getProperty("furcord.livekit.url",        "")
    val apiKey:     String = System.getProperty("furcord.livekit.apiKey",      "")
    val apiSecret:  String = System.getProperty("furcord.livekit.apiSecret",   "")

    // IO scope for signaling / networking
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Separate CPU-parallel scope for I420→ARGB conversion.
    // Keeping this off Dispatchers.IO prevents audio threads from being starved
    // by long-running native JNI calls.
    private val conversionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _localFrame  = MutableStateFlow<ImageBitmap?>(null)
    private val _remoteFrame = MutableStateFlow<ImageBitmap?>(null)
    val localFrame:  StateFlow<ImageBitmap?> = _localFrame.asStateFlow()
    val remoteFrame: StateFlow<ImageBitmap?> = _remoteFrame.asStateFlow()

    @Volatile var isPublishing: Boolean = false
        private set

    // Single factory — JNI not safe to construct more than once per process
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

    private var pubSig:     LiveKitSignalingClient? = null
    private var subSig:     LiveKitSignalingClient? = null
    private var pubPc:      RTCPeerConnection?      = null
    private var subPc:      RTCPeerConnection?      = null
    private var desktopSrc: VideoDesktopSource?     = null
    private var iceServers: List<RTCIceServer>      = emptyList()

    // Strong references prevent GC of VideoTrackSink instances passed to JNI.
    // webrtc-java holds only a weak/native ref internally; we must keep the
    // Kotlin object alive for the lifetime of the track.
    private val videoSinks = mutableListOf<VideoTrackSink>()

    // =========================================================================
    // OPTIMIZED VIDEO SINK FACTORY
    //
    // Problem with naïve approach:
    //   onVideoFrame() runs on the WebRTC decode thread.  A pure-Kotlin nested
    //   loop doing BT.601 float math (1280×720 = 921 600 pixels × 6 FP ops)
    //   takes ~20-40 ms, blocking the decode thread and starving audio threads.
    //
    // Solution:
    //   1. frame.retain()  — increment native ref-count so buffer survives after
    //                        the callback returns.
    //   2. Launch on Dispatchers.Default (bounded thread pool, not IO).
    //   3. buf.toI420()    — safe format normalisation (no-op for I420 sources,
    //                        converts NV12/NV21 etc. if the remote sends them).
    //   4. VideoBufferConverter.convertFromI420() — native JNI libyuv call,
    //                        10-20× faster than pure Kotlin math.
    //   5. Build IntArray → BufferedImage → ImageBitmap on the background thread.
    //   6. frame.release() — decrement ref-count in finally (always runs).
    // =========================================================================

    private fun makeVideoSink(target: MutableStateFlow<ImageBitmap?>): VideoTrackSink =
        object : VideoTrackSink {
            override fun onVideoFrame(frame: VideoFrame) {
                // Step 1 — retain BEFORE the coroutine so the buffer stays valid
                // across the thread boundary.
                frame.retain()

                val buf = frame.buffer
                val w   = buf.width
                val h   = buf.height

                conversionScope.launch {
                    var i420: I420Buffer? = null
                    try {
                        // Step 2 — normalize to planar I420 (no-op if already I420)
                        i420 = buf.toI420()

                        // Step 3 — native libyuv YUV→ARGB (10-20× faster than
                        //           pure-Kotlin BT.601 float math)
                        val bytes = ByteArray(w * h * 4)
                        VideoBufferConverter.convertFromI420(i420, bytes, FourCC.ARGB)

                        // Step 4 — repack [A,R,G,B,...] bytes → IntArray for
                        //           BufferedImage.setRGB().  This JVM loop has no FP
                        //           math and runs at ~1 ns/pixel (JIT-optimised).
                        val intArray = IntArray(w * h) { i ->
                            val o = i * 4
                            ((bytes[o    ].toInt() and 0xFF) shl 24) or
                            ((bytes[o + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[o + 2].toInt() and 0xFF) shl  8) or
                             (bytes[o + 3].toInt() and 0xFF)
                        }

                        // Step 5 — emit to Compose StateFlow
                        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        bi.setRGB(0, 0, w, h, intArray, 0, w)
                        target.value = bi.toComposeImageBitmap()

                    } catch (_: Exception) {
                        // Never let an exception escape here — it would silently
                        // cancel the conversionScope coroutine for all future frames.
                    } finally {
                        // Step 6 — always release (even on exception)
                        i420?.release()
                        frame.release()
                    }
                }
            }
        }.also { videoSinks.add(it) } // keep strong Kotlin ref

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
                        if (event.msg.target == 0) { // 0 = PUBLISHER side
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
                        if (event.msg.target == 1) { // 1 = SUBSCRIBER side
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
        pubPc?.close();       pubPc  = null
        pubSig?.disconnect(); pubSig = null
        isPublishing = false
        _localFrame.value = null
    }

    fun stopSubscriber() {
        subPc?.close();       subPc  = null
        subSig?.disconnect(); subSig = null
        videoSinks.clear()
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

        pubPc = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(c: RTCIceCandidate) {
                sig.sendTrickle(candidateToJson(c), 0)
            }
            override fun onRenegotiationNeeded() {
                scope.launch { publisherNegotiate(sig) }
            }
        }) ?: return

        attachDesktopSource(sig)
        attachMicrophone()
        isPublishing = true
    }

    private fun attachDesktopSource(sig: LiveKitSignalingClient) {
        val capturer = ScreenCapturer()
        val source   = capturer.getDesktopSources().firstOrNull()
            ?: run { capturer.dispose(); return }
        capturer.dispose()

        val src = VideoDesktopSource().also { desktopSrc = it }
        src.setSourceId(source.id, false) // false = entire screen, not window
        src.setFrameRate(30)
        src.setMaxFrameSize(1280, 720)

        val videoTrack = factory.createVideoTrack("screen_video", src)

        // Local preview uses the same optimised sink
        videoTrack.addSink(makeVideoSink(_localFrame))

        pubPc?.addTrack(videoTrack, listOf("screen"))
        sig.sendAddTrack(videoTrack.id, "screen", type = 1, source = 3)
        src.start()
    }

    /**
     * Attach microphone audio with hardware AEC / NS / HPF enabled.
     *
     * AudioOptions maps directly to googEchoCancellation / googNoiseSuppression /
     * googHighpassFilter / googAutoGainControl from the WebRTC constraint API.
     * On desktop JVM these are processed by the WebRTC software APM (no hardware
     * AEC conflict because the default AudioDeviceModule routes through it).
     */
    private fun attachMicrophone() {
        val audioOptions = AudioOptions().apply {
            echoCancellation    = true  // googEchoCancellation
            noiseSuppression    = true  // googNoiseSuppression
            highpassFilter      = true  // googHighpassFilter
            autoGainControl     = true  // googAutoGainControl
            residualEchoDetector = true // cleans up AEC residual artefacts
        }
        val audioSource = factory.createAudioSource(audioOptions)
        val audioTrack  = factory.createAudioTrack("mic_audio", audioSource)
        pubPc?.addTrack(audioTrack, listOf("microphone"))
    }

    private suspend fun publisherNegotiate(sig: LiveKitSignalingClient) {
        val pc    = pubPc ?: return
        val offer = createOffer(pc) ?: return
        pc.setLocalDescription(offer, noopSdpObserver())
        sig.sendOffer(offer.sdp)
    }

    // =========================================================================
    // SUBSCRIBER
    //
    // Two complementary callbacks handle incoming tracks:
    //
    //  onTrack()    — fires for each m-line in a unified-plan offer when
    //                 setRemoteDescription() succeeds.  This is the primary path
    //                 for LiveKit Cloud (SFU always uses unified-plan).
    //
    //  onAddTrack() — fires on the plan-b / legacy path and also fires
    //                 concurrently with onTrack() on some WebRTC builds.
    //                 We use it as a fallback.  Duplicate sinks on the same
    //                 track are harmless (VideoTrack deduplicates internally).
    // =========================================================================

    private fun createSubscriberPc(sig: LiveKitSignalingClient) {
        val config = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }

        subPc = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(c: RTCIceCandidate) {
                sig.sendTrickle(candidateToJson(c), 1)
            }

            // Primary path — unified-plan, one call per transceiver
            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver?.getTrack() ?: return
                if (track is VideoTrack) {
                    println("SCREEN SHARE TRACK SUBSCRIBED (onTrack)")
                    track.addSink(makeVideoSink(_remoteFrame))
                }
            }

            // Fallback — plan-b / concurrent with onTrack on older builds
            override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<MediaStream>) {
                val track = receiver.getTrack() ?: return
                if (track is VideoTrack) {
                    println("SCREEN SHARE TRACK SUBSCRIBED (onAddTrack)")
                    track.addSink(makeVideoSink(_remoteFrame))
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
    // ICE SERVER HELPER
    // =========================================================================

    private fun LKICEServer.toRTC(): RTCIceServer {
        val server = RTCIceServer()
        server.urls     = urls      // List<String> matches RTCIceServer.urls
        server.username = username
        server.password = credential // webrtc-java field is 'password'
        return server
    }
}
