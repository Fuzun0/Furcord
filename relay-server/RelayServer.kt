/**
 * Furcord Relay Sunucusu
 * =====================
 * Simetrik NAT arkasındaki istemciler için ses paketlerini yönlendirir.
 *
 * Derleme & çalıştırma (JDK 11+):
 *   kotlinc RelayServer.kt -include-runtime -d relay-server.jar
 *   java -jar relay-server.jar 8080
 *
 * Ya da direkt: kotlinc -script RelayServer.kt
 *
 * Protokol (istemci → sunucu):
 *   [4B channelHash][4B uidHash][2B dataLen][N byte ses verisi]
 * Protokol (sunucu → istemci):
 *   [4B fromUidHash][2B dataLen][N byte ses verisi]
 *
 * dataLen == 0  →  kayıt / keep-alive paketi (veri iletilmez)
 */

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    println("[RelayServer] Başlatılıyor: port=$port")

    // channelHash → istemci listesi
    val channels = ConcurrentHashMap<Int, CopyOnWriteArrayList<ClientConn>>()

    val server = ServerSocket(port)
    println("[RelayServer] Bağlantı bekleniyor...")

    while (true) {
        val sock = server.accept()
        sock.tcpNoDelay = true
        Thread {
            val conn = ClientConn(sock)
            try {
                val input  = DataInputStream(sock.getInputStream().buffered())
                val output = DataOutputStream(sock.getOutputStream().buffered())
                conn.output = output

                var channelHash = 0
                var uidHash     = 0
                var registered  = false

                while (true) {
                    val ch      = input.readInt()
                    val uid     = input.readInt()
                    val dataLen = input.readUnsignedShort()

                    if (!registered) {
                        channelHash = ch; uidHash = uid; registered = true
                        conn.uidHash = uidHash
                        channels.getOrPut(channelHash) { CopyOnWriteArrayList() }.add(conn)
                        println("[RelayServer] Yeni istemci: channel=$channelHash uid=$uidHash")
                    }

                    if (dataLen == 0) continue // kayıt / keep-alive

                    val data = ByteArray(dataLen)
                    input.readFully(data)

                    // Aynı kanalda olan diğer istemcilere ilet
                    val peers = channels[channelHash] ?: continue
                    for (peer in peers) {
                        if (peer.uidHash == uidHash) continue
                        peer.send(uidHash, data)
                    }
                }
            } catch (_: Exception) {
                val peers = channels.values
                peers.forEach { it.remove(conn) }
                println("[RelayServer] İstemci ayrıldı: ${sock.remoteSocketAddress}")
            } finally {
                runCatching { sock.close() }
            }
        }.also { it.isDaemon = true }.start()
    }
}

class ClientConn(val socket: Socket) {
    @Volatile var uidHash: Int = 0
    @Volatile var output: DataOutputStream? = null

    fun send(fromUidHash: Int, data: ByteArray) {
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeInt(fromUidHash)
                out.writeShort(data.size)
                out.write(data)
                out.flush()
            }
        } catch (_: Exception) {}
    }
}
