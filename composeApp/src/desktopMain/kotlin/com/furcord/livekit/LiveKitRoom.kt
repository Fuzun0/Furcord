package com.furcord.livekit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.logging.LogSink
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.logging.Logging.Severity
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.*
import dev.onvoid.webrtc.media.video.*
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object LiveKitRoom {

    // =========================================================================
    // STEP 0 — NATIVE WEBRTC LOGGING
    //
    // Must run before ANY native WebRTC object is created (before factory,
    // before PeerConnection, before anything).  Routes C++ libwebrtc logs to
    // stdout so silent failures become visible.
    //
    // Logging.logToDebug(INFO):
    //   Tells the C++ layer to use the debug output channel.
    //   On Windows this is OutputDebugString(); piped to Java stdout via JNI.
    //
    // Logging.addLogSink(VERBOSE, ...):
    //   Adds our own Java-land sink so messages appear in the Compose terminal.
    //   Filter to WARNING in production; use VERBOSE for capturing device errors.
    // =========================================================================

    init {
        // ── CRASH-SAFE FILE LOGGER ────────────────────────────────────────────
        // Gradle daemon buffers stdout and loses it on a hard JVM crash (SIGSEGV /
        // 0xc0000005). We tee System.out to an auto-flushing file so every println
        // is on disk before the next JNI call, surviving the crash intact.
        // File location: <working-dir>/livekit-trace.log  (overwritten each run)
        try {
            val logFile = java.io.File("livekit-trace.log").also { it.createNewFile() }
            val fos     = FileOutputStream(logFile, false)   // false = overwrite on each run
            val tee     = object : java.io.OutputStream() {
                val original = System.out
                val file     = PrintStream(fos, /*autoFlush=*/true)
                override fun write(b: Int)                        { original.write(b);         file.write(b)         }
                override fun write(b: ByteArray)                  { original.write(b);         file.write(b)         }
                override fun write(b: ByteArray, off: Int, len: Int) { original.write(b,off,len); file.write(b,off,len) }
                override fun flush() { original.flush(); file.flush() }
            }
            System.setOut(PrintStream(tee, /*autoFlush=*/true))
            println("[LiveKit] TRACE FILE: ${logFile.absolutePath} (auto-flush, survives JVM crash)")
        } catch (e: Exception) {
            println("[LiveKit] WARNING: Could not open trace log file: ${e.message}")
        }

        // Enable C++ WebRTC logging immediately — before any native init
        Logging.logToDebug(Severity.INFO)
        Logging.logTimestamps(false)  // reduce noise
        Logging.logThreads(false)

        // Route native logs → our println so they show in Compose terminal
        Logging.addLogSink(Severity.WARNING, object : LogSink {
            override fun onLogMessage(severity: Severity, message: String) {
                println("[WebRTC-C++/${severity.name}] ${message.trimEnd()}")
            }
        })

        println("[LiveKit] Native WebRTC logging enabled (WARNING+ forwarded to stdout)")
    }

    val serverUrl:  String = System.getProperty("furcord.livekit.url",        "")
    val apiKey:     String = System.getProperty("furcord.livekit.apiKey",      "")
    val apiSecret:  String = System.getProperty("furcord.livekit.apiSecret",   "")

    private val scope           = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conversionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val robotScope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _localFrame  = MutableStateFlow<ImageBitmap?>(null)
    private val _remoteFrame = MutableStateFlow<ImageBitmap?>(null)
    val localFrame:  StateFlow<ImageBitmap?> = _localFrame.asStateFlow()
    val remoteFrame: StateFlow<ImageBitmap?> = _remoteFrame.asStateFlow()

    @Volatile var isPublishing: Boolean = false
        private set

    // PeerConnectionFactory — default constructor (native desktop ADM, built-in APM).
    // Do NOT pass custom AudioDeviceModule or AudioProcessing objects:
    // the webrtc-java desktop JNI layer manages its own native ADM (WASAPI on Windows)
    // and software APM internally. Injecting Java-constructed ADM/APM pointers causes
    // EXCEPTION_ACCESS_VIOLATION inside jvm.dll because the JNI bridge cannot validate
    // the lifetime of those objects across native thread boundaries.
    // Audio quality is still controlled via AudioOptions in attachMicrophone().
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory().also {
            println("[LiveKit] PeerConnectionFactory created (native default ADM+APM)")
        }
    }

    private var pubSig:    LiveKitSignalingClient? = null
    private var subSig:    LiveKitSignalingClient? = null
    private var pubPc:     RTCPeerConnection?      = null
    private var subPc:     RTCPeerConnection?      = null
    private var desktopSrc: VideoDesktopSource?    = null
    private var iceServers: List<RTCIceServer>     = emptyList()
    private var robotJob:  Job?                    = null
    // AtomicBoolean flag for graceful Robot loop exit.
    // NEVER use robotJob?.cancel() to stop the capture loop — cancelling a
    // coroutine while it is inside a native JNI call (createScreenCapture,
    // toComposeImageBitmap) tears down the thread stack mid-native-frame,
    // corrupting the C++ engine → 0xc0000005 EXCEPTION_ACCESS_VIOLATION.
    // Instead, set isCapturing = false and let the loop exit at its own pace.
    private val isCapturing = java.util.concurrent.atomic.AtomicBoolean(false)

    // =========================================================================
    // STRONG GLOBAL REFERENCES — JNI/GC SAFETY
    //
    // AudioSource, AudioTrack, and VideoTrack are thin JNI wrapper objects whose
    // finalizers call the native C++ destructor.  If they are local variables
    // inside a function, they become eligible for GC as soon as the function
    // returns — even though the native PeerConnection is still reading those
    // native pointers on its signalling/worker threads.
    // Result: EXCEPTION_ACCESS_VIOLATION (0xc0000005) in jvm.dll.
    //
    // FIX: Store every WebRTC wrapper as a class-level field for the full
    // duration of the connection.  Null them out ONLY in stop*() AFTER the
    // PeerConnection has been closed (so the native side is done with them).
    // =========================================================================

    // Publisher wrappers — held for the entire publisher session
    private var pubAudioSource: AudioTrackSource? = null
    private var pubAudioTrack:  AudioTrack?       = null
    private var pubVideoTrack:  VideoTrack?       = null

    // Subscriber video tracks from onTrack/onAddTrack — must outlive the PC
    private val subVideoTracks = mutableListOf<VideoTrack>()

    // =========================================================================
    // ACTIVE SINK REGISTRY — for safe removeSink() on stop
    //
    // The C++ VideoTrack holds a raw pointer to each registered VideoTrackSink.
    // If we null/GC the sink while C++ still holds the pointer, we get a
    // dangling-pointer crash (0xc0000005) when the next frame is delivered.
    //
    // FIX: store (track → sink) associations here.  On stop*, explicitly call
    // track.removeSink(sink) BEFORE releasing any strong references.  This tells
    // the C++ engine to drop its raw pointer while both objects are still alive.
    // Only after removeSink returns is it safe to let the sink become GC-eligible.
    // =========================================================================
    private var pubVideoSink: VideoTrackSink? = null
    // CopyOnWriteArrayList: safe to iterate while stopSubscriber modifies it
    private val subVideoSinkPairs =
        java.util.concurrent.CopyOnWriteArrayList<Pair<VideoTrack, VideoTrackSink>>()

    // =========================================================================
    // SDP / PC OBSERVER STRONG REFERENCES — GC TRAP FIX
    //
    // webrtc-java JNI observers are thin Java wrappers that the C++ engine holds
    // as raw pointers.  Callbacks fire asynchronously on native threads.  If the
    // JVM GC collects an observer between the JNI call and the callback, the C++
    // layer dereferences a dangling pointer → EXCEPTION_ACCESS_VIOLATION 0xc0000005.
    //
    // FIX: every observer passed to a native call is assigned to a class-level
    // field so it remains reachable from a GC root until the callback fires.
    // Fields are nulled ONLY after the PC is closed (in stop*()).
    // =========================================================================
    private var pubPcObserver:      PeerConnectionObserver?           = null
    private var subPcObserver:      PeerConnectionObserver?           = null
    private var pubCreateOfferObs:  CreateSessionDescriptionObserver? = null
    private var subCreateAnswerObs: CreateSessionDescriptionObserver? = null
    private var pubSetLocalDescObs: SetSessionDescriptionObserver?    = null
    private var pubSetRemoteObs:    SetSessionDescriptionObserver?    = null
    private var subSetLocalDescObs: SetSessionDescriptionObserver?    = null
    private var subSetRemoteObs:    SetSessionDescriptionObserver?    = null

    // DataChannel strong refs — GC would destroy the observer while C++ still holds it.
    // LiveKit Cloud always creates _reliable and _lossy data channels on the publisher PC.
    private val pubDataChannels      = mutableListOf<RTCDataChannel>()
    private val subDataChannels      = mutableListOf<RTCDataChannel>()
    private val dataChannelObservers = mutableListOf<RTCDataChannelObserver>()

    // RTCConfiguration MUST be class-level. It is a JNI wrapper; if declared as a
    // local variable it is eligible for GC the moment createPeerConnection() returns,
    // while the C++ PeerConnection is still reading the ICEServer list from it.
    // GC Thread#6 finalizing this object -> EXCEPTION_ACCESS_VIOLATION in jvm.dll.
    private var pubRtcConfig: RTCConfiguration? = null
    private var subRtcConfig: RTCConfiguration? = null

    // Strong Kotlin refs prevent GC of JNI-held VideoTrackSink instances
    private val videoSinks = mutableListOf<VideoTrackSink>()

    // =========================================================================
    // GC JAIL — anti-GC collection for incoming JNI wrapper objects
    //
    // When the C++ WebRTC engine fires a callback (onIceCandidate, onDataChannel,
    // onTrack, onAddTrack, etc.) it allocates a NEW Java wrapper object around a
    // raw C++ pointer and passes it as the callback parameter.  Kotlin sees this
    // object for the first time at the call site — it has no existing strong ref.
    // If the function returns without storing it somewhere, the object immediately
    // becomes GC-eligible.  The GC finalizer calls native `delete` on the C++
    // pointer while the PeerConnection is still reading it → 0xc0000005 double-free.
    //
    // Same risk applies to RTCSessionDescription / RTCIceCandidate objects we
    // construct in Kotlin and pass into setRemoteDescription / addIceCandidate:
    // C++ keeps a raw pointer to them across a thread boundary; if GC runs between
    // the Kotlin call and the C++ read we get the same crash.
    //
    // FIX: add EVERY such object to gcJail before passing it anywhere.
    // The jail is cleared only after the PeerConnection is fully closed in stop*().
    // =========================================================================
    private val gcJail = java.util.concurrent.CopyOnWriteArrayList<Any>()

    // =========================================================================
    // CONFLATED VIDEO SINK — prevents frame pile-up and native memory leaks
    // =========================================================================

    private fun makeVideoSink(
        label:  String,
        target: MutableStateFlow<ImageBitmap?>
    ): VideoTrackSink {
        val pending     = AtomicReference<VideoFrame?>(null)
        val firstLogged = AtomicBoolean(false)

        conversionScope.launch {
            while (isActive) {
                val frame = pending.getAndSet(null)
                if (frame == null) { delay(1L); continue }
                try {
                    val bmp = convertFrameToBitmap(frame)
                    if (bmp != null) {
                        if (firstLogged.compareAndSet(false, true))
                            println("[LiveKit] [$label] First decoded frame: ${frame.buffer.width}×${frame.buffer.height}")
                        target.value = bmp
                    }
                } catch (e: Exception) {
                    println("[LiveKit] [$label] Conversion error: ${e.message}")
                } finally {
                    frame.release()
                }
            }
        }

        return object : VideoTrackSink {
            override fun onVideoFrame(frame: VideoFrame) {
                // Full try/catch: an exception thrown through a JNI callback
                // unwinds the C++ call stack → immediate 0xc0000005 crash.
                // frame.release() in the catch path prevents a native memory
                // leak if retain() succeeded but the getAndSet throws.
                try {
                    frame.retain()
                    pending.getAndSet(frame)?.release()
                } catch (e: Exception) {
                    println("[LiveKit] [$label] onVideoFrame error: ${e.message}")
                    try { frame.release() } catch (_: Exception) {}
                }
            }
        }.also {
            videoSinks.add(it)   // strong ref: prevents premature GC of the wrapper
            gcJail.add(it)       // belt-and-suspenders: also held in gcJail
        }
    }

    // =========================================================================
    // I420 → ARGB  (native JNI libyuv — ~10-20× faster than pure Kotlin)
    //
    // FourCC.ARGB byte order in memory: [B, G, R, A] per pixel (little-endian).
    // Reading those 4 bytes as a LITTLE_ENDIAN int32 gives 0xAARRGGBB, which
    // matches BufferedImage.TYPE_INT_ARGB exactly.  This is a zero-copy
    // reinterpretation; no per-pixel loop is needed.
    // =========================================================================

    private fun convertFrameToBitmap(frame: VideoFrame): ImageBitmap? {
        val buf = frame.buffer
        val w   = buf.width;  val h = buf.height
        if (w <= 0 || h <= 0) return null

        var i420: I420Buffer? = null
        return try {
            i420 = buf.toI420()
            val bytes = ByteArray(w * h * 4)
            VideoBufferConverter.convertFromI420(i420, bytes, FourCC.ARGB)

            val intBuf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val intArray = IntArray(w * h).also { intBuf.get(it) }

            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, w, h, intArray, 0, w)
            bi.toComposeImageBitmap()
        } catch (e: Exception) {
            println("[LiveKit] convertFrameToBitmap error: ${e.message}")
            null
        } finally {
            i420?.release()
        }
    }

    // =========================================================================
    // SDP MUNGE — disable Opus DTX, raise bitrate, enable FEC
    //
    // usedtx=0              DTX off — no silent packet gaps → no choppiness
    // maxaveragebitrate=64000 — 64 kbps Opus (vs ~16 kbps default)
    // useinbandfec=1        — single-packet-loss correction via FEC
    // minptime=10           — 10 ms minimum packet duration (Discord default)
    // =========================================================================

    private fun mungeOpusSdp(sdp: String): String {
        val pt = Regex("""a=rtpmap:(\d+) opus/48000""").find(sdp)
            ?.groupValues?.get(1)
            ?: return sdp.also { println("[LiveKit] SDP munge: Opus payload type not found") }

        println("[LiveKit] SDP munge: Opus PT=$pt — DTX=off, 64kbps, FEC=on")
        val newFmtp   = "a=fmtp:$pt minptime=10;useinbandfec=1;usedtx=0;maxaveragebitrate=64000"
        val fmtpRegex = Regex("""a=fmtp:$pt [^\r\n]*""")
        return if (fmtpRegex.containsMatchIn(sdp))
            fmtpRegex.replace(sdp, newFmtp)
        else
            Regex("""(a=rtpmap:$pt [^\r\n]*)""")
                .replace(sdp) { "${it.value}\r\n$newFmtp" }
    }

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
                        println(">>> TRACE [PUB] WebSocket Connected")
                        println("[LiveKit] Publisher: WebSocket connected")
                        println("<<< TRACE [PUB] WebSocket Connected handled")
                    }
                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        println(">>> TRACE [PUB] JoinReceived — ${event.response.iceServers.size} ICE server(s)")
                        println("[LiveKit] Publisher: Join ack — ${event.response.iceServers.size} ICE server(s)")
                        iceServers = event.response.iceServers.map { it.toRTC() }
                        println(">>> TRACE [PUB] Calling createPublisherPc")
                        createPublisherPc(sig)
                        println("<<< TRACE [PUB] createPublisherPc returned")
                    }
                    is LiveKitSignalingClient.Event.AnswerReceived -> {
                        println(">>> TRACE [PUB] AnswerReceived (sdp length=${event.sdp.sdp.length})")
                        println("[LiveKit] Publisher: Answer received")
                        pubSetRemoteObs = object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                try {
                                    println(">>> TRACE [PUB] setRemoteDescription.onSuccess")
                                    println("[LiveKit] pub setRemoteDescription — OK")
                                    println("<<< TRACE [PUB] setRemoteDescription.onSuccess handled")
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            override fun onFailure(e: String) {
                                try {
                                    println(">>> TRACE [PUB] setRemoteDescription.onFailure: $e")
                                    println("[LiveKit] pub setRemoteDescription — FAILED: $e")
                                    println("<<< TRACE [PUB] setRemoteDescription.onFailure handled")
                                } catch (ex: Exception) { ex.printStackTrace() }
                            }
                        }
                        val answerSdp = RTCSessionDescription(RTCSdpType.ANSWER, event.sdp.sdp)
                        gcJail.add(answerSdp)
                        println(">>> TRACE [PUB] Calling pubPc.setRemoteDescription (ANSWER)")
                        pubPc?.setRemoteDescription(answerSdp, pubSetRemoteObs!!)
                        println("<<< TRACE [PUB] pubPc.setRemoteDescription call returned (async)")
                    }
                    is LiveKitSignalingClient.Event.TrickleReceived -> {
                        if (event.msg.target == 0) {
                            println(">>> TRACE [PUB] TrickleReceived (target=0)")
                            val (sdp, mid, idx) = parseCandidateJson(event.msg.candidateInit)
                            val candidate = RTCIceCandidate(mid, idx, sdp)
                            gcJail.add(candidate)
                            println(">>> TRACE [PUB] Calling pubPc.addIceCandidate mid=$mid idx=$idx")
                            pubPc?.addIceCandidate(candidate)
                            println("<<< TRACE [PUB] pubPc.addIceCandidate returned")
                        }
                    }
                    is LiveKitSignalingClient.Event.Disconnected -> {
                        println(">>> TRACE [PUB] Disconnected event")
                        println("[LiveKit] Publisher: disconnected")
                        stopPublisher()
                        println("<<< TRACE [PUB] stopPublisher returned")
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
                        println(">>> TRACE [SUB] WebSocket Connected")
                        println("[LiveKit] Subscriber: WebSocket connected")
                        println("<<< TRACE [SUB] WebSocket Connected handled")
                    }
                    is LiveKitSignalingClient.Event.JoinReceived -> {
                        println(">>> TRACE [SUB] JoinReceived — ${event.response.iceServers.size} ICE server(s)")
                        println("[LiveKit] Subscriber: Join ack — ${event.response.iceServers.size} ICE server(s)")
                        iceServers = event.response.iceServers.map { it.toRTC() }
                        println(">>> TRACE [SUB] Calling createSubscriberPc")
                        createSubscriberPc(sig)
                        println("<<< TRACE [SUB] createSubscriberPc returned")
                    }
                    is LiveKitSignalingClient.Event.OfferReceived -> {
                        println(">>> TRACE [SUB] OfferReceived (sdp length=${event.sdp.sdp.length})")
                        println("[LiveKit] Subscriber: Offer received (SDP ${event.sdp.sdp.length} bytes)")
                        subSetRemoteObs = object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                try {
                                    println(">>> TRACE [SUB] setRemoteDescription.onSuccess — launching subscriberAnswer")
                                    scope.launch { subscriberAnswer(sig) }
                                    println("<<< TRACE [SUB] setRemoteDescription.onSuccess coroutine launched")
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            override fun onFailure(e: String) {
                                try {
                                    println(">>> TRACE [SUB] setRemoteDescription.onFailure: $e")
                                    println("[LiveKit] sub setRemoteDescription FAILED: $e")
                                    println("<<< TRACE [SUB] setRemoteDescription.onFailure handled")
                                } catch (ex: Exception) { ex.printStackTrace() }
                            }
                        }
                        val offerSdp = RTCSessionDescription(RTCSdpType.OFFER, event.sdp.sdp)
                        gcJail.add(offerSdp)
                        println(">>> TRACE [SUB] Calling subPc.setRemoteDescription (OFFER)")
                        subPc?.setRemoteDescription(offerSdp, subSetRemoteObs!!)
                        println("<<< TRACE [SUB] subPc.setRemoteDescription call returned (async)")
                    }
                    is LiveKitSignalingClient.Event.TrickleReceived -> {
                        if (event.msg.target == 1) {
                            println(">>> TRACE [SUB] TrickleReceived (target=1)")
                            val (sdp, mid, idx) = parseCandidateJson(event.msg.candidateInit)
                            val candidate = RTCIceCandidate(mid, idx, sdp)
                            gcJail.add(candidate)
                            println(">>> TRACE [SUB] Calling subPc.addIceCandidate mid=$mid idx=$idx")
                            subPc?.addIceCandidate(candidate)
                            println("<<< TRACE [SUB] subPc.addIceCandidate returned")
                        }
                    }
                    is LiveKitSignalingClient.Event.Disconnected -> {
                        println(">>> TRACE [SUB] Disconnected event")
                        println("[LiveKit] Subscriber: disconnected")
                        stopSubscriber()
                        println("<<< TRACE [SUB] stopSubscriber returned")
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopPublisher() {
        // 1. Explicitly remove the sink from the native track BEFORE releasing
        //    any strong references.  This drops the C++ raw pointer while both
        //    objects are still fully alive — the only safe window to do this.
        //    After removeSink() returns, the C++ engine will never call
        //    onVideoFrame on this sink again, so it is safe to let it GC.
        val sink = pubVideoSink
        if (sink != null) {
            try {
                pubVideoTrack?.removeSink(sink)
                println("[LiveKit] stopPublisher: sink removed from pubVideoTrack")
            } catch (e: Exception) {
                println("[LiveKit] stopPublisher: removeSink failed (non-fatal): ${e.message}")
            }
            pubVideoSink = null
        }

        // 2. Signal the Robot preview loop to exit gracefully.
        //    Do NOT cancel the coroutine: cancel() while inside a native JNI
        //    call tears down the thread stack → 0xc0000005.
        //    isCapturing.set(false) lets the current frame finish, then exits.
        isCapturing.set(false)
        isPublishing = false
        _localFrame.value = null
        println("[LiveKit] stopPublisher: isCapturing=false, sink decoupled")
    }

    fun stopSubscriber() {
        // Explicitly remove all subscriber sinks from their tracks before
        // releasing strong references.  Same rationale as stopPublisher.
        subVideoSinkPairs.forEach { (track, sink) ->
            try {
                track.removeSink(sink)
            } catch (e: Exception) {
                println("[LiveKit] stopSubscriber: removeSink failed (non-fatal): ${e.message}")
            }
        }
        subVideoSinkPairs.clear()
        _remoteFrame.value = null
        println("[LiveKit] stopSubscriber: ${subVideoSinkPairs.size} sinks decoupled")
    }

    fun leave() { stopPublisher(); stopSubscriber() }

    // =========================================================================
    // PUBLISHER PEER CONNECTION
    // =========================================================================

    private fun createPublisherPc(sig: LiveKitSignalingClient) {
        println(">>> TRACE [PUB-PC] createPublisherPc entry")
        pubRtcConfig = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }
        println(">>> TRACE [PUB-PC] RTCConfiguration created (${this@LiveKitRoom.iceServers.size} ICE servers)")

        pubPcObserver = object : PeerConnectionObserver {
            override fun onSignalingChange(s: RTCSignalingState) {
                try {
                    println(">>> TRACE [PUB-CB] onSignalingChange: $s")
                    println("<<< TRACE [PUB-CB] onSignalingChange handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onIceCandidate(c: RTCIceCandidate) {
                try {
                    println(">>> TRACE [PUB-CB] onIceCandidate: sdpMid=${c.sdpMid} sdpMLineIndex=${c.sdpMLineIndex}")
                    gcJail.add(c)
                    scope.launch { sig.sendTrickle(candidateToJson(c), 0) }
                    println("<<< TRACE [PUB-CB] onIceCandidate handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onIceCandidateError(e: RTCPeerConnectionIceErrorEvent) {
                try { println(">>> TRACE [PUB-CB] onIceCandidateError: ${e.errorText} (code=${e.errorCode})") } catch (ex: Exception) { ex.printStackTrace() }
            }

            override fun onIceGatheringChange(s: RTCIceGatheringState) {
                try { println(">>> TRACE [PUB-CB] onIceGatheringChange: $s") } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onRenegotiationNeeded() {
                try {
                    println(">>> TRACE [PUB-CB] onRenegotiationNeeded — launching publisherNegotiate")
                    println("[LiveKit] Publisher: onRenegotiationNeeded → sending offer")
                    scope.launch { publisherNegotiate(sig) }
                    println("<<< TRACE [PUB-CB] onRenegotiationNeeded coroutine launched")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onIceConnectionChange(s: RTCIceConnectionState) {
                try {
                    println(">>> TRACE [PUB-CB] onIceConnectionChange: $s")
                    println("[LiveKit] Publisher ICE → $s")
                    println("<<< TRACE [PUB-CB] onIceConnectionChange handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onConnectionChange(s: RTCPeerConnectionState) {
                try {
                    println(">>> TRACE [PUB-CB] onConnectionChange: $s")
                    println("[LiveKit] Publisher PC  → $s")
                    println("<<< TRACE [PUB-CB] onConnectionChange handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onDataChannel(dc: RTCDataChannel) {
                try {
                    println(">>> TRACE [PUB-CB] onDataChannel: label='${dc.label}'")
                    gcJail.add(dc)
                    val dummyObs = object : RTCDataChannelObserver {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: RTCDataChannelBuffer) {}
                    }
                    gcJail.add(dummyObs)
                    dc.registerObserver(dummyObs)
                    println("<<< TRACE [PUB-CB] onDataChannel handled")
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        println(">>> TRACE [PUB-PC] Calling factory.createPeerConnection (publisher)")
        pubPc = factory.createPeerConnection(pubRtcConfig!!, pubPcObserver!!) ?: run {
            println("[LiveKit] FATAL: createPeerConnection returned null (publisher)")
            return
        }
        println("<<< TRACE [PUB-PC] factory.createPeerConnection returned OK")

        // AUDIO DISABLED — isolation test
        // attachMicrophone()
        attachDesktopSource(sig)   // Robot-only capture: no DXGI, no crash
        isPublishing = true
        println("<<< TRACE [PUB-PC] createPublisherPc complete")
        println("[LiveKit] Publisher PC created")
    }

    // ── Microphone ─────────────────────────────────────────────────────────

    private fun attachMicrophone() {
        try {
            // AudioOptions: feed into PeerConnectionFactory's audio pipeline.
            // The APM (adm+apm above) handles the actual processing.
            val opts = AudioOptions().apply {
                echoCancellation     = true
                autoGainControl      = true
                noiseSuppression     = true
                highpassFilter       = true
                typingDetection      = true
                residualEchoDetector = true
            }
            // Assign to class-level fields — local vars would be GC'd when this
            // function returns, destroying the native C++ audio objects while the
            // PeerConnection's audio pipeline is still using them.
            pubAudioSource = factory.createAudioSource(opts)
            pubAudioTrack  = factory.createAudioTrack("mic_audio", pubAudioSource!!)
            pubPc?.addTrack(pubAudioTrack!!, listOf("microphone"))
            println("[LiveKit] Microphone track added (AudioOptions: AEC+AGC+NS+HPF+typing)")
        } catch (e: Exception) {
            println("[LiveKit] attachMicrophone FAILED:")
            e.printStackTrace()
        }
    }

    // ── Screen capture ──────────────────────────────────────────────────────
    //
    // Video pipeline: VideoDesktopSource (DXGI) → WebRTC encoder → remote viewers
    // Local preview:  Robot (GDI BitBlt) → _localFrame state → Compose UI
    //
    // webrtc-java 0.8.0 has no public Java API to inject frames into the
    // WebRTC encoder: VideoFrame constructor is private, and VideoTrackSource
    // has no onFrameCaptured() in the Java bindings. The only path into the
    // encoder is VideoDesktopSource.start() (native DXGI capture loop).
    // Robot provides an immediate local preview before DXGI emits its first
    // frame, and continues as a fallback if DXGI fails silently.
    //
    // GC safety: gcJail.add(src) keeps the native VideoDesktopSource wrapper
    // alive even if the Kotlin reference is unreachable. Without this, GC
    // can finalize it while the C++ capture thread is still calling back
    // into the object → 0xc0000005 double-free.
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachDesktopSource(sig: LiveKitSignalingClient) {
        try {
            println("[LiveKit] attachDesktopSource: VideoDesktopSource (DXGI) + Robot local preview")

            // Enumerate screens WITHOUT acquiring a DXGI handle.
            // getDesktopSources() reads the GDI/DXGI monitor table only — safe.
            // We must NOT call ScreenCapturer.start() here; that acquires the
            // Desktop Duplication handle and would conflict with VideoDesktopSource.
            val enumerator = ScreenCapturer()
            val sources    = enumerator.getDesktopSources()
            enumerator.dispose()
            val sourceId = sources.firstOrNull()?.id ?: 0L
            println("[LiveKit] Found ${sources.size} screen source(s), using id=$sourceId")

            val src = VideoDesktopSource().also { desktopSrc = it }
            gcJail.add(src)
            src.setSourceId(sourceId, false)   // false = full screen (not a window)
            src.setMaxFrameSize(1280, 720)
            src.setFrameRate(30)

            pubVideoTrack = factory.createVideoTrack("screen_video", src)
            gcJail.add(pubVideoTrack!!)
            println("[LiveKit] VideoTrack id=${pubVideoTrack!!.id}")

            // Sink: DXGI frames → encoder → this sink → local preview UI
            // (will overwrite Robot preview once DXGI emits its first frame)
            val localSink = makeVideoSink("webrtc-local", _localFrame)
            pubVideoSink = localSink   // registered for safe removeSink() on stop
            pubVideoTrack!!.addSink(localSink)

            pubPc?.addTrack(pubVideoTrack!!, listOf("screen"))
            sig.sendAddTrack(pubVideoTrack!!.id, "screen", type = 1, source = 3)

            // Robot for immediate local preview before DXGI emits first frame
            startRobotPreview()

            src.start()
            println("[LiveKit] VideoDesktopSource.start() called — DXGI capture running")

        } catch (e: Exception) {
            println("[LiveKit] FATAL: attachDesktopSource:")
            e.printStackTrace()
        }
    }

    // ── AWT Robot preview (local UI only, not sent to WebRTC) ─────────────
    //
    // Robot.createScreenCapture() uses GDI (BitBlt) — always works on Windows.
    // These frames do NOT enter the WebRTC encoder (no public API for that).
    // Stop: robotJob?.cancel() — safe, no native WebRTC objects touched.
    // ─────────────────────────────────────────────────────────────────────────

    private fun startRobotPreview() {
        // Signal any running loop to stop gracefully, then launch a fresh one.
        // We do NOT cancel the old job here — the old coroutine will notice
        // isCapturing=false on its next while-check and exit cleanly.
        isCapturing.set(false)   // stop old loop (if any)
        isCapturing.set(true)    // arm new loop
        robotJob = robotScope.launch(Dispatchers.IO) {
            val robot: Robot
            val screenBounds: Rectangle
            try {
                robot = Robot()
                screenBounds = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .defaultConfiguration
                    .bounds
                println("[LiveKit] Robot preview started — ${screenBounds.width}×${screenBounds.height}")
            } catch (e: Exception) {
                println("[LiveKit] Robot init FAILED: ${e.message}")
                e.printStackTrace()
                isCapturing.set(false)
                return@launch
            }

            val targetW = 1280; val targetH = 720
            var frameCount = 0L
            // AtomicBoolean exit — never throw CancellationException inside JNI calls.
            // createScreenCapture() and toComposeImageBitmap() are not safe cancellation
            // points; the JVM must complete them before we check the exit condition.
            while (isCapturing.get()) {
                try {
                    val scaled = robot.createScreenCapture(screenBounds).toScaled(targetW, targetH)
                    _localFrame.value = scaled.toComposeImageBitmap()
                    frameCount++
                    if (frameCount == 1L) println("[LiveKit] Robot: first preview frame")
                } catch (e: Exception) {
                    if (!isCapturing.get()) break   // stopping — exit silently
                    println("[LiveKit] Robot preview error: ${e.message}")
                }
                // Use Thread.sleep instead of delay() — delay() is a suspension
                // point where CancellationException can be injected by the runtime
                // even if we never call cancel(). Thread.sleep is non-interruptible
                // from the coroutine scheduler and keeps us fully in control.
                Thread.sleep(33L)
            }
            println("[LiveKit] Robot preview stopped gracefully")
        }
    }

    private fun BufferedImage.toScaled(w: Int, h: Int): BufferedImage {
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g  = bi.createGraphics()
        g.drawImage(this, 0, 0, w, h, null)
        g.dispose()
        return bi
    }

    // ── Offer with SDP munge ──────────────────────────────────────────────

    private suspend fun publisherNegotiate(sig: LiveKitSignalingClient) {
        println(">>> TRACE [PUB-NEG] publisherNegotiate entry")
        val pc    = pubPc ?: run { println(">>> TRACE [PUB-NEG] pubPc is null, aborting"); return }
        println(">>> TRACE [PUB-NEG] Calling createOffer")
        val offer = createOffer(pc) ?: run { println("[LiveKit] createOffer returned null"); return }
        println("<<< TRACE [PUB-NEG] createOffer returned (sdp length=${offer.sdp.length})")
        val mungedSdp   = mungeOpusSdp(offer.sdp)
        val mungedOffer = RTCSessionDescription(RTCSdpType.OFFER, mungedSdp)
        gcJail.add(mungedOffer)
        pubSetLocalDescObs = object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                try {
                    println(">>> TRACE [PUB-NEG] setLocalDescription.onSuccess")
                    println("[LiveKit] pub setLocalDescription — OK")
                    println("<<< TRACE [PUB-NEG] setLocalDescription.onSuccess handled")
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(e: String) {
                try {
                    println(">>> TRACE [PUB-NEG] setLocalDescription.onFailure: $e")
                    println("[LiveKit] pub setLocalDescription — FAILED: $e")
                    println("<<< TRACE [PUB-NEG] setLocalDescription.onFailure handled")
                } catch (ex: Exception) { ex.printStackTrace() }
            }
        }
        println(">>> TRACE [PUB-NEG] Calling pc.setLocalDescription (OFFER)")
        pc.setLocalDescription(mungedOffer, pubSetLocalDescObs!!)
        println("<<< TRACE [PUB-NEG] pc.setLocalDescription call returned (async)")
        println(">>> TRACE [PUB-NEG] Calling sig.sendOffer")
        sig.sendOffer(mungedSdp)
        println("<<< TRACE [PUB-NEG] publisherNegotiate complete")
        println("[LiveKit] Offer sent (DTX=off, Opus 64 kbps)")
    }

    // =========================================================================
    // SUBSCRIBER PEER CONNECTION
    // =========================================================================

    private fun createSubscriberPc(sig: LiveKitSignalingClient) {
        println(">>> TRACE [SUB-PC] createSubscriberPc entry")
        subRtcConfig = RTCConfiguration().apply { iceServers = this@LiveKitRoom.iceServers }
        println(">>> TRACE [SUB-PC] RTCConfiguration created (${this@LiveKitRoom.iceServers.size} ICE servers)")

        subPcObserver = object : PeerConnectionObserver {
            override fun onSignalingChange(s: RTCSignalingState) {
                try {
                    println(">>> TRACE [SUB-CB] onSignalingChange: $s")
                    println("<<< TRACE [SUB-CB] onSignalingChange handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onIceCandidate(c: RTCIceCandidate) {
                try {
                    println(">>> TRACE [SUB-CB] onIceCandidate: sdpMid=${c.sdpMid} sdpMLineIndex=${c.sdpMLineIndex}")
                    gcJail.add(c)
                    scope.launch { sig.sendTrickle(candidateToJson(c), 1) }
                    println("<<< TRACE [SUB-CB] onIceCandidate handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onIceCandidateError(e: RTCPeerConnectionIceErrorEvent) {
                try { println(">>> TRACE [SUB-CB] onIceCandidateError: ${e.errorText} (code=${e.errorCode})") } catch (ex: Exception) { ex.printStackTrace() }
            }

            override fun onIceGatheringChange(s: RTCIceGatheringState) {
                try { println(">>> TRACE [SUB-CB] onIceGatheringChange: $s") } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onIceConnectionChange(s: RTCIceConnectionState) {
                try {
                    println(">>> TRACE [SUB-CB] onIceConnectionChange: $s")
                    println("[LiveKit] Subscriber ICE → $s")
                    println("<<< TRACE [SUB-CB] onIceConnectionChange handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onConnectionChange(s: RTCPeerConnectionState) {
                try {
                    println(">>> TRACE [SUB-CB] onConnectionChange: $s")
                    println("[LiveKit] Subscriber PC  → $s")
                    println("<<< TRACE [SUB-CB] onConnectionChange handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            // Primary — unified-plan (LiveKit Cloud always sends unified-plan)
            override fun onTrack(transceiver: RTCRtpTransceiver) {
                try {
                    println(">>> TRACE [SUB-CB] onTrack entry")
                    gcJail.add(transceiver)
                    val receiver = transceiver.receiver ?: run { println(">>> TRACE [SUB-CB] onTrack: receiver is null"); return }
                    gcJail.add(receiver)
                    println(">>> TRACE [SUB-CB] onTrack: calling receiver.getTrack()")
                    val track = receiver.getTrack() ?: run { println(">>> TRACE [SUB-CB] onTrack: track is null"); return }
                    gcJail.add(track)
                    println("[LiveKit] onTrack: ${track.javaClass.simpleName}  id=${track.id}")
                    if (track is VideoTrack) {
                        println("SCREEN SHARE TRACK SUBSCRIBED (onTrack unified-plan)")
                        subVideoTracks.add(track)
                        println(">>> TRACE [SUB-CB] onTrack: calling track.addSink")
                        val sink = makeVideoSink("remote-view", _remoteFrame)
                        subVideoSinkPairs.add(Pair(track, sink))  // for safe removeSink on stop
                        track.addSink(sink)
                    }
                    println("<<< TRACE [SUB-CB] onTrack handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            // Fallback — older plan-b path or concurrent fire on some builds
            override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<MediaStream>) {
                try {
                    println(">>> TRACE [SUB-CB] onAddTrack entry (${streams.size} streams)")
                    gcJail.add(receiver)
                    streams.forEach { gcJail.add(it) }
                    println(">>> TRACE [SUB-CB] onAddTrack: calling receiver.getTrack()")
                    val track = receiver.getTrack() ?: run { println(">>> TRACE [SUB-CB] onAddTrack: track is null"); return }
                    gcJail.add(track)
                    println("[LiveKit] onAddTrack: ${track.javaClass.simpleName}  id=${track.id}")
                    if (track is VideoTrack && !subVideoTracks.contains(track)) {
                        println("SCREEN SHARE TRACK SUBSCRIBED (onAddTrack fallback)")
                        subVideoTracks.add(track)
                        println(">>> TRACE [SUB-CB] onAddTrack: calling track.addSink")
                        val sink = makeVideoSink("remote-view-fb", _remoteFrame)
                        subVideoSinkPairs.add(Pair(track, sink))  // for safe removeSink on stop
                        track.addSink(sink)
                    }
                    println("<<< TRACE [SUB-CB] onAddTrack handled")
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onDataChannel(dc: RTCDataChannel) {
                try {
                    println(">>> TRACE [SUB-CB] onDataChannel: label='${dc.label}'")
                    gcJail.add(dc)
                    val dummyObs = object : RTCDataChannelObserver {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: RTCDataChannelBuffer) {}
                    }
                    gcJail.add(dummyObs)
                    dc.registerObserver(dummyObs)
                    println("<<< TRACE [SUB-CB] onDataChannel handled")
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        println(">>> TRACE [SUB-PC] Calling factory.createPeerConnection (subscriber)")
        subPc = factory.createPeerConnection(subRtcConfig!!, subPcObserver!!) ?: run {
            println("[LiveKit] FATAL: createPeerConnection returned null (subscriber)")
            return
        }
        println("<<< TRACE [SUB-PC] factory.createPeerConnection returned OK")
        println("<<< TRACE [SUB-PC] createSubscriberPc complete")
        println("[LiveKit] Subscriber PC created")
    }

    private suspend fun subscriberAnswer(sig: LiveKitSignalingClient) {
        println(">>> TRACE [SUB-ANS] subscriberAnswer entry")
        val pc = subPc ?: run { println(">>> TRACE [SUB-ANS] subPc is null, aborting"); return }
        println(">>> TRACE [SUB-ANS] Calling createAnswer")
        val answer = createAnswer(pc) ?: run { println("[LiveKit] createAnswer returned null"); return }
        println("<<< TRACE [SUB-ANS] createAnswer returned (sdp length=${answer.sdp.length})")
        subSetLocalDescObs = object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                try {
                    println(">>> TRACE [SUB-ANS] setLocalDescription.onSuccess")
                    println("[LiveKit] sub setLocalDescription — OK")
                    println("<<< TRACE [SUB-ANS] setLocalDescription.onSuccess handled")
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onFailure(e: String) {
                try {
                    println(">>> TRACE [SUB-ANS] setLocalDescription.onFailure: $e")
                    println("[LiveKit] sub setLocalDescription — FAILED: $e")
                    println("<<< TRACE [SUB-ANS] setLocalDescription.onFailure handled")
                } catch (ex: Exception) { ex.printStackTrace() }
            }
        }
        println(">>> TRACE [SUB-ANS] Calling pc.setLocalDescription (ANSWER)")
        pc.setLocalDescription(answer, subSetLocalDescObs!!)
        println("<<< TRACE [SUB-ANS] pc.setLocalDescription call returned (async)")
        println(">>> TRACE [SUB-ANS] Calling sig.sendAnswer")
        sig.sendAnswer(answer.sdp)
        println("<<< TRACE [SUB-ANS] subscriberAnswer complete")
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
                        println(">>> TRACE [CREATE-OFFER] onSuccess (sdp length=${d.sdp.length})")
                        gcJail.add(d)
                        pubCreateOfferObs = null
                        cont.resumeWith(Result.success(d))
                        println("<<< TRACE [CREATE-OFFER] onSuccess continuation resumed")
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(e: String) {
                    try {
                        println(">>> TRACE [CREATE-OFFER] onFailure: $e")
                        println("[LiveKit] createOffer native FAILED: $e")
                        pubCreateOfferObs = null
                        cont.resumeWith(Result.success(null))
                    } catch (ex: Exception) { ex.printStackTrace() }
                }
            }
            println(">>> TRACE [CREATE-OFFER] Calling pc.createOffer")
            pc.createOffer(RTCOfferOptions(), pubCreateOfferObs!!)
            println("<<< TRACE [CREATE-OFFER] pc.createOffer call returned (async, waiting for callback)")
        }

    private suspend fun createAnswer(pc: RTCPeerConnection): RTCSessionDescription? =
        suspendCancellableCoroutine { cont ->
            subCreateAnswerObs = object : CreateSessionDescriptionObserver {
                override fun onSuccess(d: RTCSessionDescription) {
                    try {
                        println(">>> TRACE [CREATE-ANSWER] onSuccess (sdp length=${d.sdp.length})")
                        gcJail.add(d)
                        subCreateAnswerObs = null
                        cont.resumeWith(Result.success(d))
                        println("<<< TRACE [CREATE-ANSWER] onSuccess continuation resumed")
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(e: String) {
                    try {
                        println(">>> TRACE [CREATE-ANSWER] onFailure: $e")
                        println("[LiveKit] createAnswer native FAILED: $e")
                        subCreateAnswerObs = null
                        cont.resumeWith(Result.success(null))
                    } catch (ex: Exception) { ex.printStackTrace() }
                }
            }
            println(">>> TRACE [CREATE-ANSWER] Calling pc.createAnswer")
            pc.createAnswer(RTCAnswerOptions(), subCreateAnswerObs!!)
            println("<<< TRACE [CREATE-ANSWER] pc.createAnswer call returned (async, waiting for callback)")
        }

    private fun loggingSdpObserver(tag: String) = object : SetSessionDescriptionObserver {
        override fun onSuccess() = println("[LiveKit] $tag — OK")
        override fun onFailure(e: String) = println("[LiveKit] $tag — FAILED: $e")
    }

    // =========================================================================
    // ICE SERVER HELPER
    // =========================================================================

    private fun LKICEServer.toRTC(): RTCIceServer {
        val s = RTCIceServer()
        s.urls     = urls
        s.username = username
        s.password = credential // RTCIceServer field name is 'password' (not 'credential')
        return s
    }
}
