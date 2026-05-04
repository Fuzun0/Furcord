package com.furcord.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * Google OAuth2 PKCE flow for the Desktop app.
 *
 * How to get the Client ID:
 *  1. Firebase Console → Authentication → Sign-in method → Google
 *  2. "Web SDK yapılandırması" bölümünü genişlet → "Web istemci kimliği"ni kopyala
 *     (formatı: <sayılar>-<hash>.apps.googleusercontent.com)
 *  3. Aşağıdaki GOOGLE_CLIENT_ID sabitini bu değerle değiştir.
 *
 *  Ayrıca Google Cloud Console → APIs & Services → Credentials →
 *  ilgili OAuth istemcisi → "Yetkili yeniden yönlendirme URI'leri" bölümüne
 *  http://127.0.0.1 eklemen gerekebilir (tüm portlara izin verir).
 */

object GoogleOAuth {

    private const val AUTH_URL  = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    private fun clientId(): String =
        System.getProperty("furcord.google.clientId")
            ?: System.getenv("FURCORD_GOOGLE_CLIENT_ID")
            ?: throw IllegalStateException(
                "Google Client ID yapılandırması eksik. " +
                "Lütfen GitHub Releases'tan güncel sürümü indirin."
            )

    private fun clientSecret(): String =
        System.getProperty("furcord.google.clientSecret")
            ?: System.getenv("FURCORD_GOOGLE_CLIENT_SECRET")
            ?: throw IllegalStateException(
                "Google Client Secret yapılandırması eksik. " +
                "Lütfen GitHub Releases'tan güncel sürümü indirin."
            )

    /**
     * Runs the full PKCE flow:
     *  1. Opens a local HTTP server on a random port.
     *  2. Launches the browser to Google's auth page.
     *  3. Waits for the redirect carrying the auth code.
     *  4. Exchanges the code for a Google ID token.
     *  Returns the Google ID token string.
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val clientId     = clientId()
        val clientSecret = clientSecret()
        val port        = 8085   // sabit port – Google Console'a eklenmeli
        ServerSocket(port).use { server ->
            val redirectUri = "http://127.0.0.1:$port"
            val verifier    = randomBase64(32)
            val challenge   = sha256Base64(verifier)
            val state       = randomBase64(16)

            val authUrl = buildString {
                append(AUTH_URL); append("?")
                append(enc("client_id",             clientId))
                append("&"); append(enc("redirect_uri",  redirectUri))
                append("&response_type=code")
                append("&scope=openid%20email%20profile")
                append("&"); append(enc("code_challenge", challenge))
                append("&code_challenge_method=S256")
                append("&"); append(enc("state",          state))
                append("&access_type=online")
            }

            // Open the default browser
            Desktop.getDesktop().browse(URI(authUrl))

            // Wait for Google to redirect back with the code
            val code = server.accept().use { socket ->
                val firstLine = socket.inputStream.bufferedReader().readLine() ?: ""
                val query     = firstLine
                    .substringAfter("?", "")
                    .substringBefore(" HTTP")
                val params    = query.split("&").associate {
                    it.substringBefore("=") to
                            URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
                }

                // Send success page to the browser tab
                val html     = """
                    <html><body style="font-family:sans-serif;text-align:center;padding:60px;background:#1e1e2e;color:#cdd6f4">
                    <h2>✅ Furcord giriş başarılı!</h2>
                    <p style="color:#a6adc8">Bu sekmeyi kapatabilirsiniz.</p>
                    </body></html>
                """.trimIndent()
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: ${html.toByteArray().size}\r\n\r\n$html"
                socket.outputStream.write(response.toByteArray())

                if (params["state"] != state) throw Exception("Güvenlik hatası: state uyuşmuyor.")
                params["code"] ?: throw Exception("Google'dan auth kodu alınamadı.")
            }

            exchangeCodeForIdToken(code, verifier, redirectUri, clientId, clientSecret)
        }
    }

    private fun exchangeCodeForIdToken(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val body = listOf(
            enc("code",          code),
            enc("client_id",     clientId),
            enc("client_secret", clientSecret),
            enc("redirect_uri",  redirectUri),
            "grant_type=authorization_code",
            enc("code_verifier", verifier),
        ).joinToString("&")

        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 15_000
        conn.readTimeout    = 15_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val httpCode = conn.responseCode
        val text     = if (httpCode in 200..299)
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        else
            conn.errorStream.bufferedReader(Charsets.UTF_8).readText()
                .also { throw Exception("Token alınamadı ($httpCode): $it") }

        return Regex(""""access_token"\s*:\s*"([^"]+)"""")
            .find(text)?.groupValues?.get(1)
            ?: throw Exception("Google access token bulunamadı.")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun randomBase64(byteCount: Int): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(byteCount).also { Random.nextBytes(it) })

    private fun sha256Base64(input: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(input.toByteArray(Charsets.US_ASCII))
            )

    private fun enc(key: String, value: String): String =
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
}
