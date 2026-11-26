package com.furcord.ui

import androidx.compose.foundation.background
import com.furcord.ui.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.furcord.screenshare.ScreenShareManager
import com.furcord.screenshare.StreamViewerComposable
import com.furcord.screenshare.PiPStreamWindow
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.HeadsetOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.rounded.Send


// ─────────────────────────────────────────────────────────────────────────────
// Invite dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun InviteDialog(
    serverId: String,
    serverName: String,
    currentUser: AuthUser,
    idToken: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var friends     by remember { mutableStateOf<List<com.furcord.auth.FriendEntry>?>(null) }
    var sentTo      by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sendingTo   by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(currentUser.uid) {
        friends = runCatching { FirestoreClient.listFriends(currentUser.uid, idToken) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = AppColors.BgSidebar,
        title = {
            Text("Sunucuya Davet Et", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Arkadaşlarına davet gönder; davet bildirimlerine düşer.",
                    fontSize = 13.sp,
                    color = Color(0xFFB5BAC1),
                )
                // ── Arkadaş listesi ──
                HorizontalDivider(color = Color(0xFF3F4147))
                Text(
                    text = "ARKADAŞLAR",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = AppColors.TextMuted, letterSpacing = 0.8.sp,
                )
                val fl = friends
                when {
                    fl == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    fl.isEmpty() -> Text("Henüz arkadaşın yok.", fontSize = 12.sp, color = AppColors.TextMuted)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(fl, key = { it.uid }) { fr ->
                            val sent    = fr.uid in sentTo
                            val sending = fr.uid in sendingTo
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                UserAvatar(displayName = fr.displayName, photoURL = "", size = 30)
                                Text(
                                    text = fr.displayName,
                                    fontSize = 13.sp, color = AppColors.TextPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        sendingTo = sendingTo + fr.uid
                                        scope.launch {
                                            val ok = runCatching {
                                                FirestoreClient.sendServerInviteNotif(
                                                    toUid      = fr.uid,
                                                    serverId   = serverId,
                                                    serverName = serverName,
                                                    fromUid    = currentUser.uid,
                                                    fromName   = currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email } },
                                                    idToken    = idToken,
                                                )
                                            }.isSuccess
                                            sendingTo = sendingTo - fr.uid
                                            if (ok) sentTo = sentTo + fr.uid
                                        }
                                    },
                                    enabled = !sent && !sending,
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.Accent,
                                        disabledContainerColor = if (sent) AppColors.Online.copy(alpha = 0.3f) else AppColors.Accent.copy(alpha = 0.4f),
                                    ),
                                ) {
                                    Text(if (sent) "Gönderildi ✓" else if (sending) "…" else "Davet Et", fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat", color = AppColors.Accent)
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
    onWatchStream: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded         = true,
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111214),
    ) {
        DropdownMenuItem(
            text    = { Text("💬 Mesaj Gönder", color = AppColors.TextSecondary, fontSize = 13.sp) },
            onClick = onMessage,
        )
        HorizontalDivider(color = AppColors.BgSidebar)
        DropdownMenuItem(
            text    = { Text("➕ Arkadaş Ekle", color = AppColors.TextSecondary, fontSize = 13.sp) },
            onClick = onAddFriend,
        )
        HorizontalDivider(color = AppColors.BgSidebar)
        // Ses seviyesi
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "🔊 Ses: ${(volume * 100).toInt()}%",
                        color = AppColors.TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (showVolSlider) "▲" else "▼", color = AppColors.TextMuted, fontSize = 11.sp)
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
                                thumbColor        = AppColors.Accent,
                                activeTrackColor  = AppColors.Accent,
                                inactiveTrackColor= Color(0xFF4E5058),
                            ),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0%",   color = AppColors.TextMuted, fontSize = 10.sp)
                            Text("100%", color = AppColors.TextMuted, fontSize = 10.sp)
                            Text("200%", color = AppColors.TextMuted, fontSize = 10.sp)
                        }
                    }
                },
                onClick = {},
            )
        }
        if (onKick != null) {
            HorizontalDivider(color = AppColors.BgSidebar)
            DropdownMenuItem(
                text    = { Text("🔨 Sunucudan At", color = AppColors.Danger, fontSize = 13.sp) },
                onClick = onKick,
            )
        }
        if (onWatchStream != null) {
            HorizontalDivider(color = AppColors.BgSidebar)
            DropdownMenuItem(
                text    = { Text("📺 Yayını İzle", color = Color(0xFF00B8D4), fontSize = 13.sp) },
                onClick = onWatchStream,
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
            .background(AppColors.Accent),
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
    speakingSet: Set<Int> = emptySet(),
    timerStr: String? = null,
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val hovered by rowInteraction.collectIsHoveredAsState()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .hoverable(rowInteraction)
                .background(
                    when {
                        isSelected && !isConnected -> AppColors.BgActive
                        isConnected               -> AppColors.Online.copy(alpha = 0.15f)
                        hovered                   -> AppColors.BgElevated
                        else                      -> Color.Transparent
                    }
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isConnected) "🔊" else "🔈",
                fontSize = 16.sp,
                color = if (isConnected) AppColors.Online else AppColors.TextMuted,
            )
            Text(
                text = channel.name,
                fontSize = 15.sp,
                color = when {
                    isConnected -> AppColors.Online
                    isSelected  -> AppColors.TextPrimary
                    hovered     -> AppColors.TextSecondary
                    else        -> AppColors.TextMuted
                },
                fontWeight = if (isConnected || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!timerStr.isNullOrEmpty()) {
                Text(
                    text = timerStr,
                    fontSize = 11.sp,
                    color = AppColors.Online,
                )
            } else if (activeUsers.isNotEmpty()) {
                Text(
                    text = "${activeUsers.size}",
                    fontSize = 11.sp,
                    color = AppColors.TextSubtle,
                )
            }
        }

        // Active users beneath the channel row
        if (activeUsers.isNotEmpty()) {
            activeUsers.forEach { u ->
                val isSelf        = u.uid == currentUid
                val userInteract  = remember(u.uid) { MutableInteractionSource() }

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 30.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                            .hoverable(userInteract)
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
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val isSpeaking = speakingSet.contains(u.uid.hashCode())
                        Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            if (isSpeaking) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .border(1.5.dp, AppColors.Online, CircleShape)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                            ) {
                                UserAvatar(
                                    displayName = u.username,
                                    photoURL    = u.photoURL,
                                    size        = 30,
                                )
                            }
                        }
                        Text(
                            text = if (isSelf) "${u.username} (sen)" else u.username,
                            fontSize = 12.sp,
                            color = when {
                                isSelf || isSpeaking -> AppColors.Online
                                else -> Color(0xFFB5BAC1)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
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
            .background(AppColors.BgDark)
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
                        .background(AppColors.Online),
                )
                Text(
                    text = "Ses Bağlantısı Kuruldu",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.Online,
                )
            }
            IconButton(onClick = onDisconnect, modifier = Modifier.size(26.dp)) {
                Text("📵", fontSize = 13.sp)
            }
        }
        Text(
            text = channelName,
            fontSize = 12.sp,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(start = 14.dp, top = 2.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat message row — Discord-style grouping
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MessageRow(
    msg: ChatMessage,
    isSelf: Boolean,
    isGrouped: Boolean = false,
    onReply: (ChatMessage) -> Unit = {},
    onCopy: (ChatMessage) -> Unit = {},
    idToken: String = "",
    onDownload: (ChatMessage) -> Unit = {},
) {
    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    var showContextMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }
    val showHeader = !isGrouped || msg.replyToId.isNotBlank()

    // Dış Box: hover + arka plan + sağ tık. Eylem çubuğu bunun üstüne biner.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(if (hovered) Color.White.copy(alpha = 0.025f) else Color.Transparent)
            .pointerInput(msg.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.firstOrNull()?.position?.let {
                                menuOffset = androidx.compose.ui.unit.DpOffset(
                                    (it.x / density).dp, (it.y / density).dp
                                )
                            }
                            showContextMenu = true
                        }
                    }
                }
            },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(
                start = 20.dp, end = 20.dp,
                top = if (showHeader) 10.dp else 1.dp, bottom = 1.dp,
            )
        ) {
            // Yanıt alıntısı
            if (msg.replyToId.isNotBlank()) {
                Row(
                    Modifier
                        .padding(start = 52.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(Radius.Small))
                        .background(AppColors.BgSidebar)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.width(2.dp).height(22.dp).background(AppColors.Accent))
                    Text(msg.replyToUser, fontSize = 12.sp, color = AppColors.Accent,
                        fontWeight = FontWeight.SemiBold)
                    Text(msg.replyToText.take(80), fontSize = 12.sp, color = AppColors.TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                // Avatar sütunu (gruplanmış mesajda hover'da saat gösterir)
                Box(Modifier.width(40.dp), contentAlignment = Alignment.TopCenter) {
                    if (showHeader) {
                        UserAvatar(displayName = msg.username, photoURL = msg.photoURL, size = 40)
                    } else if (hovered) {
                        Text(timeStr, fontSize = 10.sp, color = AppColors.TextTimestamp,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (showHeader) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom) {
                            Text(msg.username, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isSelf) AppColors.SelfName else AppColors.TextPrimary)
                            Text(timeStr, fontSize = 11.sp, color = AppColors.TextTimestamp)
                        }
                    }
                    if (msg.text.isNotBlank()) {
                        Text(msg.text, fontSize = 14.sp, lineHeight = 20.sp,
                            color = AppColors.TextSecondary)
                    }
                    if (msg.imageUrl.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        AttachmentView(msg.imageUrl, idToken)
                    }
                }
            }
        }

        // Hover eylem çubuğu
        if (hovered) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 2.dp)
                    .clip(RoundedCornerShape(Radius.Small))
                    .background(AppColors.BgElevated)
                    .padding(2.dp),
            ) {
                IconButton(onClick = { onReply(msg) }, Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, "Yanıtla",
                        tint = AppColors.TextMuted, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onCopy(msg) }, Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Kopyala",
                        tint = AppColors.TextMuted, modifier = Modifier.size(16.dp))
                }
                if (msg.imageUrl.isNotBlank() && !msg.id.startsWith("img-pending-")) {
                    IconButton(onClick = { onDownload(msg) }, Modifier.size(28.dp)) {
                        Icon(Icons.Default.Download, "İndir",
                            tint = AppColors.TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = menuOffset,
        ) {
            DropdownMenuItem(text = { Text("Yanıtla") },
                onClick = { showContextMenu = false; onReply(msg) })
            DropdownMenuItem(text = { Text("Kopyala") },
                onClick = { showContextMenu = false; onCopy(msg) })
            if (msg.imageUrl.isNotBlank() && !msg.id.startsWith("img-pending-")) {
                DropdownMenuItem(text = { Text("İndir") },
                    onClick = { showContextMenu = false; onDownload(msg) })
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Disk + bellek önbellekli resim yükleyici
// ─────────────────────────────────────────────────────────────────────────────
private val imgCacheDir = java.io.File(System.getProperty("user.home"), ".furcord/img_cache")
    .also { it.mkdirs() }

@Composable
private fun AsyncChatImage(url: String) {
    val bmp = rememberCachedImage(url)
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap             = bmp!!,
            contentDescription = "Görsel",
            modifier           = Modifier.widthIn(max = 300.dp).heightIn(max = 250.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppColors.Accent)
    }
}

/**
 * Sunucu ikonu — imageUrl doluysa ağdan yükler (CircleShape), boşsa harf+renk fallback.
 */
@Composable
private fun AsyncServerIcon(imageUrl: String, name: String, size: androidx.compose.ui.unit.Dp) {
    if (imageUrl.isNotEmpty()) {
        val bmp = rememberCachedImage(imageUrl)
        Box(
            modifier = Modifier.size(size).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap             = bmp!!,
                    contentDescription = name,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(AppColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        fontSize   = (size.value * 0.4f).sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White,
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(AppColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize   = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sunucu ayarları dialog (görsel URL güncelleme)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ServerSettingsDialog(
    serverId: String,
    serverName: String,
    currentUser: AuthUser,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving   by remember { mutableStateOf(false) }
    var saved    by remember { mutableStateOf(false) }
    var error    by remember { mutableStateOf("") }
    var status   by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor   = AppColors.BgSidebar,
        title = { Text("Sunucu Ayarları", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sunucu: $serverName", fontSize = 13.sp, color = AppColors.TextMuted)
                // Dosya seç butonu
                Button(
                    enabled = !saving,
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val dialog = FileDialog(null as Frame?, "Sunucu Görseli Seç", FileDialog.LOAD)
                            dialog.file = "*.jpg;*.jpeg;*.png;*.webp"
                            dialog.isVisible = true
                            val selectedFile = dialog.directory?.let { dir ->
                                dialog.file?.let { name -> File(dir, name) }
                            } ?: return@launch
                            if (!selectedFile.exists()) return@launch
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                saving = true; error = ""; saved = false
                                status = "Yükleniyor..."
                            }
                            try {
                                // Storage yerine küçültülmüş görsel doğrudan Firestore'a yazılır
                                val dataUrl = com.furcord.auth.ImageData.toDataUrl(
                                    selectedFile.readBytes(), maxSide = 256, maxBytes = 120_000, square = true,
                                )
                                FirestoreClient.updateServerImage(serverId, dataUrl, currentUser.idToken)
                                ServerIcons.urls[serverId] = dataUrl
                                withContext(kotlinx.coroutines.Dispatchers.Main) { saved = true; status = "" }
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) { error = "Hata: ${e.message}"; status = "" }
                            } finally {
                                withContext(kotlinx.coroutines.Dispatchers.Main) { saving = false }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(status.ifEmpty { "Yükleniyor..." }, color = Color.White)
                    } else {
                        Text("📁 Görsel Seç ve Yükle", color = Color.White)
                    }
                }
                if (saved) Text("✅ Görsel güncellendi!", fontSize = 12.sp, color = AppColors.Online)
                if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = AppColors.Danger)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) {
                Text("Kapat", color = AppColors.TextMuted)
            }
        },
    )
}

/**
 * Uygulama genelinde tek bir aktif ses oturumu vardır. Başka bir sunucunun ses kanalına
 * girilirken mevcut oturum buradaki [disconnect] ile temiz şekilde kapatılır.
 */
private object VoiceSession {
    @Volatile var ownerServerId: String? = null
    @Volatile var disconnect: (suspend () -> Unit)? = null
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
    onSignOut: (() -> Unit)? = null,
    onVoiceConnectedChange: (Boolean) -> Unit = {},
    onJoinServer: ((serverId: String, serverName: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var channels           by remember { mutableStateOf<List<VoiceChannel>>(emptyList()) }
    var channelUsers       by remember { mutableStateOf<Map<String, List<ActiveUser>>>(emptyMap()) }
    var connectedChannelId by remember { mutableStateOf<String?>(null) }
    var joiningChannelId   by remember { mutableStateOf<String?>(null) }
    var selectedChannelId  by remember { mutableStateOf<String?>(null) }
    var showTextChannel    by remember { mutableStateOf(true) }   // true = # genel seçili
    var isMuted            by remember { mutableStateOf(false) }
    var isDeafened         by remember { mutableStateOf(false) }
    var loading            by remember { mutableStateOf(true) }

    // Chat state — önbellekten başla, re-entry'de mesajlar kaybolmasın
    var messages      by remember { mutableStateOf(MessageStore.get(serverId)) }
    var messageText   by remember { mutableStateOf("") }
    var replyingTo    by remember { mutableStateOf<ChatMessage?>(null) }
    var sendingMsg    by remember { mutableStateOf(false) }
    var sendingImage  by remember { mutableStateOf(false) }
    var attachError   by remember { mutableStateOf("") }
    val listState     = rememberLazyListState()

    // Profile dialog
    var showProfileDialog by remember { mutableStateOf(false) }
    // Invite dialog
    var showInviteDialog  by remember { mutableStateOf(false) }
    // Sunucu ayarları dialog (sadece sahip görür)
    var showServerSettingsDialog by remember { mutableStateOf(false) }
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
    // Sunucu kapak görseli URL'si
    val serverImageUrl = ServerIcons.urls[serverId] ?: ""
    // Konuşma göstergesi — uidHash kümesi
    var speakingSet       by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Ses kanalı kronometresi
    var connectedSinceMs  by remember { mutableStateOf<Long?>(null) }
    var voiceTimerStr     by remember { mutableStateOf("") }
    // Ekran paylaşımı
    var isScreenSharing      by remember { mutableStateOf(false) }
    var showSharePicker      by remember { mutableStateOf(false) }
    var showPiP              by remember { mutableStateOf(false) }
    var isAttemptingReceive  by remember { mutableStateOf(false) }
    val broadcastingUidHash  by ScreenShareManager.broadcastingUidHash.collectAsState()

    // Yayın bittiğinde (broadcastingUidHash sıfırlanınca) izleme durumunu temizle
    LaunchedEffect(broadcastingUidHash) {
        if (broadcastingUidHash == 0 && isAttemptingReceive) {
            isAttemptingReceive = false
            showPiP = false
        }
    }

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
            onSignOut   = onSignOut?.let { so -> { showProfileDialog = false; so() } },
            onSaved     = { newName, newPhoto ->
                onUserUpdated(newName, newPhoto)
                showProfileDialog = false
            },
        )
    }

    if (attachError.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { attachError = "" },
            containerColor   = AppColors.BgSidebar,
            title = { Text("Dosya gönderilemedi", fontWeight = FontWeight.Bold, color = AppColors.Danger) },
            text  = { Text(attachError, color = AppColors.TextSecondary, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { attachError = "" }) { Text("Tamam", color = AppColors.Accent) } },
        )
    }

    // Invite dialog
    if (showInviteDialog && isOwner) {
        InviteDialog(
            serverId   = serverId,
            serverName = serverName,
            currentUser = currentUser,
            idToken    = currentUser.idToken,
            onDismiss  = { showInviteDialog = false },
        )
    }

    // Sunucu ayarları dialog
    if (showServerSettingsDialog) {
        ServerSettingsDialog(
            serverId    = serverId,
            serverName  = serverName,
            currentUser = currentUser,
            onDismiss   = { showServerSettingsDialog = false },
        )
    }

    // Kicked dialog — sunucu sahibi tarafından atıldıysa
    if (wasKicked) {
        AlertDialog(
            onDismissRequest = {},
            containerColor   = AppColors.BgSidebar,
            title = { Text("Sunucudan Atıldın", fontWeight = FontWeight.Bold, color = AppColors.Danger) },
            text  = { Text("Sunucu sahibi seni bu sunucudan attı.", color = AppColors.TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { wasKicked = false; onLeaveServer() },
                    colors  = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                ) { Text("Tamam", color = Color.White) }
            },
        )
    }

    // PiP (küçük yapışkan pencere) — yayın aktifken her iki sekme görünümünde de erişilebilir
    // Ekran paylaşımı kaynak/kalite seçici
    if (showSharePicker) {
        ScreenSharePickerDialog(
            onDismiss = { showSharePicker = false },
            onStart   = { src, q ->
                showSharePicker = false
                showTextChannel = false  // ses kanalı görünümüne geç
                // Önceki yayından kalan sohbeti temizle, yeni yayının sohbeti boş başlasın
                GlobalScope.launch { runCatching { FirestoreClient.clearStreamChat(serverId, currentUser.idToken) } }
                ScreenShareManager.start(src, q)
                isScreenSharing = true
                showPiP = true   // yayını gösteren küçük yüzen oynatıcı
            },
        )
    }

    // Yayın bitince (her yolla) yayın sohbeti silinir
    DisposableEffect(isScreenSharing) {
        val wasSharing = isScreenSharing
        val token = currentUser.idToken
        onDispose {
            if (wasSharing) GlobalScope.launch { runCatching { FirestoreClient.clearStreamChat(serverId, token) } }
        }
    }

    // Yakalama kendiliğinden bittiyse (pencere kapandı vb.) paylaşım durumunu sıfırla
    LaunchedEffect(isScreenSharing) {
        while (isScreenSharing) {
            delay(1_000)
            if (!ScreenShareManager.isActive) {
                isScreenSharing = false
                showPiP = false
            }
        }
    }

    if (showPiP) {
        PiPStreamWindow(
            isSelfView       = isScreenSharing,
            startInFullscreen = !isScreenSharing,
            peerVolume     = 1f,
            onVolumeChange = {},
            onClose        = {
                showPiP = false
                // Yayıncının önizleme penceresini kapatması yayını bitirmez (uygulamadaki
                // "Paylaşımı Durdur" ile biter); izleyici için pencere kapanınca izleme biter.
                if (!isScreenSharing) {
                    ScreenShareManager.stopReceiver()
                    isAttemptingReceive = false
                }
            },
            chatPanel      = {
                StreamChatPanel(
                    serverId    = serverId,
                    currentUser = currentUser,
                    modifier    = Modifier.fillMaxSize(),
                )
            },
        )
    }

    // ── Sunucu görsel URL'si — tek seferlik çekme ────────────────────────────
    LaunchedEffect(serverId, "image") {
        runCatching {
            ServerIcons.urls[serverId] = FirestoreClient.getServerImageUrl(serverId, currentUser.idToken)
        }
    }

    // ── Poll channels + active users every 3 s ────────────────────────────────
    LaunchedEffect(serverId, "channels") {
        var prevUsers = mapOf<String, List<ActiveUser>>()
        var firstPoll = true
        while (true) {
            try {
                val fetched = FirestoreClient.listVoiceChannels(serverId, currentUser.idToken)
                if (fetched.isNotEmpty()) channels = fetched
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
                // Hayalet temizliği: bağlı olmadığım (veya geçiş yaptığım) kanallarda
                // adım kalmışsa (uygulama kapanışı, sunucu değişimi vb.) kendimi çıkar.
                val keepId = connectedChannelId ?: joiningChannelId
                for (ch in fetched) {
                    val list = newUsers[ch.id] ?: continue
                    if (ch.id != keepId && ch.id != joiningChannelId && list.any { it.uid == currentUser.uid }) {
                        val cleaned = list.filter { it.uid != currentUser.uid }
                        runCatching {
                            FirestoreClient.setChannelActiveUsers(serverId, ch.id, cleaned, currentUser.idToken)
                            newUsers[ch.id] = cleaned
                        }
                    }
                }
                // Join/leave ses efektleri — ilk poll'da ses çalma
                if (!firstPoll) {
                    for (chId in newUsers.keys) {
                        val oldUids = (prevUsers[chId] ?: emptyList()).map { it.uid }.toSet()
                        val newUids = (newUsers[chId] ?: emptyList()).map { it.uid }.toSet()
                        val joined  = newUids - oldUids - currentUser.uid
                        val left    = oldUids - newUids - currentUser.uid
                        if (joined.isNotEmpty()) com.furcord.platform.SoundEffect.playJoin()
                        if (left.isNotEmpty())   com.furcord.platform.SoundEffect.playLeave()
                    }
                }
                prevUsers  = newUsers
                firstPoll  = false
                channelUsers = newUsers
                loading = false
            } catch (_: Exception) {
                loading = false
            }
            delay(3_000)
        }
    }

    // ── Poll chat messages — timestamp cursor (kota tasarrufu) ───────────────
    LaunchedEffect(serverId, "messages") {
        while (true) {
            try {
                // Son bilinen timestamp'ten sonrasını çek; 0L ise ilk yükleme (full fetch)
                val lastTs = messages.filter { !it.id.startsWith("pending-") && !it.id.startsWith("img-pending-") }
                    .maxOfOrNull { it.timestamp } ?: 0L
                val newMsgs = FirestoreClient.listMessagesSince(serverId, lastTs, currentUser.idToken)
                if (newMsgs.isNotEmpty()) {
                    val hasPending = messages.any { it.id.startsWith("pending-") || it.id.startsWith("img-pending-") }
                    val wasAtBottom = messages.isEmpty() || listState.layoutInfo.let { info ->
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        last >= messages.size - 2
                    }
                    // Optimistic mesajları kaldır, gerçek mesajları birleştir
                    val confirmed = (messages.filter { !it.id.startsWith("pending-") && !it.id.startsWith("img-pending-") } + newMsgs)
                        .distinctBy { it.id }
                        .sortedBy { it.timestamp }
                    val withPending = if (hasPending)
                        confirmed + messages.filter { it.id.startsWith("pending-") || it.id.startsWith("img-pending-") }
                    else confirmed
                    messages = withPending
                    MessageStore.set(serverId, confirmed)
                    if (wasAtBottom) listState.animateScrollToItem(withPending.size - 1)
                }
            } catch (_: Exception) {}
            delay(3_000)
        }
    }

// ── Sunucuya girince en son mesajdan başla (görseller/yerleşim oturana dek birkaç kez sabitle) ──    LaunchedEffect(serverId) {        snapshotFlow { messages.size }.first { it > 0 }        repeat(4) {            listState.scrollToItem((messages.size - 1).coerceAtLeast(0))            delay(250)        }    }
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

    // ── Konuşma göstergesi — her 100ms güncelle ─────────────────────────────
    LaunchedEffect(Unit, "speaking") {
        while (true) {
            delay(100L)
            val allHashSet = latestChannelUsers.values.flatten()
                .map { it.uid.hashCode() }
                .toMutableSet()
            allHashSet += currentUser.uid.hashCode()
            speakingSet = allHashSet.filter { VoiceEngine.isSpeaking(it) }.toSet()
        }
    }

    // ── Ses bağlantı durumunu üst katmana bildir (arka planda canlı tutmak için) ──
    LaunchedEffect(connectedChannelId) { onVoiceConnectedChange(connectedChannelId != null) }

    // ── Ses kanalı kronometresi ────────────────────────────────────────
    LaunchedEffect(connectedChannelId) {
        if (connectedChannelId != null) {
            connectedSinceMs = System.currentTimeMillis()
        } else {
            connectedSinceMs = null
            voiceTimerStr = ""
        }
    }
    LaunchedEffect(Unit, "voiceTimer") {
        while (true) {
            delay(1000L)
            val since = connectedSinceMs
            if (since != null) {
                val elapsed = (System.currentTimeMillis() - since) / 1000L
                voiceTimerStr = "%d:%02d".format(elapsed / 60, elapsed % 60)
            }
        }
    }

    // ── Cleanup on dispose ────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            val cid     = latestConnectedId
            val prev    = latestChannelUsers
            val uid     = currentUser.uid
            val token   = currentUser.idToken
            // Yalnızca bu ekran ses oturumunu tutuyorsa durdur (başka sunucu devralmış olabilir)
            if (cid != null) {
                ScreenShareManager.stop()
                ScreenShareManager.stopReceiverFull()
                VoiceEngine.stop()
                if (VoiceSession.ownerServerId == serverId) {
                    VoiceSession.ownerServerId = null
                    VoiceSession.disconnect = null
                }
            }
            // runBlocking kullanarak JVM kapanmadan önce cleanup tamamlansın
            kotlinx.coroutines.runBlocking {
                if (cid != null) {
                    runCatching {
                        FirestoreClient.setChannelActiveUsers(
                            serverId, cid,
                            (prev[cid] ?: emptyList()).filter { it.uid != uid },
                            token,
                        )
                    }
                    runCatching { FirestoreClient.removeVoicePeer(serverId, uid, token) }
                }
                runCatching { FirestoreClient.deletePresence(serverId, uid, token) }
            }
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
                            ScreenShareManager.stop()
                            ScreenShareManager.stopReceiverFull()
                            isScreenSharing = false
                            showPiP = false
                            VoiceEngine.stop()
                            connectedChannelId = null
                        }
                        wasKicked = true
                    }
                }
            }
        }
    }

    // ── Leave channel (suspend) — hem manuel çıkış hem başka sunucuya geçişte kullanılır ──
    suspend fun leaveChannelSuspend() {
        val cid = connectedChannelId ?: return
        try {
            FirestoreClient.setChannelActiveUsers(
                serverId, cid,
                (channelUsers[cid] ?: emptyList()).filter { it.uid != currentUser.uid },
                currentUser.idToken,
            )
        } catch (_: Exception) {}
        runCatching { FirestoreClient.removeVoicePeer(serverId, currentUser.uid, currentUser.idToken) }
        ScreenShareManager.stop()
        ScreenShareManager.stopReceiverFull()
        isScreenSharing = false
        showPiP = false
        VoiceEngine.stop()
        connectedChannelId = null
        if (VoiceSession.ownerServerId == serverId) {
            VoiceSession.ownerServerId = null
            VoiceSession.disconnect = null
        }
    }

    // ── Join channel ──────────────────────────────────────────────────────────
    fun joinChannel(ch: VoiceChannel) {
        if (connectedChannelId == ch.id) {
            // Zaten bu ses kanalında bağlıyız — ses kanalı görünümüne geç
            showTextChannel = false
            selectedChannelId = ch.id
            return
        }
        joiningChannelId = ch.id
        scope.launch {
            // Başka bir sunucuda ses bağlantısı varsa önce onu kapat
            val otherOwner = VoiceSession.ownerServerId
            if (otherOwner != null && otherOwner != serverId) {
                runCatching { VoiceSession.disconnect?.invoke() }
                if (VoiceSession.ownerServerId == otherOwner) {
                    VoiceSession.ownerServerId = null
                    VoiceSession.disconnect = null
                }
            }
            val prevId = connectedChannelId
            if (prevId != null) {
                try {
                    val updatedPrev = (channelUsers[prevId] ?: emptyList()).filter { it.uid != currentUser.uid }
                    FirestoreClient.setChannelActiveUsers(serverId, prevId, updatedPrev, currentUser.idToken)
                    channelUsers = channelUsers + (prevId to updatedPrev)
                } catch (_: Exception) {}
                // Eski kanaldan ses bağlantısını kes
                runCatching { FirestoreClient.removeVoicePeer(serverId, currentUser.uid, currentUser.idToken) }
                ScreenShareManager.stop()
                ScreenShareManager.stopReceiverFull()
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
            joiningChannelId   = null
            selectedChannelId  = ch.id
            showTextChannel    = false   // ses kanalına geçildiğinde # genel deselect
            VoiceSession.ownerServerId = serverId
            VoiceSession.disconnect    = { leaveChannelSuspend() }
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
            // Ekran paylaşımı alıcısını başlat (yayın yoksa boşta kalır)
            ScreenShareManager.startReceiver()
        }
    }

    // ── Leave channel ─────────────────────────────────────────────────────────
    fun leaveChannel() {
        if (connectedChannelId == null) return
        scope.launch { leaveChannelSuspend() }
    }

    // ── Send image ────────────────────────────────────────────────────────────
    fun sendImage() {
        if (sendingImage) return
        scope.launch(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, "Dosya Seç (görsel, GIF, video…)", FileDialog.LOAD)
            dialog.isVisible = true
            val selectedFile = dialog.directory?.let { dir ->
                dialog.file?.let { name -> java.io.File(dir, name) }
            } ?: return@launch
            if (!selectedFile.exists()) return@launch
            withContext(Dispatchers.Main) { sendingImage = true }
            try {
                // Küçük görseller mesaja gömülür, video/büyük dosyalar parçalı yüklenir
                val attUrl = com.furcord.download.Attachments.prepare(selectedFile, currentUser.idToken)
                val now = System.currentTimeMillis()
                val optimistic = ChatMessage(
                    id = "img-pending-$now",
                    uid = currentUser.uid, username = displayNickname,
                    photoURL = currentUser.photoURL, text = "",
                    timestamp = now, imageUrl = attUrl,
                )
                withContext(Dispatchers.Main) { messages = messages + optimistic }
                FirestoreClient.sendMessage(
                    serverId = serverId, uid = currentUser.uid,
                    username = displayNickname, photoURL = currentUser.photoURL,
                    text = "", idToken = currentUser.idToken,
                    imageUrl = attUrl,
                )
                val fetched = FirestoreClient.listMessages(serverId, currentUser.idToken)
                withContext(Dispatchers.Main) {
                    if (fetched.isNotEmpty()) { messages = fetched; MessageStore.set(serverId, fetched) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages = messages.filter { !it.id.startsWith("img-pending-") }
                    attachError = e.message ?: "Dosya gönderilemedi."
                }
            }
            withContext(Dispatchers.Main) { sendingImage = false }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────
    fun sendMessage() {
        val txt = messageText.trim()
        if (txt.isEmpty() || sendingMsg) return
        val reply = replyingTo
        // Optimistic: mesajı hemen listeye ekle
        val now = System.currentTimeMillis()
        val optimistic = ChatMessage(
            id          = "pending-$now",
            uid         = currentUser.uid,
            username    = displayNickname,
            photoURL    = currentUser.photoURL,
            text        = txt,
            timestamp   = now,
            replyToId   = reply?.id   ?: "",
            replyToUser = reply?.username ?: "",
            replyToText = reply?.text ?: "",
        )
        messages = messages + optimistic
        messageText = ""
        replyingTo  = null
        scope.launch { listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0)) }
        scope.launch {
            sendingMsg = true
            try {
                FirestoreClient.sendMessage(
                    serverId    = serverId,
                    uid         = currentUser.uid,
                    username    = displayNickname,
                    photoURL    = currentUser.photoURL,
                    text        = txt,
                    idToken     = currentUser.idToken,
                    replyToId   = reply?.id   ?: "",
                    replyToUser = reply?.username ?: "",
                    replyToText = reply?.text?.take(120) ?: "",
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

    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {

        // ── Left sidebar ──────────────────────────────────────────────────────
        Box {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(AppColors.BgSidebar),
        ) {
            // Server name header
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Sunucu ikonu — görsel varsa daire, yoksa harf fallback
                AsyncServerIcon(
                    imageUrl = serverImageUrl,
                    name     = serverName,
                    size     = 30.dp,
                )
                Text(
                        text = serverName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                )
                // Notification bell
                NotificationBell(
                    currentUser = currentUser,
                    hasUpdate = hasUpdate,
                    onJoinServer = { sid, sname ->
                        com.furcord.auth.RecentServers.save(sid, sname)
                        onJoinServer?.invoke(sid, sname)
                    },
                )
             
                // Invite button — yalnızca sunucu sahibi
                if (isOwner) {
                    IconButton(onClick = { showInviteDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.PersonAddAlt, "Davet", tint = AppColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
                // Server settings (only owner)
                if (isOwner) {
                    IconButton(onClick = { showServerSettingsDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Settings, "Ayarlar", tint = AppColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = AppColors.Outline)

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
                    color = AppColors.TextMuted,
                    modifier = Modifier.weight(1f),
                    letterSpacing = 0.8.sp,
                )
            }

            // # genel satırı
            val textChInteraction = remember { MutableInteractionSource() }
            val textChHovered by textChInteraction.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .hoverable(textChInteraction)
                    .background(
                        when {
                            showTextChannel -> AppColors.BgActive
                            textChHovered   -> AppColors.BgElevated
                            else            -> Color.Transparent
                        }
                    )
                    .clickable { showTextChannel = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "#",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showTextChannel) AppColors.TextPrimary else AppColors.TextMuted,
                )
                Text(
                    text = "genel",
                    fontSize = 15.sp,
                    color = if (showTextChannel) AppColors.TextPrimary else AppColors.TextMuted,
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
                    color = AppColors.TextMuted,
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
                        speakingSet      = speakingSet,
                        timerStr         = if (connectedChannelId == ch.id) voiceTimerStr else null,
                    )
                }
                if (channels.isEmpty() && !loading) {
                    item {
                        Text(
                            text = "Henüz kanal yok.",
                            fontSize = 13.sp,
                            color = AppColors.TextMuted,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }

            // Voice connected bar
            if (connectedChannel != null) {
                HorizontalDivider(color = AppColors.Outline)
                VoiceConnectedBar(
                    channelName  = connectedChannel.name,
                    onDisconnect = { leaveChannel() },
                )
            }

            // Kullanıcı kartı
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(Radius.Medium))
                .background(AppColors.BgDark)
                .padding(horizontal = 8.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UserAvatar(
                displayName = displayName, photoURL = currentUser.photoURL, size = 30,
                modifier = Modifier.clickable { showProfileDialog = true },
            )
            Column(Modifier.weight(1f).clickable { showProfileDialog = true }) {
                Text(displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Çevrimiçi", fontSize = 11.sp, color = AppColors.Online)
            }
            IconButton(onClick = { isMuted = !isMuted }, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic, "Mikrofon",
                    tint = if (isMuted) AppColors.Danger else AppColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { isDeafened = !isDeafened }, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (isDeafened) Icons.Outlined.HeadsetOff else Icons.Outlined.Headset, "Kulaklık",
                    tint = if (isDeafened) AppColors.Danger else AppColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
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
                onWatchStream = if (broadcastingUidHash != 0 && sidebarCtxUser.uid.hashCode() == broadcastingUidHash) {{
                    contextMenuUser = null
                    ScreenShareManager.startReceiver()
                    isAttemptingReceive = true
                    showPiP = true
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
                    .background(AppColors.BgMain),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("#", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextMuted)
                    Text(
                        text = "genel",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )
                }
                ChatPanel(
                    serverId        = serverId,
                    messages        = messages,
                    currentUser     = currentUser,
                    listState       = listState,
                    messageText     = messageText,
                    onTextChange    = { messageText = it },
                    onSend          = { sendMessage() },
                    onSendImage     = { sendImage() },
                    sendingImage    = sendingImage,
                    replyingTo      = replyingTo,
                    onReply         = { replyingTo = it },
                    onReplyClear    = { replyingTo = null },
                    modifier        = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            MemberPanel(users = presenceUsers, currentUid = currentUser.uid)
        } else {
            // ── Ses kanalı görünümü — sadece kullanıcı grid, mesaj yok ────────
            val viewChannelId = connectedChannelId ?: selectedChannelId
            val viewChannel   = channels.find { it.id == viewChannelId }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(AppColors.BgMain),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🔊", fontSize = 18.sp)
                    Text(
                        text = viewChannel?.name ?: "—",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    // Ekran paylaşımı butonu — sadece bağlı kanaldayken görünür
                    if (connectedChannelId != null) {
                        FilledTonalButton(
                            onClick = {
                                if (isScreenSharing) {
                                    ScreenShareManager.stop()
                                    isScreenSharing = false
                                } else {
                                    showSharePicker = true   // kaynak + kalite seç
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isScreenSharing) Color(0xFF3A2022) else AppColors.Accent,
                                contentColor   = if (isScreenSharing) AppColors.Danger else Color.White,
                            ),
                            shape    = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text       = if (isScreenSharing) "🔴 Paylaşımı Durdur" else "🖥️ Ekranı Paylaş",
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                HorizontalDivider(color = AppColors.Outline)
                // ── Ekran paylaşımı görünümü (yayıncı ya da izleyici) ─────────
                // showPiP=true ise inline görünüm gizlenir, PiPStreamWindow devralır
                if (!showPiP && (isScreenSharing || isAttemptingReceive)) {
                    StreamViewerComposable(
                        isSelfView     = isScreenSharing,
                        peerVolume     = 1f,
                        onVolumeChange = {},
                        onStop         = {
                            if (isScreenSharing) {
                                ScreenShareManager.stop()
                                isScreenSharing = false
                            } else {
                                ScreenShareManager.stopReceiver()
                                isAttemptingReceive = false
                            }
                        },
                        onTogglePiP    = { showPiP = true },
                        modifier       = Modifier.weight(0.55f).fillMaxWidth(),
                    )
                    HorizontalDivider(color = AppColors.Outline)
                } else if (showPiP && isAttemptingReceive) {
                    // PiP modunda izleyici — inline view gizlenir, dönen daire gösterilmez
                }

                val active = channelUsers[viewChannelId] ?: emptyList()
                if (active.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Henüz kimse yok.\nSes kanalına katıl!",
                            fontSize = 14.sp,
                            color = AppColors.TextMuted,
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
                                    val isSpeakingUser = speakingSet.contains(u.uid.hashCode())
                                    Box(modifier = Modifier.size(82.dp), contentAlignment = Alignment.Center) {
                                        if (isSpeakingUser) {
                                            Box(
                                                modifier = Modifier
                                                    .size(82.dp)
                                                    .border(2.dp, AppColors.Online, CircleShape)
                                            )
                                        }
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
                                    }
                                    Text(
                                        text = if (isSelf) "${u.username} (sen)" else u.username,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelf) AppColors.Online else AppColors.TextPrimary,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                    // CANLI rozeti — bu kullanıcı ekran paylaşıyor
                                    if (broadcastingUidHash != 0 && u.uid.hashCode() == broadcastingUidHash) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AppColors.Danger)
                                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "● CANLI",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                letterSpacing = 0.5.sp,
                                            )
                                        }
                                    }
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
                                        onWatchStream = if (broadcastingUidHash != 0 && u.uid.hashCode() == broadcastingUidHash) {{
                                            contextMenuUser = null
                                            ScreenShareManager.startReceiver()
                                            isAttemptingReceive = true
                                            showPiP = true
                                        }} else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }   // end Row
    }   // end Box(fillMaxSize)
}

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
    onSendImage: () -> Unit = {},
    sendingImage: Boolean = false,
    replyingTo: ChatMessage? = null,
    onReply: (ChatMessage) -> Unit = {},
    onReplyClear: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Message list
        LazyColumn(
            state   = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp),
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
                            color = AppColors.TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            itemsIndexed(messages) { index, msg ->
                val prev = messages.getOrNull(index - 1)
                val isGrouped = prev != null &&
                    prev.uid == msg.uid &&
                    (msg.timestamp - prev.timestamp) < 5 * 60_000L &&
                    msg.replyToId.isBlank()
                MessageRow(
                    msg       = msg,
                    isSelf    = msg.uid == currentUser.uid,
                    isGrouped = isGrouped,
                    onReply   = { onReply(it) },
                    onCopy    = { m ->
                        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        cb.setContents(java.awt.datatransfer.StringSelection(m.text), null)
                    },
                    idToken    = currentUser.idToken,
                    onDownload = { m -> com.furcord.download.DownloadManager.downloadAttachment(m.imageUrl, currentUser.idToken) },
                )
            }
        }
  

        // ── Reply önizleme barı ───────────────────────────────────────────────
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.BgSidebar)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .background(AppColors.Accent),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text       = replyingTo!!.username,
                        fontSize   = 11.sp,
                        color      = AppColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text     = replyingTo!!.text.take(60),
                        fontSize = 12.sp,
                        color    = AppColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onReplyClear, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector        = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Yanıtı iptal et",
                        tint               = AppColors.TextMuted,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }
        }

        // ── Modern pill input ──────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.BgMain)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pill-shaped input container
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.Large))
                    .background(AppColors.BgInput)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Resim ekleme butonu (input içinde solda)
                IconButton(
                    onClick  = onSendImage,
                    enabled  = !sendingImage,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (sendingImage) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = AppColors.Accent, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Image, "Resim", tint = AppColors.TextMuted, modifier = Modifier.size(20.dp))
                    }
                }

                BasicTextField(
                    value         = messageText,
                    onValueChange = onTextChange,
                    modifier      = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed
                            ) {
                                onSend(); true
                            } else false
                        },
                    maxLines      = 4,
                    textStyle     = androidx.compose.ui.text.TextStyle(
                        color    = AppColors.TextPrimary,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (messageText.isEmpty()) {
                                Text(
                                    text     = "#genel kanalına mesaj gönder",
                                    fontSize = 14.sp,
                                    color    = AppColors.TextSubtle,
                                )
                            }
                            inner()
                        }
                    },
                )

                // Gönder butonu (input içinde sağda)
                IconButton(
                    onClick  = onSend,
                    enabled  = messageText.isNotBlank(),
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (messageText.isNotBlank()) AppColors.Accent
                            else AppColors.BgElevated
                        ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send, "Gönder",
                        tint = if (messageText.isNotBlank()) Color.White else AppColors.TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
@Composable
private fun MemberPanel(users: List<ActiveUser>, currentUid: String) {
    Column(
        Modifier.width(240.dp).fillMaxHeight()
            .background(AppColors.BgSidebar)
            .padding(top = 20.dp, start = 12.dp, end = 12.dp)
    ) {
        Text(
            "ÇEVRİMİÇİ — ${users.size}",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = AppColors.TextSubtle, letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(users) { u ->
                val isSelf = u.uid == currentUid
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.Small))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box {
                        UserAvatar(displayName = u.username, photoURL = u.photoURL, size = 30)
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(10.dp)
                                .clip(CircleShape).background(AppColors.BgSidebar).padding(2.dp)
                                .clip(CircleShape).background(AppColors.Online)
                        )
                    }
                    Text(
                        if (isSelf) "${u.username} (sen)" else u.username,
                        fontSize = 13.sp,
                        color = if (isSelf) AppColors.Online else AppColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
