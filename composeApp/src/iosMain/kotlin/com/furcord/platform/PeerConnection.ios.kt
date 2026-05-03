package com.furcord.platform

// TODO: Replace with a real WebRTC implementation (e.g. WebRTC iOS framework).
actual class PeerConnection actual constructor(
    private val roomId: String,
    private val clientId: String,
) {
    actual suspend fun connect() {
        // TODO: Open a WebSocket to the signaling server and create an SDP offer.
    }

    actual suspend fun handleAnswer(sdpAnswer: String) {
        // TODO: Apply the remote SDP answer to the native RTCPeerConnection.
    }

    actual suspend fun addIceCandidate(candidate: String) {
        // TODO: Add the remote ICE candidate to the native RTCPeerConnection.
    }

    actual fun close() {
        // TODO: Close the native RTCPeerConnection and WebSocket.
    }
}
