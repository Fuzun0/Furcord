package com.furcord.screenshare

/**
 * Platform-agnostic screen-share controller.
 *
 * The actual implementation (desktopMain) wraps [ScreenBroadcaster].
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
}
