package com.furcord.update

import com.furcord.auth.AppVersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

object UpdateManager {

    /** Şu an çalışan sürüm. jpackage tarafından sistem property olarak set edilir. */
    val currentVersion: String
        get() = System.getProperty("jpackage.app-version") ?: "1.0.0"

    private val publicHttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * GitHub Releases API'sinden en son sürüm bilgisini getirir.
     * Kimlik doğrulaması gerektirmez — uygulama ilk açıldığında çalışır.
     */
    suspend fun checkPublicVersion(): AppVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/Fuzun0/Furcord/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Furcord-Desktop/$currentVersion")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()
            val resp = publicHttpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (resp.statusCode() !in 200..299) return@withContext null
            val body = resp.body()
            // tag_name: "v1.0.35" → "1.0.35"
            val tag = Regex(""""tag_name"\s*:\s*"v?([^"]+)"""").find(body)
                ?.groupValues?.get(1) ?: return@withContext null
            // İlk .msi asset'in download URL'si
            val dlUrl = Regex(""""browser_download_url"\s*:\s*"([^"]+\.msi)"""").find(body)
                ?.groupValues?.get(1) ?: return@withContext null
            AppVersionInfo(latestVersion = tag, downloadUrl = dlUrl, releaseNotes = "")
        } catch (_: Exception) { null }
    }

    /**
     * Kullanıcının varsayılan tarayıcısında indirme sayfasını açar.
     */
    fun openDownloadPage(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
            }
        } catch (_: Exception) {}
    }

    private fun resolveAppExecutablePath(): String {
        val jpackagePath = System.getProperty("jpackage.app-path")
        if (!jpackagePath.isNullOrBlank()) return jpackagePath

        val local = System.getenv("LOCALAPPDATA").orEmpty()
        val candidates = listOf(
            File(local, "Furcord\\Furcord.exe"),
            File(local, "Programs\\Furcord\\Furcord.exe"),
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
            ?: candidates.first().absolutePath
    }

    /**
     * latest > current ise true döner.
     * Semver karşılaştırması: "1.2.0" vs "1.1.5"
     */
    fun isNewerVersion(latest: String, current: String = currentVersion): Boolean {
        val lParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(lParts.size, cParts.size)
        for (i in 0 until len) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Güncelleme dosyasını %TEMP% klasörüne indirir.
     * onProgress: 0f..1f arası indirme ilerlemesi (Content-Length bilinmiyorsa -1f)
     * Başarısızsa null döner.
     */
    suspend fun downloadInstaller(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val ext = if (downloadUrl.endsWith(".msi", ignoreCase = true)) ".msi" else ".exe"
            val destFile = File(System.getProperty("java.io.tmpdir"), "Furcord-update$ext")
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
            val req = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .timeout(Duration.ofSeconds(120))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) return@withContext null
            val contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L)
            resp.body().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0) {
                            onProgress(downloaded.toFloat() / contentLength.toFloat())
                        }
                    }
                }
            }
            onProgress(1f)
            destFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * MSI dosyasını sessizce kurar, uygulama açık kalır.
     * true = başarılı, false = hata
     */
    suspend fun installMsi(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "msiexec", "/i", file.absolutePath,
                "/quiet", "/norestart"
            ).start()
            process.waitFor(10, TimeUnit.MINUTES)
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Kurulum bitti, uygulamayı yeniden başlat (VBScript ile Job Object'ten bağımsız). */
    fun restartApp() {
        val appExe = resolveAppExecutablePath()
        val vbs = File(System.getProperty("java.io.tmpdir"), "furcord_restart.vbs")
        vbs.writeText(buildString {
            appendLine("WScript.Sleep 1000")
            appendLine("Set wsh = CreateObject(\"WScript.Shell\")")
            appendLine("If CreateObject(\"Scripting.FileSystemObject\").FileExists(\"$appExe\") Then")
            appendLine("    wsh.Run \"\"\"$appExe\"\"\", 1, False")
            appendLine("End If")
        })
        ProcessBuilder("wscript", "//b", "//nologo", vbs.absolutePath).start()
        kotlin.system.exitProcess(0)
    }
}
