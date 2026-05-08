package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.furcord.auth.AuthUser
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.launch

private val AccentF  = Color(0xFF5865F2)
private val BgDialF  = Color(0xFF2B2D31)
private val InputBgF = Color(0xFF383A40)
private val TxtF     = Color(0xFFDCDDDE)
private val TxtSubF  = Color(0xFF96989D)
private val GreenF   = Color(0xFF23A55A)
private val RedF     = Color(0xFFED4245)

/**
 * Nickname veya Furcord ID girerek kullanıcı arama, arkadaşlık isteği gönderme
 * ve DM başlatma diyaloğu.
 */
@Composable
fun FriendAddDialog(
    currentUser: AuthUser,
    onStartDm: (uid: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tagInput     by remember { mutableStateOf("") }
    var loading      by remember { mutableStateOf(false) }
    var error        by remember { mutableStateOf("") }
    var successMsg   by remember { mutableStateOf("") }
    // Bulunan kullanıcı: uid to displayName
    var foundUser    by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope        = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(BgDialF, RoundedCornerShape(12.dp))
                .padding(24.dp)
                .width(360.dp),
        ) {
            Text(
                "Arkadaş Ekle / Mesaj Gönder",
                color = TxtF, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Nickname veya Furcord ID gir  •  Kendi ID'n: ${currentUser.furcordId.ifBlank { "yükleniyor…" }}",
                color = TxtSubF, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            // ── Arama alanı ───────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = tagInput,
                    onValueChange = { tagInput = it.take(32); foundUser = null; error = ""; successMsg = "" },
                    label         = { Text("Kullanıcı Adı veya ID", color = TxtSubF) },
                    singleLine    = true,
                    modifier      = Modifier.weight(1f),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = AccentF,
                        unfocusedBorderColor    = Color.Gray,
                        focusedContainerColor   = InputBgF,
                        unfocusedContainerColor = InputBgF,
                        focusedTextColor        = TxtF,
                        unfocusedTextColor      = TxtF,
                        cursorColor             = AccentF,
                    ),
                )
                Button(
                    onClick = {
                        val query = tagInput.trim()
                        if (query.length < 2) { error = "En az 2 karakter girin."; return@Button }
                        loading = true; error = ""; successMsg = ""; foundUser = null
                        scope.launch {
                            val result = if (query.length == 8 && query.all { it.isLetterOrDigit() }) {
                                FirestoreClient.getUserByFurcordId(query.uppercase(), currentUser.idToken)
                            } else {
                                FirestoreClient.getUserByNickname(query, currentUser.idToken)
                            }
                            loading = false
                            when {
                                result == null           -> error = "Kullanıcı bulunamadı."
                                result.first == currentUser.uid -> error = "Kendinize istek gönderemezsiniz."
                                else                     -> foundUser = result
                            }
                        }
                    },
                    enabled = !loading,
                    colors  = ButtonDefaults.buttonColors(containerColor = AccentF),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Ara", color = Color.White)
                }
            }

            // ── Hata / başarı mesajı ──────────────────────────────────────────
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(error, color = RedF, fontSize = 12.sp)
            }
            if (successMsg.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(successMsg, color = GreenF, fontSize = 12.sp)
            }

            // ── Bulunan kullanıcı kartı ───────────────────────────────────────
            val user = foundUser
            if (user != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(InputBgF, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UserAvatar(displayName = user.second, photoURL = "", size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(user.second, color = TxtF, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Kullanıcı bulundu", color = TxtSubF, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Arkadaşlık isteği gönder
                    Button(
                        onClick = {
                            loading = true; error = ""; successMsg = ""
                            scope.launch {
                                val fromName = currentUser.nickname.ifBlank { currentUser.displayName.ifBlank { currentUser.email } }
                                val result = runCatching {
                                    FirestoreClient.sendFriendRequest(
                                        toUid     = user.first,
                                        fromUid   = currentUser.uid,
                                        fromName  = fromName,
                                        furcordId = currentUser.furcordId,
                                        idToken   = currentUser.idToken,
                                    )
                                }
                                loading = false
                                if (result.isSuccess) {
                                    successMsg = "Arkadaşlık isteği gönderildi!"
                                    foundUser  = null
                                } else {
                                    error = "Gönderilemedi: ${result.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        enabled  = !loading,
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = GreenF),
                    ) {
                        Text("İstek Gönder", color = Color.White, fontSize = 13.sp)
                    }
                    // Direkt mesaj aç
                    Button(
                        onClick  = { onStartDm(user.first, user.second) },
                        enabled  = !loading,
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = AccentF),
                    ) {
                        Text("Mesaj Gönder", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Kapat", color = TxtSubF) }
            }
        }
    }
}

