package com.furcord.battery

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

private interface DpiLib : StdCallLibrary {
    fun GetDpiForWindow(hwnd: HWND): Int
    companion object { val INSTANCE: DpiLib = Native.load("user32", DpiLib::class.java) }
}

/**
 * Pil yüzdelerini doğrudan Windows görev çubuğuna (saat/tepsi alanının soluna) çizer.
 * Shell_TrayWnd'nin saydam, tıklamayı geçiren bir alt penceresini kullanır.
 */
class BatteryTaskbar {
    @Volatile private var devices: List<BatteryDevice> = emptyList()
    @Volatile private var running = false
    private var thread: Thread? = null

    private var hwnd: HWND? = null
    private var parent: HWND? = null
    private var lastSig = ""
    private var tickCount = 0

    fun update(list: List<BatteryDevice>) { devices = list }

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "battery-taskbar").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }

    // ── Mesaj döngüsü (pencerenin oluşturulduğu thread'de dönmek zorunda) ──────
    private fun loop() {
        val u = User32.INSTANCE
        val msg = WinUser.MSG()
        var next = 0L
        try {
            while (running) {
                while (u.PeekMessage(msg, null, 0, 0, 1 /* PM_REMOVE */)) {
                    u.TranslateMessage(msg); u.DispatchMessage(msg)
                }
                val now = System.currentTimeMillis()
                if (now >= next) {
                    next = now + 1000
                    try { tick() } catch (_: Throwable) { }
                }
                Thread.sleep(100)
            }
        } finally {
            hwnd?.let { u.DestroyWindow(it) }
            hwnd = null
        }
    }

    private fun tick() {
        val u = User32.INSTANCE
        val list = devices.sortedBy { it.kind.ordinal }
        val taskbar = u.FindWindow("Shell_TrayWnd", null)

        val tb = WinDef.RECT()
        if (taskbar == null || list.isEmpty() || !u.GetWindowRect(taskbar, tb)) {
            hwnd?.let { u.ShowWindow(it, WinUser.SW_HIDE) }
            lastSig = ""
            return
        }
        val tbW = tb.right - tb.left
        val tbH = tb.bottom - tb.top
        if (tbH > tbW) {                       // dikey görev çubuğu: desteklenmiyor
            hwnd?.let { u.ShowWindow(it, WinUser.SW_HIDE) }
            lastSig = ""
            return
        }

        // Explorer yeniden başladıysa görev çubuğu tutamacı değişir → pencereyi yeniden kur
        if (taskbar != parent) {
            hwnd?.let { u.DestroyWindow(it) }
            hwnd = null
        }
        // Pencere sistem tarafından yok edildiyse (görev çubuğu yeniden kuruldu) yeniden oluştur
        if (hwnd != null && !u.IsWindow(hwnd)) hwnd = null
        if (hwnd == null) {
            hwnd = createChild(taskbar)
            parent = taskbar
            lastSig = ""
        }
        val h = hwnd ?: return

        // Tepsi alanının (TrayNotifyWnd) sol kenarı
        var rightEdge = tbW
        val notify = u.FindWindowEx(taskbar, null, "TrayNotifyWnd", null)
        val nr = WinDef.RECT()
        if (notify != null && u.GetWindowRect(notify, nr)) rightEdge = nr.left - tb.left

        val dpi = DpiLib.INSTANCE.GetDpiForWindow(taskbar).takeIf { it > 0 } ?: 96
        val scale = dpi / 96.0
        val dark = isDarkTaskbar()

        val img = render(list, tbH, scale, dark)
        val w = img.width
        val x = rightEdge - w - (8 * scale).toInt()

        // SWP_NOACTIVATE | SWP_SHOWWINDOW, HWND_TOP
        u.SetWindowPos(h, null, x, 0, w, tbH, 0x0010 or 0x0040)

        val sig = list.joinToString { "${it.id}:${it.percent}" } + "|$w|$tbH|$dark"
        // Görev çubuğu (Windows 11) içeriği sıfırlayabilir → imza değişmese de birkaç saniyede bir yeniden çiz
        tickCount++
        if (sig != lastSig || tickCount % 3 == 0) {
            lastSig = sig
            paint(h, img)
        }
    }

    // ── Pencere oluşturma ─────────────────────────────────────────────────────
    private val wndProc = WinUser.WindowProc { h, m, w, l -> User32.INSTANCE.DefWindowProc(h, m, w, l) }
    private var classRegistered = false

    private fun createChild(taskbar: HWND): HWND? {
        val u = User32.INSTANCE
        val hInst = Kernel32.INSTANCE.GetModuleHandle(null)
        if (!classRegistered) {
            val wc = WinUser.WNDCLASSEX()
            wc.lpfnWndProc = wndProc
            wc.hInstance = hInst
            wc.lpszClassName = "FurcordBatteryStrip"
            u.RegisterClassEx(wc)
            classRegistered = true
        }
        val WS_CHILD = 0x40000000
        val WS_VISIBLE = 0x10000000
        val WS_EX_LAYERED = 0x00080000
        val WS_EX_TRANSPARENT = 0x00000020   // tıklamalar alttaki görev çubuğuna geçer
        val WS_EX_NOACTIVATE = 0x08000000
        return u.CreateWindowEx(
            WS_EX_LAYERED or WS_EX_TRANSPARENT or WS_EX_NOACTIVATE,
            "FurcordBatteryStrip", "FurcordBattery",
            WS_CHILD or WS_VISIBLE,
            0, 0, 10, 10, taskbar, null, hInst, null,
        )
    }

    // ── Çizim: Java2D → premultiplied BGRA DIB → UpdateLayeredWindow ──────────
    private fun paint(h: HWND, img: BufferedImage) {
        val u = User32.INSTANCE
        val g32 = GDI32.INSTANCE
        val screenDC = u.GetDC(null)
        val memDC = g32.CreateCompatibleDC(screenDC)

        val bmi = WinGDI.BITMAPINFO()
        bmi.bmiHeader.biWidth = img.width
        bmi.bmiHeader.biHeight = -img.height          // yukarıdan aşağıya
        bmi.bmiHeader.biPlanes = 1
        bmi.bmiHeader.biBitCount = 32
        bmi.bmiHeader.biCompression = WinGDI.BI_RGB

        val bits = PointerByReference()
        val dib = g32.CreateDIBSection(memDC, bmi, WinGDI.DIB_RGB_COLORS, bits, null, 0)
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        bits.value.write(0, pixels, 0, pixels.size)
        val old = g32.SelectObject(memDC, dib)

        val blend = WinUser.BLENDFUNCTION()
        blend.BlendOp = WinUser.AC_SRC_OVER.toByte()
        blend.BlendFlags = 0
        blend.SourceConstantAlpha = 255.toByte()
        blend.AlphaFormat = WinUser.AC_SRC_ALPHA.toByte()

        u.UpdateLayeredWindow(
            h, screenDC, null, WinUser.SIZE().also { it.cx = img.width; it.cy = img.height },
            memDC, WinDef.POINT(0, 0), 0, blend, WinUser.ULW_ALPHA,
        )

        g32.SelectObject(memDC, old)
        g32.DeleteObject(dib)
        g32.DeleteDC(memDC)
        u.ReleaseDC(null, screenDC)
    }

    private val iconFamily: String by lazy {
        val fams = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        listOf("Segoe Fluent Icons", "Segoe MDL2 Assets").firstOrNull { it in fams } ?: "Segoe UI Symbol"
    }

    private fun glyph(k: DeviceKind) = when (k) {
        DeviceKind.HEADSET  -> "\uE7F6"
        DeviceKind.MOUSE    -> "\uE962"
        DeviceKind.KEYBOARD -> "\uE765"
        DeviceKind.OTHER    -> "\uE772"
    }

    private fun render(list: List<BatteryDevice>, h: Int, s: Double, dark: Boolean): BufferedImage {
        val iconFont = Font(iconFamily, Font.PLAIN, (16 * s).toInt())
        val textFont = Font("Segoe UI", Font.PLAIN, (13 * s).toInt())
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics()
        val ifm = scratch.getFontMetrics(iconFont)
        val tfm = scratch.getFontMetrics(textFont)
        scratch.dispose()

        val gap = (4 * s).toInt()
        val pad = (12 * s).toInt()
        val widths = list.map { ifm.stringWidth(glyph(it.kind)) + gap + tfm.stringWidth("${it.percent}%") }
        val total = widths.sum() + pad * (list.size - 1) + (4 * s).toInt()

        val img = BufferedImage(total.coerceAtLeast(1), h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val base = if (dark) Color.WHITE else Color.BLACK
        var x = 0
        for ((i, d) in list.withIndex()) {
            g.color = if (d.percent <= 15) Color(0xFF5555) else base
            g.font = iconFont
            g.drawString(glyph(d.kind), x, (h - ifm.height) / 2 + ifm.ascent)
            x += ifm.stringWidth(glyph(d.kind)) + gap
            g.font = textFont
            g.drawString("${d.percent}%", x, (h - tfm.height) / 2 + tfm.ascent)
            x += tfm.stringWidth("${d.percent}%") + pad
        }
        g.dispose()
        return img
    }

    private fun isDarkTaskbar(): Boolean = try {
        Advapi32Util.registryGetIntValue(
            WinReg.HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "SystemUsesLightTheme",
        ) == 0
    } catch (_: Exception) { true }
}