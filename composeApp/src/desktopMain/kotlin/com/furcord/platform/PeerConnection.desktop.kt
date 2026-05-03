package com.furcord.platform

// TODO: Replace with a real WebRTC implementation (e.g. a JVM/native WebRTC library).
actual class PeerConnection actual constructor(
    private val roomId: String,
    private val clientId: String,
) {
    actual suspend fun connect() {
        // TODO: Open a WebSocket to the signaling server and create an SDP offer.
    }

    actual suspend fun handleAnswer(sdpAnswer: String) {
        // TODO: Apply the remote SDP answer.
    }

    actual suspend fun addIceCandidate(candidate: String) {
        // TODO: Add the remote ICE candidate.
    }

    actual fun close() {
        // TODO: Close the connection and release resources.
    }
}
