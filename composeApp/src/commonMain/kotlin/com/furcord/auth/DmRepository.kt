package com.furcord.auth

import com.furcord.ui.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Global DM sohbet durum deposu.
 *
 *  • Sohbet listesi: arkadaşlar + bilinen sohbetlerin son mesajları (odakta 30 sn, arka planda 3 dk).
 *  • Gelen kutusu: gönderici her DM'de alıcının `notifications` koleksiyonuna "dm" kaydı bırakır.
 *    Bu kutu sık yoklanır (odakta 4 sn, arka planda 8 sn); yeni DM gelince Windows bildirimi
 *    gösterilir, sohbet listesi anında güncellenir ve kayıt silinir.
 *
 * Okunmamış kural: son mesajı başkası göndermiş VE sohbetin "okundu" zamanından yeniyse.
 */
object DmRepository {

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _threads  = MutableStateFlow<List<DmConversation>>(emptyList())
    val threads: StateFlow<List<DmConversation>> = _threads.asStateFlow()

    /** dmId → okundu olarak işaretlendiği zaman damgası. */
    private val _readAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Bir sohbet silindiğinde artar — açık sohbet paneli eski mesajları gizlemek için gözler. */
    private val _clearedVersion = MutableStateFlow(0)
    val clearedVersion: StateFlow<Int> = _clearedVersion.asStateFlow()

    /** Gelen kutusundan yeni DM geldiğinde artar — açık sohbet paneli hemen yenilenir. */
    private val _incomingVersion = MutableStateFlow(0)
    val incomingVersion: StateFlow<Int> = _incomingVersion.asStateFlow()

    @Volatile private var myUid_:   String = ""
    @Volatile private var idToken_: String = ""
    private var pollJob:  Job? = null
    private var inboxJob: Job? = null

    @Volatile private var focused: Boolean = true

    private fun isUnread(t: DmConversation, readAt: Map<String, Long>): Boolean =
        t.lastSenderUid.isNotEmpty() &&
            t.lastSenderUid != myUid_ &&
            t.lastTimestamp > (readAt[t.dmId] ?: storedReadAt(t.dmId))

    val unreadCount: StateFlow<Int> = combine(_threads, _readAt) { threads, readAt ->
        threads.count { isUnread(it, readAt) }
    }.stateIn(repoScope, SharingStarted.Eagerly, 0)

    val unreadThreads: StateFlow<List<DmConversation>> = combine(_threads, _readAt) { threads, readAt ->
        threads.filter { isUnread(it, readAt) }
    }.stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // ── Genel API ─────────────────────────────────────────────────────────────

    fun start(uid: String, idToken: String) {
        myUid_   = uid
        idToken_ = idToken
        _readAt.value = emptyMap()

        pollJob?.cancel()
        pollJob = repoScope.launch {
            while (isActive) {
                refreshNow()
                delay(if (focused) 30_000L else 3 * 60_000L)
            }
        }

        inboxJob?.cancel()
        inboxJob = repoScope.launch {
            while (isActive) {
                runCatching { pollInbox() }
                delay(if (focused) 4_000L else 8_000L)
            }
        }
    }

    private suspend fun refreshNow() {
        val uid = myUid_
        if (uid.isEmpty()) return
        runCatching {
            val known = _threads.value.map { it.otherUid }
            val fresh = FirestoreClient.listDmConversations(uid, idToken_, known)
            // Kullanıcının sildiği sohbetler, yeni mesaj gelene dek gizli kalır
            _threads.value = fresh.filter { it.lastTimestamp > clearedAt(it.dmId) }
        }
    }

    private suspend fun pollInbox() {
        val uid = myUid_
        if (uid.isEmpty()) return
        val notifs = FirestoreClient.listDmNotifs(uid, idToken_)
        if (notifs.isEmpty()) return
        for (n in notifs) {
            FirestoreClient.deleteNotification(uid, n.docId, idToken_)
            val dmId    = listOf(uid, n.fromUid).sorted().joinToString("_")
            val preview = n.text.ifBlank { if (n.hasAttachment) "📎 Bir dosya gönderdi" else "Yeni mesaj" }
            val name    = n.fromName.ifBlank { "Yeni mesaj" }

            // Sohbet listesini anında güncelle (silinmiş sohbet yeni mesajla geri gelir)
            val conv = DmConversation(dmId, n.fromUid, name, preview, n.timestamp, n.fromUid)
            _threads.value = (listOf(conv) + _threads.value.filter { it.dmId != dmId })
                .sortedByDescending { it.lastTimestamp }

            val chatVisible = Notifier.windowFocused && Notifier.openDmUid == n.fromUid
            if (chatVisible) markRead(dmId) else Notifier.notifyDm(n.fromUid, name, preview)
        }
        _incomingVersion.value++
    }

    fun updateToken(idToken: String) {
        idToken_ = idToken
    }

    fun setFocused(focused: Boolean) {
        this.focused = focused
    }

    /** Bu sohbette kullanıcının "sil" dediği andaki zaman damgası (0 = hiç silinmedi). */
    fun clearedAt(dmId: String): Long = AppPrefs.get("dmCleared_$dmId").toLongOrNull() ?: 0L

    private fun storedReadAt(dmId: String): Long = AppPrefs.get("dmRead_$dmId").toLongOrNull() ?: 0L

    /**
     * Sohbeti yalnızca bu kullanıcı için siler: listeden kalkar ve eski mesajlar gizlenir.
     * Karşı tarafın mesaj geçmişi etkilenmez; yeni mesaj gelirse sohbet yeniden görünür.
     */
    fun clearConversation(dmId: String) {
        val last = _threads.value.find { it.dmId == dmId }?.lastTimestamp ?: 0L
        AppPrefs.set("dmCleared_$dmId", maxOf(System.currentTimeMillis(), last).toString())
        _threads.value = _threads.value.filter { it.dmId != dmId }
        _clearedVersion.value++
    }

    /** Sohbeti okundu işaretler (şu an + son mesaj zamanı, saat farkına karşı). */
    fun markRead(dmId: String) {
        val last = _threads.value.find { it.dmId == dmId }?.lastTimestamp ?: 0L
        val ts = maxOf(System.currentTimeMillis(), last)
        _readAt.value = _readAt.value + (dmId to ts)
        repoScope.launch { AppPrefs.set("dmRead_$dmId", ts.toString()) }
    }

    fun stop() {
        pollJob?.cancel();  pollJob  = null
        inboxJob?.cancel(); inboxJob = null
        _threads.value = emptyList()
        _readAt.value  = emptyMap()
        myUid_   = ""
        idToken_ = ""
    }
}
