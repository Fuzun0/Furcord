package com.furcord.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import com.furcord.ui.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.furcord.auth.AuthUser
import com.furcord.auth.FirestoreClient
import com.furcord.auth.FriendRequest
import com.furcord.auth.JoinRequestNotif
import com.furcord.auth.ServerInviteNotif
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val NB_BG      = AppColors.BgSidebar
private val NB_SURFACE = AppColors.BgDark
private val NB_OUTLINE = Color(0xFF3F4147)
private val NB_ACCENT  = AppColors.Accent
private val NB_GREEN   = AppColors.Online
private val NB_RED     = AppColors.Danger
private val NB_TEXT    = AppColors.TextSecondary
private val NB_SUB     = Color(0xFF96989D)

/**
 * Bildirim çanı — tüm sayfalardaki başlık çubuğuna eklenir.
 * Arkadaşlık istekleri ve sunucu davetlerini gösterir.
 *
 * @param currentUser  Oturum açmış kullanıcı
 * @param onJoinServer Sunucu davetini kabul edince sunucuya geç
 */
@Composable
fun NotificationBell(
    currentUser: AuthUser,
    hasUpdate: Boolean = false,
    onJoinServer: ((serverId: String, serverName: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    val friendRequests by com.furcord.auth.NotificationPoller.friendRequests.collectAsState()
    val serverInvites  by com.furcord.auth.NotificationPoller.serverInvites.collectAsState()
    val joinRequests   by com.furcord.auth.NotificationPoller.joinRequests.collectAsState()
    var showPanel       by remember { mutableStateOf(false) }
    var expandFriends   by remember { mutableStateOf(true) }
    var expandInvites   by remember { mutableStateOf(true) }

    // Expanded state per notification item
    var expandedFriendUid   by remember { mutableStateOf<String?>(null) }
    var expandedInviteId    by remember { mutableStateOf<String?>(null) }

    val totalCount = friendRequests.size + serverInvites.size + joinRequests.size + (if (hasUpdate) 1 else 0)

    Box {
        // ── Çan butonu ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (showPanel) NB_ACCENT else NB_SURFACE)
                .clickable { showPanel = !showPanel },
            contentAlignment = Alignment.Center,
        ) {
            Text("🔔", fontSize = 15.sp)
            // Rozet
            if (totalCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(NB_RED),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (totalCount > 99) "99+" else "$totalCount",
                        fontSize = 9.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── Bildirim paneli ───────────────────────────────────────────────────
        if (showPanel) {
            Popup(
                alignment  = Alignment.TopEnd,
                offset     = androidx.compose.ui.unit.IntOffset(0, 44),
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
                onDismissRequest = { showPanel = false },
            ) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .background(NB_BG, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    // Başlık
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bildirimler", color = NB_TEXT, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.weight(1f))
                        if (totalCount > 0) {
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(NB_RED)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text("$totalCount", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    HorizontalDivider(color = NB_OUTLINE)
                    Spacer(Modifier.height(6.dp))

                    if (totalCount == 0) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🔕", fontSize = 28.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("Bildirim yok", color = NB_SUB, fontSize = 13.sp)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 400.dp)) {

                            // ── Arkadaşlık İstekleri ──────────────────────
                            if (friendRequests.isNotEmpty()) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { expandFriends = !expandFriends }
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (expandFriends) "▼" else "▶",
                                            color = NB_SUB, fontSize = 10.sp,
                                            modifier = Modifier.padding(end = 6.dp),
                                        )
                                        Text("Arkadaşlık İstekleri", color = NB_TEXT,
                                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f))
                                        Box(
                                            Modifier.clip(CircleShape).background(NB_ACCENT)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text("${friendRequests.size}", color = Color.White, fontSize = 11.sp)
                                        }
                                    }
                                }
                                if (expandFriends) {
                                    items(friendRequests, key = { it.fromUid }) { req ->
                                        NotifFriendRow(
                                            req        = req,
                                            isExpanded = expandedFriendUid == req.fromUid,
                                            onToggle   = {
                                                expandedFriendUid = if (expandedFriendUid == req.fromUid) null else req.fromUid
                                            },
                                            onAccept = {
                                                scope.launch {
                                                    FirestoreClient.acceptFriendRequest(currentUser.uid, req.fromUid, currentUser.idToken)
                                                    com.furcord.auth.NotificationPoller.dropFriendRequest(req.fromUid)
                                                    if (expandedFriendUid == req.fromUid) expandedFriendUid = null
                                                }
                                            },
                                            onReject = {
                                                scope.launch {
                                                    FirestoreClient.rejectFriendRequest(currentUser.uid, req.fromUid, currentUser.idToken)
                                                    com.furcord.auth.NotificationPoller.dropFriendRequest(req.fromUid)
                                                    if (expandedFriendUid == req.fromUid) expandedFriendUid = null
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            // ── Sunucuya Katılma İstekleri (sunucu sahibi) ─
                            if (joinRequests.isNotEmpty()) {
                                item {
                                    if (friendRequests.isNotEmpty()) {
                                        HorizontalDivider(color = NB_OUTLINE, modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Katılma İstekleri", color = NB_TEXT,
                                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f))
                                        Box(
                                            Modifier.clip(CircleShape).background(NB_ACCENT)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text("${joinRequests.size}", color = Color.White, fontSize = 11.sp)
                                        }
                                    }
                                }
                                items(joinRequests, key = { "${it.serverId}_${it.fromUid}" }) { req ->
                                    NotifJoinRequestRow(
                                        req = req,
                                        onAccept = {
                                            scope.launch {
                                                runCatching {
                                                    FirestoreClient.approveJoinRequest(
                                                        req, currentUser.uid,
                                                        currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email } },
                                                        currentUser.idToken,
                                                    )
                                                }.onSuccess { com.furcord.auth.NotificationPoller.dropJoinRequest(req) }
                                            }
                                        },
                                        onReject = {
                                            scope.launch {
                                                FirestoreClient.removeJoinRequest(currentUser.uid, req.serverId, req.fromUid, currentUser.idToken)
                                                com.furcord.auth.NotificationPoller.dropJoinRequest(req)
                                            }
                                        },
                                    )
                                }
                            }

                            // ── Sunucu Davetleri ──────────────────────────
                            if (serverInvites.isNotEmpty()) {
                                item {
                                    if (friendRequests.isNotEmpty()) {
                                        HorizontalDivider(color = NB_OUTLINE, modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { expandInvites = !expandInvites }
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (expandInvites) "▼" else "▶",
                                            color = NB_SUB, fontSize = 10.sp,
                                            modifier = Modifier.padding(end = 6.dp),
                                        )
                                        Text("Sunucu Davetleri", color = NB_TEXT,
                                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f))
                                        Box(
                                            Modifier.clip(CircleShape).background(NB_GREEN)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text("${serverInvites.size}", color = Color.White, fontSize = 11.sp)
                                        }
                                    }
                                }
                                if (expandInvites) {
                                    items(serverInvites, key = { it.serverId }) { inv ->
                                        NotifServerRow(
                                            inv        = inv,
                                            isExpanded = expandedInviteId == inv.serverId,
                                            onToggle   = {
                                                expandedInviteId = if (expandedInviteId == inv.serverId) null else inv.serverId
                                            },
                                            onAccept = {
                                                scope.launch {
                                                    FirestoreClient.removeServerInviteNotif(currentUser.uid, inv.serverId, currentUser.idToken)
                                                    com.furcord.auth.NotificationPoller.dropServerInvite(inv.serverId)
                                                    if (expandedInviteId == inv.serverId) expandedInviteId = null
                                                    showPanel = false
                                                    onJoinServer?.invoke(inv.serverId, inv.serverName)
                                                }
                                            },
                                            onReject = {
                                                scope.launch {
                                                    FirestoreClient.removeServerInviteNotif(currentUser.uid, inv.serverId, currentUser.idToken)
                                                    com.furcord.auth.NotificationPoller.dropServerInvite(inv.serverId)
                                                    if (expandedInviteId == inv.serverId) expandedInviteId = null
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Arkadaşlık isteği satırı ──────────────────────────────────────────────────
@Composable
private fun NotifFriendRow(
    req: FriendRequest,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.BgDark)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(displayName = req.fromName, photoURL = "", size = 28)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(req.fromName, color = NB_TEXT, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(req.furcordId.ifBlank { "—" }, color = NB_SUB, fontSize = 10.sp)
            }
            Text(if (isExpanded) "▲" else "▼", color = NB_SUB, fontSize = 10.sp)
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NB_GREEN),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Kabul Et", fontSize = 12.sp, color = Color.White) }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NB_RED),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NB_RED),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Reddet", fontSize = 12.sp, color = NB_RED) }
            }
        }
    }
}

// ── Katılma isteği satırı (sunucu sahibine) ───────────────────────────────────
@Composable
private fun NotifJoinRequestRow(
    req: JoinRequestNotif,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.BgDark)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(displayName = req.fromName, photoURL = "", size = 28)
            Spacer(Modifier.width(8.dp))
            Text(
                "${req.fromName} kullanıcısı \"${req.serverName}\" sunucuna giriş isteği gönderdi",
                color = NB_TEXT, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NB_GREEN),
                contentPadding = PaddingValues(0.dp),
            ) { Text("Kabul Et", fontSize = 12.sp, color = Color.White) }
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NB_RED),
                border = androidx.compose.foundation.BorderStroke(1.dp, NB_RED),
                contentPadding = PaddingValues(0.dp),
            ) { Text("Reddet", fontSize = 12.sp, color = NB_RED) }
        }
    }
}

// ── Sunucu daveti satırı ──────────────────────────────────────────────────────
@Composable
private fun NotifServerRow(
    inv: ServerInviteNotif,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.BgDark)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(NB_ACCENT),
                contentAlignment = Alignment.Center,
            ) {
                Text(inv.serverName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(inv.serverName, color = NB_TEXT, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (inv.approved) "${inv.fromName} isteğini kabul etti" else "${inv.fromName} seni davet etti", color = NB_SUB, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (isExpanded) "▲" else "▼", color = NB_SUB, fontSize = 10.sp)
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NB_GREEN),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Katıl", fontSize = 12.sp, color = Color.White) }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NB_RED),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NB_RED),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Reddet", fontSize = 12.sp, color = NB_RED) }
            }
        }
    }
}
