package com.furcord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
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
                var pendingUpdate   by remember { mutableStateOf<AppVersionInfo?>(null) }
                var updateDismissed by remember { mutableStateOf(false) }

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

                // Güncelleme dialog'u — tarayıcı ile indir
                if (pendingUpdate != null && !updateDismissed) {
                    val info = pendingUpdate!!
                    AlertDialog(
                        onDismissRequest = { updateDismissed = true },
                        title = { Text("Güncelleme Mevcut") },
                        text = {
                            Column {
                                Text("Yeni sürüm: v${info.latestVersion}  (Şu an: v${UpdateManager.currentVersion})")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "İndir butonuna tıklayarak kurulum dosyasını indirebilirsiniz.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                UpdateManager.openDownloadPage(info.downloadUrl)
                                updateDismissed = true
                            }) { Text("İndir") }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateDismissed = true }) { Text("Sonra") }
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

                    // FloatingDmPanel — sağ alt köşede, her zaman görünür
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