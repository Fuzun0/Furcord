package com.furcord.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Firestore'u her 8 saniyede bir yoklar; sohbet listesini ve okunmamış sayısını
 * [StateFlow] olarak sunar. Kullanıcı giriş yaptığında [start], çıkış yaptığında
 * [stop] çağrılmalıdır. Token yenilendiğinde [updateToken] ile bildirilmelidir.
 *
 * Okunmamış kural: son mesajın göndereni ben değilsem VE o thread'i bu oturumda
 * henüz açmadıysam → okunmamış sayılır.
 */
object DmRepository {

    // ── Dahili scope — uygulamanın tüm ömrü boyunca yaşar ───────────────────
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Durum akışları ───────────────────────────────────────────────────────
    private val _threads  = MutableStateFlow<List<DmConversation>>(emptyList())
    val threads: StateFlow<List<DmConversation>> = _threads.asStateFlow()

    /** Bu oturumda açılmış (okunmuş sayılan) dmId'lerin kümesi. */
    private val _readIds  = MutableStateFlow<Set<String>>(emptySet())

    // ── Dahili değişkenler ────────────────────────────────────────────────────
    private var myUid_:   String = ""
    @Volatile private var idToken_: String = ""
    private var pollJob:  Job?   = null

    /** true = pencere odakta (30s polling), false = arka plan (3 dakika polling) */
    @Volatile private var focused: Boolean = true

    /**
     * Okunmamış sohbet sayısı: son mesajı başkası tarafından gönderilmiş ve
     * bu oturumda henüz açılmamış thread'lerin sayısı.
     */
    val unreadCount: StateFlow<Int> = combine(_threads, _readIds) { threads, readIds ->
        threads.count { t ->
            t.lastSenderUid.isNotEmpty() &&
            t.lastSenderUid != myUid_ &&
            t.dmId !in readIds
        }
    }.stateIn(repoScope, SharingStarted.Eagerly, 0)

    /**
     * Okunmamış sohbetlerin listesi — FAB dropdown'ında gösterilir.
     */
    val unreadThreads: StateFlow<List<DmConversation>> = combine(_threads, _readIds) { threads, readIds ->
        threads.filter { t ->
            t.lastSenderUid.isNotEmpty() &&
            t.lastSenderUid != myUid_ &&
            t.dmId !in readIds
        }
    }.stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // ── Genel API ─────────────────────────────────────────────────────────────

    /**
     * Kullanıcı giriş yapınca çağrılır. Mevcut yoklama varsa önce durdurulur.
     * [idToken] her [updateToken] çağrısıyla taze kalır.
     */
    fun start(uid: String, idToken: String) {
        myUid_   = uid
        idToken_ = idToken
        _readIds.value = emptySet()

        pollJob?.cancel()
        pollJob = repoScope.launch {
            while (isActive) {
                runCatching {
                    val fresh = FirestoreClient.listDmConversations(uid, idToken_)
                    _threads.value = fresh
                }
                // Odaktayken 30 sn, arka planda 3 dakika
                delay(if (focused) 30_000L else 3 * 60_000L)
            }
        }
    }

    /**
     * Token yenilendiğinde App.kt'den çağrılır. Yoklama döngüsünü durdurmaz,
     * sadece sonraki istekte kullanılacak token'ı günceller.
     */
    fun updateToken(idToken: String) {
        idToken_ = idToken
    }

    /**
     * Pencere odak durumu değiştiğinde App.kt'den çağrılır.
     * Polling aralığını ayarlar: odakta=30s, arka plan=3 dakika.
     */
    fun setFocused(focused: Boolean) {
        this.focused = focused
    }

    /**
     * DM penceresi açıldığında çağrılır. İlgili thread'i "okundu" olarak işaretler.
     * [dmId] hesabı: `listOf(myUid, otherUid).sorted().joinToString("_")`
     */
    fun markRead(dmId: String) {
        _readIds.value = _readIds.value + dmId
    }

    /**
     * Kullanıcı çıkış yapınca veya oturum kapanınca çağrılır.
     * Yoklamayı durdurur ve tüm durumu sıfırlar.
     */
    fun stop() {
        pollJob?.cancel()
        pollJob   = null
        _threads.value  = emptyList()
        _readIds.value  = emptySet()
        myUid_   = ""
        idToken_ = ""
    }
}
