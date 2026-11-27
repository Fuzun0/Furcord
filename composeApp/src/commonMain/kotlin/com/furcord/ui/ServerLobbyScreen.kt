package com.furcord.ui

import androidx.compose.foundation.background
import com.furcord.ui.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.FriendEntry
import com.furcord.auth.FirestoreClient
import com.furcord.auth.RecentServers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PowerSettingsNew



// ─── Renk paleti ─────────────────────────────────────────────────────────────
private val BG           = AppColors.BgMain
private val SURFACE      = AppColors.BgSidebar
private val SURFACE2     = AppColors.BgElevated
private val OUTLINE      = AppColors.Outline
private val MUTED        = AppColors.TextMuted
private val TEXT         = AppColors.TextPrimary
private val TEXT2        = AppColors.TextSecondary
private val ACCENT       = AppColors.Accent
private val GREEN        = AppColors.Online

@Composable
fun ServerLobbyScreen(
    currentUser: AuthUser,
    hasUpdate: Boolean = false,
    isWindowFocused: Boolean = true,
    latestVersionNotes: String = "",
    latestVersionTag: String = "",
    onJoinServer: (serverId: String, serverName: String) -> Unit,
    onSignOut: () -> Unit,
    onUserUpdated: (newDisplayName: String, newPhotoURL: String) -> Unit = { _, _ -> },
    onOpenDm: ((uid: String, name: String) -> Unit)? = null,
    onOpenDownloader: () -> Unit = {},

) {
    val scope = rememberCoroutineScope()
    // rememberUpdatedState: coroutine içinden her iterásyonda güncel değeri okur
    val latestFocused by rememberUpdatedState(isWindowFocused)


    var showProfileDialog by remember { mutableStateOf(false) }
    var deletingServerId  by remember { mutableStateOf<String?>(null) }
    var showPatchNotes    by remember { mutableStateOf(false) }

    var recentServers    by remember { mutableStateOf(RecentServers.load()) }
    var reconnectLoading by remember { mutableStateOf<String?>(null) }
    // Sunucu ID → görsel URL önbelleği (reaktif — UI otomatik güncellenir)
    val serverImages  = ServerIcons.urls

    // Sol panel — arkadaş listesi
    var friends       by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var friendsOpen   by remember { mutableStateOf(true) }
    var showAddFriend by remember { mutableStateOf(false) }

    // Firestore'dan oluşturulan sunucuları çek (var olmayanları SONA ekler, sırayı bozmaz)
    LaunchedEffect(currentUser.uid) {
        try {
            val myServers = FirestoreClient.getMyServers(currentUser.uid, currentUser.idToken)
            myServers.forEach { (id, name) -> RecentServers.addIfAbsent(id, name) }
            recentServers = RecentServers.load()
            // Her sunucunun görsel URL'sini arka planda paralel çek
            recentServers.forEach { (sid, _) ->
                scope.launch {
                    runCatching {
                        val url = FirestoreClient.getServerImageUrl(sid, currentUser.idToken)
                        if (url.isNotEmpty()) serverImages[sid] = url
                    }
                }
            }
        } catch (_: Exception) {}
        // Arkadaş listesini odağa göre yenile: odakta 30s, arka planda 3 dakika
        while (isActive) {
            runCatching { friends = FirestoreClient.listFriends(currentUser.uid, currentUser.idToken) }
            delay(if (latestFocused) 30_000L else 3 * 60_000L)
        }
    }

    // Arkadaş ekleme diyalogu
    if (showAddFriend) {
        FriendAddDialog(
            currentUser = currentUser,
            onStartDm   = { uid, name ->
                showAddFriend = false
                onOpenDm?.invoke(uid, name)
            },
            onDismiss   = { showAddFriend = false },
        )
    }

    fun connectToServer(id: String, name: String) {
        RecentServers.save(id, name)
        recentServers = RecentServers.load()
        onJoinServer(id, name)
    }

    var showAddServer by remember { mutableStateOf(false) }

    // ── Sunucu ekle penceresi (oluştur / katıl) ──────────────────────────────
    if (showAddServer) {
        AddServerDialog(
            onDismiss = { showAddServer = false },
            onSearch  = { code ->
                FirestoreClient.getServerByInvite(code.uppercase(), currentUser.idToken)
                    ?: FirestoreClient.getServerByInvite(code, currentUser.idToken)
            },
            onCreate  = { name ->
                try {
                    // 1 sunucu limiti: kullanıcının zaten sunucusu var mı?
                    val existing = FirestoreClient.getMyServers(currentUser.uid, currentUser.idToken)
                    if (existing.isNotEmpty()) {
                        "Zaten bir sunucun var: \"${existing.first().second}\""
                    } else {
                        val id = FirestoreClient.createServer(name, currentUser.uid, currentUser.idToken)
                        // Her sunucuda tek sabit ses kanalı: "Genel". Diğerlerini sahip oluşturur.
                        FirestoreClient.ensureDefaultVoiceChannel(id, currentUser.idToken)
                        FirestoreClient.upsertMember(id, currentUser.uid,
                            currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email } },
                            currentUser.photoURL, currentUser.idToken)
                        connectToServer(id, name)
                        null
                    }
                } catch (e: Exception) {
                    "Oluşturulamadı: ${e.message}"
                }
            },
            onJoin    = { id, name -> showAddServer = false; connectToServer(id, name) },
        )
    }

    if (showProfileDialog) {
        ProfileSettingsDialog(
            currentUser = currentUser,
            onDismiss   = { showProfileDialog = false },
            onSignOut   = { showProfileDialog = false; onSignOut() },
            onSaved     = { newName, newPhoto ->
                onUserUpdated(newName, newPhoto)
                showProfileDialog = false
            },
        )
    }

    // Yama Notları popup
    if (showPatchNotes) {
        AlertDialog(
            onDismissRequest = { showPatchNotes = false },
            title = {
                Text(
                    "📋 Yama Notları  —  v${latestVersionTag.ifEmpty { "?" }}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val lines = latestVersionNotes
                            .lines()
                            .map { it.trimStart('-', '*', '•', ' ').trim() }
                            .filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) {
                            lines.forEach { line ->
                                Text("• $line", style = MaterialTheme.typography.bodySmall, color = TEXT2)
                            }
                        } else {
                            Text(
                                "Bu sürümde çeşitli iyileştirmeler yapıldı.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TEXT2,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPatchNotes = false }) { Text("Kapat") }
            },
        )
    }

    // ── Root ─────────────────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().background(BG)) {

        // ── Title bar ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(SURFACE)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FurcordLogoIcon(size = 28.dp)
            Text(
                "Furcord",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TEXT,
                letterSpacing = 0.3.sp,
            )
            // Yama Notları butonu — Furcord yazısının hemen yanında
            if (latestVersionNotes.isNotEmpty()) {
                TextButton(
                    onClick = { showPatchNotes = true },
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        "Yama Notları",
                        fontSize = 12.sp,
                        color = TEXT2,
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            //mp4 / mp3 indirme butonu
            IconButton(
                onClick = onOpenDownloader,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SURFACE2),
            ) {
                Icon(Icons.Default.Download, "Video indirici", tint = TEXT2, modifier = Modifier.size(18.dp))
            }

            // Profil butonu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showProfileDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                UserAvatar(
                    displayName = currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email } },
                    photoURL    = currentUser.photoURL,
                    size        = 32,
                )
                Column {
                    Text(
                        text = currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email.substringBefore("@") } },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TEXT,
                        maxLines = 1,
                    )
                    Text(
                        text = currentUser.email,
                        fontSize = 11.sp,
                        color = MUTED,
                        maxLines = 1,
                    )
                }
            }
            // Bildirim çanı
            NotificationBell(
                currentUser  = currentUser,
                hasUpdate    = hasUpdate,
                onJoinServer = { sid: String, sname: String ->
                    scope.launch {
                        runCatching {
                            FirestoreClient.getServerName(sid, currentUser.idToken)
                            RecentServers.save(sid, sname)
                            onJoinServer(sid, sname)
                        }
                    }
                },
            )
        }

        HorizontalDivider(color = OUTLINE, thickness = 1.dp)

        // ── Body: sol sosyal panel + orta eylemler + sağ sunucular ──────────
        Row(modifier = Modifier.fillMaxSize()) {

            // ── Sol panel — Arkadaşlar & Mesajlar ────────────────────────────
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(SURFACE),
            ) {
                // başlık
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sosyal",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MUTED, letterSpacing = 0.8.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(color = OUTLINE)

                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {

                    // ── Arkadaşlar çekmece ────────────────────────────────
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { friendsOpen = !friendsOpen }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (friendsOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                null, tint = MUTED, modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "ARKADAŞLAR",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = MUTED, letterSpacing = 0.8.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (friends.isNotEmpty()) {
                                Text(
                                    "${friends.size}",
                                    fontSize = 10.sp, color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(OUTLINE)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            // Arkadaş ekle butonu
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ACCENT)
                                    .clickable { showAddFriend = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (friendsOpen) {
                        if (friends.isEmpty()) {
                            item {
                                Text(
                                    "Henüz arkadaş yok.\n+ butonuyla ekleyin.",
                                    color = MUTED, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            items(friends.sortedByDescending { it.isOnline }, key = { it.uid }) { friend ->
                                var showContextMenu by remember { mutableStateOf(false) }
                                Box {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenDm?.invoke(friend.uid, friend.displayName) }
                                            .pointerInput(friend.uid) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        if (event.type == PointerEventType.Press &&
                                                            event.buttons.isSecondaryPressed
                                                        ) {
                                                            showContextMenu = true
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        // Avatar + online/offline dot
                                        Box {
                                            UserAvatar(displayName = friend.displayName, photoURL = "", size = 28)
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .align(Alignment.BottomEnd)
                                                    .background(SURFACE, shape = androidx.compose.foundation.shape.CircleShape)
                                                    .padding(2.dp)
                                                    .background(
                                                        if (friend.isOnline) GREEN else AppColors.TextSubtle,
                                                        shape = androidx.compose.foundation.shape.CircleShape,
                                                    ),
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(friend.displayName, color = TEXT, fontSize = 13.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(friend.furcordId, color = MUTED, fontSize = 10.sp)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded         = showContextMenu,
                                        onDismissRequest = { showContextMenu = false },
                                        containerColor   = AppColors.BgDeepest,
                                    ) {
                                        DropdownMenuItem(
                                            text    = { Text("Mesaj Gönder", color = AppColors.TextSecondary, fontSize = 13.sp) },
                                            onClick = {
                                                onOpenDm?.invoke(friend.uid, friend.displayName)
                                                showContextMenu = false
                                            },
                                        )
                                        HorizontalDivider(color = AppColors.BgSidebar)
                                        DropdownMenuItem(
                                            text    = { Text("🗑 Arkadaşı Kaldır", color = AppColors.Danger, fontSize = 13.sp) },
                                            onClick = {
                                                showContextMenu = false
                                                scope.launch {
                                                    runCatching {
                                                        FirestoreClient.removeFriend(
                                                            currentUser.uid, friend.uid, currentUser.idToken
                                                        )
                                                    }
                                                    friends = friends.filter { it.uid != friend.uid }
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
            Box(Modifier.width(1.dp).fillMaxHeight().background(OUTLINE))
            // ── Orta panel — sunucularım ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sunucularım", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TEXT)
                        Text(
                            if (recentServers.isEmpty()) "Henüz bir sunucun yok" else "${recentServers.size} sunucu",
                            fontSize = 13.sp, color = MUTED,
                        )
                    }
                    Button(
                        onClick = { showAddServer = true },
                        shape = RoundedCornerShape(Radius.Medium),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sunucu ekle", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (recentServers.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Burası şimdilik boş", fontSize = 15.sp, color = TEXT2)
                            Text(
                                "Sunucu oluştur ya da davet koduyla bir sunucuya katıl.",
                                fontSize = 12.sp, color = MUTED,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.Large))
                            .background(SURFACE),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        items(recentServers) { (sid, sname) ->
                            RecentServerRow(
                                sid          = sid,
                                sname        = sname,
                                imageUrl     = serverImages[sid] ?: "",
                                isLoading    = reconnectLoading == sid,
                                anyLoading   = reconnectLoading != null || deletingServerId != null,
                                isDeleting   = deletingServerId == sid,
                                onReconnect  = {
                                    scope.launch {
                                        reconnectLoading = sid
                                        try {
                                            val name = FirestoreClient.getServerName(sid, currentUser.idToken)
                                            connectToServer(sid, name)
                                        } catch (_: Exception) {
                                            connectToServer(sid, sname)
                                        } finally {
                                            reconnectLoading = null
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        deletingServerId = sid
                                        try {
                                            FirestoreClient.deleteServer(sid, currentUser.idToken)
                                        } catch (_: Exception) {}
                                        // Yerel listeden de çıkar
                                        val updated = RecentServers.load().filter { it.first != sid }
                                        val file = java.io.File(System.getProperty("user.home"), ".furcord_servers")
                                        runCatching { file.writeText(updated.joinToString("\n") { "${it.first}\t${it.second}" }) }
                                        recentServers = updated
                                        deletingServerId = null
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text  = text,
        fontSize     = 11.sp,
        fontWeight   = FontWeight.Bold,
        color        = MUTED,
        letterSpacing = 0.9.sp,
    )
}

@Composable
private fun RecentServerRow(
    sid: String,
    sname: String,
    imageUrl: String = "",
    isLoading: Boolean,
    anyLoading: Boolean,
    isDeleting: Boolean,
    onReconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !anyLoading, onClick = onReconnect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Sunucu ikonu — görsel varsa daire göster, yoksa harf fallback
        AsyncServerIcon(imageUrl = imageUrl, name = sname, size = 40.dp)
        // İsim
        Text(
            text     = sname,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color    = TEXT,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Bağlan butonu
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = ACCENT,
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!anyLoading) ACCENT else OUTLINE)
                    .clickable(enabled = !anyLoading, onClick = onReconnect)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    "Bağlan",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                )
            }
        }
        // Sil butonu
        if (isDeleting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppColors.Danger,
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!anyLoading) AppColors.Danger.copy(alpha = 0.15f) else OUTLINE)
                    .clickable(enabled = !anyLoading, onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Outlined.Delete, "Sil", tint = AppColors.Danger, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Sunucu ikonu — imageUrl doluysa ağdan yükler (CircleShape), boşsa harf+renk fallback.
 * Desktop-only: java.net + androidx.compose.ui.res.loadImageBitmap
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
                // Yüklenirken harf göster
                Box(
                    modifier = Modifier.fillMaxSize().background(ACCENT),
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
                .background(ACCENT),
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
