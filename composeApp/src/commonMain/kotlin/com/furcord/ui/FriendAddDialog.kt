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

/**
 * Nickname veya Furcord ID girerek arkadaş arama ve DM başlatma diyaloğu.
 *
 * @param currentUser Oturum açmış kullanıcı
 * @param onStartDm DM ekranı açılması için uid + displayName döner
 * @param onDismiss Diyalogu kapat
 */
@Composable
fun FriendAddDialog(
    currentUser: AuthUser,
    onStartDm: (uid: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tagInput  by remember { mutableStateOf("") }
    var loading   by remember { mutableStateOf(false) }
    var error     by remember { mutableStateOf("") }
    val scope     = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(BgDialF, RoundedCornerShape(12.dp))
                .padding(24.dp)
                .width(340.dp),
        ) {
            Text("Arkadaş Ekle / Mesaj Gönder",
                color = TxtF, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Kişinin Kullanıcı Adını (Nickname) veya Furcord ID'sini gir\n" +
                "Örnek: john_doe veya ABCD1234\n" +
                "Kendi ID'n: ${currentUser.furcordId.ifBlank { "yükleniyor…" }}",
                color = TxtSubF, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value         = tagInput,
                onValueChange = { tagInput = it.take(32) },
                label         = { Text("Kullanıcı Adı veya ID", color = TxtSubF) },
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentF,
                    unfocusedBorderColor = Color.Gray,
                    focusedContainerColor   = InputBgF,
                    unfocusedContainerColor = InputBgF,
                    focusedTextColor     = TxtF,
                    unfocusedTextColor   = TxtF,
                    cursorColor          = AccentF,
                ),
            )

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(error, color = Color(0xFFED4245), fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("İptal", color = TxtSubF) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val query = tagInput.trim()
                        if (query.length < 2) { error = "En az 2 karakter girin."; return@Button }
                        loading = true; error = ""
                        scope.launch {
                            // Uppercase kontr veya lowercase — nickname gibi davran
                            val result = if (query.length == 8 && query.all { it.isLetterOrDigit() }) {
                                // Furcord ID gibi görünüyor (8 karakterli alphanumeric)
                                FirestoreClient.getUserByFurcordId(query.uppercase(), currentUser.idToken)
                            } else {
                                // Nickname araması
                                FirestoreClient.getUserByNickname(query, currentUser.idToken)
                            }
                            loading = false
                            if (result == null) {
                                error = "Kullanıcı bulunamadı."
                            } else {
                                val (uid, name) = result
                                if (uid == currentUser.uid) {
                                    error = "Kendinize mesaj gönderemezsiniz."
                                } else {
                                    onStartDm(uid, name)
                                }
                            }
                        }
                    },
                    enabled  = !loading,
                    colors   = ButtonDefaults.buttonColors(containerColor = AccentF),
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Bul & Mesaj Gönder", color = Color.White)
                }
            }
        }
    }
}
