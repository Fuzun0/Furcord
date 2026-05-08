package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.DmRepository

private val AccentP   = Color(0xFF5865F2)
private val UnreadRed = Color(0xFFED4245)

/**
 * Sağ altta sabitlenen DM FAB.
 *
 * Tıklanınca [onDmWindowOpen] tetiklenir — split DM penceresi açılır.
 * Okunmamış mesaj sayısını BadgedBox ile gösterir.
 */
@Composable
fun FloatingDmPanel(
    currentUser: AuthUser?,
    myUid: String = currentUser?.uid ?: "",
    bottomOffset: Dp = 0.dp,
    onDmWindowOpen: () -> Unit,
) {
    if (currentUser == null) return

    val unreadCount by DmRepository.unreadCount.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.padding(
                start  = 16.dp,
                end    = 16.dp,
                top    = 16.dp,
                bottom = 16.dp + bottomOffset,
            ),
            horizontalAlignment = Alignment.End,
        ) {
            // ── BadgedBox FAB ─────────────────────────────────────────────
            BadgedBox(
                badge = {
                    if (unreadCount > 0) {
                        Badge(containerColor = UnreadRed) {
                            Text(
                                if (unreadCount > 9) "9+" else "$unreadCount",
                                color      = Color.White,
                                fontSize   = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AccentP)
                        .clickable(onClick = onDmWindowOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Default.Add,
                        contentDescription = "DM Mesajlar",
                        tint               = Color.White,
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

