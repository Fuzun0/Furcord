package com.furcord.screenshare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.flow.StateFlow
// ─────────────────────────────────────────────────────────────────────────────
// StreamViewer — universal video player (remote receiver OR local self-view)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-area video player for screen sharing.
 *
 * Works for both:
 *  - Remote viewing: feed [ScreenShareManager.receiverFrame]
 *  - Self-view:      feed [ScreenShareManager.localFrame]
 *
 * @param frameFlow     Source of decoded [ImageBitmap] frames.
 * @param peerVolume    Current volume level for this peer's audio (0f–2f).
 * @param onVolumeChange Callback when the volume slider changes.
 * @param onStop        Called when the user clicks "İzlemeyi Durdur".
 * @param isSelfView    When true, hides the volume slider (no remote audio).
 */
@Composable
fun StreamViewer(
    frameFlow:      StateFlow<androidx.compose.ui.graphics.ImageBitmap?>,
    peerVolume:     Float    = 1f,
    onVolumeChange: (Float) -> Unit = {},
    onStop:         () -> Unit,
    isSelfView:     Boolean  = false,
    isPiPMode:      Boolean  = false,
    onTogglePiP:    (() -> Unit)? = null,
    modifier:       Modifier = Modifier,
) {
    // Kare yalnızca FrameSurface içinde (çizim aşamasında) okunur; burada sadece "kare var mı" izlenir
    val frameState = frameFlow.collectAsState()
    val hasFrame by remember { derivedStateOf { frameState.value != null } }

    // When this composable leaves the composition (e.g. activeStreamUid → null),
    // stop the receiver so the native FFmpeg thread exits cleanly. This prevents
    // the JVM crash that occurs when the coroutine scope is cancelled while
    // FFmpeg is blocked inside a native grabImage() call.
    DisposableEffect(frameFlow) {
        onDispose {
            if (!isSelfView) {
                ScreenShareManager.stopReceiver()
            }
        }
    }

    val controlsInteraction = remember { MutableInteractionSource() }
    val controlsHovered     by controlsInteraction.collectIsHoveredAsState()

    // Show controls when hovering; always show when waiting for first frame
    val showControls = controlsHovered || !hasFrame

    Box(
        modifier         = modifier.fillMaxSize().background(Color(0xFF111214)),
        contentAlignment = Alignment.Center,
    ) {
        if (hasFrame) {
            FrameSurface(frameFlow, Modifier.fillMaxSize())
        } else {
            // Waiting for first frame
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(
                    color       = Color(0xFF5865F2),
                    strokeWidth = 3.dp,
                    modifier    = Modifier.size(36.dp),
                )
                Text(
                    text     = if (isSelfView) "Ekran önizlemesi başlatılıyor…" else "Ekran paylaşımı bekleniyor…",
                    color    = Color(0xFFB5BAC1),
                    fontSize = 13.sp,
                )
            }
        }

        // ── Bottom control bar — fade in on hover ─────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hoverable(controlsInteraction),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = showControls,
                enter   = fadeIn(),
                exit    = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC111214))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // ── Ses kontrolü (sadece izleyici): hoparlör simgesi (sessize al) + yatay kaydırıcı ──
                    // Eskiden açılır pencere (Popup) kullanılıyordu; imleç pencereye geçince çubuk
                    // gizlenip kaydırıcıyı da kapatıyordu. Artık kaydırıcı çubuğun içinde, hep görünür.
                    run { // yayıncı önizlemesinde de görünür
                        var lastNonZero by remember { mutableStateOf(1f) }
                        if (peerVolume > 0f) lastNonZero = peerVolume
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {                            IconButton(
                                onClick  = { onVolumeChange(if (peerVolume == 0f) lastNonZero else 0f) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = when {
                                        peerVolume == 0f   -> Icons.Default.VolumeOff
                                        peerVolume < 0.5f  -> Icons.Default.VolumeDown
                                        else               -> Icons.Default.VolumeUp
                                    },
                                    contentDescription = "Sessize al / aç",
                                    tint     = Color(0xFFD4D6DA),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Slider(
                                value         = peerVolume,
                                onValueChange = onVolumeChange,
                                valueRange    = 0f..2f,
                                modifier      = Modifier.width(140.dp).height(32.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor         = Color(0xFF5865F2),
                                    activeTrackColor   = Color(0xFF5865F2),
                                    inactiveTrackColor = Color(0xFF4E5058),
                                ),
                            )
                            Text("${(peerVolume * 100).toInt()}%", color = Color(0xFFB5BAC1), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // ── Yayın kalitesi (yayıncı, yayın sırasında değiştirebilir) ──
                    if (isSelfView) {
                        val currentQ by ScreenShareManager.quality.collectAsState()
                        val srcTitle by ScreenShareManager.sourceTitle.collectAsState()
                        var qMenu by remember { mutableStateOf(false) }
                        if (srcTitle.isNotEmpty()) {
                            Text(
                                text     = srcTitle,
                                color    = Color(0xFF8E9297),
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        }
                        Box {
                            TextButton(onClick = { qMenu = true }) {
                                Text(
                                    text       = "Kalite: ${currentQ.label}",
                                    color      = Color(0xFFD4D6DA),
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            DropdownMenu(expanded = qMenu, onDismissRequest = { qMenu = false }) {
                                StreamQuality.entries.forEach { q ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${q.label}  ·  ${q.desc}",
                                                color = if (q == currentQ) Color(0xFF7C5CF6) else Color.Unspecified,
                                            )
                                        },
                                        onClick = { ScreenShareManager.setQuality(q); qMenu = false },
                                    )
                                }
                            }
                        }
                    }

                    // PiP toggle button (küçült / genişlet)
                    if (onTogglePiP != null) {
                        IconButton(
                            onClick  = onTogglePiP,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Minimize,
                                contentDescription = "Küçült (PiP)",
                                tint               = Color(0xFFB5BAC1),
                                modifier           = Modifier.size(16.dp),
                            )
                        }
                    }

                    // Stop-watching button
                    FilledTonalButton(
                        onClick = onStop,
                        colors  = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF3A2022),
                            contentColor   = Color(0xFFED4245),
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text       = if (isSelfView) "Önizlemeyi Kapat" else "İzlemeyi Durdur",
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // FPS overlay — top-right corner (only when frame is present)
        if (hasFrame) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text       = if (isSelfView) "CANLI" else "İZLİYORSUN",
                    color      = if (isSelfView) Color(0xFFED4245) else Color(0xFF5865F2),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Broadcaster control bar — shown to the person sharing their screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A minimal control strip shown to the broadcaster.
 * Place this above or below the chat area when screen sharing is active.
 */
@Composable
fun ScreenShareBar(
    isSharing: Boolean,
    onStart:   () -> Unit,
    onStop:    () -> Unit,
    modifier:  Modifier = Modifier,
) {
    Row(
        modifier            = modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2D31))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isSharing) {
            // Red pulsing dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFFED4245)),
            )
            Text(
                "Ekranı paylaşıyorsun",
                color      = Color(0xFFF2F3F5),
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onStop,
                colors  = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF3A2022),
                    contentColor   = Color(0xFFED4245),
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Paylaşımı Durdur", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                "Ekran Paylaşımı",
                color    = Color(0xFF8E9297),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onStart,
                colors  = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF5865F2),
                    contentColor   = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Ekranı Paylaş", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
