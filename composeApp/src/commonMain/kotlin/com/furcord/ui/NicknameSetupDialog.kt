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
import androidx.compose.ui.window.DialogProperties
import com.furcord.auth.FirestoreClient
import kotlinx.coroutines.launch

private val AccentN  = Color(0xFF5865F2)
private val BgDialN  = Color(0xFF2B2D31)
private val InputBgN = Color(0xFF383A40)
private val TxtN     = Color(0xFFDCDDDE)
private val TxtSubN  = Color(0xFF96989D)
private val GreenN   = Color(0xFF57F287)
private val RedN     = Color(0xFFED4245)

/**
 * Yeni kullanıcı için E\u015fsiz Kullanıcı Ad\u0131 (Nickname) setup dialog'u.
 * Google login sonrası çağrılır.
 *
 * @param uid Kullanıcı UID'si
 * @param email Kullanıcı e-postası
 * @param idToken Firebase ID token
 * @param onNicknameSet Nickname kaydedilince (uid, nickname) ile çağrılır
 */
@Composable
fun NicknameSetupDialog(
    uid: String,
    email: String,
    idToken: String,
    onNicknameSet: (String, String) -> Unit,
) {
    var nicknameInput by remember { mutableStateOf("") }
    var checkingAvail by remember { mutableStateOf(false) }
    var isAvailable   by remember { mutableStateOf<Boolean?>(null) }
    var savingNick    by remember { mutableStateOf(false) }
    var saveError     by remember { mutableStateOf("") }
    val scope         = rememberCoroutineScope()

    Dialog(
        onDismissRequest = {},  // Kapat\u0131lmas\u0131n\u0131 engelle — setup zorunlu
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .background(BgDialN, RoundedCornerShape(12.dp))
                .padding(32.dp)
                .width(380.dp),
        ) {
            Text(
                "Kullan\u0131c\u0131 Ad\u0131 Olu\u015ftur",
                color = TxtN, fontWeight = FontWeight.Bold, fontSize = 20.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Furcord'da seni belirleyecek benzersiz bir kullan\u0131c\u0131 ad\u0131 seç.\n" +
                "Bu ad daha sonra de\u011fi\u015ftirilebilir.",
                color = TxtSubN, fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))

            // Nickname input
            OutlinedTextField(
                value         = nicknameInput,
                onValueChange = { nicknameInput = it.take(32) },
                label         = { Text("Kullan\u0131c\u0131 Ad\u0131", color = TxtSubN) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentN,
                    unfocusedBorderColor = Color.Gray,
                    focusedContainerColor   = InputBgN,
                    unfocusedContainerColor = InputBgN,
                    focusedTextColor     = TxtN,
                    unfocusedTextColor   = TxtN,
                    cursorColor          = AccentN,
                ),
            )

            Spacer(Modifier.height(16.dp))

            // Kullan\u0131labilirlik durumu
            if (nicknameInput.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            checkingAvail = true
                            isAvailable = null
                            saveError = ""
                            scope.launch {
                                val avail = FirestoreClient.checkNicknameAvailable(nicknameInput, idToken)
                                checkingAvail = false
                                isAvailable = avail
                                if (!avail) saveError = "Bu kullan\u0131c\u0131 ad\u0131 zaten kulllan\u0131l\u0131yor."
                            }
                        },
                        enabled = !checkingAvail && isAvailable != true,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentN),
                    ) {
                        if (checkingAvail) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
                        } else {
                            Text("Kontrol Et", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    if (isAvailable == true) {
                        Text("\u2713 M\u00fcsait", color = GreenN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    } else if (isAvailable == false) {
                        Text("\u2717 Kapat", color = RedN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (saveError.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(saveError, color = RedN, fontSize = 11.sp)
            }

            Spacer(Modifier.height(24.dp))

            // Tamam butonu
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        if (isAvailable != true) return@Button
                        savingNick = true
                        scope.launch {
                            runCatching {
                                FirestoreClient.saveUserRecord(
                                    uid = uid, displayName = email.substringBefore("@"),
                                    photoURL = "", furcordId = "",
                                    email = email, idToken = idToken, nickname = nicknameInput
                                )
                                // Da FilebaseAuth'a da kaydet (lokal)
                                com.furcord.auth.FirebaseAuth.saveNickname(nicknameInput)
                            }
                            savingNick = false
                            onNicknameSet(uid, nicknameInput)
                        }
                    },
                    enabled = isAvailable == true && !savingNick && !checkingAvail,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAvailable == true) AccentN else Color.Gray,
                    ),
                    modifier = Modifier.height(40.dp),
                ) {
                    if (savingNick) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Tamam", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
