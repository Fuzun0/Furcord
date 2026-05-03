package com.furcord.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object UpdateManager {

    /** Şu an çalışan sürüm. jpackage tarafından sistem property olarak set edilir. */
    val currentVersion: String
        get() = System.getProperty("jpackage.app-version") ?: "1.0.0"

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
            val destFile = File(System.getProperty("java.io.tmpdir"), "Furcord-update.exe")
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
     * İndirilen installer'ı çalıştırır ve uygulamayı kapatır.
     * Installer tamamlandıktan sonra kullanıcı yeni sürümü açabilir.
     */
    fun launchInstallerAndExit(file: File) {
        // VBScript ile:
        //  1) /quiet /norestart flag'leriyle sessiz kurulum (sihirbaz çıkmaz)
        //  2) Kurulum bitince uygulamayı otomatik yeniden başlat
        val appExe = File(
            System.getenv("LOCALAPPDATA") ?: "",
            "Furcord\\Furcord.exe"
        ).absolutePath
        val vbs = File(System.getProperty("java.io.tmpdir"), "furcord_update.vbs")
        // VBScript'te tırnak içinde tırnak """ ile gösterilir.
        vbs.writeText(buildString {
            appendLine("WScript.Sleep 2000")
            appendLine("Set wsh = CreateObject(\"WScript.Shell\")")
            // Sessiz kurulum — sihirbaz göstermez
            appendLine("wsh.Run \"\"\"${file.absolutePath}\"\" /quiet /norestart\", 0, True")
            // Kurulum bitti, uygulamayı yeniden başlat
            appendLine("If CreateObject(\"Scripting.FileSystemObject\").FileExists(\"$appExe\") Then")
            appendLine("    wsh.Run \"\"\"$appExe\"\"\", 1, False")
            appendLine("End If")
        })
        ProcessBuilder("wscript", "//b", "//nologo", vbs.absolutePath).start()
        kotlin.system.exitProcess(0)
    }
}
