package com.furcord.auth

import com.furcord.ui.PollBackoff
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * `users/{uid}/notifications` için TEK yoklama. Bildirim çanı (arkadaşlık/sunucu daveti/katılma isteği)
 * ve DM gelen kutusu aynı çağrıyı paylaşır. Çağrılar zaman damgası imleciyle yalnızca yeni belgeleri
 * okur; 10 dakikada bir tam yenileme güvenlik ağı olarak çalışır.
 */
object NotificationPoller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var token = ""

    private val _friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val _serverInvites  = MutableStateFlow<List<ServerInviteNotif>>(emptyList())
    private val _joinRequests   = MutableStateFlow<List<JoinRequestNotif>>(emptyList())
    val friendRequests: StateFlow<List<FriendRequest>>     = _friendRequests
    val serverInvites:  StateFlow<List<ServerInviteNotif>> = _serverInvites
    val joinRequests:   StateFlow<List<JoinRequestNotif>>  = _joinRequests

    /** DM bildirimlerini işleyen kanca (DmRepository). */
    @Volatile var dmHandler: (suspend (List<FirestoreClient.DmNotif>) -> Unit)? = null

    fun updateToken(t: String) { token = t }

    fun start(uid: String, idToken: String) {
        token = idToken
        job?.cancel()
        _friendRequests.value = emptyList(); _serverInvites.value = emptyList(); _joinRequests.value = emptyList()
        job = scope.launch {
            var cursor = 0L
            var lastFull = 0L
            // Odakta 5 sn → sonuç öncekinden FARKLI değilse 60 sn'ye kadar; odak dışında en az 45 sn (tamamen durmaz)
            val backoff = PollBackoff(baseMs = 5_000, maxMs = 60_000, unfocusedMs = 45_000)
            var prevDmIds = emptySet<String>()
            while (isActive) {
                val now = System.currentTimeMillis()
                val full = cursor <= 0L || now - lastFull > 10 * 60_000L
                val batch = runCatching {
                    FirestoreClient.fetchNotifications(uid, if (full) 0L else cursor - 120_000L, token)
                }.getOrNull()
                var changed = false
                if (batch != null) {
                    if (full) {
                        changed = batch.friendRequests != _friendRequests.value ||
                            batch.serverInvites != _serverInvites.value || batch.joinRequests != _joinRequests.value
                        _friendRequests.value = batch.friendRequests
                        _serverInvites.value  = batch.serverInvites
                        _joinRequests.value   = batch.joinRequests
                        lastFull = now
                    } else {
                        // Önce sırala, sonra karşılaştır: sıra farkı "yeni veri" sayılmasın
                        val fr = (batch.friendRequests + _friendRequests.value).distinctBy { it.fromUid }.sortedByDescending { it.timestamp }
                        val si = (batch.serverInvites + _serverInvites.value).distinctBy { it.serverId }.sortedByDescending { it.timestamp }
                        val jr = (batch.joinRequests + _joinRequests.value).distinctBy { "${it.serverId}_${it.fromUid}" }.sortedByDescending { it.timestamp }
                        changed = fr != _friendRequests.value || si != _serverInvites.value || jr != _joinRequests.value
                        _friendRequests.value = fr
                        _serverInvites.value  = si
                        _joinRequests.value   = jr
                    }
                    cursor = maxOf(cursor, batch.maxTimestamp, 1L)
                    // Zaman damgası imleci örtüşmesi aynı DM'leri tekrar getirir: kimlikler öncekinden farklıysa yeni say
                    val dmIds = batch.dms.map { it.docId }.toSet()
                    if (dmIds != prevDmIds) changed = true
                    prevDmIds = dmIds
                    if (batch.dms.isNotEmpty()) {
                        runCatching { dmHandler?.invoke(batch.dms) }
                    }
                }
                delay(backoff.next(changed))
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    // Çan eylemleri sonrası yerel kaldırma (yoklama bir sonraki turda zaten tutarlı kalır)
    fun dropFriendRequest(fromUid: String) { _friendRequests.value = _friendRequests.value.filter { it.fromUid != fromUid } }
    fun dropServerInvite(serverId: String) { _serverInvites.value = _serverInvites.value.filter { it.serverId != serverId } }
    fun dropJoinRequest(r: JoinRequestNotif) { _joinRequests.value = _joinRequests.value.filter { it != r } }
}
