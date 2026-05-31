package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic screen-share controller.
 *
 * The actual implementation (desktopMain) wraps [ScreenBroadcaster] and [ScreenReceiver].
 * Peers are expressed as plain (ip, port) pairs so this interface
 * stays free of JVM-specific types like InetSocketAddress.
 */
expect object ScreenShareManager {
    /** Update the list of remote peers to stream to. Called whenever voice peers change. */
    fun setPeers(peers: List<Pair<String, Int>>)
    /** Start capturing and broadcasting the screen. No-op if already active. */
    fun start()
    /** Stop broadcasting and release FFmpeg resources. */
    fun stop()
    /** Whether a broadcast is currently in progress. */
    val isActive: Boolean
    /** Local preview frames from the broadcaster (self-view). Null when not broadcasting. */
    val localFrame: StateFlow<ImageBitmap?>
    /** Decoded frames from a remote broadcaster. Null frames when no stream yet. */
    val receiverFrame: StateFlow<ImageBitmap?>
    /** Start receiving from a remote broadcaster. No-op if already receiving. */
    fun startReceiver()
    /** Stop receiving and release decoder resources. */
    fun stopReceiver()
    /** Ses kanalından tamamen ayrılırken çağrılır — tüm alım durumunu sıfırlar (broadcastingUidHash dahil). */
    fun stopReceiverFull()
    /** UID hash of the user currently broadcasting screen; 0 if nobody is broadcasting. */
    val broadcastingUidHash: StateFlow<Int>
}
