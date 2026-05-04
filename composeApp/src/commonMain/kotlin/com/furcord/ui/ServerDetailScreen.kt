package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.ActiveUser
import com.furcord.auth.AuthUser
import com.furcord.auth.ChatMessage
import com.furcord.auth.FirestoreClient
import com.furcord.auth.MessageStore
import com.furcord.auth.VoicePeer
import com.furcord.auth.VoiceChannel
import com.furcord.voice.VoiceEngine
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Invite dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun InviteDialog(
    serverId: String,
    idToken: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var inviteCode  by remember { mutableStateOf<String?>(null) }
    var loading     by remember { mutableStateOf(true) }
    var error       by remember { mutableStateOf("") }
    var copied      by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        try {
            inviteCode = FirestoreClient.createInvite(serverId, idToken)
        } catch (e: Exception) {
            error = "Davet oluşturulamadı: ${e.message}"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF2B2D31),
        title = {
            Text("Sunucuya Davet Et", fontWeight = FontWeight.Bold, color = Color(0xFFF2F3F5))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Aşağıdaki kodu paylaşarak arkadaşlarını sunucuya davet edebilirsin.",
                    fontSize = 13.sp,
                    color = Color(0xFFB5BAC1),
                )
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                    error.isNotEmpty() -> Text(error, color = Color(0xFFF23F43), fontSize = 13.sp)
                    inviteCode != null -> {
                        val code = inviteCode!!
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1F22))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = code,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5865F2),
                                letterSpacing = 4.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    try {
                                        val sel = StringSelection(code)
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                        copied = true
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (copied) Color(0xFF23A55A) else Color(0xFF5865F2)),
                            ) {
                                Text(if (copied) "✓" else "📋", fontSize = 14.sp, color = Color.White)
                            }
                        }
                        if (copied) {
                            Text("Kod panoya kopyalandı!", fontSize = 12.sp, color = Color(0xFF23A55A))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat", color = Color(0xFF5865F2))
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Small icon button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PanelIconBtn(
    label: String,
    tint: Color = Color(0xFFB5BAC1),
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Text(label, fontSize = 14.sp, color = tint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unified user context menu (right-click on any user in grid or sidebar)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun UserContextMenu(
    user: ActiveUser,
    isOwner: Boolean,
    volume: Float,
    showVolSlider: Boolean,
    onToggleVol: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onAddFriend: () -> Unit,
    onKick: (() -> Unit)?,
) {
    DropdownMenu(
        expanded         = true,
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111214),
    ) {
        DropdownMenuItem(
            text    = { Text("💬 Mesaj Gönder", color = Color(0xFFDCDDDE), fontSize = 13.sp) },
            onClick = onMessage,
        )
        HorizontalDivider(color = Color(0xFF2B2D31))
        DropdownMenuItem(
            text    = { Text("➕ Arkadaş Ekle", color = Color(0xFFDCDDDE), fontSize = 13.sp) },
            onClick = onAddFriend,
        )
        HorizontalDivider(color = Color(0xFF2B2D31))
        // Ses seviyesi
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "🔊 Ses: ${(volume * 100).toInt()}%",
                        color = Color(0xFFDCDDDE), fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (showVolSlider) "▲" else "▼", color = Color(0xFF8E9297), fontSize = 11.sp)
                }
            },
            onClick = onToggleVol,
        )
        if (showVolSlider) {
            DropdownMenuItem(
                text = {
                    Column(modifier = Modifier.width(200.dp)) {
                        Slider(
                            value          = volume,
                            onValueChange  = onVolumeChange,
                            valueRange     = 0f..2f,
                            modifier       = Modifier.fillMaxWidth().height(28.dp),
                            colors         = SliderDefaults.colors(
                                thumbColor        = Color(0xFF5865F2),
                                activeTrackColor  = Color(0xFF5865F2),
                                inactiveTrackColor= Color(0xFF4E5058),
                            ),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0%",   color = Color(0xFF8E9297), fontSize = 10.sp)
                            Text("100%", color = Color(0xFF8E9297), fontSize = 10.sp)
                            Text("200%", color = Color(0xFF8E9297), fontSize = 10.sp)
                        }
                    }
                },
                onClick = {},
            )
        }
        if (onKick != null) {
            HorizontalDivider(color = Color(0xFF2B2D31))
            DropdownMenuItem(
                text    = { Text("🔨 Sunucudan At", color = Color(0xFFED4245), fontSize = 13.sp) },
                onClick = onKick,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// User avatar (initial letter)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun UserInitialAvatar(username: String, size: Int = 20) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFF5865F2)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            fontSize   = (size * 0.4).sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Channel row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ChannelRow(
    channel: VoiceChannel,
    isConnected: Boolean,
    isSelected: Boolean,
    activeUsers: List<ActiveUser>,
    currentUid: String,
    onClick: () -> Unit,
    onUserRightClick: ((ActiveUser) -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSelected && !isConnected) Color(0xFF404249) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isConnected) "🔊" else "🔈",
                fontSize = 16.sp,
                color = if (isConnected) Color(0xFF23A55A) else Color(0xFF8E9297),
            )
            Text(
                text = channel.name,
                fontSize = 15.sp,
                color = when {
                    isConnected -> Color(0xFF23A55A)
                    isSelected  -> Color(0xFFF2F3F5)
                    else        -> Color(0xFF8E9297)
                },
                fontWeight = if (isConnected || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (activeUsers.isNotEmpty()) {
                Text(
                    text = "${activeUsers.size}",
                    fontSize = 11.sp,
                    color = Color(0xFF6D6F78),
                )
            }
        }

        // Active users beneath the channel row
        if (activeUsers.isNotEmpty()) {
            activeUsers.forEach { u ->
                val isSelf = u.uid == currentUid
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 30.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                            .then(
                                if (!isSelf && onUserRightClick != null) {
                                    Modifier.pointerInput(u.uid) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Press &&
                                                    event.buttons.isSecondaryPressed
                                                ) {
                                                    onUserRightClick(u)
                                                }
                                            }
                                        }
                                    }
                                } else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UserAvatar(
                            displayName = u.username,
                            photoURL    = u.photoURL,
                            size        = 20,
                        )
                        Text(
                            text = if (isSelf) "${u.username} (sen)" else u.username,
                            fontSize = 12.sp,
                            color = if (isSelf) Color(0xFF23A55A) else Color(0xFFB5BAC1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Voice connected bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun VoiceConnectedBar(channelName: String, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF232428))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF23A55A)),
                )
                Text(
                    text = "Ses Bağlantısı Kuruldu",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF23A55A),
                )
            }
            IconButton(onClick = onDisconnect, modifier = Modifier.size(26.dp)) {
                Text("📵", fontSize = 13.sp)
            }
        }
        Text(
            text = channelName,
            fontSize = 12.sp,
            color = Color(0xFF8E9297),
            modifier = Modifier.padding(start = 14.dp, top = 2.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat message row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MessageRow(msg: ChatMessage, isSelf: Boolean) {
    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
        ) {
            UserAvatar(
                displayName = msg.username,
                photoURL    = msg.photoURL,
                size        = 36,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = msg.username,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelf) Color(0xFF23A55A) else Color(0xFFF2F3F5),
                )
                Text(text = timeStr, fontSize = 11.sp, color = Color(0xFF6D6F78))
            }
            Text(
                text = msg.text,
                fontSize = 14.sp,
                color = Color(0xFFDCDDE1),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ServerDetailScreen(
    serverId: String,
    serverName: String,
    currentUser: AuthUser,
    hasUpdate: Boolean = false,
    onLeaveServer: () -> Unit,
    onUserUpdated: (newDisplayName: String, newPhotoURL: String) -> Unit = { _, _ -> },
    onOpenDm: ((uid: String, name: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var channels           by remember { mutableStateOf<List<VoiceChannel>>(emptyList()) }
    var channelUsers       by remember { mutableStateOf<Map<String, List<ActiveUser>>>(emptyMap()) }
    var connectedChannelId by remember { mutableStateOf<String?>(null) }
    var selectedChannelId  by remember { mutableStateOf<String?>(null) }
    var showTextChannel    by remember { mutableStateOf(true) }   // true = # genel seçili
    var isMuted            by remember { mutableStateOf(false) }
    var isDeafened         by remember { mutableStateOf(false) }
    var loading            by remember { mutableStateOf(true) }

    // Chat state — önbellekten başla, re-entry'de mesajlar kaybolmasın
    var messages      by remember { mutableStateOf(MessageStore.get(serverId)) }
    var messageText   by remember { mutableStateOf("") }
    var sendingMsg    by remember { mutableStateOf(false) }
    val listState     = rememberLazyListState()

    // Profile dialog
    var showProfileDialog by remember { mutableStateOf(false) }
    // Invite dialog
    var showInviteDialog  by remember { mutableStateOf(false) }
    // Birleşik sağ tık menüsü (ses grid + sidebar her ikisinden de tetiklenir)
    var contextMenuUser   by remember { mutableStateOf<ActiveUser?>(null) }
    // Per-user ses seviyeleri (uid → 0.0-2.0, default 1.0)
    val peerVolumes       = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, Float>() }
    // Ses seviyesi slider gösterilecek mi?
    var showVolumeSlider  by remember { mutableStateOf(false) }
    // Sunucu sahibi mi?
    var isOwner           by remember { mutableStateOf(false) }
    // Atıldı mı?
    var wasKicked         by remember { mutableStateOf(false) }
    // Çevrimiçi üyeler (presence)
    var presenceUsers     by remember { mutableStateOf<List<ActiveUser>>(emptyList()) }

    val latestConnectedId   by rememberUpdatedState(connectedChannelId)
    val latestChannelUsers  by rememberUpdatedState(channelUsers)

    val displayNickname = currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email } }

    val userEntry = ActiveUser(
        uid      = currentUser.uid,
        username = displayNickname,
        color    = "#5865F2",
        photoURL = currentUser.photoURL,
    )

    // Profile settings dialog
    if (showProfileDialog) {
        ProfileSettingsDialog(
            currentUser = currentUser,
            onDismiss   = { showProfileDialog = false },
            onSaved     = { newName, newPhoto ->
                onUserUpdated(newName, newPhoto)
                showProfileDialog = false
            },
        )
    }

    // Invite dialog
    if (showInviteDialog) {
        InviteDialog(
            serverId   = serverId,
            idToken    = currentUser.idToken,
            onDismiss  = { showInviteDialog = false },
        )
    }

    // Kicked dialog — sunucu sahibi tarafından atıldıysa
    if (wasKicked) {
        AlertDialog(
            onDismissRequest = {},
            containerColor   = Color(0xFF2B2D31),
            title = { Text("Sunucudan Atıldın", fontWeight = FontWeight.Bold, color = Color(0xFFED4245)) },
            text  = { Text("Sunucu sahibi seni bu sunucudan attı.", color = Color(0xFFDCDDDE), fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { wasKicked = false; onLeaveServer() },
                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF5865F2)),
                ) { Text("Tamam", color = Color.White) }
            },
        )
    }

    // ── Poll channels + active users every 3 s ────────────────────────────────
    LaunchedEffect(serverId, "channels") {
        while (true) {
            try {
                val fetched = FirestoreClient.listVoiceChannels(serverId, currentUser.idToken)
                channels = fetched
                val newUsers = mutableMapOf<String, List<ActiveUser>>()
                for (ch in fetched) {
                    try {
                        val fetchedUsers = FirestoreClient.getChannelActiveUsers(serverId, ch.id, currentUser.idToken)
                        // Firestore bazen yeni yazımdan sonra kısa süre boş döndürür (eventual consistency).
                        // Bağlı olduğumuz kanal boş gelirse mevcut listeyi koru.
                        newUsers[ch.id] = if (fetchedUsers.isEmpty() && ch.id == connectedChannelId)
                            channelUsers[ch.id] ?: emptyList()
                        else
                            fetchedUsers
                    } catch (_: Exception) {
                        newUsers[ch.id] = channelUsers[ch.id] ?: emptyList()
                    }
                }
                channelUsers = newUsers
                loading = false
            } catch (_: Exception) {
                loading = false
            }
            delay(3_000)
        }
    }

    // ── Poll chat messages every 3 s ─────────────────────────────────────────
    LaunchedEffect(serverId, "messages") {
        while (true) {
            try {
                val fetched = FirestoreClient.listMessages(serverId, currentUser.idToken)
                val hasPending = messages.any { it.id.startsWith("pending-") }
                // Boş fetch mesajları silmesin (ağ hatası / Firestore geçici boş dönüş)
                if (!hasPending && fetched.isNotEmpty() && fetched != messages) {
                    messages = fetched
                    MessageStore.set(serverId, fetched)
                    listState.animateScrollToItem(fetched.size - 1)
                }
            } catch (_: Exception) {}
            delay(3_000)
        }
    }

    // ── Presence heartbeat — 30s'de bir yaz ─────────────────────────────────
    LaunchedEffect(serverId, currentUser.uid) {
        while (true) {
            runCatching {
                FirestoreClient.upsertPresence(serverId, currentUser.uid, displayNickname, currentUser.photoURL, currentUser.idToken)
            }
            delay(30_000)
        }
    }

    // ── Presence poll — 5s'de bir oku ────────────────────────────────────────
    LaunchedEffect(serverId, "presence") {
        while (true) {
            runCatching {
                val fetched = FirestoreClient.listPresence(serverId, currentUser.idToken)
                if (fetched != presenceUsers) presenceUsers = fetched
            }
            delay(5_000)
        }
    }

    // ── Cleanup on dispose ────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            val cid  = latestConnectedId
            val prev = latestChannelUsers
            GlobalScope.launch {
                if (cid != null) {
                    try {
                        FirestoreClient.setChannelActiveUsers(
                            serverId, cid,
                            (prev[cid] ?: emptyList()).filter { it.uid != currentUser.uid },
                            currentUser.idToken,
                        )
                    } catch (_: Exception) {}
                    runCatching { FirestoreClient.removeVoicePeer(serverId, currentUser.uid, currentUser.idToken) }
                }
                runCatching { FirestoreClient.deletePresence(serverId, currentUser.uid, currentUser.idToken) }
            }
            VoiceEngine.stop()
        }
    }

    // ── Voice peers polling ───────────────────────────────────────────────────
    LaunchedEffect(connectedChannelId) {
        val cid = connectedChannelId
        if (cid == null) {
            VoiceEngine.updatePeers(emptyList())
            return@LaunchedEffect
        }
        while (true) {
            try {
                val peers = FirestoreClient.getVoicePeers(serverId, cid, currentUser.idToken)
                    .filter { it.uid != currentUser.uid }
                VoiceEngine.updatePeers(peers)
            } catch (_: Exception) {}
            delay(2_000)
        }
    }

    // ── Sync mute/deafen state with VoiceEngine ───────────────────────────────
    LaunchedEffect(isMuted)    { VoiceEngine.isMuted    = isMuted    }
    LaunchedEffect(isDeafened) { VoiceEngine.isDeafened = isDeafened }

    // ── Sunucu sahipliğini yükle ──────────────────────────────────────────────
    LaunchedEffect(serverId) {
        runCatching {
            val creatorUid = FirestoreClient.getServerCreatorUid(serverId, currentUser.idToken)
            isOwner = (creatorUid == currentUser.uid)
        }
    }

    // ── Kicked polling — 5s'de bir kontrol et ────────────────────────────────
    LaunchedEffect(serverId, currentUser.uid) {
        while (true) {
            delay(5_000)
            if (!isOwner) {
                runCatching {
                    val kicked = FirestoreClient.isKicked(serverId, currentUser.uid, currentUser.idToken)
                    if (kicked) {
                        // Kicked kaydını temizle, ses bırak, sunucudan çık
                        FirestoreClient.clearKickedFlag(serverId, currentUser.uid, currentUser.idToken)
                        val cid = connectedChannelId
                        if (cid != null) {
                            runCatching {
                                FirestoreClient.setChannelActiveUsers(
                                    serverId, cid,
                                    (channelUsers[cid] ?: emptyList()).filter { it.uid != currentUser.uid },
                                    currentUser.idToken,
                                )
                                FirestoreClient.removeVoicePeer(serverId, currentUser.uid, currentUser.idToken)
                            }
                            VoiceEngine.stop()
                            connectedChannelId = null
                        }
                        wasKicked = true
                    }
                }
            }
        }
    }

    // ── Join channel ──────────────────────────────────────────────────────────
    fun joinChannel(ch: VoiceChannel) {
        if (connectedChannelId == ch.id) return
        scope.launch {
            val prevId = connectedChannelId
            if (prevId != null) {
                try {
                    val updatedPrev = (channelUsers[prevId] ?: emptyList()).filter { it.uid != currentUser.uid }
                    FirestoreClient.setChannelActiveUsers(serverId, prevId, updatedPrev, currentUser.idToken)
                    channelUsers = channelUsers + (prevId to updatedPrev)
                } catch (_: Exception) {}
                // Eski kanaldan ses bağlantısını kes
                runCatching { FirestoreClient.removeVoicePeer(serverId, currentUser.uid, currentUser.idToken) }
                VoiceEngine.stop()
            }
            try {
                val existing = (channelUsers[ch.id] ?: emptyList()).filter { it.uid != currentUser.uid }
                val updated  = existing + userEntry
                FirestoreClient.setChannelActiveUsers(serverId, ch.id, updated, currentUser.idToken)
                // Yerel state'i hemen güncelle — 3s poll'u bekleme
                channelUsers = channelUsers + (ch.id to updated)
            } catch (_: Exception) {}
            connectedChannelId = ch.id
            selectedChannelId  = ch.id
            showTextChannel    = false   // ses kanalına geçildiğinde # genel deselect
            // Opsiyonel relay: JVM argümanlarıyla verilebilir.
            val relayHost = System.getProperty("furcord.relay.host")?.trim().orEmpty()
            val relayPort = System.getProperty("furcord.relay.port")?.toIntOrNull()
            VoiceEngine.relayServer = if (relayHost.isNotEmpty() && relayPort != null) {
                relayHost to relayPort
            } else {
                null
            }
            // Ses motorunu başlat ve Firestore'a bağlantı bilgimizi yaz
            val started = VoiceEngine.start(currentUser.uid.hashCode(), ch.id)
            if (started) {
                runCatching {
                    FirestoreClient.setVoicePeer(
                        serverId  = serverId,
                        channelId = ch.id,
                        uid       = currentUser.uid,
                        ip        = VoiceEngine.localPublicIp,
                        port      = VoiceEngine.localPort,
                        idToken   = currentUser.idToken,
                    )
                }
            }
        }
    }

    // ── Leave channel ─────────────────────────────────────────────────────────
    fun leaveChannel() {
        val cid = connectedChannelId ?: return
        scope.launch {
            try {
                FirestoreClient.setChannelActiveUsers(
                    serverId, cid,
                    (channelUsers[cid] ?: emptyList()).filter { it.uid != currentUser.uid },
                    currentUser.idToken,
                )
            } catch (_: Exception) {}
            runCatching { FirestoreClient.removeVoicePeer(serverId, currentUser.uid, currentUser.idToken) }
            VoiceEngine.stop()
            connectedChannelId = null
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────
    fun sendMessage() {
        val txt = messageText.trim()
        if (txt.isEmpty() || sendingMsg) return
        // Optimistic: mesajı hemen listeye ekle
        val now = System.currentTimeMillis()
        val optimistic = ChatMessage(
            id        = "pending-$now",
            uid       = currentUser.uid,
            username  = displayNickname,
            photoURL  = currentUser.photoURL,
            text      = txt,
            timestamp = now,
        )
        messages = messages + optimistic
        messageText = ""
        scope.launch { listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0)) }
        scope.launch {
            sendingMsg = true
            try {
                FirestoreClient.sendMessage(
                    serverId  = serverId,
                    uid       = currentUser.uid,
                    username  = displayNickname,
                    photoURL  = currentUser.photoURL,
                    text      = txt,
                    idToken   = currentUser.idToken,
                )
                // Gönderim başarılı: cache'i hemen güncelle (optimistik mesaj dahil)
                // Kullanıcı Firestore fetch gelmeden önce ayrılırsa bile mesaj kaybolmaz
                MessageStore.set(serverId, messages)
                // Firestore'dan onaylı versiyonu almaya çalış
                val fetched = FirestoreClient.listMessages(serverId, currentUser.idToken)
                if (fetched.isNotEmpty()) {
                    messages = fetched
                    MessageStore.set(serverId, fetched)
                    listState.animateScrollToItem(fetched.size - 1)
                }
            } catch (_: Exception) {
                // Gönderim başarısız — optimistic mesajı kaldır
                messages = messages.filter { it.id != optimistic.id }
            }
            sendingMsg = false
        }
    }

    val connectedChannel = channels.find { it.id == connectedChannelId }
    val selectedChannel  = channels.find { it.id == selectedChannelId }
    val displayName      = displayNickname
    val initial          = displayName.firstOrNull()?.uppercaseChar() ?: '?'

    Row(modifier = Modifier.fillMaxSize()) {

        // ── Left sidebar ──────────────────────────────────────────────────────
        Box {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(Color(0xFF2B2D31)),
        ) {
            // Server name header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = serverName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF2F3F5),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Notification bell
                NotificationBell(
                    currentUser = currentUser,
                    hasUpdate = hasUpdate,
                )
                // Invite button
                IconButton(onClick = { showInviteDialog = true }, modifier = Modifier.size(28.dp)) {
                    Text("🔗", fontSize = 14.sp)
                }
                // Leave server button
                IconButton(onClick = onLeaveServer, modifier = Modifier.size(28.dp)) {
                    Text("←", fontSize = 16.sp, color = Color(0xFF8E9297))
                }
            }
            HorizontalDivider(color = Color(0xFF1E1F22))

            // "METIN KANALLARI" label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "METİN KANALLARI",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8E9297),
                    modifier = Modifier.weight(1f),
                    letterSpacing = 0.8.sp,
                )
            }

            // # genel satırı
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (showTextChannel) Color(0xFF404249) else Color.Transparent)
                    .clickable { showTextChannel = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "#",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showTextChannel) Color(0xFFF2F3F5) else Color(0xFF8E9297),
                )
                Text(
                    text = "genel",
                    fontSize = 15.sp,
                    color = if (showTextChannel) Color(0xFFF2F3F5) else Color(0xFF8E9297),
                    fontWeight = if (showTextChannel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // "SES KANALLARI" label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SES KANALLARI",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8E9297),
                    modifier = Modifier.weight(1f),
                    letterSpacing = 0.8.sp,
                )
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(channels) { ch ->
                    ChannelRow(
                        channel          = ch,
                        isConnected      = connectedChannelId == ch.id,
                        isSelected       = selectedChannelId == ch.id,
                        activeUsers      = channelUsers[ch.id] ?: emptyList(),
                        currentUid       = currentUser.uid,
                        onClick          = { joinChannel(ch) },
                        onUserRightClick = { u -> contextMenuUser = u; showVolumeSlider = false },
                    )
                }
                if (channels.isEmpty() && !loading) {
                    item {
                        Text(
                            text = "Henüz kanal yok.",
                            fontSize = 13.sp,
                            color = Color(0xFF8E9297),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                // ── Çevrimiçi üyeler (presence) ──────────────────────────────
                if (presenceUsers.isNotEmpty()) {
                    item {
                        Text(
                            text = "ÇEVRİMİÇİ ÜYELER — ${presenceUsers.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9297),
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(presenceUsers) { u ->
                        val isSelf = u.uid == currentUser.uid
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF23A55A))
                            )
                            UserAvatar(
                                displayName = u.username,
                                photoURL    = u.photoURL,
                                size        = 20,
                            )
                            Text(
                                text = if (isSelf) "${u.username} (sen)" else u.username,
                                fontSize = 12.sp,
                                color = if (isSelf) Color(0xFF23A55A) else Color(0xFFB5BAC1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Voice connected bar
            if (connectedChannel != null) {
                HorizontalDivider(color = Color(0xFF1E1F22))
                VoiceConnectedBar(
                    channelName  = connectedChannel.name,
                    onDisconnect = { leaveChannel() },
                )
            }

            // User panel
            HorizontalDivider(color = Color(0xFF1E1F22))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF232428))
                    .padding(horizontal = 8.dp)
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Clickable avatar – opens profile settings
                UserAvatar(
                    displayName = displayName,
                    photoURL    = currentUser.photoURL,
                    size        = 32,
                    modifier    = Modifier.clickable { showProfileDialog = true },
                )
                Column(modifier = Modifier.weight(1f).clickable { showProfileDialog = true }) {
                    Text(
                        text = displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF2F3F5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = "Çevrimiçi", fontSize = 11.sp, color = Color(0xFF80848E))
                }
                PanelIconBtn(
                    label   = if (isMuted) "🔇" else "🎙️",
                    tint    = if (isMuted) Color(0xFFF23F43) else Color(0xFFB5BAC1),
                    onClick = { isMuted = !isMuted },
                )
                PanelIconBtn(
                    label   = if (isDeafened) "🔕" else "🎧",
                    tint    = if (isDeafened) Color(0xFFF23F43) else Color(0xFFB5BAC1),
                    onClick = { isDeafened = !isDeafened },
                )
            }
        }   // end sidebar Column

        // Sidebar context menu (sidebar'da sağ tıklandığında)
        val sidebarCtxUser = contextMenuUser
        if (sidebarCtxUser != null) {
            UserContextMenu(
                user          = sidebarCtxUser,
                isOwner       = isOwner,
                volume        = peerVolumes[sidebarCtxUser.uid] ?: 1f,
                showVolSlider = showVolumeSlider,
                onToggleVol   = { showVolumeSlider = !showVolumeSlider },
                onVolumeChange = { vol ->
                    peerVolumes[sidebarCtxUser.uid] = vol
                    VoiceEngine.setPeerVolume(sidebarCtxUser.uid, vol)
                },
                onDismiss   = { contextMenuUser = null },
                onMessage   = { contextMenuUser = null; onOpenDm?.invoke(sidebarCtxUser.uid, sidebarCtxUser.username) },
                onAddFriend = {
                    contextMenuUser = null
                    scope.launch {
                        runCatching {
                            FirestoreClient.sendFriendRequest(
                                toUid     = sidebarCtxUser.uid,
                                fromUid   = currentUser.uid,
                                fromName  = currentUser.displayName.ifEmpty { currentUser.email },
                                furcordId = currentUser.furcordId,
                                idToken   = currentUser.idToken,
                            )
                        }
                    }
                },
                onKick = if (isOwner) {{
                    val targetUid = sidebarCtxUser.uid
                    contextMenuUser = null
                    scope.launch {
                        runCatching { FirestoreClient.kickUser(serverId, targetUid, currentUser.idToken) }
                        channelUsers = channelUsers.mapValues { (_, users) ->
                            users.filter { it.uid != targetUid }
                        }
                        channelUsers.forEach { (cid, users) ->
                            runCatching {
                                FirestoreClient.setChannelActiveUsers(serverId, cid, users, currentUser.idToken)
                            }
                        }
                    }
                }} else null,
            )
        }
        }   // end sidebar Box

        // ── Main content area ─────────────────────────────────────────────────
        if (showTextChannel) {
            // ── # genel metin kanalı ─────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF313338)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("#", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8E9297))
                    Text(
                        text = "genel",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF2F3F5),
                    )
                }
                HorizontalDivider(color = Color(0xFF1E1F22))
                ChatPanel(
                    serverId     = serverId,
                    messages     = messages,
                    currentUser  = currentUser,
                    listState    = listState,
                    messageText  = messageText,
                    onTextChange = { messageText = it },
                    onSend       = { sendMessage() },
                    modifier     = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        } else {
            // ── Ses kanalı görünümü — sadece kullanıcı grid, mesaj yok ────────
            val viewChannelId = connectedChannelId ?: selectedChannelId
            val viewChannel   = channels.find { it.id == viewChannelId }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF313338)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🔊", fontSize = 18.sp)
                    Text(
                        text = viewChannel?.name ?: "—",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF2F3F5),
                    )
                }
                HorizontalDivider(color = Color(0xFF1E1F22))

                val active = channelUsers[viewChannelId] ?: emptyList()
                if (active.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Henüz kimse yok.\nSes kanalına katıl!",
                            fontSize = 14.sp,
                            color = Color(0xFF8E9297),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(32.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(active) { u ->
                            val isSelf = u.uid == currentUser.uid
                            Box {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = if (!isSelf) Modifier.pointerInput(u.uid) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Press &&
                                                    event.buttons.isSecondaryPressed
                                                ) {
                                                    contextMenuUser = u
                                                    showVolumeSlider = false
                                                }
                                            }
                                        }
                                    } else Modifier,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .then(
                                                if (isSelf) Modifier.background(Color(0x4023A55A))
                                                else Modifier
                                            ),
                                    ) {
                                        UserAvatar(
                                            displayName = u.username,
                                            photoURL    = u.photoURL,
                                            size        = 72,
                                        )
                                        if (isSelf) {
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0x0023A55A))
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isSelf) "${u.username} (sen)" else u.username,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelf) Color(0xFF23A55A) else Color(0xFFF2F3F5),
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                // Sağ tık bağlam menüsü (grid)
                                if (!isSelf && contextMenuUser?.uid == u.uid) {
                                    UserContextMenu(
                                        user          = u,
                                        isOwner       = isOwner,
                                        volume        = peerVolumes[u.uid] ?: 1f,
                                        showVolSlider = showVolumeSlider,
                                        onToggleVol   = { showVolumeSlider = !showVolumeSlider },
                                        onVolumeChange = { vol ->
                                            peerVolumes[u.uid] = vol
                                            VoiceEngine.setPeerVolume(u.uid, vol)
                                        },
                                        onDismiss     = { contextMenuUser = null },
                                        onMessage     = { contextMenuUser = null; onOpenDm?.invoke(u.uid, u.username) },
                                        onAddFriend   = {
                                            contextMenuUser = null
                                            scope.launch {
                                                runCatching {
                                                    FirestoreClient.sendFriendRequest(
                                                        toUid     = u.uid,
                                                        fromUid   = currentUser.uid,
                                                        fromName  = currentUser.displayName.ifEmpty { currentUser.email },
                                                        furcordId = currentUser.furcordId,
                                                        idToken   = currentUser.idToken,
                                                    )
                                                }
                                            }
                                        },
                                        onKick = if (isOwner) {{
                                            val targetUid = u.uid
                                            contextMenuUser = null
                                            scope.launch {
                                                runCatching { FirestoreClient.kickUser(serverId, targetUid, currentUser.idToken) }
                                                channelUsers = channelUsers.mapValues { (_, users) ->
                                                    users.filter { it.uid != targetUid }
                                                }
                                                channelUsers.forEach { (cid, users) ->
                                                    runCatching {
                                                        FirestoreClient.setChannelActiveUsers(serverId, cid, users, currentUser.idToken)
                                                    }
                                                }
                                            }
                                        }} else null,
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

// ─────────────────────────────────────────────────────────────────────────────
// Reusable chat panel
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ChatPanel(
    serverId: String,
    messages: List<ChatMessage>,
    currentUser: AuthUser,
    listState: androidx.compose.foundation.lazy.LazyListState,
    messageText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Message list
        LazyColumn(
            state   = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Henüz mesaj yok. İlk mesajı sen gönder!",
                            fontSize = 14.sp,
                            color = Color(0xFF8E9297),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(messages) { msg ->
                MessageRow(msg = msg, isSelf = msg.uid == currentUser.uid)
            }
        }

        HorizontalDivider(color = Color(0xFF1E1F22))

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF383A40))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value         = messageText,
                onValueChange = onTextChange,
                placeholder   = { Text("Mesaj gönder…", color = Color(0xFF6D6F78)) },
                modifier      = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            !event.isShiftPressed
                        ) {
                            onSend()
                            true
                        } else false
                    },
                maxLines = 4,
                shape    = RoundedCornerShape(8.dp),
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Color(0xFF5865F2),
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor   = Color(0xFF40444B),
                    unfocusedContainerColor = Color(0xFF40444B),
                    focusedTextColor     = Color(0xFFF2F3F5),
                    unfocusedTextColor   = Color(0xFFF2F3F5),
                ),
            )
            IconButton(
                onClick  = onSend,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5865F2)),
            ) {
                Text("➤", fontSize = 16.sp, color = Color.White)
            }
        }
    }
}