package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.DmConversation
import com.furcord.auth.FriendEntry
import com.furcord.auth.FirestoreClient
import com.furcord.auth.RecentServers
import kotlinx.coroutines.launch

// ─── Arama sonuçları ─────────────────────────────────────────────────────────
private enum class SearchType { PERSON, SERVER }
private data class SearchResult(val type: SearchType, val id: String, val name: String)

// ─── Renk paleti ─────────────────────────────────────────────────────────────
private val BG           = Color(0xFF1E1F22)
private val SURFACE      = Color(0xFF2B2D31)
private val SURFACE2     = Color(0xFF313338)
private val OUTLINE      = Color(0xFF3F4147)
private val MUTED        = Color(0xFF8E9297)
private val TEXT         = Color(0xFFF2F3F5)
private val TEXT2        = Color(0xFFB5BAC1)
private val ACCENT       = Color(0xFF5865F2)
private val GREEN        = Color(0xFF23A55A)

@Composable
fun ServerLobbyScreen(
    currentUser: AuthUser,
    hasUpdate: Boolean = false,
    onJoinServer: (serverId: String, serverName: String) -> Unit,
    onSignOut: () -> Unit,
    onUserUpdated: (newDisplayName: String, newPhotoURL: String) -> Unit = { _, _ -> },
    onOpenDm: ((uid: String, name: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var searchQuery   by remember { mutableStateOf("") }
    var searchLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchError   by remember { mutableStateOf("") }

    var createName    by remember { mutableStateOf("") }
    var createError   by remember { mutableStateOf("") }
    var createLoading by remember { mutableStateOf(false) }

    var showProfileDialog by remember { mutableStateOf(false) }
    var deletingServerId  by remember { mutableStateOf<String?>(null) }

    var recentServers    by remember { mutableStateOf(RecentServers.load()) }
    var reconnectLoading by remember { mutableStateOf<String?>(null) }

    // Sol panel — arkadaş & DM sohbetleri
    var friends       by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var conversations by remember { mutableStateOf<List<DmConversation>>(emptyList()) }
    var friendsOpen   by remember { mutableStateOf(true) }
    var convOpen      by remember { mutableStateOf(true) }
    var showAddFriend by remember { mutableStateOf(false) }

    // Firestore'dan oluşturulan sunucuları çek (var olmayanları SONA ekler, sırayı bozmaz)
    LaunchedEffect(currentUser.uid) {
        try {
            val myServers = FirestoreClient.getMyServers(currentUser.uid, currentUser.idToken)
            myServers.forEach { (id, name) -> RecentServers.addIfAbsent(id, name) }
            recentServers = RecentServers.load()
        } catch (_: Exception) {}
        // Sol panel verilerini yükle
        runCatching { friends       = FirestoreClient.listFriends(currentUser.uid, currentUser.idToken) }
        runCatching { conversations = FirestoreClient.listDmConversations(currentUser.uid, currentUser.idToken) }
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

    // ── Arama: nickname / FurcordID → kişi, davet kodu → sunucu ─────────────
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            searchError = ""
            return@LaunchedEffect
        }
        delay(400L) // debounce
        searchLoading = true
        searchError   = ""
        val results = mutableListOf<SearchResult>()
        val trimmed = searchQuery.trim()
        val upper   = trimmed.uppercase()
        // Nickname ile kişi ara
        runCatching {
            FirestoreClient.getUserByNickname(trimmed, currentUser.idToken)?.let { (uid, uname) ->
                results += SearchResult(SearchType.PERSON, uid, uname)
            }
        }
        // 8 karakter alfanümerik: FurcordID ve davet kodu olabilir
        if (trimmed.length == 8 && trimmed.all { it.isLetterOrDigit() }) {
            runCatching {
                FirestoreClient.getUserByFurcordId(upper, currentUser.idToken)?.let { (uid, uname) ->
                    if (results.none { it.id == uid }) results += SearchResult(SearchType.PERSON, uid, uname)
                }
            }
            runCatching {
                FirestoreClient.getServerByInvite(upper, currentUser.idToken)?.let { (sid, sname) ->
                    results += SearchResult(SearchType.SERVER, sid, sname)
                }
            }
        }
        searchResults = results
        if (results.isEmpty()) searchError = "Sonuç bulunamadı."
        searchLoading = false
    }

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
            Text("🎮", fontSize = 22.sp)
            Text(
                "Furcord",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TEXT,
                letterSpacing = 0.3.sp,
            )
            Spacer(Modifier.weight(1f))
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
            // Çıkış butonu
            IconButton(
                onClick = onSignOut,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SURFACE2),
            ) {
                Text("⏻", fontSize = 15.sp, color = TEXT2)
            }
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
                            items(friends, key = { it.uid }) { friend ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenDm?.invoke(friend.uid, friend.displayName) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    UserAvatar(displayName = friend.displayName, photoURL = "", size = 28)
                                    Column(Modifier.weight(1f)) {
                                        Text(friend.displayName, color = TEXT, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(friend.furcordId, color = MUTED, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ── Mesajlar çekmece ──────────────────────────────────
                    item {
                        HorizontalDivider(color = OUTLINE, modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { convOpen = !convOpen }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (convOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                null, tint = MUTED, modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "MESAJLAR",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = MUTED, letterSpacing = 0.8.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (conversations.isNotEmpty()) {
                                Text(
                                    "${conversations.size}",
                                    fontSize = 10.sp, color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(OUTLINE)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    if (convOpen) {
                        if (conversations.isEmpty()) {
                            item {
                                Text(
                                    "Henüz sohbet yok.",
                                    color = MUTED, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            items(conversations, key = { it.dmId }) { conv ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenDm?.invoke(conv.otherUid, conv.otherName) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    UserAvatar(displayName = conv.otherName, photoURL = "", size = 28)
                                    Column(Modifier.weight(1f)) {
                                        Text(conv.otherName, color = TEXT, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(conv.lastText, color = MUTED, fontSize = 11.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(Modifier.width(1.dp).fillMaxHeight().background(OUTLINE))

            // ── Orta panel — eylemler ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(40.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                // Başlık
                Text(
                    "Sunucu Lobisi",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = TEXT,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mevcut bir sunucuya katıl veya yenisini oluştur.",
                    fontSize = 14.sp,
                    color = TEXT2,
                )
                Spacer(Modifier.height(32.dp))

                // ── Kişi veya Sunucu Ara ─────────────────────────────────────
                SectionLabel("KİŞİ VEYA SUNUCU ARA")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; searchError = "" },
                    placeholder = { Text("Nickname, ID veya davet kodu...", color = MUTED, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (searchLoading) CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MUTED,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = ACCENT,
                        unfocusedBorderColor    = OUTLINE,
                        focusedContainerColor   = SURFACE2,
                        unfocusedContainerColor = SURFACE2,
                    ),
                )

                if (searchError.isNotEmpty() && searchResults.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(searchError, fontSize = 12.sp, color = MUTED)
                }

                if (searchResults.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    searchResults.forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SURFACE2)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (result.type == SearchType.PERSON) "👤" else "🏠",
                                fontSize = 18.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                result.name,
                                fontSize = 14.sp,
                                color = TEXT,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (result.type == SearchType.PERSON) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                FirestoreClient.addFriend(
                                                    currentUser.uid, result.id, currentUser.idToken
                                                )
                                            }
                                            onOpenDm?.invoke(result.id, result.name)
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                ) { Text("Arkadaş Ekle", fontSize = 12.sp) }
                            } else {
                                Button(
                                    onClick = { connectToServer(result.id, result.name) },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GREEN),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                ) { Text("Bağlan", fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(28.dp))

                // ── Yeni Sunucu Oluştur ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = OUTLINE)
                    Text("veya", fontSize = 12.sp, color = MUTED)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = OUTLINE)
                }

                Spacer(Modifier.height(28.dp))
                SectionLabel("YENİ SUNUCU OLUŞTUR")
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it; createError = "" },
                        placeholder = { Text("Sunucuna bir isim ver", color = MUTED, fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = createError.isNotEmpty(),
                        supportingText = if (createError.isNotEmpty()) {
                            { Text(createError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                        } else null,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = GREEN,
                            unfocusedBorderColor = OUTLINE,
                            focusedContainerColor   = SURFACE2,
                            unfocusedContainerColor = SURFACE2,
                        ),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val name = createName.trim()
                                if (name.isEmpty()) { createError = "Lütfen bir sunucu adı girin."; return@launch }
                                createLoading = true
                                try {
                                    // 1 sunucu limiti: kullanıcının zaten sunucusu var mı?
                                    val existing = FirestoreClient.getMyServers(currentUser.uid, currentUser.idToken)
                                    if (existing.isNotEmpty()) {
                                        createError = "Zaten bir sunucun var: \"${existing.first().second}\""
                                        return@launch
                                    }
                                    val id = FirestoreClient.createServer(name, currentUser.uid, currentUser.idToken)
                                    FirestoreClient.createVoiceChannel(id, "Genel", 0, currentUser.idToken)
                                    FirestoreClient.createVoiceChannel(id, "Oyun",  1, currentUser.idToken)
                                    connectToServer(id, name)
                                } catch (e: Exception) {
                                    createError = "Oluşturulamadı: ${e.message}"
                                } finally {
                                    createLoading = false
                                }
                            }
                        },
                        enabled  = !createLoading,
                        modifier = Modifier.height(56.dp),
                        shape    = RoundedCornerShape(8.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = GREEN),
                    ) {
                        if (createLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("Oluştur", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

            }

            // ── Sağ panel — son sunucular ────────────────────────────────────
            Box(Modifier.width(1.dp).fillMaxHeight().background(OUTLINE))
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(SURFACE),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "SON SUNUCULAR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MUTED,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${recentServers.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(OUTLINE)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                HorizontalDivider(color = OUTLINE)

                if (recentServers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("🏠", fontSize = 32.sp)
                            Text("Henüz sunucu yok", fontSize = 13.sp, color = MUTED)
                            Text(
                                "Bir sunucuya katıldıktan\nsonra burada görünür.",
                                fontSize = 12.sp,
                                color = Color(0xFF6D6F78),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(recentServers) { (sid, sname) ->
                            RecentServerRow(
                                sid          = sid,
                                sname        = sname,
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
        // Sunucu ikonu
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ACCENT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = sname.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
            )
        }
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
                color = Color(0xFFDA373C),
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!anyLoading) Color(0xFF3A2022) else OUTLINE)
                    .clickable(enabled = !anyLoading, onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    "🗑",
                    fontSize = 12.sp,
                )
            }
        }
    }
}
