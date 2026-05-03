package com.furcord.platform

/**
 * Platform-agnostic contract for a WebRTC PeerConnection.
 *
 * Each target (Android / iOS / Desktop) must supply an `actual` implementation.
 */
expect class PeerConnection(roomId: String, clientId: String) {

    /** Initiate a connection to the signaling server and create an SDP offer. */
    suspend fun connect()

    /** Accept an incoming SDP answer from a remote peer. */
    suspend fun handleAnswer(sdpAnswer: String)

    /** Feed a remote ICE candidate into the connection. */
    suspend fun addIceCandidate(candidate: String)

    /** Close the peer connection and release all resources. */
    fun close()
}
