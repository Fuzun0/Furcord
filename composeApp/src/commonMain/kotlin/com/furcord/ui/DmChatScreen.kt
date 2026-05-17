package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

// â”€â”€ Renkler â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
private val BgDark     = Color(0xFF1E1F22)
private val BgMid      = Color(0xFF2B2D31)
private val Accent     = Color(0xFF5865F2)
private val TextLight  = Color(0xFFDCDDDE)
private val TextSub    = Color(0xFF96989D)
private val InputBg    = Color(0xFF383A40)
private val QuoteBg    = Color(0xFF1A1B1E)

// DM resim Ã¶nbelleÄŸi (~/.furcord/img_cache/ ile ortak)
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
            contentDescription = "GÃ¶rsel",
            modifier           = Modifier.widthIn(max = 280.dp).heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
    }
}

/**
 * Bir kullanÄ±cÄ±yla direkt mesajlaÅŸma ekranÄ±.
 *
 * @param currentUser Oturum aÃ§mÄ±ÅŸ kullanÄ±cÄ±
 * @param recipientUid MesajlaÅŸÄ±lacak kullanÄ±cÄ±nÄ±n uid'si
 * @param recipientName MesajlaÅŸÄ±lacak kullanÄ±cÄ±nÄ±n gÃ¶rÃ¼nen adÄ±
 * @param onBack Geri butonu tÄ±klandÄ±ÄŸÄ±nda Ã§aÄŸrÄ±lÄ±r
 */
@Composable
fun DmChatScreen(
    currentUser: AuthUser,
    recipientUid: String,
    recipientName: String,
    onBack: () -> Unit,
) {
    val scope      = rememberCoroutineScope()
    val listState  = rememberLazyListState()
    var messages   by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var inputText  by remember { mutableStateOf("") }
    var sending    by remember { mutableStateOf(false) }

    // YanÄ±t & Ã§oklu seÃ§im durumlarÄ±
    var replyingTo   by remember { mutableStateOf<ChatMessage?>(null) }
    var selectedIds  by remember { mutableStateOf<Set<String>>(emptySet()) }
    val inSelection  = selectedIds.isNotEmpty()

    // Periyodik polling â€” 3 sn'de bir gÃ¼ncelle
    LaunchedEffect(recipientUid) {
        while (isActive) {
            val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
            if (fresh.isNotEmpty()) messages = fresh
            delay(3_000)
        }
    }

    // Yeni mesaj gelince en alta kaydÄ±r
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text  = inputText.trim()
        if (text.isEmpty() || sending) return
        val reply = replyingTo
        sending   = true
        inputText = ""
        replyingTo = null
        scope.launch {
            FirestoreClient.sendDm(
                senderUid    = currentUser.uid,
                senderName   = currentUser.displayName.ifBlank { currentUser.email },
                senderPhoto  = currentUser.photoURL,
                recipientUid = recipientUid,
                text         = text,
                idToken      = currentUser.idToken,
                replyToId    = reply?.id   ?: "",
                replyToUser  = reply?.username ?: "",
                replyToText  = reply?.text?.take(120) ?: "",
            )
            val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
            if (fresh.isNotEmpty()) messages = fresh
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {

        // â”€â”€ Top bar (seÃ§im modunda seÃ§im barÄ± gÃ¶ster) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (inSelection) {
            Row(
                Modifier.fillMaxWidth().background(Accent).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(
                    "${selectedIds.size} mesaj seÃ§ildi",
                    color    = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(
                    onClick  = {
                        val text = messages
                            .filter   { it.id in selectedIds }
                            .sortedBy { it.timestamp }
                            .joinToString("\n") { "[${it.username}] ${it.text}" }
                        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        cb.setContents(java.awt.datatransfer.StringSelection(text), null)
                        selectedIds = emptySet()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        } else {
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
        }

        // â”€â”€ Mesajlar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        LazyColumn(
            state        = listState,
            modifier     = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages, key = { it.id }) { msg ->
                val isMine     = msg.uid == currentUser.uid
                val isSelected = msg.id in selectedIds
                var showMenu   by remember { mutableStateOf(false) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .pointerInput(msg.id) {
                            detectTapGestures(
                                onLongPress = {
                                    if (inSelection) selectedIds = if (msg.id in selectedIds)
                                        selectedIds - msg.id else selectedIds + msg.id
                                    else showMenu = true
                                },
                                onTap = {
                                    if (inSelection) selectedIds = if (msg.id in selectedIds)
                                        selectedIds - msg.id else selectedIds + msg.id
                                },
                            )
                        },
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                ) {
                    if (!isMine) {
                        UserAvatar(
                            photoURL    = msg.photoURL,
                            displayName = msg.username,
                            size        = 32,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                        if (!isMine) {
                            Text(msg.username, color = TextSub, fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 2.dp))
                        }

                        // â”€â”€ YanÄ±t alÄ±ntÄ±sÄ± â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                                    Text(
                                        text       = msg.replyToUser,
                                        fontSize   = 10.sp,
                                        color      = Accent,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text     = msg.replyToText.take(80),
                                        fontSize = 11.sp,
                                        color    = TextSub,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }

                        // â”€â”€ Mesaj balonu â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                                if (msg.text.isNotBlank()) {
                                    Text(msg.text, color = TextLight, fontSize = 14.sp)
                                }
                                if (msg.imageUrl.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    DmAsyncImage(msg.imageUrl)
                                }
                            }
                        }

                        // BaÄŸlam menÃ¼sÃ¼
                        DropdownMenu(
                            expanded         = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text    = { Text("â†© YanÄ±tla") },
                                onClick = { showMenu = false; replyingTo = msg },
                            )
                            DropdownMenuItem(
                                text    = { Text("â˜‘ SeÃ§") },
                                onClick = {
                                    showMenu    = false
                                    selectedIds = selectedIds + msg.id
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // â”€â”€ YanÄ±t Ã¶nizleme barÄ± â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgMid)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(32.dp).background(Accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text       = replyingTo!!.username,
                        fontSize   = 11.sp,
                        color      = Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text     = replyingTo!!.text.take(60),
                        fontSize = 12.sp,
                        color    = TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = TextSub, modifier = Modifier.size(16.dp))
                }
            }
        }

        // â”€â”€ Input alanÄ± â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                placeholder   = { Text("@ $recipientName'e mesaj gÃ¶nder", color = TextSub) },
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
            IconButton(
                onClick  = ::sendMessage,
                enabled  = !sending && inputText.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null,
                    tint = if (inputText.isNotBlank()) Accent else TextSub)
            }
        }
    }
}

