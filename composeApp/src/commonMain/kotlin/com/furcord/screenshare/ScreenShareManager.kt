package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic screen-share controller.
 *
 * The actual implementation (desktopMain) wraps [ScreenEngine].
 * Peers are expressed as plain (ip, port) pairs so this interface
 * stays free of JVM-specific types like InetSocketAddress.
 */
expect object ScreenShareManager {
    /** Update the list of remote peers to stream to. Called whenever voice peers change. */
    fun setPeers(peers: List<Pair<String, Int>>)
    /** Start capturing the primary screen with the current quality. No-op if already active. */
    fun start()
    /** Start capturing the chosen [source] (screen or window) with [quality]. No-op if already active. */
    fun start(source: ShareSource, quality: StreamQuality)
    /** Switch the shared [source] while broadcasting, without ending the stream. */
    fun switchSource(source: ShareSource, quality: StreamQuality)
    /** Stop broadcasting and release capture resources. */
    fun stop()
    /** Whether a broadcast is currently in progress. */
    val isActive: Boolean
    /** Shareable monitors and windows. Blocking — call from a background dispatcher. */
    fun listSources(): List<ShareSource>
    /** Small preview image for [source]; null when it cannot be captured. Blocking. */
    fun thumbnail(source: ShareSource): ImageBitmap?
    /** Current broadcast quality. Can be changed while streaming. */
    val quality: StateFlow<StreamQuality>
    fun setQuality(q: StreamQuality)
    /** Title of the source being shared ("" when idle). */
    val sourceTitle: StateFlow<String>
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
