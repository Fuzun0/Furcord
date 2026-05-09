package com.furcord.screenshare

import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Desktop implementation: wraps [ScreenBroadcaster] on a dedicated UDP port.
 *
 * Screenshare port convention:  voicePeer.port + 100
 * e.g. voice on 50000 → screenshare on 50100
 */
actual object ScreenShareManager {

    /** Dedicated UDP port for screenshare traffic (separate from VoiceEngine). */
    const val SCREENSHARE_PORT = 55100

    private val socket: DatagramSocket by lazy { DatagramSocket(SCREENSHARE_PORT) }
    private var broadcaster: ScreenBroadcaster? = null
    private var currentPeers: List<InetSocketAddress> = emptyList()

    actual fun setPeers(peers: List<Pair<String, Int>>) {
        currentPeers = peers.map { (ip, port) -> InetSocketAddress(ip, port) }
    }

    actual fun start() {
        if (broadcaster != null) return
        broadcaster = ScreenBroadcaster(socket) { currentPeers }.also { it.start() }
    }

    actual fun stop() {
        broadcaster?.stop()
        broadcaster = null
    }

    actual val isActive: Boolean get() = broadcaster != null
}
