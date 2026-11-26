package com.furcord.download

import com.furcord.auth.FirestoreClient
import com.furcord.auth.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/**
 * Sohbet ekleri: her türlü görsel, GIF, video, ses ve genel dosya.
 *
 * Mesajın `imageUrl` alanında şu biçimlerden biri tutulur:
 *  • `data:<mime>;base64,...`            → küçük görseller/GIF'ler doğrudan mesajın içinde
 *  • `att:<id>|<boyut>|<mime>|<ad>`      → büyük dosyalar; parçalar Firestore'da (bkz. FirestoreClient.uploadAttachment)
 *  • `http(s)://...`                     → eski mesajlar
 */
object Attachments {

    const val MAX_BYTES: Long = 25L * 1024 * 1024
    private const val INLINE_LIMIT = 500_000
    /** Sohbette otomatik yüklenip gösterilecek en büyük parçalı görsel/GIF. */
    const val AUTO_LOAD_LIMIT: Long = 8L * 1024 * 1024

    enum class Kind { Image, Video, Audio, File }

    data class Info(
        val url: String,
        val kind: Kind,
        val name: String,
        val mime: String,
        val size: Long,
        val attId: String?,
    ) {
        val isGif: Boolean get() = mime == "image/gif"
        val sizeText: String
            get() = when {
                size >= 1L shl 20 -> "%.1f MB".format(size / (1L shl 20).toDouble())
                size >= 1L shl 10 -> "%d KB".format(size / 1024)
                else              -> "$size B"
            }
    }

    private val cacheDir = File(System.getProperty("user.home"), ".furcord/att_cache").also { it.mkdirs() }
    private val openDir  = File(cacheDir, "open").also { it.mkdirs() }

    private val mimeByExt = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "wmv" to "video/x-ms-wmv",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
        "flac" to "audio/flac", "opus" to "audio/opus",
    )

    private val extByMime = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "image/bmp" to "bmp",
    )

    fun mimeOf(fileName: String): String =
        mimeByExt[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun kindOf(mime: String) = when {
        mime.startsWith("image/") -> Kind.Image
        mime.startsWith("video/") -> Kind.Video
        mime.startsWith("audio/") -> Kind.Audio
        else                      -> Kind.File
    }

    fun parse(url: String): Info = when {
        url.startsWith("att:") -> {
            val p    = url.removePrefix("att:").split("|", limit = 4)
            val mime = p.getOrElse(2) { "application/octet-stream" }
            Info(url, kindOf(mime), p.getOrElse(3) { "dosya" }, mime, p.getOrElse(1) { "0" }.toLongOrNull() ?: 0L, p.getOrNull(0))
        }
        url.startsWith("data:") -> {
            val mime = url.substringAfter("data:").substringBefore(";").ifEmpty { "image/jpeg" }
            val ext  = extByMime[mime] ?: "jpg"
            Info(url, kindOf(mime), "Furcord_${url.hashCode().toUInt()}.$ext", mime, (url.length * 3L) / 4, null)
        }
        else -> {
            val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { "gorsel.jpg" }
            Info(url, Kind.Image, name, mimeOf(name).takeIf { it.startsWith("image/") } ?: "image/jpeg", 0L, null)
        }
    }

    /**
     * Seçilen dosyayı gönderilebilir `imageUrl` değerine çevirir (gerekirse yükler).
     * Hata durumunda kullanıcıya gösterilecek mesajla istisna fırlatır.
     */
    suspend fun prepare(file: File, idToken: String, onProgress: (Float) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val size = file.length()
            if (size <= 0L) throw Exception("Dosya boş veya okunamadı.")
            if (size > MAX_BYTES) throw Exception("Dosya çok büyük (en fazla ${MAX_BYTES / (1024 * 1024)} MB).")
            val bytes = file.readBytes()
            val mime  = mimeOf(file.name)

            if (mime.startsWith("image/")) {
                // Küçük görsel/GIF: aynen (animasyon ve biçim korunur) mesajın içine göm
                if (bytes.size <= INLINE_LIMIT) {
                    return@withContext "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
                }
                // Büyük statik görsel: oranı koruyarak küçült
                if (mime != "image/gif") {
                    runCatching { ImageData.toDataUrl(bytes, maxSide = 1920, maxBytes = 700_000) }
                        .getOrNull()?.let { return@withContext it }
                }
            }

            // Video, büyük GIF, ses, diğer dosyalar → parçalı yükleme
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            FirestoreClient.uploadAttachment(id, bytes, idToken, onProgress)
            val safeName = file.name.replace("|", "_")
            "att:$id|${bytes.size}|$mime|$safeName"
        }

    /** Ekin ham baytlarını döndürür (parçalı ekler diske önbelleklenir). */
    suspend fun bytes(info: Info, idToken: String, onProgress: (Float) -> Unit = {}): ByteArray =
        withContext(Dispatchers.IO) {
            val id = info.attId
            if (id == null) {
                onProgress(1f)
                return@withContext ImageData.loadBytes(info.url)
            }
            val cached = File(cacheDir, id)
            if (cached.exists() && cached.length() > 0) {
                onProgress(1f)
                return@withContext cached.readBytes()
            }
            val data = FirestoreClient.downloadAttachment(id, info.size, idToken, onProgress)
            runCatching { cached.writeBytes(data) }
            data
        }

    /** Varsayılan oynatıcıda açmak için uzantılı geçici bir kopya hazırlar. */
    suspend fun fileForOpen(info: Info, idToken: String, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val target = File(openDir, "${info.attId ?: info.url.hashCode().toUInt()}_${sanitize(info.name)}")
            if (!target.exists() || target.length() == 0L) {
                target.writeBytes(bytes(info, idToken, onProgress))
            }
            target
        }

    fun sanitize(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "dosya" }
}
