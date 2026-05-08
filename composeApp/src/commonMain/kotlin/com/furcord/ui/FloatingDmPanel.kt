package com.furcord.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.DmRepository

private val AccentP   = Color(0xFF5865F2)
private val BgPanelP  = Color(0xFF2B2D31)
private val TxtP      = Color(0xFFDCDDDE)
private val TxtSubP   = Color(0xFF96989D)
private val OutlineP  = Color(0xFF3F4147)
private val UnreadRed = Color(0xFFED4245)

/**
 * Sağ altta sabitlenen, açılıp kapanan DM paneli.
 *
 * DM sohbetlerini doğrudan [DmRepository.threads] StateFlow'dan okur;
 * kendi polling/loading mantığı yoktur. FAB üzerine [BadgedBox] ile
 * okunmamış mesaj sayısını gösterir.
 *
 * @param myUid    Oturum açmış kullanıcının uid'si (okunmamış tespiti için)
 */
@Composable
fun FloatingDmPanel(
    currentUser: AuthUser?,
    myUid: String = currentUser?.uid ?: "",
    bottomOffset: Dp = 0.dp,
    onOpenDm: (uid: String, name: String) -> Unit,
) {
    if (currentUser == null) return

    var expanded         by remember { mutableStateOf(false) }
    var showFriendDialog by remember { mutableStateOf(false) }

    // DmRepository'den reaktif sohbet listesi — kendi polling'i yok
    val conversations by DmRepository.threads.collectAsState()
    val unreadCount   by DmRepository.unreadCount.collectAsState()

    if (showFriendDialog) {
        FriendAddDialog(
            currentUser = currentUser,
            onStartDm   = { uid, name ->
                showFriendDialog = false
                onOpenDm(uid, name)
            },
            onDismiss   = { showFriendDialog = false },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomOffset),
            horizontalAlignment = Alignment.End,
        ) {
            // ── Açılır panel ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit    = slideOutVertically(targetOffsetY  = { it }) + fadeOut(),
            ) {
                Column(
                    Modifier
                        .width(260.dp)
                        .background(BgPanelP, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    // Başlık satırı
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text("Mesajlar", color = TxtP, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Yeni DM başlat butonu
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(AccentP)
                                    .clickable { showFriendDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                            Icon(
                                Icons.Default.Close, null,
                                tint     = TxtSubP,
                                modifier = Modifier.size(16.dp).clickable { expanded = false },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = OutlineP)
                    Spacer(Modifier.height(6.dp))

                    if (conversations.isEmpty()) {
                        Text(
                            "Henüz DM sohbeti yok.\n+ ile başlatın.",
                            color = TxtSubP, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(conversations, key = { it.dmId }) { conv ->
                                val isUnread = conv.lastSenderUid.isNotEmpty() &&
                                    conv.lastSenderUid != myUid
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isUnread) AccentP.copy(alpha = 0.10f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            expanded = false
                                            onOpenDm(conv.otherUid, conv.otherName)
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // Avatar + okunmamış noktası
                                    Box {
                                        UserAvatar(displayName = conv.otherName, photoURL = "", size = 28)
                                        if (isUnread) {
                                            Box(
                                                Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(UnreadRed)
                                                    .align(Alignment.TopEnd)
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            conv.otherName,
                                            color      = if (isUnread) TxtP else TxtSubP,
                                            fontSize   = 13.sp,
                                            fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines   = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            conv.lastText,
                                            color    = TxtSubP, fontSize = 11.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // Son mesaj zamanı — okunmamışsa kırmızı
                                    if (isUnread) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(UnreadRed)
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text("YENİ", color = Color.White, fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── BadgedBox FAB ─────────────────────────────────────────────
            BadgedBox(
                badge = {
                    if (unreadCount > 0) {
                        Badge(containerColor = UnreadRed) {
                            Text(
                                if (unreadCount > 9) "9+" else "$unreadCount",
                                color    = Color.White,
                                fontSize = 9.sp,
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
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = if (expanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (expanded) "Kapat" else "DM",
                        tint               = Color.White,
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}


