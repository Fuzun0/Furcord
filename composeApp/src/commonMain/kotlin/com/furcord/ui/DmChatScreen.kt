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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.ChatMessage
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ── Renkler ────────────────────────────────────────────────────────────────────
private val BgDark     = Color(0xFF1E1F22)
private val BgMid      = Color(0xFF2B2D31)
private val Accent     = Color(0xFF5865F2)
private val TextLight  = Color(0xFFDCDDDE)
private val TextSub    = Color(0xFF96989D)
private val InputBg    = Color(0xFF383A40)

/**
 * Bir kullanıcıyla direkt mesajlaşma ekranı.
 *
 * @param currentUser Oturum açmış kullanıcı
 * @param recipientUid Mesajlaşılacak kullanıcının uid'si
 * @param recipientName Mesajlaşılacak kullanıcının görünen adı
 * @param onBack Geri butonu tıklandığında çağrılır
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

    // Periyodik polling — 3 sn'de bir güncelle
    LaunchedEffect(recipientUid) {
        while (isActive) {
            val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
            if (fresh.isNotEmpty()) messages = fresh
            delay(3_000)
        }
    }

    // Yeni mesaj gelince en alta kaydır
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || sending) return
        sending   = true
        inputText = ""
        scope.launch {
            FirestoreClient.sendDm(
                senderUid   = currentUser.uid,
                senderName  = currentUser.displayName.ifBlank { currentUser.email },
                senderPhoto = currentUser.photoURL,
                recipientUid = recipientUid,
                text        = text,
                idToken     = currentUser.idToken,
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
            state        = listState,
            modifier     = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages, key = { it.id }) { msg ->
                val isMine = msg.uid == currentUser.uid
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                ) {
                    if (!isMine) {
                        UserAvatar(
                            photoURL = msg.photoURL,
                            displayName = msg.username,
                            size = 32,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                        if (!isMine) {
                            Text(msg.username, color = TextSub, fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 2.dp))
                        }
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
                            Text(msg.text, color = TextLight, fontSize = 14.sp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
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
                    focusedBorderColor   = Accent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor   = InputBg,
                    unfocusedContainerColor = InputBg,
                    focusedTextColor     = TextLight,
                    unfocusedTextColor   = TextLight,
                    cursorColor          = Accent,
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
