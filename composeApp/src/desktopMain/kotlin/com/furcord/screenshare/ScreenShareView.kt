package com.furcord.screenshare

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// Viewer — shows the incoming screen share stream
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Displays the live screen-share stream from a [ScreenReceiver].
 *
 * Usage:
 * ```kotlin
 * val receiver = remember { ScreenReceiver(socket) }
 * DisposableEffect(Unit) {
 *     receiver.start()
 *     onDispose { receiver.stop() }
 * }
 * ScreenShareView(receiver)
 * ```
 */
@Composable
fun ScreenShareView(
    receiver: ScreenReceiver,
    modifier: Modifier = Modifier,
) {
    val frame by receiver.frame.collectAsState()
    val fps   by receiver.decodedFps.collectAsState()

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

            // FPS overlay — top-right corner
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text       = "${"%.1f".format(fps)} FPS",
                    color      = Color(0xFFB5BAC1),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            // Waiting for the first frame
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
                    "Ekran paylaşımı bekleniyor…",
                    color    = Color(0xFFB5BAC1),
                    fontSize = 13.sp,
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
 *
 * Usage:
 * ```kotlin
 * var broadcaster by remember { mutableStateOf<ScreenBroadcaster?>(null) }
 *
 * ScreenShareBar(
 *     isSharing  = broadcaster != null,
 *     onStart    = {
 *         broadcaster = ScreenBroadcaster(socket) { peers }
 *         broadcaster!!.start()
 *     },
 *     onStop     = { broadcaster?.stop(); broadcaster = null },
 * )
 * ```
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
                colors  = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
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
                colors  = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
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
