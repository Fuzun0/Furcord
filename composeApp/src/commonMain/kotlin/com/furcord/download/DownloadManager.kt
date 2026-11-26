package com.furcord.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream
import java.awt.Desktop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.filechooser.FileSystemView


object DownloadManager {
        /** Masaüstündeki "Furcord Medya" klasörü (yoksa oluşturulur). */
    val mediaDir: File
        get() {
            // OneDrive gibi taşınmış Masaüstü klasörlerini de doğru bulur
            val desktop = runCatching { FileSystemView.getFileSystemView().homeDirectory }.getOrNull()
                ?.takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home"), "Desktop")
            return File(desktop, "Furcord Medya").apply { mkdirs() }
        }

    private val AUDIO_EXT = setOf("mp3", "m4a", "opus", "wav", "ogg", "flac")

    // ── Sohbetten indirilen ekler ─────────────────────────────────────────────
    data class AttachmentDownload(
        val key: String,
        val name: String,
        val progress: Float = 0f,
        val status: String = "Başlıyor…",
        val error: Boolean = false,
        val done: Boolean = false,
    )

    /** İndirme sayfasında gösterilen sohbet indirmeleri (en yenisi başta). */
    var attachmentDownloads by androidx.compose.runtime.mutableStateOf<List<AttachmentDownload>>(emptyList())
        private set

    /** Artınca uygulama indirme sayfasına geçer. */
    var openPageRequest by androidx.compose.runtime.mutableStateOf(0)
        private set

    private val dlScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun updateDownload(key: String, f: (AttachmentDownload) -> AttachmentDownload) {
        attachmentDownloads = attachmentDownloads.map { if (it.key == key) f(it) else it }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = Attachments.sanitize(name)
        var f = File(dir, safe)
        if (!f.exists()) return f
        val base = safe.substringBeforeLast('.', safe)
        val ext  = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    /**
     * Sohbetteki bir eki (görsel/GIF/video/dosya) "Furcord Medya" klasörüne indirir
     * ve indirme sayfasına geçilmesini ister.
     */
    fun downloadAttachment(url: String, idToken: String) {
        val info = Attachments.parse(url)
        val key  = "${System.nanoTime()}"
        attachmentDownloads = listOf(AttachmentDownload(key, info.name)) + attachmentDownloads
        openPageRequest++
        dlScope.launch {
            try {
                updateDownload(key) { it.copy(status = "İndiriliyor…") }
                val data = Attachments.bytes(info, idToken) { p ->
                    updateDownload(key) { it.copy(progress = p, status = "İndiriliyor… %${(p * 100).toInt()}") }
                }
                val target = uniqueFile(mediaDir, info.name)
                target.writeBytes(data)
                updateDownload(key) { it.copy(progress = 1f, done = true, status = "Tamamlandı — ${target.name}") }
            } catch (e: Exception) {
                updateDownload(key) { it.copy(error = true, done = true, status = e.message ?: "İndirme başarısız") }
            }
        }
    }

    fun clearFinishedAttachmentDownloads() {
        attachmentDownloads = attachmentDownloads.filter { !it.done }
    }

    data class MediaFile(val file: File, val sizeBytes: Long, val modified: Long) {
        val isAudio: Boolean get() = file.extension.lowercase() in AUDIO_EXT
        val sizeText: String
            get() = when {
                sizeBytes >= 1L shl 30 -> "%.2f GB".format(sizeBytes / (1L shl 30).toDouble())
                sizeBytes >= 1L shl 20 -> "%.1f MB".format(sizeBytes / (1L shl 20).toDouble())
                else                   -> "%d KB".format(sizeBytes / 1024)
            }
        val dateText: String
            get() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date(modified))
    }

    /** Klasördeki medya dosyalarını en yeniden eskiye döndürür. */
    suspend fun listMedia(): List<MediaFile> = withContext(Dispatchers.IO) {
        mediaDir.listFiles { f -> f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") }
            ?.map { MediaFile(it, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()
    }

    /** Dosyayı Windows'un varsayılan medya oynatıcısında açar. */
    fun openFile(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
        }
    }
        /** Dosyayı Geri Dönüşüm Kutusu'na taşır (desteklenmiyorsa siler). */
    suspend fun deleteFile(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH))
                Desktop.getDesktop().moveToTrash(file)
            else
                file.delete()
        }.getOrDefault(false)
    }



    enum class Quality(val label: String, val height: Int?) {
        P360("360p", 360), P480("480p", 480), P720("720p", 720), P1080("1080p", 1080),
        MP3("MP3 Audio", null),
    }

    private const val YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
    private const val FFMPEG_URL =
        "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip"

    private val binDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Furcord/bin")
    private val ytDlp = File(binDir, "yt-dlp.exe")
    private val ffmpeg = File(binDir, "ffmpeg.exe")

    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private fun open(url: String) = http.send(
        HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofInputStream(),
    ).takeIf { it.statusCode() in 200..299 }?.body()

    /** yt-dlp ve ffmpeg yoksa indirir. Hata mesajı döner, başarıda null. */
    private suspend fun ensureTools(onStatus: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            binDir.mkdirs()
            if (!ytDlp.exists()) {
                onStatus("yt-dlp indiriliyor…")
                val input = open(YTDLP_URL) ?: return@withContext "yt-dlp indirilemedi"
                input.use { i -> ytDlp.outputStream().use { i.copyTo(it) } }
            }
            if (!ffmpeg.exists()) {
                onStatus("ffmpeg indiriliyor (ilk seferde ~100 MB)…")
                val input = open(FFMPEG_URL) ?: return@withContext "ffmpeg indirilemedi"
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name.endsWith("/bin/ffmpeg.exe")) {
                            ffmpeg.outputStream().use { zip.copyTo(it) }
                            break
                        }
                    }
                }
                if (!ffmpeg.exists()) return@withContext "ffmpeg arşivi açılamadı"
            }
            null
        } catch (e: Exception) {
            "Araçlar hazırlanamadı: ${e.message}"
        }
    }

    /**
     * Videoyu/sesi İndirilenler klasörüne indirir.
     * Başarıda null, hatada kullanıcıya gösterilecek mesajı döner.
     */
    suspend fun download(
        url: String,
        quality: Quality,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
    ): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "Geçerli bir bağlantı girin"
        ensureTools(onStatus)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val outDir = mediaDir
                val cmd = mutableListOf(
                    ytDlp.absolutePath, "--newline", "--no-playlist",
                    "--ffmpeg-location", binDir.absolutePath,
                    "-o", "${outDir.absolutePath}\\%(title)s.%(ext)s",
                )
                val h = quality.height
                if (h == null) {
                    cmd += listOf("-x", "--audio-format", "mp3")
                } else {
                    cmd += listOf("-f", "bv*[height<=$h]+ba/b[height<=$h]", "--merge-output-format", "mp4")
                }
                cmd += listOf("--", url)   // "--" : url bir seçenek gibi yorumlanmasın

                onStatus("İndiriliyor…")
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val pct = Regex("""\[download]\s+(\d+(?:\.\d+)?)%\s+of\s+~?\s*(\d+(?:\.\d+)?)([KMGT]?i?B)(?:.*?at\s+(\S+))?""")
                var last = ""
                var part = 0   // video ve ses ayrı ayrı iner: 1. parça video, 2. parça ses

                                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.isNotBlank()) last = line
                    if (line.startsWith("[download] Destination:")) part++
                    pct.find(line)?.let { m ->
                        val p     = m.groupValues[1].toFloat()
                        val total = m.groupValues[2].toFloat()
                        val unit  = m.groupValues[3]
                        val speed = m.groupValues[4].takeIf { it.isNotBlank() && !it.startsWith("Unknown") }
                        onProgress(p / 100f)
                        onStatus(buildString {
                            if (h != null) append(if (part <= 1) "Video · " else "Ses · ")
                            append("%.1f / %.1f %s".format(total * p / 100f, total, unit))
                            if (speed != null) append(" · $speed")
                        })
                    }
                    if (line.contains("[Merger]") || line.contains("[ExtractAudio]")) onStatus("Dönüştürülüyor…")
                }

                if (process.waitFor() == 0) null else last.ifBlank { "İndirme başarısız" }
            } catch (e: Exception) {
                "Hata: ${e.message}"
            }
        }
    }
}
