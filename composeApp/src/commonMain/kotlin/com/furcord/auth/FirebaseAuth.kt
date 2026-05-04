package com.furcord.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val API_KEY     = "AIzaSyDBuMl5GlixeAtFb2UFHdX1D7IJtbAoBgM"
private const val BASE        = "https://identitytoolkit.googleapis.com/v1/accounts"
private const val REFRESH_URL = "https://securetoken.googleapis.com/v1/token"

// Token stored in user's home directory so sessions survive restarts
private val TOKEN_FILE   = File(System.getProperty("user.home"), ".furcord_token")
// Profil (displayName + photoURL) ayrı dosyada sakla — token'dan bağımsız
private val PROFILE_FILE = File(System.getProperty("user.home"), ".furcord_profile")
// Nickname dosyası — eşsiz kullanıcı adı
private val NICKNAME_FILE = File(System.getProperty("user.home"), ".furcord_nickname")
// Oturum: uid\temail\trefreshToken — idToken yenileme için
private val SESSION_FILE = File(System.getProperty("user.home"), ".furcord_session")

// ── Public data types ─────────────────────────────────────────────────────────

data class AuthUser(
    val uid:         String,
    val email:       String,
    val idToken:     String,
    val displayName: String = "",
    val photoURL:    String = "",
    val furcordId:   String = "",
    val nickname:    String = "",  // Eşsiz kullanıcı adı
)

sealed class AuthResult {
    data class Success(val user: AuthUser) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}

// ── Auth singleton ────────────────────────────────────────────────────────────

object FirebaseAuth {

    /** Sign in with email + password. Returns an [AuthResult]. */
    suspend fun signIn(email: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJson(
                    "email" to email,
                    "password" to password,
                    "returnSecureToken" to "true"
                )
                val response = post("$BASE:signInWithPassword?key=$API_KEY", body)
                val user = parseUser(response)
                val refreshToken = Regex("\"refreshToken\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response)?.groupValues?.get(1) ?: ""
                saveToken(user.idToken)
                if (refreshToken.isNotEmpty()) saveSession(user.uid, user.email, refreshToken)
                AuthResult.Success(user)
            } catch (e: FirebaseException) {
                AuthResult.Failure(friendlyError(e.code))
            } catch (e: Exception) {
                AuthResult.Failure(e.message ?: "Bilinmeyen hata")
            }
        }

    /** Create a new account with email + password. */
    suspend fun register(email: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJson(
                    "email" to email,
                    "password" to password,
                    "returnSecureToken" to "true"
                )
                val response = post("$BASE:signUp?key=$API_KEY", body)
                val user = parseUser(response)
                val refreshToken = Regex("\"refreshToken\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response)?.groupValues?.get(1) ?: ""
                saveToken(user.idToken)
                if (refreshToken.isNotEmpty()) saveSession(user.uid, user.email, refreshToken)
                AuthResult.Success(user)
            } catch (e: FirebaseException) {
                AuthResult.Failure(friendlyError(e.code))
            } catch (e: Exception) {
                AuthResult.Failure(e.message ?: "Bilinmeyen hata")
            }
        }

    /**
     * Try to restore a previously saved session.
     * Uses refreshToken to always get a fresh idToken — avoids 1-hour expiry.
     */
    suspend fun restoreSession(): AuthUser? = withContext(Dispatchers.IO) {
        val session = loadSession()
        if (session != null) {
            val (uid, email, refreshToken) = session
            runCatching {
                // refreshToken ile yeni idToken al
                val formBody = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}"
                val response = postForm("$REFRESH_URL?key=$API_KEY", formBody)
                val newIdToken = Regex("\"id_token\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response)?.groupValues?.get(1)
                    ?: throw Exception("id_token bulunamadı")
                val newRefreshToken = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response)?.groupValues?.get(1) ?: refreshToken
                saveToken(newIdToken)
                saveSession(uid, email, newRefreshToken)
                val (displayName, photoURL) = loadProfile()
                val nickname = loadNickname()
                val furcordId = getOrCreateFurcordId(uid)
                AuthUser(uid = uid, email = email, idToken = newIdToken, displayName = displayName, photoURL = photoURL, furcordId = furcordId, nickname = nickname)
            }.getOrElse {
                SESSION_FILE.delete()
                TOKEN_FILE.delete()
                null
            }
        } else {
            // Eski format: sadece TOKEN_FILE var — :lookup ile dene (gerç süreli)
            val token = loadToken() ?: return@withContext null
            runCatching {
                val body = buildJson("idToken" to token)
                val response = post("$BASE:lookup?key=$API_KEY", body)
                val uid   = extractField(response, "localId")
                val email = extractField(response, "email")
                val (displayName, photoURL) = loadProfile()
                val nickname = loadNickname()
                val furcordId = getOrCreateFurcordId(uid)
                AuthUser(uid = uid, email = email, idToken = token, displayName = displayName, photoURL = photoURL, furcordId = furcordId, nickname = nickname)
            }.getOrElse {
                TOKEN_FILE.delete()
                null
            }
        }
    }

    /**
     * Sign in via Google OAuth2 PKCE.
     * Opens the system browser; waits for the redirect; exchanges for a Firebase token.
     */
    suspend fun signInWithGoogle(): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                val accessToken = GoogleOAuth.getAccessToken()
                val escaped     = accessToken.replace("\\", "\\\\").replace("\"", "\\\"")
                val body        = """{"postBody":"access_token=$escaped&providerId=google.com","requestUri":"http://localhost","returnIdpCredential":true,"returnSecureToken":true}"""
                val response = post("$BASE:signInWithIdp?key=$API_KEY", body)
                val user     = parseUser(response)
                val refreshToken = Regex("\"refreshToken\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response)?.groupValues?.get(1) ?: ""
                saveToken(user.idToken)
                if (refreshToken.isNotEmpty()) saveSession(user.uid, user.email, refreshToken)
                AuthResult.Success(user)
            } catch (e: Throwable) {
                val msg = when {
                    e.message?.contains("Client ID eksik") == true ||
                    e.message?.contains("Client Secret eksik") == true ->
                        "Google OAuth yapılandırması eksik. Lütfen destek ekibiyle iletişime geçin."
                    else -> e.message ?: "Google ile giriş başarısız."
                }
                AuthResult.Failure(msg)
            }
        }

    /** Clear saved session. */
    fun signOut() {
        TOKEN_FILE.delete()
        SESSION_FILE.delete()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code   = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text   = stream.bufferedReader(Charsets.UTF_8).readText()

        if (code !in 200..299) {
            val errorCode = Regex(""""message"\s*:\s*"([^"]+)"""")
                .find(text)?.groupValues?.getOrNull(1) ?: "UNKNOWN"
            throw FirebaseException(errorCode)
        }
        return text
    }
    /** Form-encoded POST (token yenileme API'si için). */
    private fun postForm(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code   = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text   = stream.bufferedReader(Charsets.UTF_8).readText()
        if (code !in 200..299) throw Exception("Token yenileme başarısız ($code): $text")
        return text
    }
    private fun parseUser(json: String): AuthUser {
        val uid = extractField(json, "localId")
        return AuthUser(
            uid         = uid,
            email       = extractField(json, "email"),
            idToken     = extractField(json, "idToken"),
            displayName = Regex("\"displayName\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1) ?: "",
            photoURL    = Regex("\"photoUrl\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1) ?: "",
            furcordId   = getOrCreateFurcordId(uid),
            nickname    = loadNickname(),
        )
    }

    private fun extractField(json: String, field: String): String =
        Regex(""""$field"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.getOrNull(1)
            ?: throw Exception("Alan bulunamadı: $field")

    /** Build a JSON object from key-value pairs, escaping string values. */
    private fun buildJson(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            """"$k":"$escaped""""
        }

    private fun saveToken(token: String)    = try { TOKEN_FILE.writeText(token) } catch (_: Exception) {}
    private fun loadToken(): String?         =
        TOKEN_FILE.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun saveSession(uid: String, email: String, refreshToken: String) = try {
        SESSION_FILE.writeText("$uid\t$email\t$refreshToken")
    } catch (_: Exception) {}

    private fun loadSession(): Triple<String, String, String>? = try {
        val text = SESSION_FILE.takeIf { it.exists() }?.readText()?.trim() ?: return null
        val parts = text.split("\t", limit = 3)
        if (parts.size == 3 && parts[0].isNotEmpty()) Triple(parts[0], parts[1], parts[2]) else null
    } catch (_: Exception) { null }

    /** Profil adını, fotoğrafını ve nickname'i yerel dosyaya kaydeder. App.kt'den çağrılır. */
    fun saveProfile(displayName: String, photoURL: String) {
        try { PROFILE_FILE.writeText("$displayName\n$photoURL") } catch (_: Exception) {}
    }

    /** Nickname'i yerel dosyaya kaydeder. */
    fun saveNickname(nickname: String) {
        try { NICKNAME_FILE.writeText(nickname) } catch (_: Exception) {}
    }

    private fun loadProfile(): Pair<String, String> = try {
        val lines = PROFILE_FILE.readLines()
        (lines.getOrElse(0) { "" }) to (lines.drop(1).joinToString("\n"))
    } catch (_: Exception) { "" to "" }

    private fun loadNickname(): String = try {
        NICKNAME_FILE.takeIf { it.exists() }?.readText()?.trim() ?: ""
    } catch (_: Exception) { "" }

    /** Yerel furcordId dosyası — yoksa üretir ve kaydeder. */
    fun getOrCreateFurcordId(uid: String): String {
        val idFile = File(System.getProperty("user.home"), ".furcord_id_$uid")
        if (idFile.exists()) return idFile.readText().trim()
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val id    = (1..8).map { chars[java.security.SecureRandom().nextInt(chars.length)] }.joinToString("")
        idFile.writeText(id)
        return id
    }

    private fun friendlyError(code: String) = when (code) {
        "EMAIL_EXISTS"               -> "Bu e-posta zaten kayıtlı."
        "INVALID_EMAIL"              -> "Geçersiz e-posta adresi."
        "WEAK_PASSWORD"              -> "Şifre en az 6 karakter olmalıdır."
        "EMAIL_NOT_FOUND",
        "INVALID_LOGIN_CREDENTIALS",
        "INVALID_PASSWORD"           -> "E-posta veya şifre hatalı."
        "USER_DISABLED"              -> "Bu hesap devre dışı bırakıldı."
        "TOO_MANY_ATTEMPTS_TRY_LATER"-> "Çok fazla deneme. Lütfen bekleyin."
        else                         -> "Bir hata oluştu ($code)."
    }
}

private class FirebaseException(val code: String) : Exception(code)

/**
 * Son girilen sunucuları kalıcı olarak kaydeder.
 * Format: her satır `serverId\tserverName`.
 */
object RecentServers {
    private val file = File(System.getProperty("user.home"), ".furcord_servers")

    /** En son girilen sunucuları döndürür (en yenisi başta, max 10). */
    fun load(): List<Pair<String, String>> = try {
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }
    } catch (_: Exception) { emptyList() }

    /** Sunucuyu listenin başına ekler (zaten varsa taşır). Kullanıcı manuel girdiğinde kullan. */
    fun save(serverId: String, serverName: String) = try {
        val updated = listOf(serverId to serverName) + load().filter { it.first != serverId }
        file.writeText(updated.take(10).joinToString("\n") { "${it.first}\t${it.second}" })
    } catch (_: Exception) {}

    /**
     * Sunucuyu sadece listede yoksa **sona** ekler; zaten varsa sırayı değiştirmez.
     * Firestore'dan toplu senkronize ederken kullanılır — kullanıcının kronolojik sırasını bozmaz.
     */
    fun addIfAbsent(serverId: String, serverName: String) {
        try {
            val existing = load()
            if (existing.none { it.first == serverId }) {
                val updated = existing + (serverId to serverName)
                file.writeText(updated.take(10).joinToString("\n") { "${it.first}\t${it.second}" })
            }
        } catch (_: Exception) {}
    }

    /** Tüm sunucu geçmişini yerel dosyadan siler (sıfırlama sonrası kullanılır). */
    fun clear() = try { file.delete() } catch (_: Exception) {}
}
