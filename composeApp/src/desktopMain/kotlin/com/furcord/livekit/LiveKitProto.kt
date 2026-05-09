package com.furcord.livekit

import java.io.ByteArrayOutputStream

// ─────────────────────────────────────────────────────────────────────────────
// LiveKit Protobuf Binary Codec (el yazısı)
//
// Protobuf plugin yerine bu dosya kullanılır — KMP + Protobuf Gradle plugin
// uyumsuzluğunu ortadan kaldırır. Sadece ekran paylaşımı için gereken mesajları
// kodlar/çözer.
//
// Wire types: 0=varint, 2=length-delimited
// ─────────────────────────────────────────────────────────────────────────────

private const val WT_VARINT = 0
private const val WT_LEN    = 2

// ── Kotlin data sınıfları (generated proto yerine) ────────────────────────────

data class LKSessionDescription(val type: String, val sdp: String)

data class LKTrickleRequest(
    val candidateInit: String,
    val target: Int    // 0 = PUBLISHER, 1 = SUBSCRIBER
)

data class LKAddTrackRequest(
    val cid: String,
    val name: String,
    val type: Int,     // 0=AUDIO, 1=VIDEO
    val width: Int,
    val height: Int,
    val source: Int    // 3=SCREEN_SHARE
)

data class LKICEServer(
    val urls: List<String>,
    val username: String,
    val credential: String
)

data class LKJoinResponse(val iceServers: List<LKICEServer>)

data class LKTrackPublishedResponse(val cid: String)

sealed class LKSignalRequest {
    data class Offer(val sdp: LKSessionDescription)   : LKSignalRequest()
    data class Answer(val sdp: LKSessionDescription)  : LKSignalRequest()
    data class Trickle(val req: LKTrickleRequest)     : LKSignalRequest()
    data class AddTrack(val req: LKAddTrackRequest)   : LKSignalRequest()
    object Leave                                       : LKSignalRequest()
}

sealed class LKSignalResponse {
    data class Join(val response: LKJoinResponse)                 : LKSignalResponse()
    data class Answer(val sdp: LKSessionDescription)              : LKSignalResponse()
    data class Offer(val sdp: LKSessionDescription)               : LKSignalResponse()
    data class Trickle(val req: LKTrickleRequest)                 : LKSignalResponse()
    data class TrackPublished(val res: LKTrackPublishedResponse)  : LKSignalResponse()
    object Leave                                                   : LKSignalResponse()
    data class Unknown(val fieldNumber: Int)                      : LKSignalResponse()
}

// ─────────────────────────────────────────────────────────────────────────────
// ENCODER  (istemci → sunucu)
// ─────────────────────────────────────────────────────────────────────────────

object LKProtoEncoder {

    fun encode(req: LKSignalRequest): ByteArray {
        val buf = ByteArrayOutputStream()
        when (req) {
            is LKSignalRequest.Offer     -> writeMessage(buf, 1, encodeSessionDesc(req.sdp))
            is LKSignalRequest.Answer    -> writeMessage(buf, 2, encodeSessionDesc(req.sdp))
            is LKSignalRequest.Trickle   -> writeMessage(buf, 3, encodeTrickle(req.req))
            is LKSignalRequest.AddTrack  -> writeMessage(buf, 4, encodeAddTrack(req.req))
            LKSignalRequest.Leave        -> writeMessage(buf, 8, byteArrayOf())
        }
        return buf.toByteArray()
    }

    private fun encodeSessionDesc(d: LKSessionDescription): ByteArray {
        val buf = ByteArrayOutputStream()
        writeString(buf, 1, d.type)
        writeString(buf, 2, d.sdp)
        return buf.toByteArray()
    }

    private fun encodeTrickle(r: LKTrickleRequest): ByteArray {
        val buf = ByteArrayOutputStream()
        writeString(buf, 1, r.candidateInit)
        if (r.target != 0) writeVarintField(buf, 2, r.target.toLong())
        return buf.toByteArray()
    }

    private fun encodeAddTrack(r: LKAddTrackRequest): ByteArray {
        val buf = ByteArrayOutputStream()
        writeString(buf, 1, r.cid)
        writeString(buf, 2, r.name)
        if (r.type   != 0) writeVarintField(buf, 3, r.type.toLong())
        if (r.width  != 0) writeVarintField(buf, 4, r.width.toLong())
        if (r.height != 0) writeVarintField(buf, 5, r.height.toLong())
        if (r.source != 0) writeVarintField(buf, 8, r.source.toLong())
        return buf.toByteArray()
    }

    // ── Low-level primitives ─────────────────────────────────────────────

    private fun writeVarint(buf: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.toLong().inv() != 0L) {
            buf.write((v and 0x7F or 0x80).toInt())
            v = v ushr 7
        }
        buf.write(v.toInt())
    }

    private fun writeTag(buf: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(buf, (fieldNumber.toLong() shl 3) or wireType.toLong())
    }

    private fun writeVarintField(buf: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeTag(buf, fieldNumber, WT_VARINT)
        writeVarint(buf, value)
    }

    private fun writeString(buf: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        if (value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(buf, fieldNumber, WT_LEN)
        writeVarint(buf, bytes.size.toLong())
        buf.write(bytes)
    }

    private fun writeMessage(buf: ByteArrayOutputStream, fieldNumber: Int, data: ByteArray) {
        writeTag(buf, fieldNumber, WT_LEN)
        writeVarint(buf, data.size.toLong())
        buf.write(data)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DECODER  (sunucu → istemci)
// ─────────────────────────────────────────────────────────────────────────────

object LKProtoDecoder {

    fun decodeSignalResponse(bytes: ByteArray): LKSignalResponse? {
        val r = ProtoReader(bytes)
        if (!r.hasMore()) return null
        val tag = r.readVarint()
        val fieldNumber = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        if (wireType != WT_LEN) { r.skipVarint(); return LKSignalResponse.Unknown(fieldNumber) }
        val len = r.readVarint().toInt()
        val data = r.readBytes(len)
        return when (fieldNumber) {
            1 -> LKSignalResponse.Join(decodeJoinResponse(data))
            2 -> LKSignalResponse.Answer(decodeSessionDesc(data))
            3 -> LKSignalResponse.Offer(decodeSessionDesc(data))
            4 -> LKSignalResponse.Trickle(decodeTrickle(data))
            6 -> LKSignalResponse.TrackPublished(decodeTrackPublished(data))
            8 -> LKSignalResponse.Leave
            else -> LKSignalResponse.Unknown(fieldNumber)
        }
    }

    private fun decodeSessionDesc(bytes: ByteArray): LKSessionDescription {
        var type = ""; var sdp = ""
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readVarint(); val fn = (tag ushr 3).toInt(); val wt = (tag and 7).toInt()
            if (wt == WT_LEN) {
                val s = r.readLenString()
                when (fn) { 1 -> type = s; 2 -> sdp = s }
            } else r.readVarint()
        }
        return LKSessionDescription(type, sdp)
    }

    private fun decodeTrickle(bytes: ByteArray): LKTrickleRequest {
        var cand = ""; var target = 0
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readVarint(); val fn = (tag ushr 3).toInt(); val wt = (tag and 7).toInt()
            when {
                wt == WT_LEN    && fn == 1 -> cand   = r.readLenString()
                wt == WT_VARINT && fn == 2 -> target = r.readVarint().toInt()
                wt == WT_LEN               -> r.skipLen()
                else                       -> r.readVarint()
            }
        }
        return LKTrickleRequest(cand, target)
    }

    private fun decodeJoinResponse(bytes: ByteArray): LKJoinResponse {
        val servers = mutableListOf<LKICEServer>()
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readVarint(); val fn = (tag ushr 3).toInt(); val wt = (tag and 7).toInt()
            if (wt == WT_LEN) {
                val len = r.readVarint().toInt()
                val data = r.readBytes(len)
                if (fn == 5) servers += decodeICEServer(data)
            } else r.readVarint()
        }
        return LKJoinResponse(servers)
    }

    private fun decodeICEServer(bytes: ByteArray): LKICEServer {
        val urls = mutableListOf<String>(); var user = ""; var cred = ""
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readVarint(); val fn = (tag ushr 3).toInt(); val wt = (tag and 7).toInt()
            if (wt == WT_LEN) {
                val s = r.readLenString()
                when (fn) { 1 -> urls += s; 2 -> user = s; 3 -> cred = s }
            } else r.readVarint()
        }
        return LKICEServer(urls, user, cred)
    }

    private fun decodeTrackPublished(bytes: ByteArray): LKTrackPublishedResponse {
        var cid = ""
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val tag = r.readVarint(); val fn = (tag ushr 3).toInt(); val wt = (tag and 7).toInt()
            if (wt == WT_LEN) {
                val s = r.readLenString()
                if (fn == 1) cid = s
            } else r.readVarint()
        }
        return LKTrackPublishedResponse(cid)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProtoReader — minimal binary parser
// ─────────────────────────────────────────────────────────────────────────────

private class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0

    fun hasMore() = pos < bytes.size

    fun readVarint(): Long {
        var result = 0L; var shift = 0
        while (pos < bytes.size) {
            val b = bytes[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    fun skipVarint() { readVarint() }

    fun readBytes(len: Int): ByteArray {
        val end = minOf(pos + len, bytes.size)
        val result = bytes.copyOfRange(pos, end)
        pos = end
        return result
    }

    /** Okur: varint length + UTF-8 bytes → String */
    fun readLenString(): String {
        val len = readVarint().toInt()
        return String(readBytes(len), Charsets.UTF_8)
    }

    /** Len-delimited alanı atla */
    fun skipLen() {
        val len = readVarint().toInt()
        pos = minOf(pos + len, bytes.size)
    }
}
