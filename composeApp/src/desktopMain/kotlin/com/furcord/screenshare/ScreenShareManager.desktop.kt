package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

/**
 * Desktop implementation: wraps [ScreenBroadcaster] and [ScreenReceiver] on a
 * dedicated UDP port (SCREENSHARE_PORT = 55100).
 *
 * A single DatagramSocket is shared for both sending (broadcaster) and receiving
 * (receiver). Only one role is active at a time per machine.
 *
 * Port convention: voicePeer.port + 100 → screenshare port.
 */
actual object ScreenShareManager {

    const val SCREENSHARE_PORT = 55100

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var broadcaster: ScreenBroadcaster? = null
    private var receiver: ScreenReceiver?       = null
    private var currentPeers: List<InetSocketAddress> = emptyList()

    private var localFrameJob: Job?    = null
    private var receiverFrameJob: Job? = null

    // ── Local preview (self-view) ────────────────────────────────────────────

    private val _localFrame = MutableStateFlow<ImageBitmap?>(null)
    actual val localFrame: StateFlow<ImageBitmap?> = _localFrame.asStateFlow()

    // ── Remote stream ────────────────────────────────────────────────────────

    private val _receiverFrame = MutableStateFlow<ImageBitmap?>(null)
    actual val receiverFrame: StateFlow<ImageBitmap?> = _receiverFrame.asStateFlow()

    // ── Peer management ──────────────────────────────────────────────────────

    actual fun setPeers(peers: List<Pair<String, Int>>) {
        currentPeers = peers.map { (ip, port) -> InetSocketAddress(ip, port) }
    }

    // ── Broadcaster ──────────────────────────────────────────────────────────

    actual fun start() {
        if (broadcaster != null) return
        stopReceiver()
        val b = ScreenBroadcaster { currentPeers }
        broadcaster = b
        b.start()
        // Forward local preview frames
        localFrameJob = managerScope.launch {
            b.localFrame.collect { _localFrame.value = it }
        }
    }

    actual fun stop() {
        localFrameJob?.cancel()
        localFrameJob = null
        broadcaster?.stop()
        broadcaster = null
        _localFrame.value = null
    }

    actual val isActive: Boolean get() = broadcaster != null

    // ── Receiver ─────────────────────────────────────────────────────────────

    actual fun startReceiver() {
        if (receiver != null) return
        val r = ScreenReceiver(SCREENSHARE_PORT)
        receiver = r
        r.start()
        receiverFrameJob = managerScope.launch {
            r.frame.collect { _receiverFrame.value = it }
        }
    }

    actual fun stopReceiver() {
        receiverFrameJob?.cancel()
        receiverFrameJob = null
        val r = receiver
        receiver = null
        _receiverFrame.value = null
        // stop() blocks waiting for the native FFmpeg call to return (up to 5 s).
        // Run it on the manager's IO scope to avoid blocking the caller's thread.
        if (r != null) {
            managerScope.launch(Dispatchers.IO) { r.stop() }
        }
    }
}

