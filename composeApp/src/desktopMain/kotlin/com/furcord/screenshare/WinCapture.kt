package com.furcord.screenshare

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Windows GDI tabanlı yakalama (JNA).
 *
 * libwebrtc DesktopCapturer yerine kullanılır: o kütüphanenin geri çağırımı GC sırasında JVM'i
 * çökertiyordu (EXCEPTION_ACCESS_VIOLATION). GDI/PrintWindow yolu saf JNA olduğundan GC'ye dayanıklıdır.
 *
 *  • Ekran  : monitör bölgesi BitBlt (CAPTUREBLT) ile kopyalanır.
 *  • Pencere: PrintWindow(PW_RENDERFULLCONTENT) — pencere başka pencerenin arkasında olsa bile içeriği alır.
 *             Başarısız olursa pencerenin ekrandaki bölgesi BitBlt ile alınır.
 */
internal object WinCapture {

    private val u32 = User32.INSTANCE
    private val g32 = GDI32.INSTANCE

    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: WinDef.HWND, attr: Int, out: IntByReference, size: Int): Int
    }
    private val dwm: Dwm? = runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()

    private const val SRCCOPY       = 0x00CC0020
    private const val CAPTUREBLT    = 0x40000000
    private const val PW_RENDERFULL = 2
    private const val GWL_EXSTYLE   = -20
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val DWMWA_CLOAKED = 14

    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    // ── Kaynak listesi ───────────────────────────────────────────────────────

    private class Monitor(val id: Long, val rect: Rect, val primary: Boolean)

    private fun monitors(): List<Monitor> {
        val out = mutableListOf<Monitor>()
        val cb = object : WinUser.MONITORENUMPROC {
            override fun apply(hMon: WinUser.HMONITOR, hdc: WinDef.HDC?, r: WinDef.RECT, lp: WinDef.LPARAM?): Int {
                val info = WinUser.MONITORINFO()
                u32.GetMonitorInfo(hMon, info)
                out += Monitor(
                    id      = Pointer.nativeValue(hMon.pointer),
                    rect    = Rect(r.left, r.top, r.right - r.left, r.bottom - r.top),
                    primary = (info.dwFlags and 1) != 0,
                )
                return 1
            }
        }
        u32.EnumDisplayMonitors(null, null, cb, WinDef.LPARAM(0))
        return out.sortedByDescending { it.primary }
    }

    fun listScreens(): List<ShareSource> =
        monitors().mapIndexed { i, m ->
            ShareSource(
                m.id,
                "Ekran ${i + 1}" + (if (m.primary) " (ana)" else "") + " — ${m.rect.width}×${m.rect.height}",
                isScreen = true,
            )
        }

    fun listWindows(): List<ShareSource> {
        val out = mutableListOf<ShareSource>()
        val cb = WinUser.WNDENUMPROC { hwnd, _ ->
            runCatching {
                if (!u32.IsWindowVisible(hwnd)) return@runCatching
                if ((u32.GetWindowLong(hwnd, GWL_EXSTYLE) and WS_EX_TOOLWINDOW) != 0) return@runCatching
                val len = u32.GetWindowTextLength(hwnd)
                if (len <= 0) return@runCatching
                val cloaked = IntByReference()
                dwm?.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloaked, 4)
                if (cloaked.value != 0) return@runCatching          // sanal masaüstünde gizli / UWP askıda
                val rect = WinDef.RECT(); u32.GetWindowRect(hwnd, rect)
                if (rect.right - rect.left < 120 || rect.bottom - rect.top < 80) return@runCatching
                val buf = CharArray(len + 1)
                val n = u32.GetWindowText(hwnd, buf, buf.size)
                if (n <= 0) return@runCatching
                val title = String(buf, 0, n)
                if (title == "Program Manager") return@runCatching
                out += ShareSource(Pointer.nativeValue(hwnd.pointer), title, isScreen = false)
            }
            true
        }
        u32.EnumWindows(cb, null)
        return out
    }

    // ── Yakalayıcı ───────────────────────────────────────────────────────────

    /** Bir ekran ya da pencereden kare alır. Aynı thread'den kullanın; işi bitince [close]. */
    class Grabber private constructor(
        private val isScreen: Boolean,
        private val handle: Long,
        private var screenRect: Rect?,
    ) : AutoCloseable {

        private val screenDc: WinDef.HDC? = u32.GetDC(null)
        private val memDc: WinDef.HDC? = g32.CreateCompatibleDC(screenDc)
        private var hBmp: WinDef.HBITMAP? = null
        private var oldObj: WinNT.HANDLE? = null
        private var bits: Pointer? = null
        private var bmpW = 0
        private var bmpH = 0
        private var raw: BufferedImage? = null          // tam çözünürlüklü ham kare (yeniden kullanılır)
        private var scaled: BufferedImage? = null       // hedef boyuta indirilmiş kare

        private fun ensureBitmap(w: Int, h: Int): Boolean {
            if (w == bmpW && h == bmpH && hBmp != null) return true
            freeBitmap()
            val bmi = WinGDI.BITMAPINFO()
            bmi.bmiHeader.biWidth = w
            bmi.bmiHeader.biHeight = -h                 // üstten alta
            bmi.bmiHeader.biPlanes = 1
            bmi.bmiHeader.biBitCount = 32
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB
            val ppv = PointerByReference()
            val bmp = g32.CreateDIBSection(memDc, bmi, WinGDI.DIB_RGB_COLORS, ppv, null, 0) ?: return false
            if (ppv.value == null) { g32.DeleteObject(bmp); return false }
            hBmp = bmp; bits = ppv.value; bmpW = w; bmpH = h
            oldObj = g32.SelectObject(memDc, bmp)
            raw = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            return true
        }

        private fun freeBitmap() {
            runCatching { if (oldObj != null) g32.SelectObject(memDc, oldObj) }
            runCatching { hBmp?.let { g32.DeleteObject(it) } }
            hBmp = null; oldObj = null; bits = null; bmpW = 0; bmpH = 0; raw = null
        }

        /** Kareyi alır ve [maxW]×[maxH] içine sığacak şekilde küçültür; alınamazsa null. */
        fun grab(maxW: Int, maxH: Int): BufferedImage? {
            if (screenDc == null || memDc == null) return null

            val src: Rect
            var printed = false
            if (isScreen) {
                src = screenRect ?: return null
                if (!ensureBitmap(src.width, src.height)) return null
                g32.BitBlt(memDc, 0, 0, src.width, src.height, screenDc, src.left, src.top, SRCCOPY or CAPTUREBLT)
            } else {
                val hwnd = WinDef.HWND(Pointer.createConstant(handle))
                val r = WinDef.RECT()
                if (!u32.GetWindowRect(hwnd, r)) return null
                val w = r.right - r.left; val h = r.bottom - r.top
                if (w <= 0 || h <= 0 || w > 16_000 || h > 16_000) return null   // küçültülmüş / kapanmış
                src = Rect(r.left, r.top, w, h)
                if (!ensureBitmap(w, h)) return null
                printed = u32.PrintWindow(hwnd, memDc, PW_RENDERFULL)
                if (!printed) {
                    g32.BitBlt(memDc, 0, 0, w, h, screenDc, r.left, r.top, SRCCOPY or CAPTUREBLT)
                }
            }

            val img = raw ?: return null
            val data = (img.raster.dataBuffer as DataBufferInt).data
            bits!!.read(0, data, 0, src.width * src.height)

            // Hedef boyut (büyütme yok, çift sayı)
            val ratio = minOf(maxW.toDouble() / src.width, maxH.toDouble() / src.height, 1.0)
            val tw = ((src.width * ratio).toInt() and 1.inv()).coerceAtLeast(2)
            val th = ((src.height * ratio).toInt() and 1.inv()).coerceAtLeast(2)

            val out: BufferedImage
            if (tw == src.width && th == src.height) {
                // Ham görüntüyü doğrudan kullan; imleci çizmek için kopya gerek yok (paylaşılan buffer)
                out = img
            } else {
                var s = scaled
                if (s == null || s.width != tw || s.height != th) {
                    s = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB); scaled = s
                }
                val g = s.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, tw, th, null)
                g.dispose()
                out = s
            }

            drawCursor(out, src, tw.toDouble() / src.width)
            return out
        }

        /** Fare imlecini (basit ok) kareye çizer — GDI yakalama imleci içermez. */
        private fun drawCursor(img: BufferedImage, src: Rect, scale: Double) {
            runCatching {
                val p = WinDef.POINT()
                if (!u32.GetCursorPos(p)) return
                val x = ((p.x - src.left) * scale).toInt()
                val y = ((p.y - src.top) * scale).toInt()
                if (x < 0 || y < 0 || x >= img.width || y >= img.height) return
                val k = (scale.coerceIn(0.5, 1.5) * 1.0)
                fun sc(v: Int) = (v * k).toInt()
                val xs = intArrayOf(x, x, x + sc(4), x + sc(7), x + sc(10), x + sc(7), x + sc(14))
                val ys = intArrayOf(y, y + sc(19), y + sc(15), y + sc(23), y + sc(22), y + sc(14), y + sc(14))
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = java.awt.Color.WHITE
                g.fillPolygon(xs, ys, xs.size)
                g.color = java.awt.Color.BLACK
                g.drawPolygon(xs, ys, xs.size)
                g.dispose()
            }
        }

        override fun close() {
            freeBitmap()
            runCatching { if (memDc != null) g32.DeleteDC(memDc) }
            runCatching { if (screenDc != null) u32.ReleaseDC(null, screenDc) }
        }

        companion object {
            /** [source] null → ana monitör. */
            fun open(source: ShareSource?): Grabber? = runCatching {
                if (source == null || source.isScreen) {
                    val mons = monitors()
                    val m = mons.firstOrNull { source == null || source.id == 0L || it.id == source.id }
                        ?: mons.firstOrNull() ?: return@runCatching null
                    Grabber(true, m.id, m.rect)
                } else {
                    Grabber(false, source.id, null)
                }
            }.getOrNull()
        }
    }
}
