package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import com.furcord.ui.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.gestures.detectTapGestures
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
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FwBg      = AppColors.BgSidebar
private val FwSidebar = AppColors.BgDark
private val FwHeader  = AppColors.Outline
private val FwInput   = Color(0xFF383A40)
private val FwAccent  = AppColors.Accent
private val FwText    = AppColors.TextSecondary
private val FwSub     = Color(0xFF96989D)
private val FwOutline = Color(0xFF3F4147)
private val FwUnread  = AppColors.Danger

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

    // Dışarıdan (ör. bildirime tıklama) başka bir sohbet istenirse ona geç
    LaunchedEffect(initialRecipientUid) {
        if (initialRecipientUid != null) {
            selectedUid  = initialRecipientUid
            selectedName = initialRecipientName ?: ""
        }
    }

    // Arkadaş listesi (sohbeti olmayan arkadaşlar da listede görünür)
    LaunchedEffect(currentUser.uid) {
        runCatching { friends = FirestoreClient.listFriends(currentUser.uid, currentUser.idToken) }
        friendsLoaded = true
    }

    /** Sohbeti yalnızca bu kullanıcı için siler (karşı tarafın geçmişi etkilenmez). */
    fun deleteChat(otherUid: String) {
        DmRepository.clearConversation(listOf(currentUser.uid, otherUid).sorted().joinToString("_"))
        if (selectedUid == otherUid) { selectedUid = null; selectedName = "" }
    }

    // Pencerenin dışına tıklayınca kapanır (iç kısım tıklamaları yutar)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // clickable değil: odaklanabilir olduğu için Enter tuşu "tıklama" sayılıp pencereyi kapatıyordu
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 80.dp, bottom = bottomPadding),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Surface(
            modifier        = Modifier.width(580.dp).heightIn(max = 460.dp)
                .pointerInput(Unit) { detectTapGestures { } },
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
                        val convUids     = conversations.map { it.otherUid }.toSet()
                        val otherFriends = friends.filter { it.uid !in convUids }

                        if (conversations.isEmpty() && otherFriends.isEmpty()) {
                            Box(
                                Modifier.fillMaxSize().padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text      = if (friendsLoaded) "Henüz sohbet yok.\nArkadaş ekleyerek başla." else "Yükleniyor…",
                                    color     = FwSub,
                                    fontSize  = 11.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                if (conversations.isNotEmpty()) {
                                    item(key = "hdr_conv") { DmListHeader("SOHBETLER") }
                                    items(conversations, key = { "c_" + it.dmId }) { conv ->
                                        DmListRow(
                                            name       = conv.otherName,
                                            subtitle   = conv.lastText,
                                            isSelected = conv.otherUid == selectedUid,
                                            isUnread   = unreadThreads.any { it.dmId == conv.dmId },
                                            onClick    = {
                                                selectedUid  = conv.otherUid
                                                selectedName = conv.otherName
                                                DmRepository.markRead(conv.dmId)
                                            },
                                            onDelete   = { deleteChat(conv.otherUid) },
                                        )
                                    }
                                }
                                if (otherFriends.isNotEmpty()) {
                                    item(key = "hdr_friends") { DmListHeader("ARKADAŞLAR") }
                                    items(otherFriends, key = { "f_" + it.uid }) { friend ->
                                        DmListRow(
                                            name       = friend.displayName,
                                            subtitle   = friend.furcordId,
                                            isSelected = selectedUid == friend.uid,
                                            isUnread   = false,
                                            onClick    = {
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
                                            },
                                            onDelete   = { deleteChat(friend.uid) },
                                        )
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
    }   // dış tıklama katmanı
}

@Composable
private fun DmListHeader(text: String) {
    Column {
        Text(
            text          = text,
            color         = FwSub,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier      = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = FwOutline)
    }
}

/** Sol listedeki bir sohbet/arkadaş satırı — sağ tık: "Sohbeti Sil" (onaylı). */
@Composable
private fun DmListRow(
    name: String,
    subtitle: String,
    isSelected: Boolean,
    isUnread: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen    by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    var menuOffset  by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            containerColor   = AppColors.BgSidebar,
            title = { Text("Sohbeti sil", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary) },
            text  = {
                Text(
                    "$name ile olan sohbet geçmişi senin için silinecek. Karşı tarafın mesajları etkilenmez; " +
                        "yeni mesaj gelirse sohbet tekrar görünür.",
                    color = AppColors.TextSecondary, fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmOpen = false; onDelete() },
                    colors  = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                ) { Text("Sil", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("İptal", color = AppColors.TextMuted) }
            },
        )
    }

    Box {
        Row(
            modifier = Modifier
                .pointerInput(name) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
                                event.buttons.isSecondaryPressed
                            ) {
                                event.changes.firstOrNull()?.position?.let {
                                    menuOffset = androidx.compose.ui.unit.DpOffset((it.x / density).dp, 0.dp)
                                }
                                menuOpen = true
                            }
                        }
                    }
                }
                .fillMaxWidth()
                .background(
                    when {
                        isSelected -> FwAccent.copy(alpha = 0.22f)
                        isUnread   -> FwAccent.copy(alpha = 0.08f)
                        else       -> Color.Transparent
                    }
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                UserAvatar(name, "", 28)
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
                    text       = name,
                    color      = if (isUnread || isSelected) FwText else FwSub,
                    fontSize   = 12.sp,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text     = subtitle,
                        color    = FwSub,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(
            expanded         = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset           = menuOffset,
            containerColor   = Color(0xFF111214),
        ) {
            DropdownMenuItem(
                text    = { Text("🗑  Sohbeti Sil", color = AppColors.Danger, fontSize = 13.sp) },
                onClick = { menuOpen = false; confirmOpen = true },
            )
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
    var messages      by remember(recipientUid) { mutableStateOf(listOf<ChatMessage>()) }
    var inputText     by remember(recipientUid) { mutableStateOf("") }
    var sending       by remember { mutableStateOf(false) }
    var sendingImage  by remember { mutableStateOf(false) }
    var sendError     by remember { mutableStateOf("") }
    var replyingTo    by remember(recipientUid) { mutableStateOf<ChatMessage?>(null) }
    val dmId = remember(recipientUid) { listOf(currentUser.uid, recipientUid).sorted().joinToString("_") }
    // Kullanıcının bu sohbeti sildiği andan önceki mesajlar gösterilmez (silinince anında güncellenir)
    val clearedVersion by DmRepository.clearedVersion.collectAsState()
    val clearedTs = remember(recipientUid, clearedVersion) { DmRepository.clearedAt(dmId) }
    val incoming by DmRepository.incomingVersion.collectAsState()

    // Bu sohbet açıkken gelen mesajlar için Windows bildirimi gösterilmez
    DisposableEffect(recipientUid) {
        Notifier.openDmUid = recipientUid
        onDispose { if (Notifier.openDmUid == recipientUid) Notifier.openDmUid = null }
    }

    // Periyodik mesaj yoklama — timestamp cursor (kota tasarrufu); gelen kutusu yeni DM bildirirse hemen
    LaunchedEffect(recipientUid, incoming) {
        val backoff = PollBackoff(baseMs = 3_000, maxMs = 20_000, unfocusedMs = 15_000)
        while (isActive) {
            var changed = false
            runCatching {
                val lastTs = messages.maxOfOrNull { it.timestamp } ?: 0L
                val newMsgs = FirestoreClient.listDmsSince(currentUser.uid, recipientUid, lastTs, currentUser.idToken)
                if (newMsgs.isNotEmpty()) {
                    changed = true
                    messages = (messages + newMsgs).distinctBy { it.id }.sortedBy { it.timestamp }
                }
            }
            delay(backoff.next(changed))
        }
    }

    val visibleMessages = remember(messages, clearedTs) { messages.filter { it.timestamp > clearedTs } }

    // Yeni mesaj gelince en alta kaydır + okundu işaretle
    LaunchedEffect(visibleMessages.size) {
        DmRepository.markRead(dmId)
        if (visibleMessages.isNotEmpty())
            listState.animateScrollToItem(visibleMessages.size + 1)
    }

    fun reactTo(msg: ChatMessage, emoji: String) {
        fun apply(id: String, r: Map<String, List<String>>) {
            messages = messages.map { if (it.id == id) it.copy(reactions = r) else it }
        }
        val users = msg.reactions[emoji].orEmpty()
        apply(msg.id, (if (currentUser.uid in users) msg.reactions + (emoji to users - currentUser.uid)
                       else msg.reactions + (emoji to users + currentUser.uid)).filterValues { it.isNotEmpty() })
        scope.launch {
            runCatching { FirestoreClient.toggleDmReaction(currentUser.uid, recipientUid, msg.id, emoji, currentUser.idToken) }
                .onSuccess { apply(msg.id, it) }
                .onFailure { apply(msg.id, msg.reactions) }
        }
    }

    // Karşı tarafın tepkilerini tazele (son 30 mesaj; yalnızca pencere odaktayken)
    LaunchedEffect(recipientUid) {
        // 8 sn → tepki değişmiyorsa 2 dk'ya kadar uzar; odak dışında okunmaz
        val backoff = PollBackoff(baseMs = 8_000, maxMs = 120_000)
        var wait = 8_000L
        while (isActive) {
            delay(wait)
            if (!Notifier.windowFocused) { wait = 15_000L; continue }
            var changed = false
            runCatching {
                val fresh = FirestoreClient.listRecentDms(currentUser.uid, recipientUid, 30, currentUser.idToken).associateBy { it.id }
                if (messages.any { m -> fresh[m.id]?.let { it.reactions != m.reactions } == true }) {
                    changed = true
                    messages = messages.map { m -> fresh[m.id]?.let { m.copy(reactions = it.reactions) } ?: m }
                }
            }
            wait = backoff.next(changed)
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || sending) return
        val reply = replyingTo; replyingTo = null
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
                    replyToId    = reply?.id ?: "",
                    replyToUser  = reply?.username ?: "",
                    replyToText  = quotePreviewText(reply?.text ?: "", reply?.imageUrl ?: ""),
                )
                val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
                if (fresh.isNotEmpty()) messages = fresh
            }
            sending = false
        }
    }

    fun sendImage() {
        if (sendingImage) return
        val replyForImage = replyingTo; replyingTo = null
        scope.launch(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, "Dosya Seç (görsel, GIF, video…)", FileDialog.LOAD)
            dialog.isVisible = true
            val selectedFile = dialog.directory?.let { dir ->
                dialog.file?.let { name -> java.io.File(dir, name) }
            } ?: return@launch
            if (!selectedFile.exists()) return@launch
            withContext(Dispatchers.Main) { sendingImage = true }
            try {
                val senderNick = currentUser.nickname.ifBlank { currentUser.displayName.ifBlank { currentUser.email } }
                val attUrl = com.furcord.download.Attachments.prepare(selectedFile, currentUser.idToken)
                FirestoreClient.sendDm(
                    senderUid    = currentUser.uid,
                    senderName   = senderNick,
                    senderPhoto  = currentUser.photoURL,
                    recipientUid = recipientUid,
                    text         = "",
                    idToken      = currentUser.idToken,
                    imageUrl     = attUrl,
                    replyToId    = replyForImage?.id ?: "",
                    replyToUser  = replyForImage?.username ?: "",
                    replyToText  = quotePreviewText(replyForImage?.text ?: "", replyForImage?.imageUrl ?: ""),
                )
                val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
                withContext(Dispatchers.Main) { if (fresh.isNotEmpty()) messages = fresh }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { sendError = e.message ?: "Dosya gönderilemedi." }
            }
            withContext(Dispatchers.Main) { sendingImage = false }
        }
    }

    if (sendError.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { sendError = "" },
            title = { Text("Dosya gönderilemedi") },
            text  = { Text(sendError) },
            confirmButton = { TextButton(onClick = { sendError = "" }) { Text("Tamam") } },
        )
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
            if (visibleMessages.isEmpty()) {
                item {
                    Text(
                        "Henüz mesaj yok. İlk mesajı sen gönder!",
                        color = FwSub, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                }
            }
            items(visibleMessages, key = { it.id }) { msg ->
                DmBubble(
                    msg        = msg,
                    isMine     = msg.uid == currentUser.uid,
                    myUid      = currentUser.uid,
                    idToken    = currentUser.idToken,
                    repliedMsg = if (msg.replyToId.isNotBlank()) messages.firstOrNull { it.id == msg.replyToId } else null,
                    onReply    = { replyingTo = it },
                    onCopy     = { m ->
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(m.text), null)
                    },
                    onReact    = { m, e -> reactTo(m, e) },
                )
            }
            item { Spacer(Modifier.height(6.dp)) }
        }

        HorizontalDivider(color = FwOutline)

        // ── Alıntı önizleme çubuğu ────────────────────────────────────────────
        replyingTo?.let { r ->
            Row(
                modifier = Modifier.fillMaxWidth().background(FwSidebar).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.width(3.dp).height(34.dp).background(FwAccent))
                if (r.imageUrl.isNotBlank()) AttachmentThumb(r.imageUrl, currentUser.idToken, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(r.username, color = FwAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        quotePreviewText(r.text, r.imageUrl).take(60),
                        color = FwSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Default.Close, "Alıntıyı iptal et", tint = FwSub,
                    modifier = Modifier.size(16.dp).clickable { replyingTo = null },
                )
            }
        }

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
                        if (event.key == Key.Enter && !event.isShiftPressed) {
                            if (event.type == KeyEventType.KeyDown) sendMessage()
                            true
                        } else false
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
                    onClick  = ::sendImage,
                    enabled  = !sendingImage,
                    modifier = Modifier.size(30.dp),
                ) {
                    if (sendingImage) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = FwAccent)
                    } else {
                        Text("🖼️", fontSize = 15.sp)
                    }
                }
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

/** DM baloncuğu: üzerine gelince Alıntıla / Kopyala / Tepki ver; altında tepki çipleri. */
@Composable
private fun DmBubble(
    msg: ChatMessage,
    isMine: Boolean,
    myUid: String,
    idToken: String,
    repliedMsg: ChatMessage?,
    onReply: (ChatMessage) -> Unit,
    onCopy: (ChatMessage) -> Unit,
    onReact: (ChatMessage, String) -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var showEmoji by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().hoverable(interaction)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        ) {
            if (!isMine) {
                UserAvatar(msg.username, msg.photoURL, 22)
                Spacer(Modifier.width(6.dp))
            }
            Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (msg.replyToId.isNotBlank()) {
                            Row(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.22f))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.width(2.dp).height(22.dp).background(Color.White.copy(alpha = 0.7f)))
                                if (repliedMsg != null && repliedMsg.imageUrl.isNotBlank())
                                    AttachmentThumb(repliedMsg.imageUrl, idToken, 28.dp)
                                Column {
                                    Text(msg.replyToUser, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(msg.replyToText.take(60), color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (msg.text.isNotBlank()) Text(msg.text, color = FwText, fontSize = 13.sp)
                        if (msg.imageUrl.isNotBlank()) {
                            AttachmentView(
                                url        = msg.imageUrl,
                                idToken    = idToken,
                                maxWidth   = 220.dp,
                                maxHeight  = 180.dp,
                                onDownload = { com.furcord.download.DownloadManager.downloadAttachment(msg.imageUrl, idToken) },
                            )
                        }
                    }
                }
                if (msg.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    ReactionChips(msg.reactions, myUid, onToggle = { onReact(msg, it) }, modifier = Modifier.widthIn(max = 240.dp))
                }
            }
        }

        if (hovered || showEmoji) {
            Row(
                Modifier
                    .align(if (isMine) Alignment.TopStart else Alignment.TopEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FwSidebar)
                    .padding(2.dp),
            ) {
                IconButton(onClick = { onReply(msg) }, Modifier.size(24.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, "Alıntıla", tint = FwSub, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { onCopy(msg) }, Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Kopyala", tint = FwSub, modifier = Modifier.size(14.dp))
                }
                Box {
                    IconButton(onClick = { showEmoji = true }, Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.AddReaction, "Tepki ver", tint = FwSub, modifier = Modifier.size(14.dp))
                    }
                    EmojiPickerMenu(showEmoji, { showEmoji = false }) { onReact(msg, it) }
                }
            }
        }
    }
}
