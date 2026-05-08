package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.ChatMessage
import com.furcord.auth.DmRepository
import com.furcord.auth.FriendEntry
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val FwBg      = Color(0xFF2B2D31)
private val FwSidebar = Color(0xFF232428)
private val FwHeader  = Color(0xFF1E1F22)
private val FwInput   = Color(0xFF383A40)
private val FwAccent  = Color(0xFF5865F2)
private val FwText    = Color(0xFFDCDDDE)
private val FwSub     = Color(0xFF96989D)
private val FwOutline = Color(0xFF3F4147)
private val FwUnread  = Color(0xFFED4245)

/**
 * LoL tarzı bölünmüş DM penceresi.
 *
 * Sol panel (190dp): son sohbetler (DmRepository.threads) veya arkadaş listesi (sohbet yoksa).
 * Sağ panel (390dp): seçili sohbetin mesajları + mesaj giriş alanı.
 *
 * @param initialRecipientUid  onOpenDm ile açıldığında başlangıç seçimi
 * @param initialRecipientName Başlangıç alıcısının görünen adı
 * @param bottomPadding        FAB / diğer UI için alt boşluk
 * @param onClose              Kapat butonuna basıldığında çağrılır
 */
@Composable
fun FloatingDmWindow(
    currentUser: AuthUser,
    initialRecipientUid: String? = null,
    initialRecipientName: String? = null,
    bottomPadding: Dp = 76.dp,
    onClose: () -> Unit,
) {
    val scope         = rememberCoroutineScope()
    val conversations by DmRepository.threads.collectAsState()
    val unreadThreads by DmRepository.unreadThreads.collectAsState()

    var friends       by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var friendsLoaded by remember { mutableStateOf(false) }

    // Seçili sohbet — başlangıç: parametre → ilk sohbet → null
    var selectedUid  by remember {
        mutableStateOf(initialRecipientUid ?: conversations.firstOrNull()?.otherUid)
    }
    var selectedName by remember {
        mutableStateOf(initialRecipientName ?: conversations.firstOrNull()?.otherName ?: "")
    }

    // Sohbet listesi yüklenince ama henüz seçim yoksa ilkini seç
    LaunchedEffect(conversations) {
        if (selectedUid == null && conversations.isNotEmpty()) {
            selectedUid  = conversations.first().otherUid
            selectedName = conversations.first().otherName
        }
    }

    // Sohbet yoksa arkadaş listesini yükle
    LaunchedEffect(conversations.isEmpty()) {
        if (conversations.isEmpty() && !friendsLoaded) {
            runCatching { friends = FirestoreClient.listFriends(currentUser.uid, currentUser.idToken) }
            friendsLoaded = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 80.dp, bottom = bottomPadding),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Surface(
            modifier        = Modifier.width(580.dp).heightIn(max = 460.dp),
            shape           = RoundedCornerShape(12.dp),
            color           = FwBg,
            shadowElevation = 16.dp,
            tonalElevation  = 0.dp,
        ) {
            Column {
                // ── Başlık çubuğu ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FwHeader)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text       = "💬  Mesajlar",
                        color      = FwText,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 13.sp,
                        modifier   = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint               = FwSub,
                        modifier           = Modifier.size(16.dp).clickable(onClick = onClose),
                    )
                }
                HorizontalDivider(color = FwOutline)

                // ── Gövde: sol liste + sağ sohbet ─────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                ) {
                    // ── Sol panel ─────────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .width(190.dp)
                            .fillMaxHeight()
                            .background(FwSidebar),
                    ) {
                        Text(
                            text          = if (conversations.isEmpty()) "ARKADAŞLAR" else "SOHBETLER",
                            color         = FwSub,
                            fontSize      = 10.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier      = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        HorizontalDivider(color = FwOutline)

                        if (conversations.isEmpty()) {
                            // Hiç sohbet yoksa → arkadaş listesinden başlat
                            if (friends.isEmpty()) {
                                Box(
                                    Modifier.fillMaxSize().padding(12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text      = "Henüz sohbet yok.\nArkadaş ekleyerek başla.",
                                        color     = FwSub,
                                        fontSize  = 11.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(friends, key = { it.uid }) { friend ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (selectedUid == friend.uid)
                                                        FwAccent.copy(alpha = 0.20f)
                                                    else Color.Transparent
                                                )
                                                .clickable {
                                                    selectedUid  = friend.uid
                                                    selectedName = friend.displayName
                                                    scope.launch {
                                                        runCatching {
                                                            FirestoreClient.initDmThread(
                                                                currentUser.uid,
                                                                friend.uid,
                                                                currentUser.idToken,
                                                            )
                                                        }
                                                    }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment     = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            UserAvatar(friend.displayName, "", 28)
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    friend.displayName,
                                                    color    = FwText,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(friend.furcordId, color = FwSub, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Sohbet listesi
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(conversations, key = { it.dmId }) { conv ->
                                    val isUnread   = unreadThreads.any { it.dmId == conv.dmId }
                                    val isSelected = conv.otherUid == selectedUid
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when {
                                                    isSelected -> FwAccent.copy(alpha = 0.22f)
                                                    isUnread   -> FwAccent.copy(alpha = 0.08f)
                                                    else       -> Color.Transparent
                                                }
                                            )
                                            .clickable {
                                                selectedUid  = conv.otherUid
                                                selectedName = conv.otherName
                                                DmRepository.markRead(conv.dmId)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box {
                                            UserAvatar(conv.otherName, "", 28)
                                            if (isUnread) {
                                                Box(
                                                    Modifier
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(FwUnread)
                                                        .align(Alignment.TopEnd)
                                                )
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text       = conv.otherName,
                                                color      = if (isUnread || isSelected) FwText else FwSub,
                                                fontSize   = 12.sp,
                                                fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines   = 1,
                                                overflow   = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text     = conv.lastText,
                                                color    = FwSub,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Dikey ayraç
                    Box(Modifier.width(1.dp).fillMaxHeight().background(FwOutline))

                    // ── Sağ panel: aktif sohbet ────────────────────────────────
                    val uid  = selectedUid
                    val name = selectedName
                    if (uid != null && uid.isNotEmpty()) {
                        DmChatPane(
                            currentUser   = currentUser,
                            recipientUid  = uid,
                            recipientName = name,
                            modifier      = Modifier.weight(1f).fillMaxHeight(),
                        )
                    } else {
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text      = "Sohbet başlatmak için\nsol taraftan birini seç.",
                                color     = FwSub,
                                fontSize  = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DM sohbet paneli (sağ taraf)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DmChatPane(
    currentUser: AuthUser,
    recipientUid: String,
    recipientName: String,
    modifier: Modifier = Modifier,
) {
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages  by remember(recipientUid) { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember(recipientUid) { mutableStateOf("") }
    var sending   by remember { mutableStateOf(false) }

    // Periyodik mesaj yoklama (3 sn)
    LaunchedEffect(recipientUid) {
        while (isActive) {
            runCatching {
                val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
                if (fresh.isNotEmpty()) messages = fresh
            }
            delay(3_000)
        }
    }

    // Yeni mesaj gelince en alta kaydır
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty())
            listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || sending) return
        sending = true; inputText = ""
        scope.launch {
            runCatching {
                FirestoreClient.sendDm(
                    senderUid    = currentUser.uid,
                    senderName   = currentUser.nickname.ifBlank {
                        currentUser.displayName.ifBlank { currentUser.email }
                    },
                    senderPhoto  = currentUser.photoURL,
                    recipientUid = recipientUid,
                    text         = text,
                    idToken      = currentUser.idToken,
                )
                val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
                if (fresh.isNotEmpty()) messages = fresh
            }
            sending = false
        }
    }

    Column(modifier = modifier) {
        // ── Sohbet başlık satırı ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FwBg)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserAvatar(displayName = recipientName, photoURL = "", size = 24)
            Text(
                text       = "@ $recipientName",
                color      = FwText,
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f),
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = FwOutline)

        // ── Mesaj listesi ─────────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            items(messages, key = { it.id }) { msg ->
                val isMine = msg.uid == currentUser.uid
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                ) {
                    if (!isMine) {
                        UserAvatar(msg.username, msg.photoURL, 22)
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isMine) FwAccent else FwInput,
                                shape = RoundedCornerShape(
                                    topStart    = if (isMine) 10.dp else 2.dp,
                                    topEnd      = if (isMine) 2.dp  else 10.dp,
                                    bottomStart = 10.dp,
                                    bottomEnd   = 10.dp,
                                ),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .widthIn(max = 240.dp),
                    ) {
                        Text(msg.text, color = FwText, fontSize = 13.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }

        HorizontalDivider(color = FwOutline)

        // ── Mesaj giriş alanı ─────────────────────────────────────────────────
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .background(FwBg)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value         = inputText,
                onValueChange = { inputText = it },
                modifier      = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key  == Key.Enter &&
                            !event.isShiftPressed
                        ) { sendMessage(); true } else false
                    },
                placeholder = { Text("Mesaj yaz...", color = FwSub, fontSize = 12.sp) },
                singleLine  = true,
                colors      = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = FwAccent,
                    unfocusedBorderColor    = Color.Transparent,
                    focusedContainerColor   = FwInput,
                    unfocusedContainerColor = FwInput,
                    focusedTextColor        = FwText,
                    unfocusedTextColor      = FwText,
                    cursorColor             = FwAccent,
                ),
            )
            IconButton(
                onClick  = ::sendMessage,
                enabled  = inputText.isNotBlank() && !sending,
                modifier = Modifier.size(36.dp),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(16.dp),
                        color       = FwAccent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Gönder",
                        tint               = FwAccent,
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
