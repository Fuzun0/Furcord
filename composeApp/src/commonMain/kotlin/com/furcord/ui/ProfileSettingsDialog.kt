package com.furcord.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.furcord.auth.AppPrefs
import com.furcord.auth.AuthUser
import com.furcord.auth.FirestoreClient
import com.furcord.platform.decodeBase64ToBitmap
import com.furcord.platform.pickImageAsBase64
import com.furcord.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Reusable avatar — shows real photo if photoURL is base64, otherwise initials
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun UserAvatar(
    displayName: String,
    photoURL: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap: ImageBitmap? = remember(photoURL) {
        if (photoURL.length > 100) decodeBase64ToBitmap(photoURL) else null
    }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFF5865F2)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap           = bitmap,
                contentDescription = displayName,
                modifier         = Modifier.fillMaxSize(),
                contentScale     = ContentScale.Crop,
            )
        } else {
            Text(
                text       = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize   = (size * 0.4).sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile settings dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ProfileSettingsDialog(
    currentUser: AuthUser,
    onDismiss: () -> Unit,
    onSaved: (newDisplayName: String, newPhotoURL: String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var displayName    by remember { mutableStateOf(currentUser.displayName) }
    var photoURL       by remember { mutableStateOf(currentUser.photoURL) }
    var pickingPhoto   by remember { mutableStateOf(false) }
    var photoMsg       by remember { mutableStateOf("") }
    var saving         by remember { mutableStateOf(false) }
    var error          by remember { mutableStateOf("") }
    var success        by remember { mutableStateOf(false) }
    var micGain        by remember { mutableStateOf(AppPrefs.micGain) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    LaunchedEffect(currentUser.uid) {
        val profile = try {
            FirestoreClient.getUserProfile(currentUser.uid, currentUser.idToken)
        } catch (_: Exception) { null }
        if (profile != null) {
            if (displayName.isEmpty()) displayName = profile.first
            if (photoURL.isEmpty())    photoURL    = profile.second
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape          = RoundedCornerShape(12.dp),
            color          = Color(0xFF2B2D31),
            tonalElevation = 8.dp,
            modifier       = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text       = "Profil Ayarlari",
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFFF2F3F5),
                )
                HorizontalDivider(color = Color(0xFF3F4147))

                // Avatar + pick button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Clickable avatar circle
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF5865F2))
                            .clickable(enabled = !pickingPhoto) {
                                scope.launch {
                                    pickingPhoto = true
                                    error = ""; photoMsg = ""
                                    val b64 = withContext(Dispatchers.Default) { pickImageAsBase64() }
                                    if (b64 != null) {
                                        photoURL = b64
                                        photoMsg = "Fotograf secildi!"
                                    }
                                    pickingPhoto = false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap: ImageBitmap? = remember(photoURL) {
                            if (photoURL.length > 100) decodeBase64ToBitmap(photoURL) else null
                        }
                        if (pickingPhoto) {
                            CircularProgressIndicator(modifier = Modifier.size(30.dp), color = Color.White)
                        } else if (bitmap != null) {
                            Image(
                                bitmap             = bitmap,
                                contentDescription = "Avatar",
                                modifier           = Modifier.fillMaxSize(),
                                contentScale       = ContentScale.Crop,
                            )
                        } else {
                            Text(
                                text       = displayName.ifEmpty { currentUser.email }.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                fontSize   = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color      = Color.White,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pickingPhoto = true
                                error = ""; photoMsg = ""
                                val b64 = withContext(Dispatchers.Default) { pickImageAsBase64() }
                                if (b64 != null) {
                                    photoURL = b64
                                    photoMsg = "Fotograf secildi!"
                                }
                                pickingPhoto = false
                            }
                        },
                        enabled = !pickingPhoto,
                        shape   = RoundedCornerShape(8.dp),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB5BAC1)),
                    ) {
                        Text(if (pickingPhoto) "Seciliyor..." else "Dosyadan Sec", fontSize = 13.sp)
                    }
                    if (photoMsg.isNotEmpty()) {
                        Text(text = photoMsg, fontSize = 12.sp, color = Color(0xFF23A55A))
                    }
                }

                // Display name field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text          = "KULLANICI ADI",
                        fontSize      = 11.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = Color(0xFF8E9297),
                        letterSpacing = 0.8.sp,
                    )
                    OutlinedTextField(
                        value         = displayName,
                        onValueChange = { displayName = it; error = ""; success = false },
                        placeholder   = { Text("Kullanici adin") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        shape         = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = Color(0xFF5865F2),
                            unfocusedBorderColor    = Color(0xFF3F4147),
                            focusedTextColor        = Color(0xFFF2F3F5),
                            unfocusedTextColor      = Color(0xFFF2F3F5),
                            focusedContainerColor   = Color(0xFF1E1F22),
                            unfocusedContainerColor = Color(0xFF1E1F22),
                        ),
                    )
                }

                // Furcord ID satırı — tıklanınca panoya kopyalanır
                if (currentUser.furcordId.isNotBlank()) {
                    var copyDone by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text          = "FURCORD ID",
                                fontSize      = 11.sp,
                                fontWeight    = FontWeight.Bold,
                                color         = Color(0xFF8E9297),
                                letterSpacing = 0.8.sp,
                            )
                            Text(currentUser.furcordId, color = Color(0xFFDCDDDE), fontSize = 14.sp)
                        }
                        TextButton(onClick = {
                            val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            clip.setContents(java.awt.datatransfer.StringSelection(currentUser.furcordId), null)
                            copyDone = true
                        }) {
                            Text(if (copyDone) "Kopyalandı!" else "Kopyala", color = Color(0xFF5865F2), fontSize = 12.sp)
                        }
                    }
                }

                if (error.isNotEmpty()) Text(text = error, fontSize = 12.sp, color = Color(0xFFF23F43))
                if (success)            Text(text = "Profil guncellendi!", fontSize = 12.sp, color = Color(0xFF23A55A))

                HorizontalDivider(color = Color(0xFF3F4147))

                // ── Mikrofon Seviyesi ──────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text          = "MİKROFON SEVİYESİ",
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.Bold,
                            color         = Color(0xFF8E9297),
                            letterSpacing = 0.8.sp,
                            modifier      = Modifier.weight(1f),
                        )
                        Text(
                            text      = "${(micGain * 100).toInt()}%",
                            fontSize  = 12.sp,
                            color     = Color(0xFFDCDDDE),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value         = micGain,
                        onValueChange = { v ->
                            micGain = v
                            VoiceEngine.micGain = v
                            AppPrefs.micGain    = v
                        },
                        valueRange    = 0f..4f,
                        steps         = 79,   // 0.05 adım
                        colors        = SliderDefaults.colors(
                            thumbColor       = Color(0xFF5865F2),
                            activeTrackColor = Color(0xFF5865F2),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Sessiz", fontSize = 10.sp, color = Color(0xFF6D6F78))
                        Text("Normal", fontSize = 10.sp, color = Color(0xFF6D6F78))
                        Text("2x", fontSize = 10.sp, color = Color(0xFF6D6F78))
                        Text("4x", fontSize = 10.sp, color = Color(0xFF6D6F78))
                    }
                }

                HorizontalDivider(color = Color(0xFF3F4147))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Iptal", color = Color(0xFFB5BAC1))
                    }
                    Button(
                        onClick = {
                            val name = displayName.trim()
                            if (name.isEmpty()) { error = "Kullanici adi bos olamaz."; return@Button }
                            scope.launch {
                                saving = true
                                try {
                                    FirestoreClient.saveUserProfile(
                                        uid         = currentUser.uid,
                                        displayName = name,
                                        photoURL    = photoURL,
                                        idToken     = currentUser.idToken,
                                    )
                                    success = true
                                    onSaved(name, photoURL)
                                } catch (e: Exception) {
                                    error = "Kaydedilemedi: ${e.message}"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving && !pickingPhoto,
                        shape   = RoundedCornerShape(8.dp),
                        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF5865F2)),
                    ) {
                        Text(if (saving) "Kaydediliyor..." else "Kaydet", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}