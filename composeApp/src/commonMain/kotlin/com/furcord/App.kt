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
import java.io.File
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.furcord.auth.AppVersionInfo
import com.furcord.auth.AuthUser
import com.furcord.auth.FirebaseAuth
import com.furcord.auth.FirestoreClient
import com.furcord.ui.AuthScreen
import com.furcord.update.UpdateManager
import kotlinx.coroutines.launch
import com.furcord.ui.ServerLobbyScreen
import com.furcord.ui.ServerDetailScreen
import com.furcord.ui.DmChatScreen
import com.furcord.ui.FloatingDmPanel
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
        var authState   by remember { mutableStateOf<AppAuthState>(AppAuthState.Loading) }
        var serverState by remember { mutableStateOf<AppServerState>(AppServerState.None) }
        var dmTarget    by remember { mutableStateOf<DmTarget?>(null) }
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
                        photoURL    = profile.second.ifEmpty { restored.photoURL }
                    )
                else restored
                authState = AppAuthState.Authenticated(user)
                // Firestore'dan gelen güncel profili yerel dosyaya da yaz
                FirebaseAuth.saveProfile(user.displayName, user.photoURL)
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
                    photoURL    = profile.second.ifEmpty { user.photoURL }
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
                var pendingUpdate     by remember { mutableStateOf<AppVersionInfo?>(null) }
                var updateDismissed   by remember { mutableStateOf(false) }
                var downloading       by remember { mutableStateOf(false) }
                var downloadProgress  by remember { mutableStateOf(0f) }
                var downloadError     by remember { mutableStateOf(false) }
                var installerFile     by remember { mutableStateOf<File?>(null) }
                var installing        by remember { mutableStateOf(false) }
                var installStatus     by remember { mutableStateOf("") }
                var installError      by remember { mutableStateOf(false) }

                // İndirme bitti → kurulum başlat
                LaunchedEffect(installerFile) {
                    val file = installerFile ?: return@LaunchedEffect
                    installing = true
                    installStatus = "Kuruluyor..."
                    val ok = UpdateManager.installMsi(file)
                    if (ok) {
                        // Kurulum başarılı — durumu kalsın
                        installStatus = "Kurulum tamamlandı"
                    } else {
                        installStatus = "Kurulum başarısız!"
                        installError = true
                        installing = false
                    }
                }

                // Giriş sonrası güncelleme kontrolü (tek seferlik)
                LaunchedEffect(currentUser.uid) {
                    val info = runCatching {
                        FirestoreClient.getLatestVersion(currentUser.idToken)
                    }.getOrNull()
                    if (info != null && UpdateManager.isNewerVersion(info.latestVersion)) {
                        pendingUpdate = info
                    }
                }

                // Nickname setup dialog
                if (showNicknameSetup) {
                    NicknameSetupDialog(
                        uid = currentUser.uid,
                        email = currentUser.email,
                        idToken = currentUser.idToken,
                        onNicknameSet = { uid, nickname ->
                            currentUser = currentUser.copy(nickname = nickname)
                            showNicknameSetup = false
                        }
                    )
                    return@MaterialTheme
                }

                // Kurulum tam ekran overlay
                if (installing) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (!installError) CircularProgressIndicator()
                            Spacer(Modifier.height(20.dp))
                            Text(installStatus, style = MaterialTheme.typography.titleMedium)
                            if (!installError) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Uygulama kapanıp geri açılacak",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { installing = false; installerFile = null; installError = false }) {
                                    Text("Tamam")
                                }
                            }
                        }
                    }
                    return@MaterialTheme
                }

                // Güncelleme dialog'u
                if (pendingUpdate != null && !updateDismissed && installerFile == null) {
                    val info = pendingUpdate!!
                    AlertDialog(
                        onDismissRequest = { if (!downloading) updateDismissed = true },
                        title = { Text("Güncelleme Mevcut") },
                        text = {
                            Column {
                                Text("Yeni sürüm: ${info.latestVersion}  (Şu an: ${UpdateManager.currentVersion})")
                                if (info.releaseNotes.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall)
                                }
                                if (downloading) {
                                    Spacer(Modifier.height(12.dp))
                                    if (downloadProgress > 0f) {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "%${(downloadProgress * 100).toInt()} indiriliyor…",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text("İndiriliyor…", style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                                if (downloadError) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("İndirme başarısız. İnternet bağlantınızı kontrol edin.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !downloading,
                                onClick = {
                                    downloading = true
                                    downloadError = false
                                    scope.launch {
                                        val file = UpdateManager.downloadInstaller(info.downloadUrl) { p ->
                                            downloadProgress = p
                                        }
                                        if (file != null) {
                                            installerFile = file
                                        } else {
                                            downloadError = true
                                            downloading = false
                                        }
                                    }
                                }
                            ) { Text("Güncelle") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !downloading,
                                onClick = { updateDismissed = true }
                            ) { Text("Sonra") }
                        }
                    )
                }

                // DM ekranı tüm navigasyonun üstüne geliyor
                if (dmTarget != null) {
                    val dm = dmTarget!!
                    Box(Modifier.fillMaxSize()) {
                        DmChatScreen(
                            currentUser   = currentUser,
                            recipientUid  = dm.uid,
                            recipientName = dm.name,
                            onBack        = { dmTarget = null },
                        )
                        Text(
                            text = "v${UpdateManager.currentVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 8.dp)
                        )
                    }
                    return@MaterialTheme
                }

                Box(Modifier.fillMaxSize()) {
                    when (val ss = serverState) {
                        is AppServerState.None -> {
                            ServerLobbyScreen(
                                currentUser    = currentUser,
                                onJoinServer   = { id, name ->
                                    serverState = AppServerState.InServer(id, name)
                                },
                                onSignOut      = {
                                    FirebaseAuth.signOut()
                                    serverState = AppServerState.None
                                    authState   = AppAuthState.Unauthenticated
                                },
                                onUserUpdated  = { newName, newPhoto ->
                                    authState = AppAuthState.Authenticated(
                                        currentUser.copy(displayName = newName, photoURL = newPhoto)
                                    )
                                    FirebaseAuth.saveProfile(newName, newPhoto)
                                },
                                onOpenDm       = { uid, name -> dmTarget = DmTarget(uid, name) },
                            )
                        }
                        is AppServerState.InServer -> {
                            ServerDetailScreen(
                                serverId      = ss.serverId,
                                serverName    = ss.serverName,
                                currentUser   = currentUser,
                                onLeaveServer = {
                                    serverState = AppServerState.None
                                },
                                onUserUpdated = { newName, newPhoto ->
                                    authState = AppAuthState.Authenticated(
                                        currentUser.copy(displayName = newName, photoURL = newPhoto)
                                    )
                                    FirebaseAuth.saveProfile(newName, newPhoto)
                                },
                                onOpenDm = { uid, name -> dmTarget = DmTarget(uid, name) },
                            )
                        }
                    }

                    // FloatingDmPanel — sağ alt köşede, sadece sunucu detay ekranında göster
                    if (serverState is AppServerState.InServer) {
                        FloatingDmPanel(
                            currentUser = currentUser,
                            bottomOffset = 64.dp,
                            onOpenDm    = { uid, name -> dmTarget = DmTarget(uid, name) },
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