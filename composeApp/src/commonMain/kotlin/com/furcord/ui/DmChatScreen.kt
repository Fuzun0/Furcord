package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.ChatMessage
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Renkler ───────────────────────────────────────────────────────────────────
private val BgDark    = Color(0xFF1E1F22)
private val BgMid     = Color(0xFF2B2D31)
private val Accent    = Color(0xFF5865F2)
private val TextLight = Color(0xFFDCDDDE)
private val TextSub   = Color(0xFF96989D)
private val InputBg   = Color(0xFF383A40)
private val QuoteBg   = Color(0xFF1A1B1E)

// DM resim önbelleği (~/.furcord/img_cache/ ile ortak)
private val dmImgCacheDir = java.io.File(System.getProperty("user.home"), ".furcord/img_cache")
    .also { it.mkdirs() }

@Composable
private fun DmAsyncImage(url: String) {
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val key  = url.hashCode().toString()
                val file = java.io.File(dmImgCacheDir, key)
                val bytes = if (file.exists()) file.readBytes()
                            else java.net.URI.create(url).toURL().readBytes().also { file.writeBytes(it) }
                bmp = androidx.compose.ui.res.loadImageBitmap(bytes.inputStream())
            } catch (_: Exception) {}
        }
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap             = bmp!!,
            contentDescription = "Görsel",
            modifier           = Modifier.widthIn(max = 280.dp).heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
    }
}

/**
 * Bir kullanıcıyla direkt mesajlaşma ekranı.
 */
@Composable
fun DmChatScreen(
    currentUser: AuthUser,
    recipientUid: String,
    recipientName: String,
    onBack: () -> Unit,
) {
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages  by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var sending   by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }

    // Periyodik polling — 3 sn'de bir güncelle
    LaunchedEffect(recipientUid) {
        while (isActive) {
            val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
            if (fresh.isNotEmpty()) messages = fresh
            delay(3_000)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text  = inputText.trim()
        if (text.isEmpty() || sending) return
        val reply = replyingTo
        sending    = true
        inputText  = ""
        replyingTo = null
        scope.launch {
            FirestoreClient.sendDm(
                senderUid    = currentUser.uid,
                senderName   = currentUser.displayName.ifBlank { currentUser.email },
                senderPhoto  = currentUser.photoURL,
                recipientUid = recipientUid,
                text         = text,
                idToken      = currentUser.idToken,
                replyToId    = reply?.id          ?: "",
                replyToUser  = reply?.username    ?: "",
                replyToText  = reply?.text?.take(120) ?: "",
            )
            val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
            if (fresh.isNotEmpty()) messages = fresh
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(BgMid).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextLight)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("@ $recipientName", color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Direkt Mesaj", color = TextSub, fontSize = 11.sp)
            }
        }

        // ── Mesajlar ─────────────────────────────────────────────────────────
        LazyColumn(
            state       = listState,
            modifier    = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages, key = { it.id }) { msg ->
                val isMine   = msg.uid == currentUser.uid
                var showMenu by remember { mutableStateOf(false) }
                var menuOff  by remember { mutableStateOf(DpOffset.Zero) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(msg.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press &&
                                        event.buttons.isSecondaryPressed
                                    ) {
                                        val pos = event.changes.firstOrNull()?.position
                                        if (pos != null) {
                                            menuOff = DpOffset(
                                                x = (pos.x / density).dp,
                                                y = (pos.y / density).dp,
                                            )
                                        }
                                        showMenu = true
                                    }
                                }
                            }
                        },
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                ) {
                    if (!isMine) {
                        UserAvatar(photoURL = msg.photoURL, displayName = msg.username, size = 32)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                        if (!isMine) {
                            Text(msg.username, color = TextSub, fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 2.dp))
                        }

                        // ── Yanıt alıntısı ────────────────────────────────────
                        if (msg.replyToId.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 400.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(QuoteBg)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(3.dp).height(24.dp).background(Accent))
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(msg.replyToUser, fontSize = 10.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                                    Text(msg.replyToText.take(80), fontSize = 11.sp, color = TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }

                        // ── Mesaj balonu ──────────────────────────────────────
                        Box(
                            Modifier
                                .background(
                                    if (isMine) Accent else InputBg,
                                    RoundedCornerShape(
                                        topStart    = if (isMine) 12.dp else 2.dp,
                                        topEnd      = if (isMine) 2.dp  else 12.dp,
                                        bottomStart = 12.dp,
                                        bottomEnd   = 12.dp,
                                    )
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .widthIn(max = 400.dp)
                        ) {
                            Column {
                                if (msg.text.isNotBlank()) Text(msg.text, color = TextLight, fontSize = 14.sp)
                                if (msg.imageUrl.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    DmAsyncImage(msg.imageUrl)
                                }
                            }
                        }

                        // ── Sağ-tık bağlam menüsü ─────────────────────────────
                        DropdownMenu(
                            expanded         = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset           = menuOff,
                        ) {
                            DropdownMenuItem(
                                text    = { Text("↩ Yanıtla") },
                                onClick = { showMenu = false; replyingTo = msg },
                            )
                            DropdownMenuItem(
                                text    = { Text("📋 Kopyala") },
                                onClick = {
                                    showMenu = false
                                    val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                    cb.setContents(java.awt.datatransfer.StringSelection(msg.text), null)
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Yanıt önizleme barı ───────────────────────────────────────────────
        if (replyingTo != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(BgMid).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(32.dp).background(Accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(replyingTo!!.username, fontSize = 11.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(replyingTo!!.text.take(60), fontSize = 12.sp, color = TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = TextSub, modifier = Modifier.size(16.dp))
                }
            }
        }

        // ── Input alanı ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(BgMid).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value         = inputText,
                onValueChange = { inputText = it },
                modifier      = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        sendMessage(); true
                    } else false
                },
                placeholder   = { Text("@ $recipientName'e mesaj gönder", color = TextSub) },
                singleLine    = false,
                maxLines      = 4,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = Accent,
                    unfocusedBorderColor    = Color.Transparent,
                    focusedContainerColor   = InputBg,
                    unfocusedContainerColor = InputBg,
                    focusedTextColor        = TextLight,
                    unfocusedTextColor      = TextLight,
                    cursorColor             = Accent,
                ),
                shape = RoundedCornerShape(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = ::sendMessage, enabled = !sending && inputText.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, null,
                    tint = if (inputText.isNotBlank()) Accent else TextSub)
            }
        }
    }
}