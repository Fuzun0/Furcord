package com.furcord.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val id:           String,
    val uid:          String,
    val username:     String,
    val photoURL:     String,
    val text:         String,
    val timestamp:    Long,
    val imageUrl:     String = "",  // Firebase Storage görsel URL'si (opsiyonel)
    val replyToId:    String = "",  // Yanıtlanan mesajın Firestore doc ID'si
    val replyToUser:  String = "",  // Yanıtlanan mesajın yazarı
    val replyToText:  String = "",  // Yanıtlanan mesajın kısa önizlemesi
    /** emoji → tepki veren kullanıcı uid'leri */
    val reactions:    Map<String, List<String>> = emptyMap(),
)

/** Ses kanalına bağlı bir eşin bağlantı bilgileri. */
data class VoicePeer(val uid: String, val ip: String, val port: Int, val isBroadcasting: Boolean = false)

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
data class FriendEntry(val uid: String, val displayName: String, val furcordId: String, val isOnline: Boolean = false)

/** Firebase Storage REST API upload sonucu. */
data class StorageUploadResult(val downloadUrl: String)

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
    /** true: davet değil, sunucu sahibinin katılma isteğini onayladığı bildirim. */
    val approved:   Boolean = false,
)

/** Sunucu sahibine gelen "sunucuna katılmak istiyorum" isteği. */
data class JoinRequestNotif(
    val serverId:   String,
    val serverName: String,
    val fromUid:    String,
    val fromName:   String,
    val timestamp:  Long,
)

/** İsimle sunucu arama sonucu. */
data class ServerSearchResult(val id: String, val name: String, val ownerUid: String)

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
     * Format v2: id\u0001uid\u0001username\u0001photoURL\u0001timestamp\u0001text\u0001imageUrl\u0001replyToId\u0001replyToUser\u0001replyToText
     * Eski format (6 alan) da desteklenir.
     */
    fun get(serverId: String): List<ChatMessage> {
        return try {
            val f = file(serverId)
            if (!f.exists()) return emptyList()
            f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split("\u0001", limit = 10)
                if (p.size < 6) return@mapNotNull null
                ChatMessage(
                    id          = p[0],
                    uid         = p[1],
                    username    = p[2],
                    photoURL    = p[3],
                    timestamp   = p[4].toLongOrNull() ?: 0L,
                    text        = p[5],
                    imageUrl    = p.getOrElse(6) { "" },
                    replyToId   = p.getOrElse(7) { "" },
                    replyToUser = p.getOrElse(8) { "" },
                    replyToText = p.getOrElse(9) { "" },
                )
            }.sortedBy { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    /** Mesajları diske yazar. Pending (optimistik) mesajlar dahil edilir. */
    fun set(serverId: String, messages: List<ChatMessage>) = try {
        file(serverId).writeText(
            messages.joinToString("\n") { m ->
                "${m.id}\u0001${m.uid}\u0001${m.username}\u0001${m.photoURL}\u0001${m.timestamp}" +
                "\u0001${m.text}\u0001${m.imageUrl}\u0001${m.replyToId}\u0001${m.replyToUser}\u0001${m.replyToText}"
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
    /** Sunucunun `imageUrl` alanını getirir; alan yoksa veya hata olursa boş string döner. */
    suspend fun getServerImageUrl(serverId: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId", idToken = idToken)
            if (code !in 200..299) return@withContext ""
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
            doc?.fields?.str("imageUrl") ?: ""
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

    /** Her sunucuda bulunan sabit "Genel" ses kanalının belge kimliği. */
    const val DEFAULT_VOICE_CHANNEL_ID = "genel"

    /** Sabit "Genel" ses kanalını oluşturur (varsa yalnızca adını/sırasını korur). */
    suspend fun ensureDefaultVoiceChannel(serverId: String, idToken: String) = withContext(Dispatchers.IO) {
        val body = """{"fields":{"name":{"stringValue":"Genel"},"order":{"integerValue":"0"}}}"""
        val url  = "$BASE/servers/$serverId/voiceChannels/$DEFAULT_VOICE_CHANNEL_ID?updateMask.fieldPaths=name&updateMask.fieldPaths=order"
        val (code, text) = request("PATCH", url, body, idToken)
        if (code !in 200..299) throw Exception("Genel kanal oluşturulamadı ($code): $text")
    }

    /** Ses kanalını siler. */
    suspend fun deleteVoiceChannel(serverId: String, channelId: String, idToken: String) = withContext(Dispatchers.IO) {
        val (code, text) = request("DELETE", "$BASE/servers/$serverId/voiceChannels/$channelId", idToken = idToken)
        if (code !in 200..299) throw Exception("Kanal silinemedi ($code): $text")
    }

    // ── Sunucu üyeleri (sunucuya kayıtlı kullanıcılar) ───────────────────────

    /** Kullanıcıyı sunucunun üye listesine yazar / bilgilerini günceller. */
    suspend fun upsertMember(
        serverId: String, uid: String, username: String, photoURL: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{"uid":{"stringValue":"${uid.esc()}"},"username":{"stringValue":"${username.esc()}"},"photoURL":{"stringValue":"${photoURL.esc()}"},"lastSeen":{"integerValue":"${System.currentTimeMillis()}"}}}"""
        val mask = "updateMask.fieldPaths=uid&updateMask.fieldPaths=username&updateMask.fieldPaths=photoURL&updateMask.fieldPaths=lastSeen"
        runCatching { request("PATCH", "$BASE/servers/$serverId/members/$uid?$mask", body, idToken) }
    }

    /** Sunucuya kayıtlı tüm üyeler. */
    suspend fun listMembers(serverId: String, idToken: String): List<ActiveUser> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/members?pageSize=300", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            resp.documents.mapNotNull { doc ->
                val f   = doc.fields
                val uid = f.str("uid").ifEmpty { doc.name.substringAfterLast("/") }.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                ActiveUser(uid, f.str("username").ifEmpty { "Kullanıcı" }, "#5865F2", f.str("photoURL"))
            }
        }

    /** Kullanıcıyı sunucunun üye listesinden çıkarır. */
    suspend fun removeMember(serverId: String, uid: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/servers/$serverId/members/$uid", idToken = idToken) }
    }

    // ── Ses kanalları arası taşıma (sunucu sahibi → hedef kullanıcı) ─────────

    /** Sunucu sahibi, [targetUid]'yi [channelId] ses kanalına taşınmaya çağırır. */
    suspend fun requestVoiceMove(serverId: String, targetUid: String, channelId: String, idToken: String) =
        withContext(Dispatchers.IO) {
            val body = """{"fields":{"channelId":{"stringValue":"$channelId"},"ts":{"integerValue":"${System.currentTimeMillis()}"}}}"""
            val (code, text) = request("PATCH", "$BASE/servers/$serverId/voiceMoves/$targetUid", body, idToken)
            if (code !in 200..299) throw Exception("Taşıma isteği gönderilemedi ($code): $text")
        }

    /** Bu kullanıcı için bekleyen taşıma isteğinin hedef kanalı (yoksa null). */
    suspend fun getVoiceMove(serverId: String, uid: String, idToken: String): String? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voiceMoves/$uid", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull() ?: return@withContext null
            // Çok eski istekler (ör. kullanıcı çevrimdışıyken gelen) yok sayılır
            if (System.currentTimeMillis() - doc.fields.lng("ts") > 60_000L) {
                runCatching { request("DELETE", "$BASE/servers/$serverId/voiceMoves/$uid", idToken = idToken) }
                return@withContext null
            }
            doc.fields.str("channelId").takeIf { it.isNotEmpty() }
        }

    suspend fun clearVoiceMove(serverId: String, uid: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/servers/$serverId/voiceMoves/$uid", idToken = idToken) }
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

    /** Sunucuyu adıyla arar (tam eşleşme; yoksa ilk harfi büyük/küçük varyantlarını dener). */
    suspend fun searchServerByName(name: String, idToken: String): ServerSearchResult? =
        withContext(Dispatchers.IO) {
            val variants = linkedSetOf(
                name, name.lowercase(), name.uppercase(),
                name.replaceFirstChar { it.uppercaseChar() },
            )
            for (v in variants) {
                val doc = runStructuredQuery("servers", "name", v, idToken) ?: continue
                val sid   = doc.name.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: continue
                val owner = doc.fields.str("creatorUid")
                if (owner.isEmpty()) continue
                return@withContext ServerSearchResult(sid, doc.fields.str("name").ifEmpty { v }, owner)
            }
            null
        }

    /** Sunucu sahibinin bildirim kutusuna "katılma isteği" bırakır. */
    suspend fun sendJoinRequest(
        ownerUid: String, serverId: String, serverName: String,
        fromUid: String, fromName: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{
            "type":{"stringValue":"join_request"},
            "serverId":{"stringValue":"${serverId.esc()}"},
            "serverName":{"stringValue":"${serverName.esc()}"},
            "fromUid":{"stringValue":"${fromUid.esc()}"},
            "fromName":{"stringValue":"${fromName.esc()}"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        val (code, _) = request("PATCH", "$BASE/users/$ownerUid/notifications/join_${serverId}_$fromUid", idToken = idToken, body = body)
        if (code !in 200..299) throw Exception("HTTP $code")
    }

    /** Sahibin bekleyen katılma isteklerini listeler. */
    suspend fun listJoinRequests(myUid: String, idToken: String): List<JoinRequestNotif> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/users/$myUid/notifications?pageSize=100", idToken = idToken)
            if (code !in 200..299) return@withContext emptyList()
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext emptyList()
            resp.documents.mapNotNull { doc ->
                val f = doc.fields
                if (f.str("type") != "join_request") return@mapNotNull null
                val sid = f.str("serverId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val uid = f.str("fromUid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                JoinRequestNotif(sid, f.str("serverName"), uid, f.str("fromName"), f.lng("timestamp"))
            }.sortedByDescending { it.timestamp }
        }

    /** İsteği kaldırır (kabul/red sonrası). */
    suspend fun removeJoinRequest(ownerUid: String, serverId: String, fromUid: String, idToken: String) =
        withContext(Dispatchers.IO) {
            runCatching { request("DELETE", "$BASE/users/$ownerUid/notifications/join_${serverId}_$fromUid", idToken = idToken) }
        }

    /** İsteği kabul eder: istek sahibine "onaylandı" bildirimi gönderir (o da Katıl'a basınca sunucuya girer). */
    suspend fun approveJoinRequest(
        req: JoinRequestNotif, ownerUid: String, ownerName: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{
            "type":{"stringValue":"server_invite"},
            "approved":{"booleanValue":true},
            "serverId":{"stringValue":"${req.serverId.esc()}"},
            "serverName":{"stringValue":"${req.serverName.esc()}"},
            "fromUid":{"stringValue":"${ownerUid.esc()}"},
            "fromName":{"stringValue":"${ownerName.esc()}"},
            "timestamp":{"integerValue":"${System.currentTimeMillis()}"}
        }}""".trimIndent()
        val (code, _) = request("PATCH", "$BASE/users/${req.fromUid}/notifications/${req.serverId}", idToken = idToken, body = body)
        if (code !in 200..299) throw Exception("HTTP $code")
        removeJoinRequest(ownerUid, req.serverId, req.fromUid, idToken)
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
                Triple(VoiceChannel(doc.name.substringAfterLast("/"), name), doc.fields.lng("order"), doc.createTime)
            }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
        }

    /**
     * Kanal listesi + her kanalın aktif kullanıcıları TEK istekte (kanal başına ayrı okuma yok).
     * Hata durumunda null döner (çağıran mevcut durumu korusun).
     */
    suspend fun listVoiceChannelsWithUsers(
        serverId: String, idToken: String,
    ): Pair<List<VoiceChannel>, Map<String, List<ActiveUser>>>? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/servers/$serverId/voiceChannels", idToken = idToken)
            if (code !in 200..299) return@withContext null
            val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
                ?: return@withContext null
            val rows = resp.documents.mapNotNull { doc ->
                val name = doc.fields.str("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val id = doc.name.substringAfterLast("/")
                Triple(VoiceChannel(id, name), doc.fields.lng("order") to doc.createTime, parseActiveUsersFromDoc(doc))
            }.sortedWith(compareBy({ it.second.first }, { it.second.second }))
            rows.map { it.first } to rows.associate { it.first.id to it.third }
        }

    /** Son [limit] mesajı (en yeni sonda) getirir — orderBy + limit, tüm geçmişi okumaz. */
    suspend fun listLatestMessages(serverId: String, limit: Int, idToken: String): List<ChatMessage> =
        queryMessages(serverId, limit, beforeTimestamp = null, idToken = idToken)

    /** [beforeTimestamp]'tan eski [limit] mesajı getirir (yukarı kaydırınca sayfalama; en yeni sonda). */
    suspend fun listMessagesBefore(serverId: String, beforeTimestamp: Long, limit: Int, idToken: String): List<ChatMessage> =
        queryMessages(serverId, limit, beforeTimestamp, idToken)

    private suspend fun queryMessages(serverId: String, limit: Int, beforeTimestamp: Long?, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val queryUrl = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/servers/$serverId:runQuery"
            val where = if (beforeTimestamp != null)
                """"where":{"fieldFilter":{"field":{"fieldPath":"timestamp"},"op":"LESS_THAN","value":{"integerValue":"$beforeTimestamp"}}},"""
            else ""
            val body = """{"structuredQuery":{"from":[{"collectionId":"messages"}],$where"orderBy":[{"field":{"fieldPath":"timestamp"},"direction":"DESCENDING"}],"limit":$limit}}"""
            val (code, text) = request("POST", queryUrl, body, idToken)
            if (code !in 200..299) return@withContext emptyList()
            (runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }.getOrNull()
                ?.mapNotNull { it.document?.let { doc -> parseMsgFromDoc(doc) } } ?: emptyList())
                .sortedBy { it.timestamp }
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
        val body = """{"fields":{"channelId":{"stringValue":"$channelId"},"ip":{"stringValue":"$ip"},"port":{"integerValue":"$port"},"isBroadcasting":{"booleanValue":false}}}"""
        request("PATCH", "$BASE/servers/$serverId/voicePeers/$uid", idToken = idToken, body = body)
    }

    /** Yayın durumunu günceller (start/stop broadcasting). */
    suspend fun setBroadcastingStatus(
        serverId: String, uid: String, isBroadcasting: Boolean, idToken: String,
    ) = withContext(Dispatchers.IO) {
        val body = """{"fields":{"isBroadcasting":{"booleanValue":$isBroadcasting}}}"""
        request("PATCH", "$BASE/servers/$serverId/voicePeers/$uid?updateMask.fieldPaths=isBroadcasting", idToken = idToken, body = body)
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
            VoicePeer(uid = uid, ip = ip, port = port, isBroadcasting = f.bool("isBroadcasting"))
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
        imageUrl: String = "",
        replyToId:   String = "",
        replyToUser: String = "",
        replyToText: String = "",
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val ts = System.currentTimeMillis()
        val imgField   = if (imageUrl.isNotBlank())   ",\"imageUrl\":{\"stringValue\":\"${imageUrl.esc()}\"}"   else ""
        val replyField = if (replyToId.isNotBlank())
            ",\"replyToId\":{\"stringValue\":\"${replyToId.esc()}\"}" +
            ",\"replyToUser\":{\"stringValue\":\"${replyToUser.esc()}\"}" +
            ",\"replyToText\":{\"stringValue\":\"${replyToText.take(120).esc()}\"}"
        else ""
        val body = """{"fields":{"uid":{"stringValue":"${uid.esc()}"},"username":{"stringValue":"${username.esc()}"},"photoURL":{"stringValue":"${photoURL.esc()}"},"text":{"stringValue":"${text.esc()}"},"timestamp":{"integerValue":"$ts"}$imgField$replyField}}"""
        val (code, resp) = request("POST", "$BASE/servers/$serverId/messages", body, idToken)
        if (code !in 200..299) throw Exception("Mesaj gönderilemedi ($code): $resp")
    }

    /** Fetches the latest messages (up to 50) from servers/{serverId}/messages. */
    suspend fun listMessages(serverId: String, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            // En yeni 30 mesaj (orderBy timestamp DESC + limit); eski sürüm sıralamasız 50 belge okuyordu
            listLatestMessages(serverId, 30, idToken)
        }

    /**
     * Belirli bir timestamp'ten sonraki mesajları getirir.
     * Firestore Structured Query — sadece yeni dökümanlar okunur, kota tasarrufu yapılır.
     * [afterTimestamp] == 0L ise tüm mesajları getirir (ilk yükleme).
     */
    suspend fun listMessagesSince(
        serverId: String,
        afterTimestamp: Long,
        idToken: String,
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (afterTimestamp <= 0L) return@withContext listLatestMessages(serverId, 40, idToken)
        val queryUrl = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/servers/$serverId:runQuery"
        val body = """
            {
              "structuredQuery": {
                "from": [{"collectionId": "messages"}],
                "where": {
                  "fieldFilter": {
                    "field": {"fieldPath": "timestamp"},
                    "op": "GREATER_THAN",
                    "value": {"integerValue": "$afterTimestamp"}
                  }
                },
                "orderBy": [{"field": {"fieldPath": "timestamp"}, "direction": "ASCENDING"}]
              }
            }
        """.trimIndent()
        val (code, text) = request("POST", queryUrl, body, idToken)
        if (code !in 200..299) return@withContext emptyList()
        runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }
            .getOrNull()
            ?.mapNotNull { it.document?.let { doc -> parseMsgFromDoc(doc) } }
            ?: emptyList()
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
    private fun parseMsgFromDoc(doc: FsDocument): ChatMessage? {
        val f = doc.fields
        // Hem text hem imageUrl boşsa mesaj değildir
        if (f.str("text").isEmpty() && f.str("imageUrl").isEmpty()) return null
        return ChatMessage(
            id          = doc.name.substringAfterLast("/"),
            uid         = f.str("uid"),
            username    = f.str("username"),
            photoURL    = f.str("photoURL"),
            text        = f.str("text"),
            timestamp   = f.lng("timestamp"),
            imageUrl    = f.str("imageUrl"),
            replyToId   = f.str("replyToId"),
            replyToUser = f.str("replyToUser"),
            replyToText = f.str("replyToText"),
            reactions   = parseReactions(f),
        )
    }

    private fun parseReactions(f: Map<String, FsValue>): Map<String, List<String>> {
        val m = f["reactions"]?.map?.fields ?: return emptyMap()
        return m.mapNotNull { (emoji, v) ->
            val uids = v.array?.values?.mapNotNull { it.string?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
            if (uids.isEmpty()) null else emoji to uids
        }.toMap()
    }

    private fun reactionsBody(r: Map<String, List<String>>): String {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val inner = r.entries.joinToString(",") { (e, uids) ->
            "\"${e.esc()}\":{\"arrayValue\":{\"values\":[" + uids.joinToString(",") { "{\"stringValue\":\"${it.esc()}\"}" } + "]}}"
        }
        return if (r.isEmpty()) """{"fields":{}}"""
        else """{"fields":{"reactions":{"mapValue":{"fields":{$inner}}}}}"""
    }

    /** Sunucu sohbeti mesajına tepki ekler/kaldırır. Güncel tepki haritasını döndürür. */
    suspend fun toggleServerReaction(serverId: String, msgId: String, emoji: String, uid: String, idToken: String) =
        toggleReaction("$BASE/servers/$serverId/messages/$msgId", emoji, uid, idToken)

    /** Özel sohbet mesajına tepki ekler/kaldırır. */
    suspend fun toggleDmReaction(myUid: String, otherUid: String, msgId: String, emoji: String, idToken: String): Map<String, List<String>> {
        val dmId = listOf(myUid, otherUid).sorted().joinToString("_")
        return toggleReaction("$BASE/dm_threads/$dmId/messages/$msgId", emoji, myUid, idToken)
    }

    private suspend fun toggleReaction(docUrl: String, emoji: String, uid: String, idToken: String): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            val (gc, gt) = request("GET", docUrl, idToken = idToken)
            if (gc !in 200..299) throw Exception("Mesaj bulunamadı ($gc)")
            val cur = runCatching { parseReactions(fsJson.decodeFromString<FsDocument>(gt).fields) }.getOrDefault(emptyMap())
            val users = cur[emoji].orEmpty()
            val next = (if (uid in users) cur + (emoji to users - uid) else cur + (emoji to users + uid))
                .filterValues { it.isNotEmpty() }
            val (pc, pt) = request("PATCH", "$docUrl?updateMask.fieldPaths=reactions", reactionsBody(next), idToken)
            if (pc !in 200..299) throw Exception("Tepki kaydedilemedi ($pc): $pt")
            next
        }

    /** Sunucu sohbetinin son [limit] mesajı (tepkileri tazelemek için). */
    suspend fun listRecentMessages(serverId: String, limit: Int, idToken: String): List<ChatMessage> =
        recentQuery("$BASE/servers/$serverId:runQuery", limit, idToken)

    suspend fun listRecentDms(myUid: String, otherUid: String, limit: Int, idToken: String): List<ChatMessage> {
        val dmId = listOf(myUid, otherUid).sorted().joinToString("_")
        return recentQuery("$BASE/dm_threads/$dmId:runQuery", limit, idToken)
    }

    private suspend fun recentQuery(url: String, limit: Int, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val body = """{"structuredQuery":{"from":[{"collectionId":"messages"}],"orderBy":[{"field":{"fieldPath":"timestamp"},"direction":"DESCENDING"}],"limit":$limit}}"""
            val (code, text) = request("POST", url, body, idToken)
            if (code !in 200..299) return@withContext emptyList()
            runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }.getOrNull()
                ?.mapNotNull { it.document?.let { d -> parseMsgFromDoc(d) } } ?: emptyList()
        }

    private fun parseMsgsFromJson(json: String): List<ChatMessage> {
        val resp = runCatching { fsJson.decodeFromString<FsListResponse>(json) }.getOrNull()
            ?: return emptyList()
        return resp.documents.mapNotNull { parseMsgFromDoc(it) }.sortedBy { it.timestamp }
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
        imageUrl: String = "",
        replyToId:   String = "",
        replyToUser: String = "",
        replyToText: String = "",
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val dmId = listOf(senderUid, recipientUid).sorted().joinToString("_")
        val imgField   = if (imageUrl.isNotBlank())   ",\"imageUrl\":{\"stringValue\":\"${imageUrl.esc()}\"}"   else ""
        val replyField = if (replyToId.isNotBlank())
            ",\"replyToId\":{\"stringValue\":\"${replyToId.esc()}\"}" +
            ",\"replyToUser\":{\"stringValue\":\"${replyToUser.esc()}\"}" +
            ",\"replyToText\":{\"stringValue\":\"${replyToText.take(120).esc()}\"}"
        else ""
        val ts   = System.currentTimeMillis()
        val body = """{"fields":{"uid":{"stringValue":"$senderUid"},"username":{"stringValue":"${senderName.esc()}"},"photoURL":{"stringValue":"${senderPhoto.esc()}"},"text":{"stringValue":"${text.esc()}"},"timestamp":{"integerValue":"$ts"}$imgField$replyField}}"""
        val result = request("POST", "$BASE/dm_threads/$dmId/messages", idToken = idToken, body = body)
        // Alıcının bildirim kutusuna da bırak → anlık Windows bildirimi + okunmamış rozeti
        if (result.first in 200..299) {
            val notif = """{"fields":{"type":{"stringValue":"dm"},"fromUid":{"stringValue":"${senderUid.esc()}"},"fromName":{"stringValue":"${senderName.esc()}"},"text":{"stringValue":"${text.take(300).esc()}"},"hasAttachment":{"booleanValue":${imageUrl.isNotBlank()}},"timestamp":{"integerValue":"$ts"}}}"""
            runCatching { request("POST", "$BASE/users/$recipientUid/notifications", notif, idToken) }
        }
        result
    }

    /** Gelen (henüz işlenmemiş) DM bildirimi. */
    data class DmNotif(
        val docId: String, val fromUid: String, val fromName: String,
        val text: String, val hasAttachment: Boolean, val timestamp: Long,
    )

    /** Bildirim kutusundaki DM bildirimlerini getirir. */
    suspend fun listDmNotifs(myUid: String, idToken: String): List<DmNotif> = withContext(Dispatchers.IO) {
        val (code, text) = request("GET", "$BASE/users/$myUid/notifications?pageSize=100", idToken = idToken)
        if (code !in 200..299) return@withContext emptyList()
        val resp = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()
            ?: return@withContext emptyList()
        resp.documents.mapNotNull { doc ->
            val f = doc.fields
            if (f.str("type") != "dm") return@mapNotNull null
            val from = f.str("fromUid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DmNotif(doc.name.substringAfterLast("/"), from, f.str("fromName"), f.str("text"),
                f.bool("hasAttachment"), f.lng("timestamp"))
        }.sortedBy { it.timestamp }
    }

    suspend fun deleteNotification(myUid: String, docId: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/users/$myUid/notifications/$docId", idToken = idToken) }
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
     * Belirli bir timestamp'ten sonraki DM mesajlarını getirir.
     * Firestore Structured Query — kota tasarrufu için only-new-docs.
     */
    suspend fun listDmsSince(
        senderUid: String,
        recipientUid: String,
        afterTimestamp: Long,
        idToken: String,
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (afterTimestamp <= 0L) return@withContext listDms(senderUid, recipientUid, idToken)
        val dmId     = listOf(senderUid, recipientUid).sorted().joinToString("_")
        val queryUrl = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/dm_threads/$dmId:runQuery"
        val body = """
            {
              "structuredQuery": {
                "from": [{"collectionId": "messages"}],
                "where": {
                  "fieldFilter": {
                    "field": {"fieldPath": "timestamp"},
                    "op": "GREATER_THAN",
                    "value": {"integerValue": "$afterTimestamp"}
                  }
                },
                "orderBy": [{"field": {"fieldPath": "timestamp"}, "direction": "ASCENDING"}]
              }
            }
        """.trimIndent()
        val (code, text) = request("POST", queryUrl, body, idToken)
        if (code !in 200..299) return@withContext emptyList()
        runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }
            .getOrNull()
            ?.mapNotNull { it.document?.let { doc -> parseMsgFromDoc(doc) } }
            ?: emptyList()
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

    /** Global kullanıcı lastSeen'ini günceller (çevrimiçi kalp atışı). timestamp=0L → çevrimdışı. */
    suspend fun setUserLastSeen(uid: String, idToken: String, timestamp: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val body = """{"fields":{"lastSeen":{"integerValue":"$timestamp"}}}"""
            val url  = "$BASE/users/$uid?updateMask.fieldPaths=lastSeen"
            runCatching { request("PATCH", url, body, idToken) }
        }

    // ── Request helper ────────────────────────────────────────────────────────

    /**
     * Bu kullanıcının katıldığı tüm DM sohbetlerini döner.
     * directMessages koleksiyonundaki tüm dökümanları tarar —
     * dmId = "uid1_uid2" formatında, her ikisi de biz olabilir.
     */
    private val dmNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun listDmConversations(
        myUid: String,
        idToken: String,
        extraUids: Collection<String> = emptyList(),
    ): List<DmConversation> =
        withContext(Dispatchers.IO) {
            // Not: dm_threads koleksiyonunu listelemek güvenlik kuralına takılır (kural belge
            // kimliğine bakıyor). Bu yüzden aday karşı taraflar arkadaş listesinden + bilinen
            // sohbetlerden çıkarılıp her thread'in son mesajı doğrudan okunur.
            val friendUids = runCatching {
                val (fc, ft) = request("GET", "$BASE/friends/$myUid/list?pageSize=200", idToken = idToken)
                if (fc in 200..299)
                    fsJson.decodeFromString<FsListResponse>(ft).documents
                        .map { it.name.substringAfterLast("/") }.filter { it.isNotEmpty() }
                else emptyList()
            }.getOrDefault(emptyList())
            val candidates = (friendUids + extraUids).filter { it.isNotEmpty() && it != myUid }.distinct()
            val found = mutableListOf<DmConversation>()
            for (otherUid in candidates) {
                val dmId = listOf(myUid, otherUid).sorted().joinToString("_")
                val (mc, mt) = request("GET",
                    "$BASE/dm_threads/$dmId/messages?pageSize=1&orderBy=timestamp%20desc",
                    idToken = idToken)
                if (mc !in 200..299) continue
                val msgs = parseMsgsFromJson(mt)
                val last = msgs.lastOrNull() ?: continue
                val otherName = if (last.uid != myUid && last.username.isNotEmpty()) {
                    last.username.also { dmNameCache[otherUid] = it }
                } else dmNameCache[otherUid] ?: runCatching {
                    val (uc, ut) = request("GET", "$BASE/users/$otherUid", idToken = idToken)
                    if (uc in 200..299) {
                        val udoc = fsJson.decodeFromString<FsDocument>(ut)
                        udoc.fields.str("nickname").ifEmpty { udoc.fields.str("displayName") }.ifEmpty { null }
                    } else null
                }.getOrNull()?.also { dmNameCache[otherUid] = it } ?: otherUid
                found.add(DmConversation(
                    dmId          = dmId,
                    otherUid      = otherUid,
                    otherName     = otherName,
                    lastText      = last.text.ifBlank { if (last.imageUrl.isNotBlank()) "📎 Dosya" else "" },
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
                val lastSeen = udoc.fields.lng("lastSeen")
                val isOnline = lastSeen > 0L && (System.currentTimeMillis() - lastSeen) < 3 * 60_000L
                entries.add(FriendEntry(uid, udoc.fields.str("nickname").ifEmpty { udoc.fields.str("displayName") }, udoc.fields.str("furcordId"), isOnline))
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

    /** Arkadaşlığı kaldır (iki taraflı silme). */
    suspend fun removeFriend(myUid: String, friendUid: String, idToken: String) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "$BASE/friends/$myUid/list/$friendUid",  idToken = idToken) }
        runCatching { request("DELETE", "$BASE/friends/$friendUid/list/$myUid",  idToken = idToken) }
    }

    /** Sunucu görsel URL'sini günceller (sadece imageUrl alanını değiştirir). */
    suspend fun updateServerImage(serverId: String, imageUrl: String, idToken: String) = withContext(Dispatchers.IO) {
        val escaped = imageUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        val body    = """{"fields":{"imageUrl":{"stringValue":"$escaped"}}}"""
        val url     = "$BASE/servers/$serverId?updateMask.fieldPaths=imageUrl"
        val (code, resp) = request("PATCH", url, body, idToken)
        if (code !in 200..299) throw Exception("Görsel güncellenemedi ($code): $resp")
    }

    // ── Firebase Storage REST API ─────────────────────────────────────────────

    // Yeni Firebase projeleri (2024+) .firebasestorage.app formatı kullanır
    private const val STORAGE_BUCKET = "$PROJECT_ID.firebasestorage.app"
    private const val STORAGE_BASE   = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o"

    /**
     * Bir dosyayı Firebase Storage'a yükler.
     * @param storagePath "chat_images/img123.jpg" veya "server_icons/srv456.jpg" gibi
     * @param fileBytes   Yüklenecek ham byte dizisi
     * @param mimeType    "image/jpeg", "image/png" gibi
     * @param idToken     Firebase Auth token
     * @return Public download URL (token dahil)
     */
    suspend fun uploadToStorage(
        storagePath: String,
        fileBytes:   ByteArray,
        mimeType:    String,
        idToken:     String,
    ): StorageUploadResult = withContext(Dispatchers.IO) {
        val encodedPath = java.net.URLEncoder.encode(storagePath, "UTF-8")
        val uploadUrl   = "$STORAGE_BASE?name=$encodedPath"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .header("Authorization", "Bearer $idToken")
            .header("Content-Type", mimeType)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (resp.statusCode() !in 200..299)
            throw Exception("Storage yükleme başarısız (${resp.statusCode()}): ${resp.body()}")
        val body  = resp.body()
        val token = Regex(""""downloadTokens"\s*:\s*"([^"]+)"""").find(body)
            ?.groupValues?.get(1) ?: throw Exception("downloadTokens bulunamadı")
        val name  = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)
            ?.groupValues?.get(1) ?: storagePath
        val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
        val downloadUrl = "$STORAGE_BASE/$encodedName?alt=media&token=$token"
        StorageUploadResult(downloadUrl)
    }

    // ── Yayın sohbeti (yalnızca aktif ekran paylaşımına özgü, yayın bitince silinir) ──

    suspend fun sendStreamChat(
        serverId: String, uid: String, username: String, photoURL: String, text: String, idToken: String,
    ) = withContext(Dispatchers.IO) {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"fields":{"uid":{"stringValue":"${uid.esc()}"},"username":{"stringValue":"${username.esc()}"},"photoURL":{"stringValue":""},"text":{"stringValue":"${text.take(500).esc()}"},"timestamp":{"integerValue":"${System.currentTimeMillis()}"}}}"""
        val (code, resp) = request("POST", "$BASE/servers/$serverId/streamChat", body, idToken)
        if (code !in 200..299) throw Exception("Mesaj gönderilemedi ($code): $resp")
    }

    /** [afterTimestamp]'ten sonraki yayın sohbeti mesajları (artan sırada). */
    suspend fun listStreamChat(serverId: String, afterTimestamp: Long, idToken: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val queryUrl = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/servers/$serverId:runQuery"
            val body = """
                {"structuredQuery":{"from":[{"collectionId":"streamChat"}],
                 "where":{"fieldFilter":{"field":{"fieldPath":"timestamp"},"op":"GREATER_THAN","value":{"integerValue":"$afterTimestamp"}}},
                 "orderBy":[{"field":{"fieldPath":"timestamp"},"direction":"ASCENDING"}],"limit":200}}
            """.trimIndent()
            val (code, text) = request("POST", queryUrl, body, idToken)
            if (code !in 200..299) return@withContext emptyList()
            runCatching { fsJson.decodeFromString<List<FsQueryItem>>(text) }
                .getOrNull()
                ?.mapNotNull { it.document?.let { doc -> parseMsgFromDoc(doc) } }
                ?: emptyList()
        }

    /** Yayın sohbetindeki tüm mesajları siler (yayın başlarken/biterken yayıncı çağırır). */
    suspend fun clearStreamChat(serverId: String, idToken: String) = withContext(Dispatchers.IO) {
        repeat(5) {
            val (code, text) = runCatching { request("GET", "$BASE/servers/$serverId/streamChat?pageSize=300", idToken = idToken) }
                .getOrElse { return@withContext }
            if (code !in 200..299) return@withContext
            val docs = runCatching { fsJson.decodeFromString<FsListResponse>(text) }.getOrNull()?.documents.orEmpty()
            if (docs.isEmpty()) return@withContext
            docs.forEach { d ->
                val id = d.name.substringAfterLast("/")
                runCatching { request("DELETE", "$BASE/servers/$serverId/streamChat/$id", idToken = idToken) }
            }
        }
    }

    // ── Büyük dosya ekleri (Storage yerine Firestore parçaları) ──────────────

    /** Parça başına ham bayt (base64 sonrası ~800 KB → Firestore 1 MiB belge limiti altında). */
    const val ATTACH_CHUNK = 600_000
    private const val ATTACH_BASE = "$BASE/servers/_files/attachments"

    /**
     * Dosyayı [ATTACH_CHUNK] boyutlu parçalara bölüp servers/_files/attachments/{id}_{n} belgelerine yazar.
     * (Mevcut güvenlik kuralları bu yola izin verir; DM ve sunucu sohbetleri aynı yolu kullanır.)
     */
    suspend fun uploadAttachment(
        id: String,
        bytes: ByteArray,
        idToken: String,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val total = (bytes.size + ATTACH_CHUNK - 1) / ATTACH_CHUNK
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            (0 until total).map { n ->
                async {
                    gate.acquire()
                    try {
                        val from = n * ATTACH_CHUNK
                        val to   = minOf(bytes.size, from + ATTACH_CHUNK)
                        val b64  = java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(from, to))
                        val body = """{"fields":{"d":{"stringValue":"$b64"}}}"""
                        var lastErr = ""
                        var ok = false
                        repeat(3) { attempt ->
                            if (!ok) {
                                val (code, resp) = runCatching {
                                    request("PATCH", "$ATTACH_BASE/${id}_$n", body, idToken, timeoutSec = 60)
                                }.getOrElse { -1 to (it.message ?: "") }
                                if (code in 200..299) ok = true else { lastErr = "($code) $resp"; kotlinx.coroutines.delay(500L * (attempt + 1)) }
                            }
                        }
                        if (!ok) throw Exception("Dosya yüklenemedi $lastErr")
                        onProgress(done.incrementAndGet().toFloat() / total)
                    } finally { gate.release() }
                }
            }.forEach { it.await() }
        }
    }

    /** [uploadAttachment] ile yüklenmiş dosyanın tüm parçalarını indirip birleştirir. */
    suspend fun downloadAttachment(
        id: String,
        size: Long,
        idToken: String,
        onProgress: (Float) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.IO) {
        val total = ((size + ATTACH_CHUNK - 1) / ATTACH_CHUNK).toInt().coerceAtLeast(1)
        val done  = java.util.concurrent.atomic.AtomicInteger(0)
        val gate  = kotlinx.coroutines.sync.Semaphore(4)
        val parts = kotlinx.coroutines.coroutineScope {
            (0 until total).map { n ->
                async {
                    gate.acquire()
                    try {
                        var data: ByteArray? = null
                        repeat(3) { attempt ->
                            if (data == null) {
                                val (code, text) = runCatching {
                                    request("GET", "$ATTACH_BASE/${id}_$n", idToken = idToken, timeoutSec = 60)
                                }.getOrElse { -1 to "" }
                                if (code in 200..299) {
                                    val doc = runCatching { fsJson.decodeFromString<FsDocument>(text) }.getOrNull()
                                    val s = doc?.fields?.str("d").orEmpty()
                                    if (s.isNotEmpty()) data = java.util.Base64.getDecoder().decode(s)
                                } else kotlinx.coroutines.delay(500L * (attempt + 1))
                            }
                        }
                        onProgress(done.incrementAndGet().toFloat() / total)
                        data ?: throw Exception("Dosya parçası alınamadı (${n + 1}/$total)")
                    } finally { gate.release() }
                }
            }.map { it.await() }
        }
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        parts.forEach { out.write(it) }
        out.toByteArray()
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
                ServerInviteNotif(sid, f.str("serverName"), f.str("fromUid"), f.str("fromName"), f.lng("timestamp"),
                    approved = f.bool("approved"))
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
        timeoutSec: Long = 15,
    ): Pair<Int, String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $idToken")
            .timeout(Duration.ofSeconds(timeoutSec))
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
