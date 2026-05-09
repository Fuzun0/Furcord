package com.furcord.livekit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * LiveKit sinyal WebSocket istemcisi.
 *
 * Binary protobuf mesajlarını LKProtoDecoder ile çözer ve olayları
 * [events] SharedFlow üzerinden yayar.
 */
class LiveKitSignalingClient(
    private val serverUrl: String,
    private val token: String
) : WebSocketListener() {

    sealed class Event {
        data class JoinReceived(val response: LKJoinResponse)          : Event()
        data class AnswerReceived(val sdp: LKSessionDescription)       : Event()
        data class OfferReceived(val sdp: LKSessionDescription)        : Event()
        data class TrickleReceived(val msg: LKTrickleRequest)          : Event()
        data class TrackPublished(val res: LKTrackPublishedResponse)   : Event()
        object Connected                                               : Event()
        object Disconnected                                            : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var ws: WebSocket? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = buildString {
            append(serverUrl.trimEnd('/'))
            append("/rtc")
            append("?access_token=").append(token)
            append("&auto_subscribe=1&protocol=9&sdk=jvm&version=1.0.0")
        }
        ws = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    fun disconnect() {
        ws?.close(1000, "Leaving")
        ws = null
    }

    // ── Gönderme yardımcıları ─────────────────────────────────────────────

    fun sendOffer(sdp: String) = sendRequest(
        LKSignalRequest.Offer(LKSessionDescription("offer", sdp))
    )

    fun sendAnswer(sdp: String) = sendRequest(
        LKSignalRequest.Answer(LKSessionDescription("answer", sdp))
    )

    /** target: 0=PUBLISHER, 1=SUBSCRIBER */
    fun sendTrickle(candidateJson: String, target: Int) = sendRequest(
        LKSignalRequest.Trickle(LKTrickleRequest(candidateJson, target))
    )

    fun sendAddTrack(
        cid: String,
        name: String,
        type: Int   = 1, // VIDEO
        source: Int = 3, // SCREEN_SHARE
        w: Int = 1280,
        h: Int = 720
    ) = sendRequest(
        LKSignalRequest.AddTrack(LKAddTrackRequest(cid, name, type, w, h, source))
    )

    fun sendLeave() = sendRequest(LKSignalRequest.Leave)

    private fun sendRequest(req: LKSignalRequest) {
        val bytes = LKProtoEncoder.encode(req)
        ws?.send(ByteString.of(*bytes))
    }

    // ── WebSocketListener ─────────────────────────────────────────────────

    override fun onOpen(webSocket: WebSocket, response: Response) {
        _events.tryEmit(Event.Connected)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val resp = LKProtoDecoder.decodeSignalResponse(bytes.toByteArray()) ?: return
        val event: Event? = when (resp) {
            is LKSignalResponse.Join           -> Event.JoinReceived(resp.response)
            is LKSignalResponse.Answer         -> Event.AnswerReceived(resp.sdp)
            is LKSignalResponse.Offer          -> Event.OfferReceived(resp.sdp)
            is LKSignalResponse.Trickle        -> Event.TrickleReceived(resp.req)
            is LKSignalResponse.TrackPublished -> Event.TrackPublished(resp.res)
            is LKSignalResponse.Leave          -> Event.Disconnected
            is LKSignalResponse.Unknown        -> null
        }
        event?.let { _events.tryEmit(it) }
    }

    override fun onMessage(webSocket: WebSocket, text: String) { /* LiveKit her zaman binary */ }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        _events.tryEmit(Event.Disconnected)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        _events.tryEmit(Event.Disconnected)
    }
}
