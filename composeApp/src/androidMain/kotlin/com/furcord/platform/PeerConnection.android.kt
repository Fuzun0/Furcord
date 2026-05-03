package com.furcord.platform

// TODO: Replace with a real WebRTC implementation (e.g. Google's libwebrtc for Android).
actual class PeerConnection actual constructor(
    private val roomId: String,
    private val clientId: String,
) {
    actual suspend fun connect() {
        // TODO: Open a WebSocket to the signaling server and create an SDP offer.
    }

    actual suspend fun handleAnswer(sdpAnswer: String) {
        // TODO: Apply the remote SDP answer to the native PeerConnection.
    }

    actual suspend fun addIceCandidate(candidate: String) {
        // TODO: Add the remote ICE candidate to the native PeerConnection.
    }

    actual fun close() {
        // TODO: Close the native PeerConnection and WebSocket.
    }
}
