package com.furcord.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * TCP relay istemcisi – simetrik NAT durumunda UDP hole punching başarısız
 * olduğunda kullanılır. Basit mesaj protokolü:
 *
 * İstemci → Sunucu:  [4B channelHash][4B uidHash][2B dataLen][N byte ses]
 * Sunucu  → İstemci: [4B fromUidHash][2B dataLen][N byte ses]
 *
 * Sunucu kodu: relay-server/RelayServer.kt
 */
class RelayClient(
    private val host: String,
    private val port: Int,
    private val channelIdHash: Int,
    private val selfUidHash: Int,
    private val onFrame: (fromUidHash: Int, data: ByteArray) -> Unit,
) {
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var scope: CoroutineScope? = null

    var isConnected: Boolean = false
        private set

    /** Bağlantıyı başlatır. Blocking değil – arka planda receive döngüsü başlar. */
    fun connect() {
        try {
            socket = Socket(host, port).also { it.soTimeout = 0 }
            output = DataOutputStream(socket!!.getOutputStream().buffered())
            isConnected = true
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope!!.launch { receiveLoop() }
            // Kayıt paketi gönder (sunucu bu kanalda bizi tanısın; veri yok)
            sendRegistration()
        } catch (_: Exception) {
            isConnected = false
        }
    }

    /** Ses karesini relay sunucusuna gönderir. */
    fun sendFrame(data: ByteArray) {
        if (!isConnected || data.isEmpty()) return
        try {
            val out = output ?: return
            synchronized(out) {
                out.writeInt(channelIdHash)
                out.writeInt(selfUidHash)
                out.writeShort(data.size)
                out.write(data)
                out.flush()
            }
        } catch (_: Exception) {
            isConnected = false
        }
    }

    fun disconnect() {
        isConnected = false
        scope?.cancel(); scope = null
        runCatching { socket?.close() }
        socket = null; output = null
    }

    // ── Özel ──────────────────────────────────────────────────────────────────

    private fun sendRegistration() {
        try {
            val out = output ?: return
            synchronized(out) {
                out.writeInt(channelIdHash)
                out.writeInt(selfUidHash)
                out.writeShort(0) // dataLen = 0 → kayıt paketi
                out.flush()
            }
        } catch (_: Exception) {
            isConnected = false
        }
    }

    private fun receiveLoop() {
        val input = DataInputStream(socket!!.getInputStream().buffered())
        try {
            while (isConnected) {
                val fromUidHash = input.readInt()
                val dataLen     = input.readUnsignedShort()
                if (dataLen == 0) continue // keep-alive
                val data = ByteArray(dataLen)
                input.readFully(data)
                if (fromUidHash != selfUidHash) onFrame(fromUidHash, data)
            }
        } catch (_: Exception) {
            isConnected = false
        }
    }
}
