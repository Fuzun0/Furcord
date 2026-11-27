package com.furcord.screenshare

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

private const val PIP_W  = 380
private const val PIP_H  = 224
private const val MARGIN = 16

/** Noktayı içeren monitörün yapılandırması (yoksa ana monitör). */
private fun screenAt(x: Int, y: Int): java.awt.GraphicsConfiguration {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    return ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(x, y) }
        ?: ge.defaultScreenDevice.defaultConfiguration
}

/** Uygulamanın ana penceresinin merkezindeki monitör — yayın orada açılır. */
private fun mainWindowScreen(): java.awt.GraphicsConfiguration {
    val main = java.awt.Window.getWindows()
        .filterIsInstance<java.awt.Frame>()
        .firstOrNull { it.isVisible && it.title == "Furcord — Voice Chat" }
    return if (main != null) screenAt(main.x + main.width / 2, main.y + main.height / 2)
    else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
}

/** Monitörün görev çubuğu hariç alanında sağ alt köşedeki küçük pencere konumu. */
private fun pipRect(gc: java.awt.GraphicsConfiguration): Rectangle {
    val b   = gc.bounds
    val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(
        b.x + b.width - ins.right - PIP_W - MARGIN,
        b.y + b.height - ins.bottom - PIP_H - MARGIN,
        PIP_W, PIP_H,
    )
}

/**
 * Yayın penceresi (küçük yüzen oynatıcı / tam ekran).
 *
 * Tam ekran, Windows'un "özel tam ekran" modu yerine monitörü tamamen kaplayan çerçevesiz bir
 * pencereyle yapılır. Özel tam ekran, odak başka monitöre geçince pencereyi simge durumuna
 * küçültür (oyun gibi davranır); bu yöntemde yayın açık kalır ve diğer monitör rahatça kullanılır.
 *
 * Pencere ekran yakalamasından dışlanır: yayıncı kendi ekranını izlerken iç içe görüntü oluşmaz,
 * izleyicilerin gördüğünün aynısını görür.
 */
@Composable
actual fun PiPStreamWindow(
    isSelfView:        Boolean,
    startInFullscreen: Boolean,
    peerVolume:        Float,
    onVolumeChange:    (Float) -> Unit,
    onClose:           () -> Unit,
    chatPanel:         (@Composable () -> Unit)?,
) {
    val startScreen = remember { mainWindowScreen() }
    val initial     = remember { if (startInFullscreen) startScreen.bounds else pipRect(startScreen) }

    val windowState = rememberWindowState(
        placement = WindowPlacement.Floating,
        size      = DpSize(initial.width.dp, initial.height.dp),
        position  = WindowPosition(initial.x.dp, initial.y.dp),
    )
    var isFullscreen by remember { mutableStateOf(startInFullscreen) }
    var awtWindow    by remember { mutableStateOf<java.awt.Window?>(null) }

    fun currentScreen(): java.awt.GraphicsConfiguration {
        val w = awtWindow ?: return startScreen
        return screenAt(w.x + w.width / 2, w.y + w.height / 2)
    }

    fun enterFullscreen() {
        val b = currentScreen().bounds
        windowState.position = WindowPosition(b.x.dp, b.y.dp)
        windowState.size     = DpSize(b.width.dp, b.height.dp)
        isFullscreen = true
        awtWindow?.let { runCatching { it.toFront(); it.requestFocus() } }
    }

    fun exitFullscreen() {
        val r = pipRect(currentScreen())
        isFullscreen = false
        windowState.size     = DpSize(r.width.dp, r.height.dp)
        windowState.position = WindowPosition(r.x.dp, r.y.dp)
    }

    fun toggleFullscreen() = if (isFullscreen) exitFullscreen() else enterFullscreen()

    val frameFlow = if (isSelfView) ScreenShareManager.localFrame else ScreenShareManager.receiverFrame
    val frameState = frameFlow.collectAsState()
    val hasFrame by remember(frameState) { derivedStateOf { frameState.value != null } }

    var showQualityMenu by remember { mutableStateOf(false) }
    var chatOpen        by remember { mutableStateOf(true) }
    val currentQuality  by ScreenEngine.quality.collectAsState()

    // Kontroller: küçük pencerede hep görünür; tam ekranda fare hareketinden sonra 3 sn
    var lastActivityMs    by remember { mutableStateOf(System.currentTimeMillis()) }
    var showControlsState by remember { mutableStateOf(true) }
    LaunchedEffect(lastActivityMs, isFullscreen) {
        showControlsState = true
        if (isFullscreen) {
            delay(3_000)
            showControlsState = false
        }
    }
    val showControls = !isFullscreen || showControlsState || !hasFrame || showQualityMenu

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        title          = if (isSelfView) "Furcord — Ekran Önizleme" else "Furcord — Yayın İzleniyor",
        alwaysOnTop    = !isFullscreen,    // küçük oynatıcı üstte kalır, tam ekran normal pencere gibi davranır
        undecorated    = true,
        resizable      = !isFullscreen,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && isFullscreen) { exitFullscreen(); true }
            else if (e.type == KeyEventType.KeyDown && e.key == Key.F) { toggleFullscreen(); true }
            else false
        },
    ) {
        // Pencere oluşunca yakalamadan dışla (iç içe ayna oluşmasın)
        DisposableEffect(window) {
            awtWindow = window
            CaptureExclusion.pin(window)
            onDispose { }
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF111214))) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = if (isFullscreen && chatPanel != null && chatOpen) 340.dp else 0.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                lastActivityMs = System.currentTimeMillis()
                            }
                        }
                    }
                    // Çift tık: tam ekran aç/kapat (küçük pencerede tek tık da büyütür)
                    .pointerInput(isFullscreen) {
                        detectTapGestures(
                            onDoubleTap = { toggleFullscreen() },
                            onTap       = { if (!isFullscreen) enterFullscreen() },
                        )
                    },
            ) {
                if (hasFrame) {
                    FrameSurface(frameFlow, Modifier.fillMaxSize())
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Belirsiz dönen gösterge yerine sabit metin: boşta CPU harcamaz
                        Text(
                            if (isSelfView) "Önizleme hazırlanıyor…" else "Yayın bekleniyor…",
                            color = Color(0xFFB5BAC1), fontSize = 13.sp,
                        )
                    }
                }

                // Sürükleme alanı (sadece küçük modda)
                if (!isFullscreen) {
                    WindowDraggableArea(
                        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().height(40.dp),
                    )
                }

                if (showControls) {
                    // Kapat — sağ üst
                    IconButton(
                        onClick  = onClose,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp),
                    ) {
                        Icon(Icons.Default.Close, "Kapat", tint = Color(0xFFED4245), modifier = Modifier.size(22.dp))
                    }

                    // Sağ alt: kalite + sohbet + tam ekran
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        if (isSelfView) {
                            Box {
                                IconButton(onClick = { showQualityMenu = !showQualityMenu }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Settings, "Kalite", tint = Color(0xFFB5BAC1), modifier = Modifier.size(22.dp))
                                }
                                DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }) {
                                    StreamQuality.entries.forEach { q ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${q.label}  ·  ${q.desc}",
                                                    color = if (currentQuality == q) Color(0xFF7C5CF6) else Color.Unspecified,
                                                )
                                            },
                                            onClick = { ScreenEngine.setQuality(q); showQualityMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        if (isFullscreen && chatPanel != null) {
                            TextButton(onClick = { chatOpen = !chatOpen }) {
                                Text(if (chatOpen) "Sohbeti Gizle" else "Sohbeti Göster", color = Color(0xFFB5BAC1), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { toggleFullscreen(); showQualityMenu = false }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                if (isFullscreen) "Küçült" else "Tam ekran",
                                tint = Color(0xFFB5BAC1), modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Sol alt: ses (sadece izleyici)
                    if (!isSelfView) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).width(160.dp),
                        ) {
                            Text("🔊", fontSize = 14.sp)
                            Slider(
                                value         = peerVolume,
                                onValueChange = onVolumeChange,
                                valueRange    = 0f..2f,
                                modifier      = Modifier.weight(1f).height(32.dp),
                                colors        = SliderDefaults.colors(
                                    thumbColor         = Color(0xFF5865F2),
                                    activeTrackColor   = Color(0xFF5865F2),
                                    inactiveTrackColor = Color(0xFF4E5058),
                                ),
                            )
                        }
                    }
                }
            }

            // Yayın sohbeti (sağ panel, tam ekranda)
            if (isFullscreen && chatPanel != null && chatOpen) {
                Box(Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight()) { chatPanel() }
            }
        }
    }
}
