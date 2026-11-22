package com.furcord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import com.furcord.auth.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uygulama genelinde ortak görsel önbelleği.
 *
 *  • Bellek : çözülmüş (decode edilmiş) görseller LRU olarak RAM'de tutulur → tekrar gösterimde anında çıkar,
 *             yükleme çarkı ve yerleşim zıplaması olmaz.
 *  • Disk   : ağdan indirilen (http) görseller kalıcı saklanır → uygulama yeniden açılınca indirme yapılmaz.
 *  • data:  : Firestore'a gömülü görseller zaten yerelde; sadece çözülüp bellekte tutulur.
 */
object ImageCache {
    private const val MAX_ENTRIES = 200
    private val dir = File(System.getProperty("user.home"), ".furcord/img_cache").also { it.mkdirs() }

    private val mem = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_ENTRIES
    }

    private fun key(url: String) = "${url.length}_${url.hashCode()}"

    /** Bellekte varsa anında döner (composition sırasında güvenle çağrılabilir). */
    fun peek(url: String): ImageBitmap? = synchronized(mem) { mem[key(url)] }

    /** Bellek → disk → ağ sırasıyla yükler. Başarısızsa null. */
    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (url.startsWith("data:")) {
                    ImageData.loadBytes(url)
                } else {
                    val f = File(dir, key(url))
                    if (f.exists() && f.length() > 0) f.readBytes()
                    else ImageData.loadBytes(url).also { runCatching { f.writeBytes(it) } }
                }
                val bmp = loadImageBitmap(bytes.inputStream())
                synchronized(mem) { mem[key(url)] = bmp }
                bmp
            }.getOrNull()
        }
    }
}

/** URL'deki görseli önbellekten verir; bellekte varsa ilk karede hazırdır. */
@Composable
fun rememberCachedImage(url: String): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf(if (url.isEmpty()) null else ImageCache.peek(url)) }
    LaunchedEffect(url) {
        if (bmp == null && url.isNotEmpty()) bmp = ImageCache.load(url)
    }
    return bmp
}

/** Sunucu ikonları için ortak harita: şerit, lobi ve sunucu ekranı aynı kaynaktan okur; değişince hepsi güncellenir. */
object ServerIcons {
    val urls = androidx.compose.runtime.mutableStateMapOf<String, String>()
}
