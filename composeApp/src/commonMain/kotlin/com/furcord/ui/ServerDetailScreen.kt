package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                                        .border(3.dp, Color(0xFF23A55A), CircleShape)
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
                                isSelf || isSpeaking -> Color(0xFF23A55A)
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
// Chat message row — Discord-style grouping
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MessageRow(
    msg: ChatMessage,
    isSelf: Boolean,
    isGrouped: Boolean = false,
    onReply: (ChatMessage) -> Unit = {},
    onCopy:  (ChatMessage) -> Unit = {},
) {
    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    var showContextMenu by remember { mutableStateOf(false) }
    var menuOffset      by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(if (hovered) AppColors.BgElevated.copy(alpha = 0.45f) else Color.Transparent)
            .pointerInput(msg.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Secondary button = right click
                        if (event.type == PointerEventType.Press &&
                            event.buttons.isSecondaryPressed
                        ) {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) {
                                menuOffset = androidx.compose.ui.unit.DpOffset(
                                    x = (pos.x / density).dp,
                                    y = (pos.y / density).dp,
                                )
                            }
                            showContextMenu = true
                        }
                    }
                }
            }
            .padding(
                start  = 16.dp,
                end    = 16.dp,
                top    = if (isGrouped) 1.dp else 8.dp,
                bottom = 1.dp,
            ),
    ) {
        // ── Reply quote block (yanıt alıntısı) ────────────────────────────────
        if (msg.replyToId.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1B1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(28.dp)
                        .background(AppColors.Accent),
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text       = msg.replyToUser,
                        fontSize   = 11.sp,
                        color      = AppColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text     = msg.replyToText.take(100),
                        fontSize = 12.sp,
                        color    = AppColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        // ── Ana mesaj satırı ──────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.Top,
        ) {
            if (isGrouped && msg.replyToId.isBlank()) {
                Spacer(Modifier.width(36.dp))
            } else {
                Box(modifier = Modifier.size(36.dp).padding(top = 2.dp)) {
                    UserAvatar(
                        displayName = msg.username,
                        photoURL    = msg.photoURL,
                        size        = 36,
                    )
                }
            }

            Column(
                modifier             = Modifier.weight(1f),
                verticalArrangement  = Arrangement.spacedBy(2.dp),
            ) {
                if (!isGrouped || msg.replyToId.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = msg.username,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = if (isSelf) AppColors.SelfName else AppColors.TextPrimary,
                        )
                        Text(
                            text     = timeStr,
                            fontSize = 11.sp,
                            color    = AppColors.TextTimestamp,
                        )
                    }
                }

                if (msg.text.isNotBlank()) {
                    Text(
                        text     = msg.text,
                        fontSize = 14.sp,
                        color    = AppColors.TextSecondary,
                    )
                }

                if (msg.imageUrl.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    AsyncChatImage(msg.imageUrl)
                }
            }

            if (isGrouped && msg.replyToId.isBlank() && hovered) {
                Text(
                    text     = timeStr,
                    fontSize = 10.sp,
                    color    = AppColors.TextTimestamp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }

        // ── Sağ-tık bağlam menüsü ─────────────────────────────────────────────
        DropdownMenu(
            expanded         = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset           = menuOffset,
        ) {
            DropdownMenuItem(
                text    = { Text("↩ Yanıtla") },
                onClick = { showContextMenu = false; onReply(msg) },
            )
            DropdownMenuItem(
                text    = { Text("📋 Kopyala") },
                onClick = { showContextMenu = false; onCopy(msg) },
            )
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
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val key  = url.hashCode().toString()
                val file = java.io.File(imgCacheDir, key)
                val bytes = if (file.exists()) {
                    file.readBytes()
                } else {
                    java.net.URI.create(url).toURL().readBytes().also { file.writeBytes(it) }
                }
                bmp = androidx.compose.ui.res.loadImageBitmap(bytes.inputStream())
            } catch (_: Exception) {}
        }
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap             = bmp!!,
            contentDescription = "Görsel",
            modifier           = Modifier.widthIn(max = 300.dp).heightIn(max = 250.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF5865F2))
    }
}

/**
 * Sunucu ikonu — imageUrl doluysa ağdan yükler (CircleShape), boşsa harf+renk fallback.
 */
@Composable
private fun AsyncServerIcon(imageUrl: String, name: String, size: androidx.compose.ui.unit.Dp) {
    if (imageUrl.isNotEmpty()) {
        var bmp by remember(imageUrl) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        LaunchedEffect(imageUrl) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = java.net.URI.create(imageUrl).toURL().readBytes()
                    bmp = androidx.compose.ui.res.loadImageBitmap(bytes.inputStream())
                }
            }
        }
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
        containerColor   = Color(0xFF2B2D31),
        title = { Text("Sunucu Ayarları", fontWeight = FontWeight.Bold, color = Color(0xFFF2F3F5)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sunucu: $serverName", fontSize = 13.sp, color = Color(0xFF8E9297))
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
                                val ext   = selectedFile.extension.lowercase().ifEmpty { "jpg" }
                                val mime  = if (ext == "png") "image/png" else "image/jpeg"
                                val path  = "server_icons/${serverId}_${System.currentTimeMillis()}.$ext"
                                val result = FirestoreClient.uploadToStorage(
                                    storagePath = path,
                                    fileBytes   = selectedFile.readBytes(),
                                    mimeType    = mime,
                                    idToken     = currentUser.idToken,
                                )
                                FirestoreClient.updateServerImage(serverId, result.downloadUrl, currentUser.idToken)
                                withContext(kotlinx.coroutines.Dispatchers.Main) { saved = true; status = "" }
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) { error = "Hata: ${e.message}"; status = "" }
                            } finally {
                                withContext(kotlinx.coroutines.Dispatchers.Main) { saving = false }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5865F2)),
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
                if (saved) Text("✅ Görsel güncellendi!", fontSize = 12.sp, color = Color(0xFF23A55A))
                if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = Color(0xFFED4245))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) {
                Text("Kapat", color = Color(0xFF8E9297))
            }
        },
    )
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
    var replyingTo    by remember { mutableStateOf<ChatMessage?>(null) }
    var sendingMsg    by remember { mutableStateOf(false) }
    var sendingImage  by remember { mutableStateOf(false) }
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
    var serverImageUrl    by remember { mutableStateOf("") }
    // Konuşma göstergesi — uidHash kümesi
    var speakingSet       by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Ses kanalı kronometresi
    var connectedSinceMs  by remember { mutableStateOf<Long?>(null) }
    var voiceTimerStr     by remember { mutableStateOf("") }
    // Ekran paylaşımı
    var isScreenSharing   by remember { mutableStateOf(false) }
    var showPiP           by remember { mutableStateOf(false) }
    val receiverFrame     by ScreenShareManager.receiverFrame.collectAsState()

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

    // PiP (küçük yapışkan pencere) — yayın aktifken her iki sekme görünümünde de erişilebilir
    if (showPiP) {
        PiPStreamWindow(
            isSelfView     = isScreenSharing,
            peerVolume     = 1f,
            onVolumeChange = {},
            onExpand       = { showPiP = false },
            onClose        = {
                showPiP = false
                if (isScreenSharing) {
                    ScreenShareManager.stop()
                    isScreenSharing = false
                } else {
                    ScreenShareManager.stopReceiver()
                }
            },
        )
    }

    // ── Sunucu görsel URL'si — tek seferlik çekme ────────────────────────────
    LaunchedEffect(serverId, "image") {
        runCatching {
            serverImageUrl = FirestoreClient.getServerImageUrl(serverId, currentUser.idToken)
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
            ScreenShareManager.stop()
            ScreenShareManager.stopReceiver()
            VoiceEngine.stop()
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
                            ScreenShareManager.stopReceiver()
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
                ScreenShareManager.stop()
                ScreenShareManager.stopReceiver()
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
            // Ekran paylaşımı alıcısını başlat (yayın yoksa boşta kalır)
            ScreenShareManager.startReceiver()
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
            ScreenShareManager.stop()
            ScreenShareManager.stopReceiver()
            isScreenSharing = false
            showPiP = false
            VoiceEngine.stop()
            connectedChannelId = null
        }
    }

    // ── Send image ────────────────────────────────────────────────────────────
    fun sendImage() {
        if (sendingImage) return
        scope.launch(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, "Resim Seç", FileDialog.LOAD)
            dialog.file = "*.jpg;*.jpeg;*.png;*.webp;*.gif"
            dialog.isVisible = true
            val selectedFile = dialog.directory?.let { dir ->
                dialog.file?.let { name -> java.io.File(dir, name) }
            } ?: return@launch
            if (!selectedFile.exists()) return@launch
            withContext(Dispatchers.Main) { sendingImage = true }
            try {
                val ext  = selectedFile.extension.lowercase().ifEmpty { "jpg" }
                val mime = when (ext) { "png" -> "image/png"; "gif" -> "image/gif"; else -> "image/jpeg" }
                val path = "chat_images/${serverId}_${System.currentTimeMillis()}.$ext"
                val result = FirestoreClient.uploadToStorage(
                    storagePath = path,
                    fileBytes   = selectedFile.readBytes(),
                    mimeType    = mime,
                    idToken     = currentUser.idToken,
                )
                val now = System.currentTimeMillis()
                val optimistic = ChatMessage(
                    id = "img-pending-$now",
                    uid = currentUser.uid, username = displayNickname,
                    photoURL = currentUser.photoURL, text = "",
                    timestamp = now, imageUrl = result.downloadUrl,
                )
                withContext(Dispatchers.Main) { messages = messages + optimistic }
                FirestoreClient.sendMessage(
                    serverId = serverId, uid = currentUser.uid,
                    username = displayNickname, photoURL = currentUser.photoURL,
                    text = "", idToken = currentUser.idToken,
                    imageUrl = result.downloadUrl,
                )
                val fetched = FirestoreClient.listMessages(serverId, currentUser.idToken)
                withContext(Dispatchers.Main) {
                    if (fetched.isNotEmpty()) { messages = fetched; MessageStore.set(serverId, fetched) }
                }
            } catch (_: Exception) {}
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
                // Sunucu ikonu — görsel varsa daire, yoksa harf fallback
                AsyncServerIcon(
                    imageUrl = serverImageUrl,
                    name     = serverName,
                    size     = 30.dp,
                )
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
                // Server settings (only owner)
                if (isOwner) {
                    IconButton(onClick = { showServerSettingsDialog = true }, modifier = Modifier.size(28.dp)) {
                        Text("⚙", fontSize = 14.sp, color = Color(0xFF8E9297))
                    }
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
                        speakingSet      = speakingSet,
                        timerStr         = if (connectedChannelId == ch.id) voiceTimerStr else null,
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
                // Yayın bildirimi — metin kanalındayken yayın devam ediyor
                if (receiverFrame != null || isScreenSharing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF5865F2))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment   = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text     = if (isScreenSharing) "🔴 Ekranınızı paylaşıyorsunuz" else "🖥️ Birisi ekranını paylaşıyor",
                            color    = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPiP = true }) {
                            Text("📺 Küçük Pencere", color = Color.White, fontSize = 12.sp)
                        }
                        TextButton(onClick = { showTextChannel = false }) {
                            Text("▶ İzle", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
                                    ScreenShareManager.start()
                                    isScreenSharing = true
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isScreenSharing) Color(0xFF3A2022) else Color(0xFF5865F2),
                                contentColor   = if (isScreenSharing) Color(0xFFED4245) else Color.White,
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
                HorizontalDivider(color = Color(0xFF1E1F22))
                // ── Ekran paylaşımı görünümü (yayıncı ya da izleyici) ─────────
                // showPiP=true ise inline görünüm gizlenir, PiPStreamWindow devralır
                if (!showPiP && (isScreenSharing || receiverFrame != null)) {
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
                            }
                        },
                        onTogglePiP    = { showPiP = true },
                        modifier       = Modifier.weight(0.55f).fillMaxWidth(),
                    )
                    HorizontalDivider(color = Color(0xFF1E1F22))
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
                                    val isSpeakingUser = speakingSet.contains(u.uid.hashCode())
                                    Box(modifier = Modifier.size(82.dp), contentAlignment = Alignment.Center) {
                                        if (isSpeakingUser) {
                                            Box(
                                                modifier = Modifier
                                                    .size(82.dp)
                                                    .border(4.dp, Color(0xFF23A55A), CircleShape)
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
                )
            }
        }

        HorizontalDivider(color = AppColors.Outline)

        // ── Reply önizleme barı ───────────────────────────────────────────────
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B2D31))
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pill-shaped input container
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
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
                        Text("🖼️", fontSize = 16.sp)
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
                    Text(
                        text     = "➤",
                        fontSize = 14.sp,
                        color    = if (messageText.isNotBlank()) Color.White else AppColors.TextMuted,
                    )
                }
            }
        }
    }
}