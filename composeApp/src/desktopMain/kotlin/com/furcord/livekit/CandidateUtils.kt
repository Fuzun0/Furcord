package com.furcord.livekit

import dev.onvoid.webrtc.RTCIceCandidate

/** RTCIceCandidate → JSON string (LiveKit candateInit formatı) */
fun candidateToJson(c: RTCIceCandidate): String =
    """{"candidate":"${c.sdp}","sdpMid":"${c.sdpMid}","sdpMLineIndex":${c.sdpMLineIndex}}"""

/** JSON string → Triple(sdp, sdpMid, sdpMLineIndex) */
fun parseCandidateJson(json: String): Triple<String, String, Int> {
    val sdp = Regex(""""candidate"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1) ?: ""
    val mid = Regex(""""sdpMid"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1) ?: "0"
    val idx = Regex(""""sdpMLineIndex"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return Triple(sdp, mid, idx)
}
