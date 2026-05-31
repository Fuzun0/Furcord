package com.furcord.screenshare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Toolkit

@Composable
actual fun PiPStreamWindow(
    isSelfView:     Boolean,
    peerVolume:     Float,
    onVolumeChange: (Float) -> Unit,
    onClose:        () -> Unit,
) {
    val screenSize: Dimension = Toolkit.getDefaultToolkit().screenSize
    val pipW   = 380
    val pipH   = 224
    val margin = 16
    val posX   = screenSize.width  - pipW - margin
    val posY   = screenSize.height - pipH - margin - 48

    val windowState = rememberWindowState(
        size     = DpSize(pipW.dp, pipH.dp),
        position = WindowPosition(posX.dp, posY.dp),
    )

    val localFrame  by ScreenShareManager.localFrame.collectAsState()
    val remoteFrame by ScreenShareManager.receiverFrame.collectAsState()
    val frame = if (isSelfView) localFrame else remoteFrame

    var isFullscreen    by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var currentQuality  by remember { mutableStateOf(ScreenEngine.quality) }

    // Tam ekran / küçük pencere geçişi
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            windowState.placement = WindowPlacement.Fullscreen
        } else {
            windowState.placement = WindowPlacement.Floating
            windowState.size      = DpSize(pipW.dp, pipH.dp)
            windowState.position  = WindowPosition(posX.dp, posY.dp)
        }
    }

    val bgInteraction = remember { MutableInteractionSource() }
    val bgHovered     by bgInteraction.collectIsHoveredAsState()
    // Küçük pencerede her zaman, tam ekranda hover'da göster
    val showControls  = !isFullscreen || bgHovered || frame == null

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        title          = if (isSelfView) "Furcord — Ekran Önizleme" else "Furcord — Yayın İzleniyor",
        alwaysOnTop    = !isFullscreen,
        undecorated    = true,
        resizable      = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111214))
                .hoverable(bgInteraction),
        ) {
            // ── Video karesi ──────────────────────────────────────────────────
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap             = bmp,
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(
                    color       = Color(0xFF5865F2),
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(28.dp).align(Alignment.Center),
                )
            }

            // ── Sürükleme alanı (sadece küçük modda) ─────────────────────────
            if (!isFullscreen) {
                WindowDraggableArea(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(32.dp),
                )
            }

            // ── Kapat butonu — sağ üst, her zaman görünür ────────────────────
            IconButton(
                onClick  = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Kapat",
                    tint               = Color(0xFFED4245),
                    modifier           = Modifier.size(15.dp),
                )
            }

            // ── Sağ alt kontroller (tam ekran butonu + ayarlar) ───────────────
            AnimatedVisibility(
                visible  = showControls,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                enter    = fadeIn(),
                exit     = fadeOut(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    // Kalite ayarları (sadece tam ekranda)
                    if (isFullscreen) {
                        Box {
                            IconButton(
                                onClick  = { showQualityMenu = !showQualityMenu },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Settings,
                                    contentDescription = "Kalite",
                                    tint               = Color(0xFFB5BAC1),
                                    modifier           = Modifier.size(16.dp),
                                )
                            }
                            DropdownMenu(
                                expanded         = showQualityMenu,
                                onDismissRequest = { showQualityMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text    = {
                                        Text(
                                            "720p",
                                            color = if (currentQuality == StreamQuality.Q_720P) Color(0xFF5865F2) else Color.Unspecified,
                                        )
                                    },
                                    onClick = {
                                        ScreenEngine.setQuality(StreamQuality.Q_720P)
                                        currentQuality = StreamQuality.Q_720P
                                        showQualityMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text    = {
                                        Text(
                                            "480p",
                                            color = if (currentQuality == StreamQuality.Q_480P) Color(0xFF5865F2) else Color.Unspecified,
                                        )
                                    },
                                    onClick = {
                                        ScreenEngine.setQuality(StreamQuality.Q_480P)
                                        currentQuality = StreamQuality.Q_480P
                                        showQualityMenu = false
                                    },
                                )
                            }
                        }
                    }

                    // Tam ekran / küçült butonu
                    IconButton(
                        onClick  = { isFullscreen = !isFullscreen; showQualityMenu = false },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector        = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "Küçült" else "Tam ekran",
                            tint               = Color(0xFFB5BAC1),
                            modifier           = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // ── Ses slider — sol alt (sadece izleyici) ───────────────────────
            if (!isSelfView) {
                AnimatedVisibility(
                    visible  = showControls,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    enter    = fadeIn(),
                    exit     = fadeOut(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.width(140.dp),
                    ) {
                        Text("🔊", fontSize = 11.sp)
                        Slider(
                            value          = peerVolume,
                            onValueChange  = onVolumeChange,
                            valueRange     = 0f..2f,
                            modifier       = Modifier.weight(1f).height(24.dp),
                            colors         = SliderDefaults.colors(
                                thumbColor         = Color(0xFF5865F2),
                                activeTrackColor   = Color(0xFF5865F2),
                                inactiveTrackColor = Color(0xFF4E5058),
                            ),
                        )
                    }
                }
            }
        }
    }
}
