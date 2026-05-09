package com.furcord.screenshare

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Toolkit

@Composable
actual fun PiPStreamWindow(
    isSelfView:     Boolean,
    peerVolume:     Float,
    onVolumeChange: (Float) -> Unit,
    onExpand:       () -> Unit,
    onClose:        () -> Unit,
) {
    // Position to bottom-right corner of the primary screen
    val screenSize: Dimension = Toolkit.getDefaultToolkit().screenSize
    val pipW = 380
    val pipH = 224
    val margin = 16
    val posX = screenSize.width  - pipW - margin
    val posY = screenSize.height - pipH - margin - 48  // 48 = typical taskbar height

    val windowState = rememberWindowState(
        size     = DpSize(pipW.dp, pipH.dp),
        position = WindowPosition(posX.dp, posY.dp),
    )

    val localFrame  by ScreenShareManager.localFrame.collectAsState()
    val remoteFrame by ScreenShareManager.receiverFrame.collectAsState()
    val frame = if (isSelfView) localFrame else remoteFrame

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        title          = if (isSelfView) "Furcord — Ekran Önizleme" else "Furcord — Yayın İzleniyor",
        alwaysOnTop    = true,
        undecorated    = true,
        resizable      = true,
        transparent    = false,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111214))
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // ── Video frame ───────────────────────────────────────────────────
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap             = bmp,
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Fit,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        color       = Color(0xFF5865F2),
                        strokeWidth = 2.dp,
                        modifier    = Modifier.size(28.dp),
                    )
                    Text(
                        text     = if (isSelfView) "Önizleme başlıyor…" else "Yayın bekleniyor…",
                        color    = Color(0xFFB5BAC1),
                        fontSize = 11.sp,
                    )
                }
            }

            // ── Drag & title bar ──────────────────────────────────────────────
            WindowDraggableArea {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color(0xDD0D0E10))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = if (isSelfView) "📡 Ön İzleme" else "📺 Yayın İzleniyor",
                        color      = Color(0xFFDCDDDE),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick  = onExpand,
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Fullscreen,
                                contentDescription = "Tam ekrana geç",
                                tint               = Color(0xFFB5BAC1),
                                modifier           = Modifier.size(14.dp),
                            )
                        }
                        IconButton(
                            onClick  = onClose,
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Close,
                                contentDescription = "Kapat",
                                tint               = Color(0xFFED4245),
                                modifier           = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            // ── Volume control (bottom bar, only for remote) ──────────────────
            if (!isSelfView) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC0D0E10))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔊", fontSize = 12.sp)
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
                    Text(
                        text     = "${(peerVolume * 100).toInt()}%",
                        color    = Color(0xFF8E9297),
                        fontSize = 10.sp,
                        modifier = Modifier.width(30.dp),
                    )
                }
            }
        }
    }
}
