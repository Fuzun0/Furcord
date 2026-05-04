package com.furcord.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val PROJECT_ID = "furcord-13ab3"
private const val BASE = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"

data class VoiceChannel(val id: String, val name: String)

data class ActiveUser(
    val uid: String,
    val username: String,
    val color: String,
    val photoURL: String,
)

data class ChatMessage(
    val id:        String,
    val uid:       String,
    val username:  String,
    val photoURL:  String,
    val text:      String,
    val timestamp: Long,
)

/** Ses kanalına bağlı bir eşin bağlantı bilgileri. */
data class VoicePeer(val uid: String, val ip: String, val port: Int)

/** Aktif DM sohbeti — sol panelde ve FAB'da gösterilir. */
data class DmConversation(
    val dmId:          String,
    val otherUid:      String,
    val otherName:     String,
    val lastText:      String,
    val lastTimestamp: Long,
)

/** Arkadaş listesi kaydı. */
data class FriendEntry(val uid: String, val displayName: String, val furcordId: String)

/** Gelen arkadaşlık isteği. */
data class FriendRequest(
    val fromUid:  String,
    val fromName: String,
    val furcordId: String,
    val timestamp: Long,
)

/** Gelen sunucu daveti bildirimi. */
data class ServerInviteNotif(
    val serverId:   String,
    val serverName: String,
    val fromUid:    String,
    val fromName:   String,
    val timestamp:  Long,
)

/** Uygulama güncelleme bilgisi. */
data class AppVersionInfo(
    val latestVersion: String,
    val downloadUrl:   String,
    val releaseNotes:  String = "",
)

/** Disk tabanlı mesaj önbelleği — uygulama kapanıp açılsa da mesajlar kaybolmaz. */
object MessageStore {
    private fun file(serverId: String) =
        java.io.File(System.getProperty("user.home"), ".furcord_msgs_$serverId")

    /**
     * Mesajları disk'ten yükler.
     * Format: id\u0001uid\u0001username\u0001photoURL\u0001timestamp\u0001text (6 alan, limit=6 ile split)
     */
    fun get(serverId: String): List<ChatMessage> {
        return try {
            val f = file(serverId)
            if (!f.exists()) return emptyList()
            f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split("\u0001", limit = 6)
                if (p.size < 6) return@mapNotNull null
                ChatMessage(
                    id        = p[0],
                    uid       = p[1],
                    username  = p[2],
                    photoURL  = p[3],
                    timestamp = p[4].toLongOrNull() ?: 0L,
                    text      = p[5],
                )
            }.sortedBy { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    /** Mesajları diske yazar. Pending (optimistik) mesajlar dahil edilir. */
    fun set(serverId: String, messages: List<ChatMessage>) = try {
        file(serverId).writeText(
            messages.joinToString("\n") { m ->
                "${m.id}\u0001${m.uid}\u0001${m.username}\u0001${m.photoURL}\u0001${m.timestamp}\u0001${m.text}"
            }
        )
    } catch (_: Exception) {}

    /** Sunucu silindiğinde yerel mesaj dosyasını da temizler. */
    fun clear(serverId: String) = try { file(serverId).delete() } catch (_: Exception) {}
}

object FirestoreClient {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** Returns the server name if the server exists, throws otherwise. */
    suspend fun getServerName(serverId: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId", idToken = idToken)
            if (code !in 200..299) throw Exception("Sunucu bulunamadı.")
            val fieldsIdx = text.indexOf("\"fields\"")
            val search = if (fieldsIdx >= 0) text.substring(fieldsIdx) else text
            Regex("\"name\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(search)?.groupValues?.get(1) ?: serverId
        }

    /** Sunucunun sahibi olan kullanıcının UID'sini döndürür. */
    suspend fun getServerCreatorUid(serverId: String, idToken: String): String? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val fieldsIdx = text.indexOf("\"fields\"")
            val search = if (fieldsIdx >= 0) text.substring(fieldsIdx) else text
            Regex("\"creatorUid\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(search)?.groupValues?.get(1)
        }

    // ── Kick (sunucudan at) ───────────────────────────────────────────────────

    /** Kullanıcıyı sunucudan atar — kicked koleksiyonuna yazar. */
    suspend fun kickUser(serverId: String, targetUid: String, idToken: String) = withContext(Dispatchers.IO) {
        val body = """{"fields":{"kickedAt":{"integerValue":"${System.currentTimeMillis()}"}}}"""
        runCatching { request("PATCH", "$BASE/servers/$serverId/kicked/$targetUid", idToken = idToken, body = body) }
    }

    /** Kullanıcının sunucudan atılıp atılmadığını kontrol eder. */
    suspend fun isKicked(serverId: String, myUid: String, idToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val (code, _) = request("GET", "$BASE/servers/$serverId/kicked/$myUid", idToken = idToken)
            code in 200..299
        }

    /** Kicked kaydını temizler (kullanıcı sunucudan ayrıldıktan sonra). */
    suspend fun clearKickedFlag(serverId: String, uid: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/servers/$serverId/kicked/$uid", idToken = idToken) }
    }

    /** Kullanıcının oluşturduğu sunucuları listeler.
     * Returns list of (serverId, serverName).
     */
    suspend fun getMyServers(creatorUid: String, idToken: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val projectRoot = "projects/$PROJECT_ID/databases/(default)/documents"
            val queryUrl = "https://firestore.googleapis.com/v1/$projectRoot:runQuery"
            val body = """
                {
                  "structuredQuery": {
                    "from": [{"collectionId": "servers"}],
                    "where": {
                      "fieldFilter": {
                        "field": {"fieldPath": "creatorUid"},
                        "op": "EQUAL",
                        "value": {"stringValue": "$creatorUid"}
                      }
                    },
                    "limit": 20
                  }
                }
            """.trimIndent()
            val (status, text) = request("POST", queryUrl, body, idToken)
            if (status !in 200..299) return@withContext emptyList()
            // Her document'ı parse et
            val result = mutableListOf<Pair<String, String>>()
            val segments = text.split(Regex("""/servers/"""))
            for (i in 1 until segments.size) {
                val seg = segments[i]
                val sid = seg.substringBefore("\"").trim()
                if (sid.isEmpty()) continue
                val fieldsIdx = seg.indexOf("\"fields\"")
                val search = if (fieldsIdx >= 0) seg.substring(fieldsIdx) else seg
                val name = Regex("\"name\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                    .find(search)?.groupValues?.get(1) ?: continue
                result.add(sid to name)
            }
            result
        }

    /** Creates a new server document with auto-generated ID. Returns the new document ID. */
    suspend fun createServer(name: String, creatorUid: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val escapedName = name.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedUid  = creatorUid.replace("\\", "\\\\").replace("\"", "\\\"")
            val body = """{"fields":{"name":{"stringValue":"$escapedName"},"creatorUid":{"stringValue":"$escapedUid"}}}"""
            val (code, text) = request("POST", "$BASE/servers", body, idToken)
            if (code !in 200..299) throw Exception("Sunucu oluşturulamadı ($code): $text")
            // Response name: "projects/.../documents/servers/AUTOID"
            Regex("""/documents/servers/([^"/]+)""")
                .find(text)?.groupValues?.get(1)
                ?: throw Exception("Sunucu ID alınamadı.")
        }

    /** Creates a voice channel in the server's voiceChannels subcollection. */
    suspend fun createVoiceChannel(serverId: String, name: String, order: Int, idToken: String) =
        withContext(Dispatchers.IO) {
            val escapedName = name.replace("\\", "\\\\").replace("\"", "\\\"")
            val body = """{"fields":{"name":{"stringValue":"$escapedName"},"order":{"integerValue":"$order"}}}"""
            val (code, text) = request("POST", "$BASE/servers/$serverId/voiceChannels", body, idToken)
            if (code !in 200..299) throw Exception("Kanal oluşturulamadı ($code): $text")
        }

    // ── Invite links ──────────────────────────────────────────────────────────

    /**
     * Creates a new invite code and saves it as the `inviteCode` field on
     * servers/{serverId} — no separate collection needed, uses existing rules.
     * Returns the 8-character uppercase invite code.
     */
    suspend fun createInvite(serverId: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val code = (1..8).map { chars.random() }.joinToString("")
            val body = """{"fields":{"inviteCode":{"stringValue":"$code"}}}"""
            val url  = "$BASE/servers/$serverId?updateMask.fieldPaths=inviteCode"
            val (status, text) = request("PATCH", url, body, idToken)
            if (status !in 200..299) throw Exception("Davet oluşturulamadı ($status): $text")
            code
        }

    /**
     * Looks up a server by invite code via a structured Firestore query.
     * Returns (serverId, serverName) or null if not found.
     */
    suspend fun getServerByInvite(inviteCode: String, idToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val projectRoot = "projects/$PROJECT_ID/databases/(default)/documents"
            val queryUrl = "https://firestore.googleapis.com/v1/$projectRoot:runQuery"
            val body = """
                {
                  "structuredQuery": {
                    "from": [{"collectionId": "servers"}],
                    "where": {
                      "fieldFilter": {
                        "field": {"fieldPath": "inviteCode"},
                        "op": "EQUAL",
                        "value": {"stringValue": "$inviteCode"}
                      }
                    },
                    "limit": 1
                  }
                }
            """.trimIndent()
            val (status, text) = request("POST", queryUrl, body, idToken)
            if (status !in 200..299) return@withContext null
            // Response is an array; extract document name and name field
            val docPath = Regex(""""name"\s*:\s*"([^"]+/servers/[^"]+)"""").find(text)?.groupValues?.get(1)
                ?: return@withContext null
            val sid = docPath.substringAfterLast("/")
            if (sid.isEmpty()) return@withContext null
            // "fields" bölümünden sonra ara — document path "name" alanını atla
            val fieldsIdx = text.indexOf("\"fields\"")
            val search = if (fieldsIdx >= 0) text.substring(fieldsIdx) else text
            val serverName = Regex("\"name\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(search)?.groupValues?.get(1) ?: sid
            sid to serverName
        }

    /** Lists voice channels for the given server. */
    suspend fun listVoiceChannels(serverId: String, idToken: String): List<VoiceChannel> =        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voiceChannels", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseVoiceChannels(text)
        }

    /**
     * Firebase'deki TÜM sunucuları siler (voiceChannels + messages alt koleksiyonlarıyla).
     * Temiz başlangıç için kullanılır. Yerel mesaj dosyalarını da temizler.
     */
    suspend fun deleteAllServers(idToken: String) = withContext(Dispatchers.IO) {
        val (code, text) = request("GET", "$BASE/servers?pageSize=100", idToken = idToken)
        if (code !in 200..299) return@withContext
        val serverIds = Regex("""/servers/([^"/]+)""").findAll(text)
            .map { it.groupValues[1] }.filter { it.isNotEmpty() }.toSet()
        for (sid in serverIds) {
            deleteServerById(sid, idToken)
        }
    }

    /** Tek bir sunucuyu alt koleksiyonlarıyla birlikte siler ve yerel mesaj dosyasını temizler. */
    suspend fun deleteServer(serverId: String, idToken: String) = withContext(Dispatchers.IO) {
        deleteServerById(serverId, idToken)
    }

    // ── Voice peer signaling ──────────────────────────────────────────────────

    /**
     * Kullanıcının ses kanalı bağlantı bilgilerini (IP:port) Firestore'a yazar.
     * Diğer kullanıcılar bunu okuyarak UDP paketleri gönderebilir.
     */
    suspend fun setVoicePeer(
        serverId: String, channelId: String, uid: String,
        ip: String, port: Int, idToken: String,
    ) = withContext(Dispatchers.IO) {
        val body = """{"fields":{"channelId":{"stringValue":"$channelId"},"ip":{"stringValue":"$ip"},"port":{"integerValue":"$port"}}}"""
        request("PATCH", "$BASE/servers/$serverId/voicePeers/$uid", idToken = idToken, body = body)
    }

    /** Aynı ses kanalındaki diğer kullanıcıların bağlantı bilgilerini getirir. */
    suspend fun getVoicePeers(serverId: String, channelId: String, idToken: String): List<VoicePeer> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voicePeers?pageSize=50", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseVoicePeers(text, channelId)
        }

    /** Kullanıcının ses kanalı bağlantı kaydını siler (kanal terk edildiğinde). */
    suspend fun removeVoicePeer(serverId: String, uid: String, idToken: String) =
        withContext(Dispatchers.IO) {
            runCatching { request("DELETE", "$BASE/servers/$serverId/voicePeers/$uid", idToken = idToken) }
        }

    private fun parseVoicePeers(json: String, filterChannelId: String): List<VoicePeer> {
        if (!json.contains("\"documents\"")) return emptyList()
        val nameRegex = Regex("""/voicePeers/([^"/\s]+)""")
        val docs = nameRegex.findAll(json).toList()
        return docs.mapIndexed { i, m ->
            val uid   = m.groupValues[1]
            val from  = m.range.last
            val to    = docs.getOrNull(i + 1)?.range?.first ?: json.length
            val chunk = json.substring(from, to)
            val ip    = Regex(""""ip"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: return@mapIndexed null
            val port  = Regex(""""port"\s*:\s*\{"integerValue"\s*:\s*"(\d+)"""").find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapIndexed null
            val chId  = Regex(""""channelId"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: return@mapIndexed null
            if (chId != filterChannelId) return@mapIndexed null
            VoicePeer(uid = uid, ip = ip, port = port)
        }.filterNotNull()
    }

    private suspend fun deleteServerById(sid: String, idToken: String) {
        runCatching {
            val (mc, mt) = request("GET", "$BASE/servers/$sid/messages?pageSize=200", idToken = idToken)
            if (mc in 200..299) {
                Regex("""/messages/([^"/]+)""").findAll(mt).map { it.groupValues[1] }
                    .filter { it.isNotEmpty() }.forEach { mid ->
                        runCatching { request("DELETE", "$BASE/servers/$sid/messages/$mid", idToken = idToken) }
                    }
            }
        }
        runCatching {
            val (vc, vt) = request("GET", "$BASE/servers/$sid/voiceChannels?pageSize=100", idToken = idToken)
            if (vc in 200..299) {
                Regex("""/voiceChannels/([^"/]+)""").findAll(vt).map { it.groupValues[1] }
                    .filter { it.isNotEmpty() }.forEach { vcid ->
                        runCatching { request("DELETE", "$BASE/servers/$sid/voiceChannels/$vcid", idToken = idToken) }
                    }
            }
        }
        runCatching { request("DELETE", "$BASE/servers/$sid", idToken = idToken) }
        MessageStore.clear(sid)
    }

    // ── Chat messages ─────────────────────────────────────────────────────────

    /** Sends a chat message to servers/{serverId}/messages subcollection. */
    suspend fun sendMessage(
        serverId: String,
        uid: String,
        username: String,
        photoURL: String,
        text: String,
        idToken: String,
    ) = withContext(Dispatchers.IO) {
        val escapedUsername = username.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedText     = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedPhoto    = photoURL.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedUid      = uid.replace("\\", "\\\\").replace("\"", "\\\"")
        val ts = System.currentTimeMillis()
        val body = """{"fields":{"uid":{"stringValue":"$escapedUid"},"username":{"stringValue":"$escapedUsername"},"photoURL":{"stringValue":"$escapedPhoto"},"text":{"stringValue":"$escapedText"},"timestamp":{"integerValue":"$ts"}}}"""
        val (code, resp) = request("POST", "$BASE/servers/$serverId/messages", body, idToken)
        if (code !in 200..299) throw Exception("Mesaj gönderilemedi ($code): $resp")
    }

    /** Fetches the latest messages (up to 50) from servers/{serverId}/messages. */
    suspend fun listMessages(serverId: String, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            // Firestore REST doesn't support orderBy without an index; we fetch all and sort client-side
            val (code, text) = request("GET", "$BASE/servers/$serverId/messages?pageSize=50", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseMessages(text)
        }

    // ── User profile ──────────────────────────────────────────────────────────

    /** Saves / updates user profile in users/{uid} document. */
    suspend fun saveUserProfile(
        uid: String,
        displayName: String,
        photoURL: String,
        idToken: String,
    ) = withContext(Dispatchers.IO) {
        val escapedName  = displayName.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedPhoto = photoURL.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{"displayName":{"stringValue":"$escapedName"},"photoURL":{"stringValue":"$escapedPhoto"}}}"""
        val url  = "$BASE/users/$uid?updateMask.fieldPaths=displayName&updateMask.fieldPaths=photoURL"
        val (code, resp) = request("PATCH", url, body, idToken)
        if (code !in 200..299) throw Exception("Profil kaydedilemedi ($code): $resp")
    }

    /** Reads user profile from users/{uid} document. Returns null if not found. */
    suspend fun getUserProfile(uid: String, idToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/users/$uid", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val displayName = Regex(""""displayName"\s*:\s*\{"stringValue"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1) ?: ""
            val photoURL    = Regex(""""photoURL"\s*:\s*\{"stringValue"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1) ?: ""
            displayName to photoURL
        }

    /** Returns the active users list for a single voice channel document. */
    suspend fun getChannelActiveUsers(serverId: String, channelId: String, idToken: String): List<ActiveUser> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voiceChannels/$channelId", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseActiveUsers(text)
        }

    /** Overwrites the activeUsers field of a voice channel document. */
    suspend fun setChannelActiveUsers(
        serverId: String,
        channelId: String,
        users: List<ActiveUser>,
        idToken: String,
    ) = withContext(Dispatchers.IO) {
        val body = buildActiveUsersBody(users)
        val url  = "$BASE/servers/$serverId/voiceChannels/$channelId?updateMask.fieldPaths=activeUsers"
        val (code, text) = request("PATCH", url, body, idToken)
        if (code !in 200..299) throw Exception("Aktif kullanıcılar güncellenemedi ($code): $text")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMessages(json: String): List<ChatMessage> {
        if (!json.contains("/messages/")) return emptyList()
        val result = mutableListOf<ChatMessage>()
        val segments = json.split(Regex("""/messages/"""))
        for (i in 1 until segments.size) {
            val seg  = segments[i]
            val docId = seg.substringBefore("\"").trim()
            fun str(name: String) = Regex(""""$name"\s*:\s*\{"stringValue"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                .find(seg)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
            fun lng(name: String) = Regex(""""$name"\s*:\s*\{"integerValue"\s*:\s*"?(\d+)"?""")
                .find(seg)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val text = str("text")
            if (docId.isNotEmpty() && text.isNotEmpty()) {
                result.add(ChatMessage(
                    id        = docId,
                    uid       = str("uid"),
                    username  = str("username"),
                    photoURL  = str("photoURL"),
                    text      = text,
                    timestamp = lng("timestamp"),
                ))
            }
        }
        return result.sortedBy { it.timestamp }
    }

    private fun parseActiveUsers(json: String): List<ActiveUser> {
        val auIdx = json.indexOf(""""activeUsers"""")
        if (auIdx < 0) return emptyList()
        val afterAu = json.substring(auIdx)
        val avIdx   = afterAu.indexOf(""""arrayValue"""")
        if (avIdx < 0) return emptyList()
        val afterAv = afterAu.substring(avIdx)
        val mapParts = afterAv.split(""""mapValue"""")
        if (mapParts.size <= 1) return emptyList()
        val result = mutableListOf<ActiveUser>()
        for (i in 1 until mapParts.size) {
            val part = mapParts[i]
            fun field(name: String) =
                Regex(""""$name"\s*:\s*\{"stringValue"\s*:\s*"([^"]*)"""").find(part)?.groupValues?.get(1) ?: ""
            val uid = field("uid")
            if (uid.isNotEmpty()) result.add(ActiveUser(uid, field("username"), field("color"), field("photoURL")))
        }
        return result
    }

    private fun buildActiveUsersBody(users: List<ActiveUser>): String {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val values = users.joinToString(",") { u ->
            """{"mapValue":{"fields":{"uid":{"stringValue":"${u.uid.esc()}"},"username":{"stringValue":"${u.username.esc()}"},"color":{"stringValue":"${u.color.esc()}"},"photoURL":{"stringValue":"${u.photoURL.esc()}"}}}}"""
        }
        return """{"fields":{"activeUsers":{"arrayValue":{"values":[$values]}}}}"""
    }

    private fun parseVoiceChannels(json: String): List<VoiceChannel> {
        if (!json.contains("/voiceChannels/")) return emptyList()
        val result = mutableListOf<VoiceChannel>()
        val segments = json.split(Regex("""/voiceChannels/"""))
        for (i in 1 until segments.size) {
            val seg = segments[i]
            val docId = seg.substringBefore("\"").trim()
            // Specifically extract the "name" field to avoid picking up activeUsers stringValues
            val channelName = Regex("\"name\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(seg)?.groupValues?.get(1) ?: continue
            if (docId.isNotEmpty()) result.add(VoiceChannel(docId, channelName))
        }
        return result
    }

    // ── Direct Messages ───────────────────────────────────────────────────────

    /**
     * İki kullanıcı arasındaki DM sohbetine mesaj yazar.
     * Koleksiyon: directMessages/{dmId}/messages
     * dmId = küçük uid önce gelecek şekilde iki uid'nin alfabetik birleşimi.
     */
    suspend fun sendDm(
        senderUid: String, senderName: String, senderPhoto: String,
        recipientUid: String, text: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        val dmId  = listOf(senderUid, recipientUid).sorted().joinToString("_")
        val esc   = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val escN  = senderName.replace("\\", "\\\\").replace("\"", "\\\"")
        val body  = """{"fields":{
            "uid":{"stringValue":"$senderUid"},
            "username":{"stringValue":"$escN"},
            "photoURL":{"stringValue":"$senderPhoto"},
            "text":{"stringValue":"$esc"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        request("POST", "$BASE/directMessages/$dmId/messages", idToken = idToken, body = body)
    }

    /** DM sohbetindeki mesajları getirir (en eski → en yeni). */
    suspend fun listDms(senderUid: String, recipientUid: String, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val dmId = listOf(senderUid, recipientUid).sorted().joinToString("_")
            val (code, text) = request("GET", "$BASE/directMessages/$dmId/messages?pageSize=200", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseMessages(text)
        }

    /**
     * Kullanıcının arkadaş profilini uid'si ile getirir.
     * Firestore: users/{uid} — register/login sırasında kaydedilir.
     */
    suspend fun getUserByFurcordId(furcordId: String, idToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            // furcordId arama: users koleksiyonunu tara (pageSize=500, küçük koleksiyon)
            val (code, text) = request("GET", "$BASE/users?pageSize=500", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val uidRegex  = Regex("""/users/([^"/\s]+)""")
            val docs      = uidRegex.findAll(text).toList()
            docs.forEach { m ->
                val uid   = m.groupValues[1]
                val from  = m.range.last
                val to    = docs.getOrNull(docs.indexOf(m) + 1)?.range?.first ?: text.length
                val chunk = text.substring(from, to)
                val fid   = Regex(""""furcordId"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                if (fid == furcordId) {
                    val name  = Regex(""""displayName"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: uid
                    return@withContext uid to name
                }
            }
            null
        }

    /**
     * Kullanıcı profilini Firestore'a kaydeder (displayName, photoURL, furcordId, email).
     * Her login/kayıt + profil güncelleme sırasında çağrılır.
     */
    /**
     * Kullanıcıyı nickname ile arar. Nickname e\u015fsiz oldu\u011fu için tam e\u015fle\u015fme yap\u0131l\u0131r.
     */
    suspend fun getUserByNickname(nickname: String, idToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/users?pageSize=500", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val uidRegex  = Regex("""/users/([^"/\s]+)""")
            val docs      = uidRegex.findAll(text).toList()
            docs.forEach { m ->
                val uid   = m.groupValues[1]
                val from  = m.range.last
                val to    = docs.getOrNull(docs.indexOf(m) + 1)?.range?.first ?: text.length
                val chunk = text.substring(from, to)
                val nic   = Regex(""""nickname"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                if (nic == nickname) {
                    val name  = Regex(""""displayName"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: uid
                    return@withContext uid to name
                }
            }
            null
        }

    /**
     * Belirli bir nickname'in kullan\u0131labilir olup olmad\u0131\u011f\u0131n\u0131 kontrol eder.
     */
    suspend fun checkNicknameAvailable(nickname: String, idToken: String): Boolean =
        withContext(Dispatchers.IO) {
            (getUserByNickname(nickname, idToken) == null)
        }

    /**
     * Kullan\u0131c\u0131 profilini Firestore'a kaydeder (displayName, photoURL, furcordId, email, nickname).
     * Her login/kay\u0131t + profil g\u00fcncellemesi s\u0131ras\u0131nda \u00e7a\u011fr\u0131l\u0131r.
     */
    suspend fun saveUserRecord(
        uid: String, displayName: String, photoURL: String,
        furcordId: String, email: String, idToken: String, nickname: String = "",
    ) = withContext(Dispatchers.IO) {
        val escN = displayName.replace("\\", "\\\\").replace("\"", "\\\"")
        val escE = email.replace("\\", "\\\\").replace("\"", "\\\"")
        val escNic = nickname.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{
            "displayName":{"stringValue":"$escN"},
            "photoURL":{"stringValue":"$photoURL"},
            "furcordId":{"stringValue":"$furcordId"},
            "email":{"stringValue":"$escE"},
            "nickname":{"stringValue":"$escNic"}
        }}""".trimIndent()
        runCatching { request("PATCH", "$BASE/users/$uid", idToken = idToken, body = body) }
    }

    // ── Request helper ────────────────────────────────────────────────────────

    /**
     * Bu kullanıcının katıldığı tüm DM sohbetlerini döner.
     * directMessages koleksiyonundaki tüm dökümanları tarar —
     * dmId = "uid1_uid2" formatında, her ikisi de biz olabilir.
     */
    suspend fun listDmConversations(myUid: String, idToken: String): List<DmConversation> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/directMessages?pageSize=500", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val dmIdRegex = Regex("""/directMessages/([^"/\s]+)""")
            val found     = mutableListOf<DmConversation>()
            val dmIds     = dmIdRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()
            for (dmId in dmIds) {
                val parts = dmId.split("_")
                if (parts.size < 2) continue
                val otherUid = parts.firstOrNull { it != myUid } ?: continue
                if (!dmId.contains(myUid)) continue
                // Son mesajı al
                val (mc, mt) = request("GET",
                    "$BASE/directMessages/$dmId/messages?pageSize=1&orderBy=timestamp%20desc",
                    idToken = idToken)
                if (mc !in 200..299) continue
                val msgs = parseMessages(mt)
                val last = msgs.lastOrNull() ?: continue
                // Karşı tarafın adını bul
                val otherName = if (last.uid == myUid) {
                    // en son mesaj bizim, diğer kullanıcı adını users koleksiyonundan çek
                    runCatching {
                        val (uc, ut) = request("GET", "$BASE/users/$otherUid", idToken = idToken)
                        if (uc in 200..299) {
                            Regex(""""displayName"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(ut)?.groupValues?.get(1)
                                ?: otherUid
                        } else otherUid
                    }.getOrDefault(otherUid)
                } else last.username
                found.add(DmConversation(
                    dmId          = dmId,
                    otherUid      = otherUid,
                    otherName     = otherName,
                    lastText      = last.text,
                    lastTimestamp = last.timestamp,
                ))
            }
            found.sortedByDescending { it.lastTimestamp }
        }

    /**
     * Bu kullanıcının arkadaş listesini döner.
     * Firestore: friends/{myUid}/list/{friendUid} → friends koleksiyonunu okur.
     */
    suspend fun listFriends(myUid: String, idToken: String): List<FriendEntry> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/friends/$myUid/list?pageSize=200", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val uidRegex = Regex("""/list/([^"/\s]+)""")
            val uids     = uidRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()
            val entries  = mutableListOf<FriendEntry>()
            for (uid in uids) {
                val (uc, ut) = request("GET", "$BASE/users/$uid", idToken = idToken)
                if (uc !in 200..299) continue
                val name = Regex(""""displayName"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(ut)?.groupValues?.get(1) ?: uid
                val fid  = Regex(""""furcordId"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(ut)?.groupValues?.get(1) ?: ""
                entries.add(FriendEntry(uid, name, fid))
            }
            entries
        }

    /** Arkadaş ekle (iki taraflı kayıt). */
    suspend fun addFriend(myUid: String, friendUid: String, idToken: String) = withContext(Dispatchers.IO) {
        val ts   = System.currentTimeMillis()
        val body = """{"fields":{"addedAt":{"integerValue":"$ts"}}}"""
        runCatching { request("PATCH", "$BASE/friends/$myUid/list/$friendUid", idToken = idToken, body = body) }
        runCatching { request("PATCH", "$BASE/friends/$friendUid/list/$myUid",  idToken = idToken, body = body) }
    }

    // ── Friend request notifications ──────────────────────────────────────────

    /** Arkadaşlık isteği gönder — karşı tarafın bildirim koleksiyonuna yazar. */
    suspend fun sendFriendRequest(
        toUid: String, fromUid: String, fromName: String, furcordId: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        val escN  = fromName.replace("\\", "\\\\").replace("\"", "\\\"")
        val escId = furcordId.replace("\\", "\\\\").replace("\"", "\\\"")
        val body  = """{"fields":{
            "fromUid":{"stringValue":"$fromUid"},
            "fromName":{"stringValue":"$escN"},
            "furcordId":{"stringValue":"$escId"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        runCatching { request("PATCH", "$BASE/friendRequests/$toUid/pending/$fromUid", idToken = idToken, body = body) }
    }

    /** Gelen arkadaşlık isteklerini listeler. */
    suspend fun listFriendRequests(myUid: String, idToken: String): List<FriendRequest> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/friendRequests/$myUid/pending?pageSize=50", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val segments = text.split(Regex("""/pending/"""))
            val result   = mutableListOf<FriendRequest>()
            for (i in 1 until segments.size) {
                val seg = segments[i]
                fun str(n: String) = Regex(""""$n"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(seg)?.groupValues?.get(1) ?: ""
                fun lng(n: String) = Regex(""""$n"\s*:\s*\{"integerValue"\s*:\s*"?(\d+)"?""").find(seg)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uid = str("fromUid")
                if (uid.isNotEmpty()) result.add(FriendRequest(uid, str("fromName"), str("furcordId"), lng("timestamp")))
            }
            result.sortedByDescending { it.timestamp }
        }

    /** Arkadaşlık isteğini kabul et. */
    suspend fun acceptFriendRequest(myUid: String, fromUid: String, idToken: String) = withContext(Dispatchers.IO) {
        addFriend(myUid, fromUid, idToken)
        runCatching { request("DELETE", "$BASE/friendRequests/$myUid/pending/$fromUid", idToken = idToken) }
    }

    /** Arkadaşlık isteğini reddet. */
    suspend fun rejectFriendRequest(myUid: String, fromUid: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/friendRequests/$myUid/pending/$fromUid", idToken = idToken) }
    }

    // ── Server invite notifications ───────────────────────────────────────────

    /** Kullanıcıya sunucu daveti bildirimi gönder. */
    suspend fun sendServerInviteNotif(
        toUid: String, serverId: String, serverName: String,
        fromUid: String, fromName: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        val escS = serverName.replace("\\", "\\\\").replace("\"", "\\\"")
        val escN = fromName.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{
            "serverId":{"stringValue":"$serverId"},
            "serverName":{"stringValue":"$escS"},
            "fromUid":{"stringValue":"$fromUid"},
            "fromName":{"stringValue":"$escN"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        runCatching { request("PATCH", "$BASE/serverInvites/$toUid/pending/$serverId", idToken = idToken, body = body) }
    }

    /** Gelen sunucu davetlerini listeler. */
    suspend fun listServerInviteNotifs(myUid: String, idToken: String): List<ServerInviteNotif> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/serverInvites/$myUid/pending?pageSize=50", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val segments = text.split(Regex("""/pending/"""))
            val result   = mutableListOf<ServerInviteNotif>()
            for (i in 1 until segments.size) {
                val seg = segments[i]
                fun str(n: String) = Regex(""""$n"\s*:\s*\{"stringValue"\s*:\s*"([^"]+)"""").find(seg)?.groupValues?.get(1) ?: ""
                fun lng(n: String) = Regex(""""$n"\s*:\s*\{"integerValue"\s*:\s*"?(\d+)"?""").find(seg)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val sid = str("serverId")
                if (sid.isNotEmpty()) result.add(ServerInviteNotif(sid, str("serverName"), str("fromUid"), str("fromName"), lng("timestamp")))
            }
            result.sortedByDescending { it.timestamp }
        }

    /** Sunucu davetini siler. */
    suspend fun removeServerInviteNotif(myUid: String, serverId: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/serverInvites/$myUid/pending/$serverId", idToken = idToken) }
    }

    /** Firestore'daki güncel sürüm bilgisini getirir.
     *  Belge yolu: config/appVersion
     *  Alanlar   : latestVersion, downloadUrl, releaseNotes */
    suspend fun getLatestVersion(idToken: String): AppVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val (code, body) = request("GET", "$BASE/config/appVersion", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val latestVersion = Regex("\"latestVersion\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1) ?: return@withContext null
            val downloadUrl = Regex("\"downloadUrl\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1) ?: return@withContext null
            val releaseNotes = Regex("\"releaseNotes\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1) ?: ""
            AppVersionInfo(latestVersion, downloadUrl, releaseNotes)
        } catch (_: Exception) { null }
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        idToken: String,
    ): Pair<Int, String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $idToken")
            .timeout(Duration.ofSeconds(15))
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return response.statusCode() to response.body()
    }
}
