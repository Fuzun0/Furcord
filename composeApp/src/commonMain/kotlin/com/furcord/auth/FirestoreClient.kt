package com.furcord.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
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
    /** Son mesajı gönderenin uid'si — okunmamış tespiti için kullanılır. */
    val lastSenderUid: String = "",
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
            if (code !in 200..299) throw Exception("Sunucu bulunamad\u0131.")
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
            doc?.fields?.str("name")?.takeIf { it.isNotEmpty() } ?: serverId
        }

    /** Sunucunun sahibi olan kullan\u0131c\u0131n\u0131n UID'sini d\u00f6nd\u00fcr\u00fcr. */
    suspend fun getServerCreatorUid(serverId: String, idToken: String): String? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
            doc?.fields?.str("creatorUid")?.takeIf { it.isNotEmpty() }
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
            val queryUrl = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents:runQuery"
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
            val results = runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }.getOrNull()
                ?: return@withContext emptyList()
            results.mapNotNull { item ->
                val doc  = item.document ?: return@mapNotNull null
                val sid  = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = doc.fields.str("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                sid to name
            }
        }

    /** Creates a new server document with auto-generated ID. Returns the new document ID. */
    suspend fun createServer(name: String, creatorUid: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val escapedName = name.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedUid  = creatorUid.replace("\\", "\\\\").replace("\"", "\\\"")
            val body = """{"fields":{"name":{"stringValue":"$escapedName"},"creatorUid":{"stringValue":"$escapedUid"}}}"""
            val (code, text) = request("POST", "$BASE/servers", body, idToken)
            if (code !in 200..299) throw Exception("Sunucu oluşturulamadı ($code): $text")
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
            doc?.name?.substringAfterLast("/")?.takeIf { it.isNotEmpty() }
                ?: Regex("""/documents/servers/([^"/ ]+)""").find(text)?.groupValues?.get(1)
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
            val doc = runStructuredQuery("servers", "inviteCode", inviteCode, idToken)
                ?: return@withContext null
            val sid  = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: return@withContext null
            val name = doc.fields.str("name").takeIf { it.isNotEmpty() } ?: sid
            sid to name
        }

    /** Lists voice channels for the given server. */
    suspend fun listVoiceChannels(serverId: String, idToken: String): List<VoiceChannel> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voiceChannels", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            resp.documents.mapNotNull { doc ->
                val name = doc.fields.str("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                VoiceChannel(doc.name.substringAfterLast("/"), name)
            }
        }

    /**
     * Firebase'deki TÜM sunucuları siler (voiceChannels + messages alt koleksiyonlarıyla).
     * Temiz başlangıç için kullanılır. Yerel mesaj dosyalarını da temizler.
     */
    suspend fun deleteAllServers(idToken: String) = withContext(Dispatchers.IO) {
        val (code, text) = request("GET", "$BASE/servers?pageSize=100", idToken = idToken)
        if (code !in 200..299) return@withContext
        val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull() ?: return@withContext
        for (doc in resp.documents) {
            val sid = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: continue
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
        val resp = runCatching { fsJson.decodeFromString<FsListResponse>(json) }.getOrNull()
            ?: return emptyList()
        return resp.documents.mapNotNull { doc ->
            val f    = doc.fields
            val uid  = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val chId = f.str("channelId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (chId != filterChannelId) return@mapNotNull null
            val ip   = f.str("ip").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val port = f["port"]?.integer?.toIntOrNull() ?: return@mapNotNull null
            VoicePeer(uid = uid, ip = ip, port = port)
        }
    }

    private suspend fun deleteServerById(sid: String, idToken: String) {
        runCatching {
            val (mc, mt) = request("GET", "$BASE/servers/$sid/messages?pageSize=200", idToken = idToken)
            if (mc in 200..299) {
                val resp = runCatching { fsJson.decodeFromString<FsListResponse>(mt) }.getOrNull()
                resp?.documents?.forEach { doc ->
                    val mid = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: return@forEach
                    runCatching { request("DELETE", "$BASE/servers/$sid/messages/$mid", idToken = idToken) }
                }
            }
        }
        runCatching {
            val (vc, vt) = request("GET", "$BASE/servers/$sid/voiceChannels?pageSize=100", idToken = idToken)
            if (vc in 200..299) {
                val resp = runCatching { fsJson.decodeFromString<FsListResponse>(vt) }.getOrNull()
                resp?.documents?.forEach { doc ->
                    val vcid = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: return@forEach
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
            val (code, text) = request("GET", "$BASE/servers/$serverId/messages?pageSize=50", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseMsgsFromJson(text)
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

    suspend fun getUserProfile(uid: String, idToken: String): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/users/$uid", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
                ?: return@withContext null
            Triple(
                doc.fields.str("displayName"),
                doc.fields.str("photoURL"),
                doc.fields.str("nickname"),
            )
        }

    /** Returns the active users list for a single voice channel document. */
    suspend fun getChannelActiveUsers(serverId: String, channelId: String, idToken: String): List<ActiveUser> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voiceChannels/$channelId", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
                ?: return@withContext emptyList()
            parseActiveUsersFromDoc(doc)
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

    /**
     * Parses a Firestore list response (GET /collection) into ChatMessage list.
     * Uses kotlinx.serialization — no regex.
     */
    private fun parseMsgsFromJson(json: String): List<ChatMessage> {
        val resp = runCatching { fsJson.decodeFromString<FsListResponse>(json) }.getOrNull()
            ?: return emptyList()
        return resp.documents.mapNotNull { doc ->
            val f   = doc.fields
            val msg = f.str("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChatMessage(
                id        = doc.name.substringAfterLast("/"),
                uid       = f.str("uid"),
                username  = f.str("username"),
                photoURL  = f.str("photoURL"),
                text      = msg,
                timestamp = f.lng("timestamp"),
            )
        }.sortedBy { it.timestamp }
    }

    /**
     * Parses activeUsers arrayValue from an already-deserialized FsDocument.
     */
    private fun parseActiveUsersFromDoc(doc: FsDocument): List<ActiveUser> {
        val values = doc.fields["activeUsers"]?.array?.values ?: return emptyList()
        return values.mapNotNull { v ->
            val f   = v.map?.fields ?: return@mapNotNull null
            val uid = f.str("uid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ActiveUser(uid, f.str("username"), f.str("color"), f.str("photoURL"))
        }
    }

    private fun buildActiveUsersBody(users: List<ActiveUser>): String {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val values = users.joinToString(",") { u ->
            """{"mapValue":{"fields":{"uid":{"stringValue":"${u.uid.esc()}"},"username":{"stringValue":"${u.username.esc()}"},"color":{"stringValue":"${u.color.esc()}"},"photoURL":{"stringValue":"${u.photoURL.esc()}"}}}}"""
        }
        return """{"fields":{"activeUsers":{"arrayValue":{"values":[$values]}}}}"""
    }

    /**
     * Runs a Firestore Structured Query for an exact field match.
     * Returns the first matching FsDocument or null.
     */
    private suspend fun runStructuredQuery(
        collectionId: String,
        fieldPath: String,
        value: String,
        idToken: String,
    ): FsDocument? {
        val queryUrl    = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents:runQuery"
        val escapedVal  = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """
            {
              "structuredQuery": {
                "from": [{"collectionId": "$collectionId"}],
                "where": {
                  "fieldFilter": {
                    "field": {"fieldPath": "$fieldPath"},
                    "op": "EQUAL",
                    "value": {"stringValue": "$escapedVal"}
                  }
                },
                "limit": 1
              }
            }
        """.trimIndent()
        val (code, text) = request("POST", queryUrl, body, idToken)
        if (code !in 200..299) return null
        return runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }
            .getOrNull()?.firstOrNull { it.document != null }?.document
    }

    // ── Direct Messages ───────────────────────────────────────────────────────

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
        request("POST", "$BASE/dm_threads/$dmId/messages", idToken = idToken, body = body)
    }

    /**
     * DM thread belgesini oluşturur (eğer yoksa).
     * dm_threads/{uid1_uid2} — her iki UID alfabetik sıralı.
     * Zaten varsa PATCH idem-potent çalışır, var olan veriyi bozmaz.
     */
    suspend fun initDmThread(
        myUid: String, otherUid: String, idToken: String,
    ): String = withContext(Dispatchers.IO) {
        val dmId = listOf(myUid, otherUid).sorted().joinToString("_")
        val body = """{"fields":{
            "participants":{"arrayValue":{"values":[
                {"stringValue":"$myUid"},
                {"stringValue":"$otherUid"}
            ]}},
            "createdAt":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        val (code, _) = request("PATCH", "$BASE/dm_threads/$dmId", idToken = idToken, body = body)
        if (code !in 200..299) throw Exception("DM thread oluşturulamadı: HTTP $code")
        dmId
    }

    suspend fun listDms(senderUid: String, recipientUid: String, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val dmId = listOf(senderUid, recipientUid).sorted().joinToString("_")
            val (code, text) = request("GET", "$BASE/dm_threads/$dmId/messages?pageSize=200", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            parseMsgsFromJson(text)
        }

    /**
     * Kullanıcının arkadaş profilini uid'si ile getirir.
     * Firestore: users/{uid} — register/login sırasında kaydedilir.
     */
    suspend fun getUserByFurcordId(furcordId: String, idToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val doc = runStructuredQuery("users", "furcordId", furcordId.uppercase(), idToken)
                ?: return@withContext null
            val uid  = doc.name.substringAfterLast("/")
            val name = doc.fields.str("nickname").takeIf { it.isNotEmpty() }
                ?: doc.fields.str("displayName").takeIf { it.isNotEmpty() }
                ?: uid
            uid to name
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
            val doc = runStructuredQuery("users", "nickname", nickname, idToken)
                ?: return@withContext null
            val uid  = doc.name.substringAfterLast("/")
            val name = doc.fields.str("nickname").takeIf { it.isNotEmpty() }
                ?: doc.fields.str("displayName").takeIf { it.isNotEmpty() }
                ?: uid
            uid to name
        }

    /**
     * Belirli bir nickname'in kullan\u0131labilir olup olmad\u0131\u011f\u0131n\u0131 kontrol eder.
     */
    suspend fun checkNicknameAvailable(nickname: String, idToken: String): Boolean =
        withContext(Dispatchers.IO) {
            (getUserByNickname(nickname, idToken) == null)
        }

    /**
     * Sadece nickname alanını günceller — diğer alanları (furcordId, photoURL vb.) silmez.
     */
    suspend fun saveNicknameOnly(uid: String, nickname: String, idToken: String) = withContext(Dispatchers.IO) {
        val escNic = nickname.replace("\\", "\\\\").replace("\"", "\\\"")
        val body   = """{"fields":{"nickname":{"stringValue":"$escNic"}}}"""
        val url    = "$BASE/users/$uid?updateMask.fieldPaths=nickname"
        runCatching { request("PATCH", url, idToken = idToken, body = body) }
    }

    /**
     * Kullanıcı profilini Firestore'a kaydeder (displayName, photoURL, furcordId, email, nickname).
     * Her login/kayıt + profil güncellemesi sırasında çağrılır.
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

    // ── Server Presence ───────────────────────────────────────────────────────

    /** Kullanıcının sunucuda çevrimiçi olduğunu Firestore'a yazar (kalp atışı). */
    suspend fun upsertPresence(
        serverId: String, uid: String, username: String, photoURL: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val ts = System.currentTimeMillis()
        val body = """{"fields":{"uid":{"stringValue":"${uid.esc()}"},"username":{"stringValue":"${username.esc()}"},"photoURL":{"stringValue":"${photoURL.esc()}"},"lastSeen":{"integerValue":"$ts"}}}"""
        runCatching { request("PATCH", "$BASE/servers/$serverId/presence/$uid", body, idToken) }
    }

    /** Sunucudaki tüm çevrimiçi kullanıcıları döner (son 90 sn içinde lastSeen güncellemiş). */
    suspend fun listPresence(serverId: String, idToken: String): List<ActiveUser> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/presence?pageSize=100", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            val cutoff = System.currentTimeMillis() - 90_000L
            resp.documents.mapNotNull { doc ->
                val f        = doc.fields
                val uid      = f.str("uid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val lastSeen = f.lng("lastSeen")
                if (lastSeen < cutoff) return@mapNotNull null
                ActiveUser(uid, f.str("username"), "#5865F2", f.str("photoURL"))
            }
        }

    /** Kullanıcının sunucu presence kaydını siler (sunucudan çıkınca). */
    suspend fun deletePresence(serverId: String, uid: String, idToken: String) =
        withContext(Dispatchers.IO) {
            runCatching { request("DELETE", "$BASE/servers/$serverId/presence/$uid", idToken = idToken) }
        }

    // ── Request helper ────────────────────────────────────────────────────────

    /**
     * Bu kullanıcının katıldığı tüm DM sohbetlerini döner.
     * directMessages koleksiyonundaki tüm dökümanları tarar —
     * dmId = "uid1_uid2" formatında, her ikisi de biz olabilir.
     */
    suspend fun listDmConversations(myUid: String, idToken: String): List<DmConversation> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/dm_threads?pageSize=500", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            val found = mutableListOf<DmConversation>()
            for (doc in resp.documents) {
                val dmId     = doc.name.substringAfterLast("/")
                val parts    = dmId.split("_")
                if (parts.size < 2) continue
                if (!dmId.contains(myUid)) continue
                val otherUid = parts.firstOrNull { it != myUid } ?: continue
                val (mc, mt) = request("GET",
                    "$BASE/dm_threads/$dmId/messages?pageSize=1&orderBy=timestamp%20desc",
                    idToken = idToken)
                if (mc !in 200..299) continue
                val msgs = parseMsgsFromJson(mt)
                val last = msgs.lastOrNull() ?: continue
                val otherName = if (last.uid == myUid) {
                    runCatching {
                        val (uc, ut) = request("GET", "$BASE/users/$otherUid", idToken = idToken)
                        if (uc in 200..299) {
                            val udoc = fsJson.decodeFromString<FsDocument>(ut)
                            udoc.fields.str("displayName").takeIf { it.isNotEmpty() } ?: otherUid
                        } else otherUid
                    }.getOrDefault(otherUid)
                } else last.username
                found.add(DmConversation(
                    dmId          = dmId,
                    otherUid      = otherUid,
                    otherName     = otherName,
                    lastText      = last.text,
                    lastTimestamp = last.timestamp,
                    lastSenderUid = last.uid,
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
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            val entries = mutableListOf<FriendEntry>()
            for (fdoc in resp.documents) {
                val uid = fdoc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: continue
                val (uc, ut) = request("GET", "$BASE/users/$uid", idToken = idToken)
                if (uc !in 200..299) continue
                val udoc = runCatching { fsJson.decodeFromString<FsDocument>(ut) }.getOrNull() ?: continue
                entries.add(FriendEntry(uid, udoc.fields.str("displayName"), udoc.fields.str("furcordId")))
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
            "type":{"stringValue":"friend_request"},
            "fromUid":{"stringValue":"$fromUid"},
            "fromName":{"stringValue":"$escN"},
            "furcordId":{"stringValue":"$escId"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        val (code, _) = request("PATCH", "$BASE/users/$toUid/notifications/$fromUid", idToken = idToken, body = body)
        if (code !in 200..299) throw Exception("HTTP $code")
    }

    /** Gelen arkadaşlık isteklerini listeler. */
    suspend fun listFriendRequests(myUid: String, idToken: String): List<FriendRequest> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/users/$myUid/notifications?pageSize=100", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            resp.documents.mapNotNull { doc ->
                val f = doc.fields
                if (f.str("type") != "friend_request") return@mapNotNull null
                val uid = f.str("fromUid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                FriendRequest(uid, f.str("fromName"), f.str("furcordId"), f.lng("timestamp"))
            }.sortedByDescending { it.timestamp }
        }

    /** Arkadaşlık isteğini kabul et. */
    suspend fun acceptFriendRequest(myUid: String, fromUid: String, idToken: String) = withContext(Dispatchers.IO) {
        addFriend(myUid, fromUid, idToken)
        runCatching { request("DELETE", "$BASE/users/$myUid/notifications/$fromUid", idToken = idToken) }
    }

    /** Arkadaşlık isteğini reddet. */
    suspend fun rejectFriendRequest(myUid: String, fromUid: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/users/$myUid/notifications/$fromUid", idToken = idToken) }
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
            "type":{"stringValue":"server_invite"},
            "serverId":{"stringValue":"$serverId"},
            "serverName":{"stringValue":"$escS"},
            "fromUid":{"stringValue":"$fromUid"},
            "fromName":{"stringValue":"$escN"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        runCatching { request("PATCH", "$BASE/users/$toUid/notifications/$serverId", idToken = idToken, body = body) }
    }

    /** Gelen sunucu davetlerini listeler. */
    suspend fun listServerInviteNotifs(myUid: String, idToken: String): List<ServerInviteNotif> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/users/$myUid/notifications?pageSize=100", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            resp.documents.mapNotNull { doc ->
                val f   = doc.fields
                if (f.str("type") != "server_invite") return@mapNotNull null
                val sid = f.str("serverId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ServerInviteNotif(sid, f.str("serverName"), f.str("fromUid"), f.str("fromName"), f.lng("timestamp"))
            }.sortedByDescending { it.timestamp }
        }

    /** Sunucu davetini siler. */
    suspend fun removeServerInviteNotif(myUid: String, serverId: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/users/$myUid/notifications/$serverId", idToken = idToken) }
    }

    /** Firestore'daki güncel sürüm bilgisini getirir. */
    suspend fun getLatestVersion(idToken: String): AppVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val (code, text) = request("GET", "$BASE/config/appVersion", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val doc = fsJson.decodeFromString<FsDocument>(text)
            val latestVersion = doc.fields.str("latestVersion").takeIf { it.isNotEmpty() } ?: return@withContext null
            val downloadUrl   = doc.fields.str("downloadUrl").takeIf { it.isNotEmpty() } ?: return@withContext null
            val releaseNotes  = doc.fields.str("releaseNotes")
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
