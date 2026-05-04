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
import com.furcord.auth.DmConversation
import com.furcord.auth.FirestoreClient

private val AccentP   = Color(0xFF5865F2)
private val BgPanelP  = Color(0xFF2B2D31)
private val TxtP      = Color(0xFFDCDDDE)
private val TxtSubP   = Color(0xFF96989D)
private val OutlineP  = Color(0xFF3F4147)

/**
 * Sağ altta sabitlenen, açılıp kapanan DM paneli.
 * Açıldığında önceki DM sohbetlerini listeler (yoksa gizlenir).
 * Sadece sunucu ekranında gösterilir.
 */
@Composable
fun FloatingDmPanel(
    currentUser: AuthUser?,
    bottomOffset: Dp = 0.dp,
    onOpenDm: (uid: String, name: String) -> Unit,
) {
    if (currentUser == null) return

    var expanded         by remember { mutableStateOf(false) }
    var showFriendDialog by remember { mutableStateOf(false) }
    var conversations    by remember { mutableStateOf<List<DmConversation>>(emptyList()) }
    var loading          by remember { mutableStateOf(false) }

    // Panel açıldığında sohbetleri yükle
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        loading = true
        runCatching {
            conversations = FirestoreClient.listDmConversations(currentUser.uid, currentUser.idToken)
        }
        loading = false
    }

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
                            // Yeni DM butonu
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(AccentP)
                                    .clickable { showFriendDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                            Icon(Icons.Default.Close, null,
                                tint     = TxtSubP,
                                modifier = Modifier.size(16.dp).clickable { expanded = false })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = OutlineP)
                    Spacer(Modifier.height(6.dp))

                    if (loading) {
                        Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = AccentP, strokeWidth = 2.dp)
                        }
                    } else if (conversations.isEmpty()) {
                        Text(
                            "Henüz DM sohbeti yok.\n+ ile başlatın.",
                            color = TxtSubP, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(conversations, key = { it.dmId }) { conv ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = false
                                            onOpenDm(conv.otherUid, conv.otherName)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    UserAvatar(displayName = conv.otherName, photoURL = "", size = 28)
                                    Column(Modifier.weight(1f)) {
                                        Text(conv.otherName, color = TxtP, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(conv.lastText, color = TxtSubP, fontSize = 11.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── FAB butonu ────────────────────────────────────────────────
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AccentP)
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (expanded) "Kapat" else "DM",
                    tint     = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
