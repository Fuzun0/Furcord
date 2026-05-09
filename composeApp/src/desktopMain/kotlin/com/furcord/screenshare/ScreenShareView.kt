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
    val frame by frameFlow.collectAsState()

    val controlsInteraction = remember { MutableInteractionSource() }
    val controlsHovered     by controlsInteraction.collectIsHoveredAsState()

    // Show controls when hovering; always show when waiting for first frame
    val showControls = controlsHovered || frame == null

    Box(
        modifier         = modifier.fillMaxSize().background(Color(0xFF111214)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = frame
        if (bmp != null) {
            Image(
                bitmap             = bmp,
                contentDescription = "Ekran Paylaşımı",
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.Fit,
            )
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
                    // ── Ses kontrolü (sadece remote view) — Discord-stili speaker icon + popup ──
                    if (!isSelfView) {
                        var showVolumeFlyout by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick  = { showVolumeFlyout = !showVolumeFlyout },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = when {
                                        peerVolume == 0f    -> Icons.Default.VolumeOff
                                        peerVolume < 0.5f  -> Icons.Default.VolumeDown
                                        else               -> Icons.Default.VolumeUp
                                    },
                                    contentDescription = "Ses",
                                    tint     = if (showVolumeFlyout) Color(0xFF5865F2) else Color(0xFFB5BAC1),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            // Popup flyout — dikey slider
                            if (showVolumeFlyout) {
                                Popup(
                                    alignment       = Alignment.TopCenter,
                                    offset          = IntOffset(0, -185),
                                    onDismissRequest = { showVolumeFlyout = false },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2B2D31))
                                            .border(1.dp, Color(0xFF3A3C43), RoundedCornerShape(8.dp))
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text     = "${(peerVolume * 100).toInt()}%",
                                                color    = Color(0xFFB5BAC1),
                                                fontSize = 9.sp,
                                            )
                                            Slider(
                                                value         = peerVolume,
                                                onValueChange = onVolumeChange,
                                                valueRange    = 0f..2f,
                                                modifier      = Modifier
                                                    .height(120.dp)
                                                    .graphicsLayer { rotationZ = -90f },
                                                colors = SliderDefaults.colors(
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
                    Spacer(Modifier.weight(1f))

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
        if (bmp != null) {
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
// ScreenShareView (legacy) — kept for backwards compatibility, wraps StreamViewer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ScreenShareView(
    receiver: ScreenReceiver,
    modifier: Modifier = Modifier,
) {
    StreamViewer(
        frameFlow  = receiver.frame,
        onStop     = {},
        modifier   = modifier,
    )
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
