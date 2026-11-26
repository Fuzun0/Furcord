package com.furcord.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.furcord.ui.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.isActive
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
            .background(AppColors.Accent),
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

private enum class MicTestState { Idle, Recording, Recorded, Playing }

private val MIC_TEST_FORMAT = javax.sound.sampled.AudioFormat(48000f, 16, 1, true, false)
private const val MIC_TEST_MAX_BYTES = 48000 * 2 * 10   // en fazla 10 sn kayıt

// ─────────────────────────────────────────────────────────────────────────────
// Profile settings dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ProfileSettingsDialog(
    currentUser: AuthUser,
    onDismiss: () -> Unit,
    onSaved: (newDisplayName: String, newPhotoURL: String) -> Unit,
    onSignOut: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var micGain      by remember { mutableStateOf(AppPrefs.micGain) }
    // ── Mikrofon testi durumu ──
    var micState     by remember { mutableStateOf(MicTestState.Idle) }
    var micLevel     by remember { mutableStateOf(0f) }
    var micTestError by remember { mutableStateOf("") }
    var micRecording by remember { mutableStateOf<ByteArray?>(null) }
    var micJob       by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) { onDispose { micJob?.cancel() } }

    fun startMicRecord() {
        micJob?.cancel()
        micTestError = ""
        micRecording = null
        micState = MicTestState.Recording
        micJob = scope.launch(Dispatchers.IO) {
            var line: javax.sound.sampled.TargetDataLine? = null
            val out = java.io.ByteArrayOutputStream()
            try {
                line = javax.sound.sampled.AudioSystem.getLine(
                    javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine::class.java, MIC_TEST_FORMAT)
                ) as javax.sound.sampled.TargetDataLine
                line.open(MIC_TEST_FORMAT)
                line.start()
                val buf = ByteArray(1920)   // 20 ms
                while (isActive && out.size() < MIC_TEST_MAX_BYTES) {
                    val n = line.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val gain = micGain
                    var peak = 0
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        val v = (s * gain).toInt().coerceIn(-32768, 32767)
                        buf[i]     = (v and 0xFF).toByte()
                        buf[i + 1] = ((v shr 8) and 0xFF).toByte()
                        val a = kotlin.math.abs(v)
                        if (a > peak) peak = a
                        i += 2
                    }
                    micLevel = (peak / 32768f).coerceIn(0f, 1f)
                    out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                micTestError = "Mikrofon açılamadı: ${e.message}"
            } finally {
                runCatching { line?.stop(); line?.close() }
                micLevel = 0f
                val data = out.toByteArray()
                micRecording = if (data.isNotEmpty()) data else null
                micState = if (data.isNotEmpty()) MicTestState.Recorded else MicTestState.Idle
            }
        }
    }

    fun startMicPlayback() {
        val data = micRecording ?: return
        micJob?.cancel()
        micTestError = ""
        micState = MicTestState.Playing
        micJob = scope.launch(Dispatchers.IO) {
            var line: javax.sound.sampled.SourceDataLine? = null
            try {
                line = javax.sound.sampled.AudioSystem.getLine(
                    javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, MIC_TEST_FORMAT)
                ) as javax.sound.sampled.SourceDataLine
                line.open(MIC_TEST_FORMAT)
                line.start()
                var off = 0
                while (isActive && off < data.size) {
                    val len = minOf(3840, data.size - off)
                    line.write(data, off, len)
                    off += len
                }
                if (isActive) line.drain()
            } catch (e: Exception) {
                micTestError = "Ses çalınamadı: ${e.message}"
            } finally {
                runCatching { line?.stop(); line?.close() }
                micState = MicTestState.Recorded
            }
        }
    }

    var displayName    by remember { mutableStateOf(currentUser.displayName) }
    var photoURL       by remember { mutableStateOf(currentUser.photoURL) }
    var pickingPhoto   by remember { mutableStateOf(false) }
    var photoMsg       by remember { mutableStateOf("") }
    var saving         by remember { mutableStateOf(false) }
    var error          by remember { mutableStateOf("") }
    var success        by remember { mutableStateOf(false) }
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
            color          = AppColors.BgSidebar,
            tonalElevation = 8.dp,
            modifier       = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text       = "Profil Ayarlari",
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color      = AppColors.TextPrimary,
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
                            .background(AppColors.Accent)
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
                        Text(text = photoMsg, fontSize = 12.sp, color = AppColors.Online)
                    }
                }

                // Display name field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text          = "KULLANICI ADI",
                        fontSize      = 11.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = AppColors.TextMuted,
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
                            focusedBorderColor      = AppColors.Accent,
                            unfocusedBorderColor    = Color(0xFF3F4147),
                            focusedTextColor        = AppColors.TextPrimary,
                            unfocusedTextColor      = AppColors.TextPrimary,
                            focusedContainerColor   = AppColors.Outline,
                            unfocusedContainerColor = AppColors.Outline,
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
                                color         = AppColors.TextMuted,
                                letterSpacing = 0.8.sp,
                            )
                            Text(currentUser.furcordId, color = AppColors.TextSecondary, fontSize = 14.sp)
                        }
                        TextButton(onClick = {
                            val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            clip.setContents(java.awt.datatransfer.StringSelection(currentUser.furcordId), null)
                            copyDone = true
                        }) {
                            Text(if (copyDone) "Kopyalandı!" else "Kopyala", color = AppColors.Accent, fontSize = 12.sp)
                        }
                    }
                }

                if (error.isNotEmpty()) Text(text = error, fontSize = 12.sp, color = AppColors.Danger)
                if (success)            Text(text = "Profil guncellendi!", fontSize = 12.sp, color = AppColors.Online)

                HorizontalDivider(color = Color(0xFF3F4147))

                // ── Mikrofon Seviyesi ──────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text          = "MİKROFON SEVİYESİ",
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.Bold,
                            color         = AppColors.TextMuted,
                            letterSpacing = 0.8.sp,
                            modifier      = Modifier.weight(1f),
                        )
                        Text(
                            text      = "${(micGain * 100).toInt()}%",
                            fontSize  = 12.sp,
                            color     = AppColors.TextSecondary,
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
                            thumbColor       = AppColors.Accent,
                            activeTrackColor = AppColors.Accent,
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

                // ── Mikrofon testi ─────────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text          = "MİKROFON TESTİ",
                        fontSize      = 11.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = AppColors.TextMuted,
                        letterSpacing = 0.8.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (micState) {
                            MicTestState.Idle -> OutlinedButton(
                                onClick = { startMicRecord() },
                                shape   = RoundedCornerShape(8.dp),
                                colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB5BAC1)),
                            ) { Text("Mikrofonu Test Et", fontSize = 13.sp) }

                            MicTestState.Recording -> Button(
                                onClick = { micJob?.cancel() },
                                shape   = RoundedCornerShape(8.dp),
                                colors  = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                            ) { Text("Kaydı Durdur", fontSize = 13.sp) }

                            MicTestState.Recorded -> {
                                Button(
                                    onClick = { startMicPlayback() },
                                    shape   = RoundedCornerShape(8.dp),
                                    colors  = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                                ) { Text("▶ Yürüt", fontSize = 13.sp) }
                                OutlinedButton(
                                    onClick = { startMicRecord() },
                                    shape   = RoundedCornerShape(8.dp),
                                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB5BAC1)),
                                ) { Text("Tekrar Kaydet", fontSize = 13.sp) }
                            }

                            MicTestState.Playing -> Button(
                                onClick = { micJob?.cancel() },
                                shape   = RoundedCornerShape(8.dp),
                                colors  = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                            ) { Text("■ Durdur", fontSize = 13.sp) }
                        }
                    }
                    when (micState) {
                        MicTestState.Recording -> {
                            LinearProgressIndicator(
                                progress   = { micLevel },
                                modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color      = AppColors.Online,
                                trackColor = AppColors.Outline,
                            )
                            Text("Kaydediliyor… konuş, sonra durdur (en fazla 10 sn)", fontSize = 11.sp, color = AppColors.TextMuted)
                        }
                        MicTestState.Playing  -> Text("Kayıt çalınıyor…", fontSize = 11.sp, color = AppColors.TextMuted)
                        MicTestState.Recorded -> Text("Kayıt hazır. Yürüt'e basarak kendini dinle.", fontSize = 11.sp, color = AppColors.TextMuted)
                        else -> {}
                    }
                    if (micTestError.isNotEmpty()) Text(micTestError, fontSize = 12.sp, color = AppColors.Danger)
                }

                HorizontalDivider(color = Color(0xFF3F4147))

                if (onSignOut != null) {
                    OutlinedButton(
                        onClick  = onSignOut,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(8.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger),
                    ) { Text("Hesaptan Çıkış Yap", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }

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
                        colors  = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                    ) {
                        Text(if (saving) "Kaydediliyor..." else "Kaydet", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}