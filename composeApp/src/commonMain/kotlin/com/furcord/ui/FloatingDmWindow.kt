package com.furcord.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthUser
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val FwBg      = Color(0xFF2B2D31)
private val FwHeader  = Color(0xFF232428)
private val FwInput   = Color(0xFF383A40)
private val FwAccent  = Color(0xFF5865F2)
private val FwText    = Color(0xFFDCDDDE)
private val FwSub     = Color(0xFF96989D)
private val FwOutline = Color(0xFF3F4147)

/**
 * Sağ alt köşede kayan DM sohbet penceresi.
 *
 * App.kt içindeki Box'ta [Alignment.BottomEnd] konumuna sabitlenerek
 * alttaki ekranın (ServerDetailScreen / ServerLobbyScreen) üstüne yığıtlanır.
 * Alttaki ekran hiç unmount edilmez → ses bağlantısı korunur.
 *
 * @param bottomPadding FAB / diğer UI elemanları için bırakılan alt boşluk
 * @param onClose       Kapat butonuna basıldığında çağrılır
 */
@Composable
fun FloatingDmWindow(
    currentUser: AuthUser,
    recipientUid: String,
    recipientName: String,
    bottomPadding: Dp = 76.dp,
    onClose: () -> Unit,
) {
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages  by remember(recipientUid) { mutableStateOf(listOf<com.furcord.auth.ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var sending   by remember { mutableStateOf(false) }
    var minimized by remember { mutableStateOf(false) }

    // ── Periyodik mesaj yoklama (3 sn) ───────────────────────────────────────
    LaunchedEffect(recipientUid) {
        while (isActive) {
            runCatching {
                val fresh = FirestoreClient.listDms(currentUser.uid, recipientUid, currentUser.idToken)
                if (fresh.isNotEmpty()) messages = fresh
            }
            delay(3_000)
        }
    }

    // ── Yeni mesaj gelince en alta kaydır ────────────────────────────────────
    LaunchedEffect(messages.size) {
        if (!minimized && messages.isNotEmpty())
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

    // ── Pencere konumu: ekranın sağ altı (FAB'ın üstüne) ─────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 16.dp, bottom = bottomPadding),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Surface(
            modifier      = Modifier.width(300.dp),
            shape         = RoundedCornerShape(12.dp),
            color         = FwBg,
            shadowElevation = 12.dp,
            tonalElevation  = 0.dp,
        ) {
            Column {
                // ── Başlık çubuğu ─────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FwHeader)
                        .clickable { minimized = !minimized }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UserAvatar(displayName = recipientName, photoURL = "", size = 24)
                    Text(
                        text      = "@ $recipientName",
                        color     = FwText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize  = 13.sp,
                        modifier  = Modifier.weight(1f),
                        maxLines  = 1,
                        overflow  = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector        = if (minimized) Icons.Default.KeyboardArrowUp
                                             else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint               = FwSub,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint               = FwSub,
                        modifier           = Modifier
                            .size(16.dp)
                            .clickable(onClick = onClose),
                    )
                }

                // ── Gövde (küçültülmüşse gizli) ───────────────────────────────
                AnimatedVisibility(
                    visible = !minimized,
                    enter   = expandVertically(expandFrom = Alignment.Top),
                    exit    = shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    Column {
                        // Mesaj listesi
                        LazyColumn(
                            state               = listState,
                            modifier            = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            item { Spacer(Modifier.height(6.dp)) }
                            items(messages, key = { it.id }) { msg ->
                                val isMine = msg.uid == currentUser.uid
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isMine) Arrangement.End
                                                           else Arrangement.Start,
                                ) {
                                    if (!isMine) {
                                        UserAvatar(
                                            displayName = msg.username,
                                            photoURL    = msg.photoURL,
                                            size        = 22,
                                        )
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
                                            .widthIn(max = 210.dp),
                                    ) {
                                        Text(msg.text, color = FwText, fontSize = 13.sp)
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(6.dp)) }
                        }

                        HorizontalDivider(color = FwOutline)

                        // Input satırı
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
                                placeholder  = {
                                    Text("Mesaj...", color = FwSub, fontSize = 12.sp)
                                },
                                singleLine   = true,
                                colors       = OutlinedTextFieldDefaults.colors(
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
            }
        }
    }
}
