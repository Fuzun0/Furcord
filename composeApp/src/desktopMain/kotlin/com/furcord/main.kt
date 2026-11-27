package com.furcord

import androidx.compose.foundation.background
import com.furcord.ui.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import java.awt.GraphicsEnvironment
import com.furcord.battery.BatteryReader
import com.furcord.battery.BatteryTaskbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SINGLE_INSTANCE_PORT = 47652

/** Ikinci kez açılma istekleri (masaüstü kısayolu vb.) — artınca mevcut pencere öne getirilir. */
private var showRequests by mutableStateOf(0)

/**
 * Tek örnek kilidi: localhost portunu ilk açan uygulama sahibidir. Ikinci örnek, sahibine
 * "göster" sinyali yollayıp kendini kapatır.
 */
private fun acquireSingleInstance(): Boolean = try {
    val ss = java.net.ServerSocket(SINGLE_INSTANCE_PORT, 5, java.net.InetAddress.getLoopbackAddress())
    Thread {
        while (true) {
            try { ss.accept().use { showRequests++ } } catch (_: Exception) { break }
        }
    }.apply { isDaemon = true; start() }
    true
} catch (_: java.net.BindException) {
    runCatching {
        java.net.Socket(java.net.InetAddress.getLoopbackAddress(), SINGLE_INSTANCE_PORT).use { it.getOutputStream().write(1) }
    }.isFailure   // sahibine ulaşılamadıysa (port başka bir uygulamada) normal aç
} catch (_: Exception) { true }

fun main() {
    if (acquireSingleInstance()) runApp()
}

private fun runApp() = application {
    val icon = painterResource("furcord_logo.png")

    // Görev çubuğu hariç kullanılabilir ekran alanı. AWT'nin mantıksal pikselleri
    // (DPI ölçeği uygulanmış) Compose'daki dp ile birebir aynıdır.
    val usable   = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val fullPos  = remember { WindowPosition(usable.x.dp, usable.y.dp) }
    val fullSize = remember { DpSize(usable.width.dp, usable.height.dp) }

    // Native "maximize" yerine pencereyi kendimiz ölçülendiriyoruz: çerçevesiz pencerede
    // native maximize hem görev çubuğunu örter hem de ölçekli ekranlarda taşar.
    val state = rememberWindowState(
        placement = WindowPlacement.Floating,
        position  = fullPos,
        size      = fullSize,
    )
    var windowVisible by remember { mutableStateOf(true) }

          // ── Kablosuz cihaz pil göstergesi (doğrudan görev çubuğunda) ──────────────
    val batteryBar = remember { BatteryTaskbar().also { it.start() } }
    LaunchedEffect(Unit) {
        while (true) {
            val devices = withContext(Dispatchers.IO) { BatteryReader.readStable() }
            batteryBar.update(devices)
            delay(30_000)            // 30 saniyede bir yenile
        }
    }
    DisposableEffect(Unit) { onDispose { batteryBar.stop() } }

    // Pencerenin bulunduğu monitörü bulmak için AWT penceresine referans
    val winHolder = remember { arrayOfNulls<java.awt.Window>(1) }

    /** Pencerenin merkezinin bulunduğu monitörün (görev çubuğu hariç) kullanılabilir alanı. */
    fun usableForWindow(): java.awt.Rectangle {
        val win = winHolder[0] ?: return usable
        return runCatching {
            val center = java.awt.Point(win.x + win.width / 2, win.y + win.height / 2)
            val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            val gc = devices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(center) }
                ?: win.graphicsConfiguration
                ?: return@runCatching usable
            val b   = gc.bounds
            val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            java.awt.Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
        }.getOrDefault(usable)
    }

    val isFilled = usableForWindow().let { r ->
        state.size == DpSize(r.width.dp, r.height.dp) &&
            state.position.x == r.x.dp && state.position.y == r.y.dp
    }

    fun fillScreen() {
        val r = usableForWindow()
        state.position = WindowPosition(r.x.dp, r.y.dp)
        state.size     = DpSize(r.width.dp, r.height.dp)
    }

    fun restoreDown() {
        val r = usableForWindow()
        val w = (r.width  * 0.8).toInt()
        val h = (r.height * 0.8).toInt()
        state.size     = DpSize(w.dp, h.dp)
        state.position = WindowPosition((r.x + (r.width - w) / 2).dp, (r.y + (r.height - h) / 2).dp)
    }

    fun showWindow(fill: Boolean) {
        windowVisible = true
        state.isMinimized = false
        if (fill) fillScreen()
    }

    fun bringToFront() {
        showWindow(fill = false)
        winHolder[0]?.let { w ->
            runCatching {
                w.isAlwaysOnTop = true; w.toFront(); w.requestFocus(); w.isAlwaysOnTop = false
            }
        }
    }

    // Uygulama zaten açıkken tekrar başlatılırsa (masaüstü kısayolu) mevcut pencereyi öne getir
    LaunchedEffect(showRequests) {
        if (showRequests > 0) bringToFront()
    }

    // ── Windows bildirimleri (mesaj gelince sağ altta toast) ─────────────────
    val trayState = rememberTrayState()
    DisposableEffect(trayState) {
        com.furcord.ui.Notifier.sink = { title, body ->
            trayState.sendNotification(Notification(title, body, Notification.Type.None))
        }
        onDispose { com.furcord.ui.Notifier.sink = null }
    }

    // ── Sistem tepsisi (saat yanındaki simge) ─────────────────────────────────
    Tray(
        icon     = icon,
        state    = trayState,
        tooltip  = "Furcord",
        // Çift tık veya bildirime tıklama: pencereyi öne getir (bildirim bir DM ise sohbeti aç)
        onAction = { bringToFront(); com.furcord.ui.Notifier.onActivated() },
        menu     = {
            Item("Uygulamayı Büyüt", onClick = { showWindow(fill = true) })
            Separator()
            Item("Uygulamadan Çık", onClick = { exitApplication() })
        },
    )

    Window(
        visible        = windowVisible,
        onCloseRequest = { windowVisible = false },       // çarpı: kapatma, tepsiye küçült
        title          = "Furcord — Voice Chat",
        icon           = icon,
        state          = state,
        undecorated    = true,
    ) {
        SideEffect { winHolder[0] = window }
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Koyu özel başlık çubuğu ────────────────────────────────────
            WindowDraggableArea(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Color(0xFF1E1F22)),
            ) {
                Row(
                    modifier          = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text      = "Furcord — Voice Chat",
                        color     = Color(0xFF96989D),
                        fontSize  = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))

                    // Küçült ──────────────────────────────────────────────────
                    TitleBarButton(
                        onClick      = { state.isMinimized = true },
                        hoverColor   = Color(0xFF3A3B3D),
                    ) { Text("—", color = Color(0xFFDCDDDE), fontSize = 12.sp) }

                    // Büyüt / Geri yükle ──────────────────────────────────────
                    TitleBarButton(
                        onClick    = { if (isFilled) restoreDown() else fillScreen() },
                        hoverColor = Color(0xFF3A3B3D),
                    ) {
                        Text(
                            text     = if (isFilled) "❐" else "□",
                            color    = Color(0xFFDCDDDE),
                            fontSize = 12.sp,
                        )
                    }

                    // Kapat: tepsiye küçült ───────────────────────────────────
                    TitleBarButton(
                        onClick    = { windowVisible = false },
                        hoverColor = Color(0xFFED4245),
                    ) { Text("×", color = Color(0xFFDCDDDE), fontSize = 14.sp) }
                }
            }

            // ── Uygulama içeriği ───────────────────────────────────────────
            App()
        }
    }
}

@Composable
private fun TitleBarButton(
    onClick:    () -> Unit,
    hoverColor: Color,
    content:    @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered     by interaction.collectIsHoveredAsState()
    Box(
        modifier          = Modifier
            .size(46.dp, 32.dp)
            .background(if (hovered) hoverColor else Color.Transparent)
            .hoverable(interaction)
            .clickable(indication = null, interactionSource = interaction, onClick = onClick),
        contentAlignment  = Alignment.Center,
    ) { content() }
}
