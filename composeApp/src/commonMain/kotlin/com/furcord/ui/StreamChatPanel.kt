package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.ChatMessage
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val NameColors = listOf(
    Color(0xFFFF6B6B), Color(0xFFFFA94D), Color(0xFFFFD43B), Color(0xFF69DB7C),
    Color(0xFF38D9A9), Color(0xFF4DABF7), Color(0xFF9775FA), Color(0xFFF783AC),
)

/**
 * Twitch tarzı yayın sohbeti. Yalnızca o sunucudaki aktif yayına özgüdür;
 * yayıncı paylaşımı bitirince mesajlar silinir (bkz. FirestoreClient.clearStreamChat).
 */
@Composable
fun StreamChatPanel(
    serverId: String,
    currentUser: AuthUser,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var msgs  by remember(serverId) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val nick = currentUser.nickname.ifEmpty { currentUser.displayName.ifEmpty { currentUser.email.substringBefore("@") } }

    // Yeni mesajları 2 sn'de bir çek (zaman damgası imleci)
    LaunchedEffect(serverId) {
        while (isActive) {
            runCatching {
                val real = msgs.filter { !it.id.startsWith("pending-") }
                val last = real.maxOfOrNull { it.timestamp } ?: 0L
                val fresh = FirestoreClient.listStreamChat(serverId, last, currentUser.idToken)
                if (fresh.isNotEmpty()) {
                    val pend = msgs.filter { p ->
                        p.id.startsWith("pending-") && fresh.none { it.uid == p.uid && it.text == p.text }
                    }
                    msgs = ((real + fresh).distinctBy { it.id }.sortedBy { it.timestamp } + pend).takeLast(300)
                }
            }
            delay(2_000)
        }
    }

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        val now = System.currentTimeMillis()
        msgs = msgs + ChatMessage(
            id = "pending-$now", uid = currentUser.uid, username = nick, photoURL = "",
            text = text, timestamp = now,
        )
        scope.launch {
            runCatching {
                FirestoreClient.sendStreamChat(serverId, currentUser.uid, nick, "", text, currentUser.idToken)
            }.onFailure {
                msgs = msgs.filter { m -> !(m.id.startsWith("pending-") && m.text == text) }
                error = "Gönderilemedi"
            }
        }
    }

    Column(modifier.background(Color(0xFF18181B))) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text("Yayın Sohbeti", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Color(0xFF2F2F35))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (msgs.isEmpty()) {
                item {
                    Text(
                        "Yayın sohbetine hoş geldin!",
                        color = Color(0xFF9A9AA3), fontSize = 12.sp,
                    )
                }
            }
            items(msgs, key = { it.id }) { m ->
                val c = NameColors[(m.uid.hashCode() and 0x7fffffff) % NameColors.size]
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c, fontWeight = FontWeight.Bold)) { append(m.username) }
                        withStyle(SpanStyle(color = Color(0xFFEFEFF1))) { append(": ${m.text}") }
                    },
                    fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }

        if (error.isNotEmpty()) Text(error, color = Color(0xFFED4245), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(500); error = "" },
            placeholder = { Text("Mesaj gönder", color = Color(0xFF9A9AA3), fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { send(); true } else false
                },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = AppColors.Accent, unfocusedBorderColor = Color(0xFF3F3F46),
                focusedContainerColor = Color(0xFF26262C), unfocusedContainerColor = Color(0xFF26262C),
                cursorColor = AppColors.Accent,
            ),
        )
    }
}
