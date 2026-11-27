package com.furcord.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Windows bildirimleri (sağ alttaki toast) için uygulama geneli köprü.
 *
 * Masaüstü giriş noktası ([sink]) sistem tepsisi üzerinden bildirimi gösterir.
 * Bildirime tıklanınca [onActivated] çağrılır; son bildirim bir DM ise uygulama o sohbeti açar.
 */
object Notifier {
    /** Masaüstü tarafının bağladığı gösterici: (başlık, içerik). */
    @Volatile var sink: ((title: String, body: String) -> Unit)? = null

    /** Ana pencere odakta mı (App tarafından güncellenir). */
    @Volatile var windowFocused: Boolean = true

    /** Şu an açık olan DM sohbetinin karşı taraf uid'si (açık değilse null). */
    @Volatile var openDmUid: String? = null

    /** Şu an ekranda görünen sunucunun kimliği (sunucu ekranında değilse null). */
    @Volatile var visibleServerId: String? = null

    /** Bildirime tıklanınca açılacak DM (uid, ad) — kısa süre geçerlidir. */
    private data class Target(val uid: String, val name: String, val at: Long)
    @Volatile private var lastDmTarget: Target? = null

    /** Bildirime tıklanınca açılması istenen DM; App gözler ve işler. */
    var dmOpenRequest by mutableStateOf<Pair<String, String>?>(null)

    fun notify(title: String, body: String) {
        val text = body.replace('\n', ' ').let { if (it.length > 240) it.take(237) + "…" else it }
        runCatching { sink?.invoke(title, text) }
    }

    fun notifyDm(fromUid: String, fromName: String, body: String) {
        lastDmTarget = Target(fromUid, fromName, System.currentTimeMillis())
        notify(fromName, body)
    }

    fun notifyServerMessage(serverName: String, username: String, body: String) {
        lastDmTarget = null
        notify("$username  •  $serverName", body)
    }

    /** Bildirim (veya tepsi simgesi) tıklandığında masaüstü tarafı çağırır. */
    fun onActivated() {
        val t = lastDmTarget ?: return
        lastDmTarget = null
        if (System.currentTimeMillis() - t.at < 60_000L) dmOpenRequest = t.uid to t.name
    }
}
