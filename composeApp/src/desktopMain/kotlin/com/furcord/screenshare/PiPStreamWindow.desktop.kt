package com.furcord.screenshare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay

@Composable
actual fun PiPStreamWindow(
    isSelfView:        Boolean,
    startInFullscreen: Boolean,
    peerVolume:        Float,
    onVolumeChange:    (Float) -> Unit,
    onClose:           () -> Unit,
) {
    val screenSize: Dimension = Toolkit.getDefaultToolkit().screenSize
    val pipW   = 380
    val pipH   = 224
    val margin = 16
    val posX   = screenSize.width  - pipW - margin
    val posY   = screenSize.height - pipH - margin - 48

    val windowState = rememberWindowState(
        placement = if (startInFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        size      = if (startInFullscreen) DpSize(screenSize.width.dp, screenSize.height.dp) else DpSize(pipW.dp, pipH.dp),
        position  = if (startInFullscreen) WindowPosition(0.dp, 0.dp) else WindowPosition(posX.dp, posY.dp),
    )

    val localFrame  by ScreenShareManager.localFrame.collectAsState()
    val remoteFrame by ScreenShareManager.receiverFrame.collectAsState()
    val frame = if (isSelfView) localFrame else remoteFrame

    val isFullscreen    by remember { derivedStateOf { windowState.placement == WindowPlacement.Fullscreen } }
    var showQualityMenu by remember { mutableStateOf(false) }
    var currentQuality  by remember { mutableStateOf(ScreenEngine.quality) }

    // Kontrol gÃ¶rÃ¼nÃ¼rlÃ¼ÄŸÃ¼: fare hareket edince 5 saniye gÃ¶ster
    var lastActivityMs   by remember { mutableStateOf(System.currentTimeMillis()) }
    var showControlsState by remember { mutableStateOf(true) }

    LaunchedEffect(lastActivityMs) {
        showControlsState = true
        if (isFullscreen) {
            delay(5_000)
            showControlsState = false
        }
    }

    // KÃ¼Ã§Ã¼k pencerede daima gÃ¶ster, tam ekranda sadece son aktiviteden 5sn iÃ§inde gÃ¶ster
    val showControls = !isFullscreen || showControlsState || frame == null

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        title          = if (isSelfView) "Furcord â€” Ekran Ã–nizleme" else "Furcord â€” YayÄ±n Ä°zleniyor",
        alwaysOnTop    = !isFullscreen,
        undecorated    = true,
        resizable      = !isFullscreen,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111214))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Main)
                            lastActivityMs = System.currentTimeMillis()
                        }
                    }
                },
        ) {
            // â”€â”€ Video karesi â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

            // â”€â”€ SÃ¼rÃ¼kleme alanÄ± (sadece kÃ¼Ã§Ã¼k modda) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (!isFullscreen) {
                WindowDraggableArea(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(40.dp),
                )
            }

            // â”€â”€ Kapat butonu â€” saÄŸ Ã¼st â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            AnimatedVisibility(
                visible  = showControls,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                enter    = fadeIn(),
                exit     = fadeOut(),
            ) {
                IconButton(
                    onClick  = onClose,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint               = Color(0xFFED4245),
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }

            // â”€â”€ SaÄŸ alt kontroller (tam ekran butonu + ayarlar) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            AnimatedVisibility(
                visible  = showControls,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                enter    = fadeIn(),
                exit     = fadeOut(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    // Kalite ayarlarÄ± (sadece yayÄ±ncÄ± kendi Ã¶nizlemesindeyken)
                    if (isSelfView) {
                        Box {
                            IconButton(
                                onClick  = { showQualityMenu = !showQualityMenu },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Settings,
                                    contentDescription = "Kalite",
                                    tint               = Color(0xFFB5BAC1),
                                    modifier           = Modifier.size(24.dp),
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
                        onClick  = {
                            if (windowState.placement == WindowPlacement.Fullscreen) {
                                windowState.placement = WindowPlacement.Floating
                                windowState.size      = DpSize(pipW.dp, pipH.dp)
                                windowState.position  = WindowPosition(posX.dp, posY.dp)
                            } else {
                                windowState.placement = WindowPlacement.Fullscreen
                            }
                            showQualityMenu = false
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector        = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "KÃ¼Ã§Ã¼lt" else "Tam ekran",
                            tint               = Color(0xFFB5BAC1),
                            modifier           = Modifier.size(24.dp),
                        )
                    }
                }
            }

            // â”€â”€ Ses slider â€” sol alt (sadece izleyici) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (!isSelfView) {
                AnimatedVisibility(
                    visible  = showControls,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    enter    = fadeIn(),
                    exit     = fadeOut(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.width(160.dp),
                    ) {
                        Text("\uD83D\uDD0A", fontSize = 14.sp)
                        Slider(
                            value          = peerVolume,
                            onValueChange  = onVolumeChange,
                            valueRange     = 0f..2f,
                            modifier       = Modifier.weight(1f).height(32.dp),
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
