package com.furcord.screenshare

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary

/**
 * Furcord'un yayın gösteren pencerelerini ekran yakalamasından dışlar.
 *
 * Yayıncı kendi ekranını paylaşırken önizleme penceresi de o ekranda durduğu için yakalanan
 * görüntünün içinde yine kendisi görünür → sonsuz "iç içe ayna" oluşur. Windows 10 2004+ ile
 * gelen WDA_EXCLUDEFROMCAPTURE bayrağı pencereyi tüm ekran yakalamalarından (BitBlt, DXGI,
 * Graphics Capture) gizler: kullanıcı pencereyi normal görür, yakalamada ise arkasındaki içerik
 * görünür. Böylece yayıncı kendi yayınını izleyicilerin gördüğü şekilde izler.
 */
internal object CaptureExclusion {

    private interface User32Ex : StdCallLibrary {
        fun SetWindowDisplayAffinity(hWnd: WinDef.HWND, dwAffinity: Int): Boolean
    }

    private const val WDA_NONE               = 0x00
    private const val WDA_MONITOR            = 0x01
    private const val WDA_EXCLUDEFROMCAPTURE = 0x11

    private val user32: User32Ex? = runCatching { Native.load("user32", User32Ex::class.java) }.getOrNull()

    private fun hwnd(window: java.awt.Window): WinDef.HWND? = runCatching {
        val p: Pointer = Native.getWindowPointer(window) ?: return@runCatching null
        WinDef.HWND(p)
    }.getOrNull()

    /** [window] yakalamadan dışlanır ([exclude]=true) veya tekrar görünür yapılır. */
    fun set(window: java.awt.Window, exclude: Boolean) {
        val api = user32 ?: return
        val h = hwnd(window) ?: return
        if (!exclude) { runCatching { api.SetWindowDisplayAffinity(h, WDA_NONE) }; return }
        val ok = runCatching { api.SetWindowDisplayAffinity(h, WDA_EXCLUDEFROMCAPTURE) }.getOrDefault(false)
        // Eski Windows sürümleri: pencere yakalamada siyah görünür (yine de iç içe ayna oluşmaz)
        if (!ok) runCatching { api.SetWindowDisplayAffinity(h, WDA_MONITOR) }
    }

    /** Her zaman dışlanan pencereler (yayın penceresi) — [setAll] bunları geri açmaz. */
    private val pinned = java.util.Collections.newSetFromMap(java.util.WeakHashMap<java.awt.Window, Boolean>())

    /** Yayın penceresi gibi her zaman dışlanacak bir pencereyi kaydeder ve dışlar. */
    fun pin(window: java.awt.Window) {
        synchronized(pinned) { pinned += window }
        set(window, true)
    }

    /** Uygulamanın görünür tüm pencerelerine uygular (ana pencere içindeki önizleme için). */
    fun setAll(exclude: Boolean) {
        val keep = synchronized(pinned) { pinned.toSet() }
        java.awt.Window.getWindows()
            .filter { it.isDisplayable && (exclude || it !in keep) }
            .forEach { set(it, exclude) }
    }
}
