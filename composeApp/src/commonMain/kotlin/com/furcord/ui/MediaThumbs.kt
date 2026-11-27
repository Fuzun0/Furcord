package com.furcord.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import com.furcord.download.Attachments
import com.furcord.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Video önizleme görselleri (küçük resim).
 *
 * ffmpeg ile videonun bir karesi alınıp diske (`~/.furcord/thumbs`) JPEG olarak kaydedilir;
 * aynı video için bir daha ffmpeg çalıştırılmaz. Üretim tek tek yapılır (CPU'yu yormaz).
 */
object MediaThumbs {
    private val dir = File(System.getProperty("user.home"), ".furcord/thumbs").also { it.mkdirs() }
    private val gate = Semaphore(1)
    private val mem = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 60
    }
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun peek(key: String): ImageBitmap? = synchronized(mem) { mem[key] }

    private fun safeKey(key: String) = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    /** Yerel bir video dosyasının küçük resmi (yoksa null). */
    suspend fun forFile(file: File): ImageBitmap? =
        load("f_${file.name.hashCode().toUInt()}_${file.length()}_${file.lastModified()}") { file }

    /** Sohbet ekindeki videonun küçük resmi; dosya gerekirse indirilir (önbelleğe alınır). */
    suspend fun forAttachment(info: Attachments.Info, idToken: String): ImageBitmap? =
        load("a_${info.attId ?: info.url.hashCode().toUInt()}") {
            Attachments.fileForOpen(info, idToken)
        }

    private suspend fun load(key: String, source: suspend () -> File): ImageBitmap? {
        peek(key)?.let { return it }
        if (key in failed) return null
        return withContext(Dispatchers.IO) {
            val out = File(dir, safeKey(key) + ".jpg")
            val bmp = runCatching {
                if (!out.exists() || out.length() == 0L) {
                    gate.withPermit {
                        if (out.exists() && out.length() > 0L) return@withPermit
                        val video = source()
                        if (DownloadManager.ensureFfmpeg { } != null) error("ffmpeg yok")
                        grab(video, out)
                    }
                }
                out.inputStream().use { loadImageBitmap(it) }
            }.getOrNull()
            if (bmp != null) synchronized(mem) { mem[key] = bmp } else failed += key
            bmp
        }
    }

    private fun grab(video: File, out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp.jpg")
        for (t in listOf("1", "0")) {   // çok kısa videolarda 1. saniye yoksa ilk kareyi dene
            tmp.delete()
            val p = ProcessBuilder(
                DownloadManager.ffmpegExe.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", t, "-i", video.absolutePath,
                "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", tmp.absolutePath,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
            if (tmp.exists() && tmp.length() > 0L) { tmp.renameTo(out); return }
        }
        error("Kare alınamadı")
    }
}
