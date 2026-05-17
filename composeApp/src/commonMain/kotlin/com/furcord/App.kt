package com.furcord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.furcord.auth.AppVersionInfo
import com.furcord.auth.AuthUser
import com.furcord.auth.FirebaseAuth
import com.furcord.auth.FirestoreClient
import com.furcord.auth.DmRepository
import com.furcord.ui.AuthScreen
import com.furcord.update.UpdateManager
import kotlinx.coroutines.launch
import com.furcord.ui.ServerLobbyScreen
import com.furcord.ui.ServerDetailScreen
import com.furcord.ui.FloatingDmPanel
import com.furcord.ui.FloatingDmWindow
import com.furcord.ui.NicknameSetupDialog

private sealed class AppAuthState {
    object Loading         : AppAuthState()
    object Unauthenticated : AppAuthState()
    data class Authenticated(val user: AuthUser) : AppAuthState()
}

private sealed class AppServerState {
    object None : AppServerState()
    data class InServer(val serverId: String, val serverName: String) : AppServerState()
}

private data class DmTarget(val uid: String, val name: String)

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // Pencere odak durumu — polling hızını kontrol eder
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        var authState   by remember { mutableStateOf<AppAuthState>(AppAuthState.Loading) }
        var serverState by remember { mutableStateOf<AppServerState>(AppServerState.None) }
        var dmTarget    by remember { mutableStateOf<DmTarget?>(null) }
        var dmWindowOpen by remember { mutableStateOf(false) }
        // Giriş sonrası Firestore'dan profil yüklemek için bekleyen kullanıcı
        var pendingProfileLoad by remember { mutableStateOf<AuthUser?>(null) }
        val scope = rememberCoroutineScope()

        // Oturum geri yükle + Firestore profili merge et
        LaunchedEffect(Unit) {
            val restored = FirebaseAuth.restoreSession()
            if (restored != null) {
                val profile = runCatching {
                    FirestoreClient.getUserProfile(restored.uid, restored.idToken)
                }.getOrNull()
                val user = if (profile != null)
                    restored.copy(
                        displayName = profile.first.ifEmpty { restored.displayName },
                        photoURL    = profile.second.ifEmpty { restored.photoURL },
                        nickname    = profile.third.ifEmpty { restored.nickname },
                    )
                else restored
                authState = AppAuthState.Authenticated(user)
                // Firestore'dan gelen güncel profili yerel dosyaya da yaz
                FirebaseAuth.saveProfile(user.displayName, user.photoURL)
                if (user.nickname.isNotEmpty()) FirebaseAuth.saveNickname(user.nickname)
                // Kullanıcı kaydını Firestore'a yaz (her session restore'da güncellensin)
                runCatching {
                    FirestoreClient.saveUserRecord(
                        uid         = user.uid,
                        displayName = user.displayName,
                        photoURL    = user.photoURL,
                        furcordId   = user.furcordId,
                        email       = user.email,
                        idToken     = user.idToken,
                        nickname    = user.nickname,
                    )
                }
            } else {
                authState = AppAuthState.Unauthenticated
            }
        }

        // Giriş sonrası profil yükleme (pendingProfileLoad değişince tetiklenir)
        LaunchedEffect(pendingProfileLoad) {
            val user = pendingProfileLoad ?: return@LaunchedEffect
            val profile = runCatching {
                FirestoreClient.getUserProfile(user.uid, user.idToken)
            }.getOrNull()
            val updatedUser = if (profile != null) {
                user.copy(
                    displayName = profile.first.ifEmpty { user.displayName },
                    photoURL    = profile.second.ifEmpty { user.photoURL },
                    nickname    = profile.third.ifEmpty { user.nickname },
                )
            } else user
            authState = AppAuthState.Authenticated(updatedUser)
            // Profili yerel dosyaya kaydet
            FirebaseAuth.saveProfile(updatedUser.displayName, updatedUser.photoURL)
            // Furcord ID'yi Firestore'a kaydet (getUserByFurcordId aramaları için)
            runCatching {
                FirestoreClient.saveUserRecord(
                    uid         = updatedUser.uid,
                    displayName = updatedUser.displayName,
                    photoURL    = updatedUser.photoURL,
                    furcordId   = updatedUser.furcordId,
                    email       = updatedUser.email,
                    idToken     = updatedUser.idToken,
                    nickname    = updatedUser.nickname,
                )
            }
            pendingProfileLoad = null
        }

        when (val state = authState) {
            is AppAuthState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is AppAuthState.Unauthenticated -> {
                AuthScreen(
                    onAuthenticated = { user ->
                        serverState = AppServerState.None
                        authState   = AppAuthState.Authenticated(user)
                        pendingProfileLoad = user   // LaunchedEffect ile Firestore'dan profil yükle
                    }
                )
            }
            is AppAuthState.Authenticated -> {
                var currentUser      = state.user

                // Nickname setup
                var showNicknameSetup by remember { mutableStateOf(currentUser.nickname.isEmpty()) }

                // Güncelleme durumu
                var pendingUpdate    by remember { mutableStateOf<AppVersionInfo?>(null) }
                var updateDismissed  by remember { mutableStateOf(false) }
                var updating         by remember { mutableStateOf(false) }
                var updateProgress   by remember { mutableStateOf(0f) }
                var updateError      by remember { mutableStateOf("") }

                // Güncelleme kontrolü: başlangıçta hemen + her 30 dakikada bir
                // GitHub Releases API — kimlik doğrulaması gerektirmez
                LaunchedEffect(currentUser.uid) {
                    while (true) {
                        val info = runCatching { UpdateManager.checkPublicVersion() }.getOrNull()
                        if (info != null && UpdateManager.isNewerVersion(info.latestVersion)) {
                            if (pendingUpdate == null) updateDismissed = false
                            pendingUpdate = info
                        }
                        delay(30 * 60_000L)  // 30 dakika
                    }
                }

                // Token yenileme döngüsü — her 45 dakikada bir (1 saatlik süre dolmadan)
                LaunchedEffect(currentUser.uid, "tokenRefresh") {
                    delay(45 * 60 * 1000L)  // ilk 45 dk bekle
                    while (true) {
                        val newToken = runCatching { FirebaseAuth.refreshToken() }.getOrNull()
                        if (newToken != null) {
                            DmRepository.updateToken(newToken)
                            authState = AppAuthState.Authenticated(currentUser.copy(idToken = newToken))
                        }
                        delay(45 * 60 * 1000L)
                    }
                }

                // DM sohbet havuzu — giriş anından itibaren arka planda yoklar
                LaunchedEffect(currentUser.uid, "dmRepo") {
                    DmRepository.start(currentUser.uid, currentUser.idToken)
                }

                // Pencere odak değişikliğini DmRepository ve polling döngülerine ilet
                LaunchedEffect(windowFocused) {
                    DmRepository.setFocused(windowFocused)
                }

                // Nickname setup dialog
                if (showNicknameSetup) {
                    NicknameSetupDialog(
                        uid = currentUser.uid,
                        email = currentUser.email,
                        idToken = currentUser.idToken,
                        onNicknameSet = { uid, nickname ->
                            showNicknameSetup = false
                            authState = AppAuthState.Authenticated(currentUser.copy(nickname = nickname))
                        }
                    )
                    return@MaterialTheme
                }

                // Güncelleme dialog'u — otomatik indir + kur
                if (pendingUpdate != null && !updateDismissed) {
                    val info = pendingUpdate!!
                    val scope = rememberCoroutineScope()
                    AlertDialog(
                        onDismissRequest = { if (!updating) updateDismissed = true },
                        title = { Text("� Furcord Yeni Sürüm Yayında!") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("v${UpdateManager.currentVersion}  →  v${info.latestVersion}")
                                HorizontalDivider()
                                // Scrollable, kullanıcı dostu Türkçe değişiklik listesi
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val changelog = listOf(
                                            "🎙️ Pürüzsüz Ses Deneyimi: Ses altyapımızı tamamen yeniledik. Artık arka plan gürültüsü olmadan, Discord kalitesinde kesintisiz sohbet edebilirsiniz.",
                                            "💬 Mesaj Yanıtlama: Tıpkı WhatsApp'taki gibi artık önceki mesajları alıntılayıp cevap verebilirsiniz.",
                                            "📋 Toplu Kopyalama: Birden fazla mesajı seçip tek seferde kopyalama özelliği eklendi.",
                                            "⚡ Işık Hızında Görseller: Gönderilen resimler artık cihazınıza kaydediliyor. Eskilere dönüp bakarken resimler internet beklemeden anında açılacak!",
                                            "🛠️ Yayın özelliği, size çok daha iyi bir deneyim sunabilmemiz için kısa süreliğine bakıma alındı.",
                                        )
                                        changelog.forEach { line ->
                                            Text("• $line", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if (updating) {
                                    Spacer(Modifier.height(4.dp))
                                    if (updateProgress > 0f) {
                                        LinearProgressIndicator(
                                            progress = { updateProgress },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "İndiriliyor… %${(updateProgress * 100).toInt()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text("Hazırlanıyor…", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (updateError.isNotBlank()) {
                                    Text(updateError, style = MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.ui.graphics.Color(0xFFFF5555))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !updating,
                                onClick = {
                                    updating = true; updateProgress = 0f; updateError = ""
                                    scope.launch {
                                        val file = UpdateManager.downloadInstaller(info.downloadUrl) {
                                            updateProgress = it
                                        }
                                        if (file == null) {
                                            updateError = "İndirme başarısız. Tekrar deneyin."
                                            updating = false
                                            return@launch
                                        }
                                        val ok = UpdateManager.installMsi(file)
                                        if (!ok) {
                                            updateError = "Kurulum başarısız. Manuel kurulum gerekebilir."
                                            updating = false
                                            return@launch
                                        }
                                        UpdateManager.restartApp()
                                    }
                                },
                            ) { Text(if (updating) "Güncelleniyor…" else "Güncelle") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !updating,
                                onClick = { updateDismissed = true },
                            ) { Text("Sonra") }
                        }
                    )
                }

                Box(Modifier.fillMaxSize()) {
                    when (val ss = serverState) {
                        is AppServerState.None -> {
                            ServerLobbyScreen(
                                currentUser     = currentUser,
                                hasUpdate       = pendingUpdate != null && !updateDismissed,
                                isWindowFocused = windowFocused,
                                onJoinServer    = { id, name ->
                                    serverState = AppServerState.InServer(id, name)
                                },
                                onSignOut      = {
                                    FirebaseAuth.signOut()
                                    DmRepository.stop()
                                    serverState = AppServerState.None
                                    authState   = AppAuthState.Unauthenticated
                                },
                                onUserUpdated  = { newName, newPhoto ->
                                    authState = AppAuthState.Authenticated(
                                        currentUser.copy(displayName = newName, photoURL = newPhoto)
                                    )
                                    FirebaseAuth.saveProfile(newName, newPhoto)
                                },
                                onOpenDm       = { uid, name ->
                                    DmRepository.markRead(listOf(currentUser.uid, uid).sorted().joinToString("_"))
                                    dmTarget = DmTarget(uid, name)
                                    dmWindowOpen = true
                                },
                            )
                        }
                        is AppServerState.InServer -> {
                            ServerDetailScreen(
                                serverId      = ss.serverId,
                                serverName    = ss.serverName,
                                currentUser   = currentUser,
                                hasUpdate     = pendingUpdate != null && !updateDismissed,
                                onLeaveServer = {
                                    serverState = AppServerState.None
                                },
                                onUserUpdated = { newName, newPhoto ->
                                    authState = AppAuthState.Authenticated(
                                        currentUser.copy(displayName = newName, photoURL = newPhoto)
                                    )
                                    FirebaseAuth.saveProfile(newName, newPhoto)
                                },
                                onOpenDm = { uid, name ->
                                    DmRepository.markRead(listOf(currentUser.uid, uid).sorted().joinToString("_"))
                                    dmTarget = DmTarget(uid, name)
                                    dmWindowOpen = true
                                },
                            )
                        }
                    }

                    FloatingDmPanel(
                        currentUser    = currentUser,
                        myUid          = currentUser.uid,
                        bottomOffset   = 0.dp,
                        onDmWindowOpen = { dmWindowOpen = true },
                    )

                    // ── Kayan DM penceresi overlay (ses bağlantısını kesmez) ───────────
                    if (dmWindowOpen) {
                        FloatingDmWindow(
                            currentUser          = currentUser,
                            initialRecipientUid  = dmTarget?.uid,
                            initialRecipientName = dmTarget?.name,
                            onClose              = { dmWindowOpen = false; dmTarget = null },
                        )
                    }

                    // Sol altta her zaman görünür sürüm etiketi (en üst katman)
                    Text(
                        text = "v${UpdateManager.currentVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
}