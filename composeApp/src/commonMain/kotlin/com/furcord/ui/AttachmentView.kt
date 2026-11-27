package com.furcord.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.furcord.ui.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.download.Attachments
import com.furcord.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

/** Çözülmüş görsel: tek kare (statik) veya birden çok kare (animasyonlu GIF). */
class LoadedMedia(val frames: List<ImageBitmap>, val durationsMs: List<Int>)

/** Çözülmüş ekler için bellek önbelleği (LRU). */
private object MediaCache {
    private val mem = object : LinkedHashMap<String, LoadedMedia>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedMedia>?) = size > 40
    }
    private fun key(url: String) = "${url.length}_${url.hashCode()}"

    fun peek(url: String): LoadedMedia? = synchronized(mem) { mem[key(url)] }

    suspend fun load(info: Attachments.Info, idToken: String): LoadedMedia? {
        peek(info.url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Attachments.bytes(info, idToken)
                val media = (if (info.isGif) decodeGif(bytes) else null)
                    ?: LoadedMedia(listOf(loadImageBitmap(bytes.inputStream())), listOf(0))
                synchronized(mem) { mem[key(info.url)] = media }
                media
            }.getOrNull()
        }
    }

    /** GIF karelerini tek tek çözer; tek kareliyse null döner (statik yol kullanılır). */
    private fun decodeGif(bytes: ByteArray): LoadedMedia? {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        try {
            val n = codec.frameCount
            if (n <= 1) return null
            val w = codec.width
            val h = codec.height
            val perFrame = w.toLong() * h * 4
            val maxFrames = (160L * 1024 * 1024 / perFrame).toInt().coerceIn(1, n)
            val infos = codec.framesInfo
            val bitmaps = ArrayList<Bitmap>(maxFrames)
            for (i in 0 until maxFrames) {
                val prior = infos[i].requiredFrame
                val bmp = if (prior in 0 until bitmaps.size) bitmaps[prior].makeClone()
                else Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
                if (prior in 0 until bitmaps.size) codec.readPixels(bmp, i, prior) else codec.readPixels(bmp, i)
                bitmaps += bmp
            }
            val frames = bitmaps.map { it.asComposeImageBitmap() }
            val durs = (0 until maxFrames).map { i -> infos[i].duration.let { if (it <= 10) 100 else it } }
            return LoadedMedia(frames, durs)
        } finally {
            codec.close()
        }
    }
}

/**
 * Mesaj ekini gösterir: görsel/GIF satır içi, video/ses/diğer dosyalar kart olarak.
 * [onDownload] verilirse kartta ve görselin altında küçük indir butonu çıkar.
 */
@Composable
fun AttachmentView(
    url: String,
    idToken: String,
    maxWidth: Dp = 300.dp,
    maxHeight: Dp = 250.dp,
    onDownload: (() -> Unit)? = null,
) {
    val info = remember(url) { Attachments.parse(url) }
    if (info.kind == Attachments.Kind.Image) {
        ImageAttachment(info, idToken, maxWidth, maxHeight, onDownload)
    } else if (info.kind == Attachments.Kind.Video) {
        VideoAttachment(info, idToken, maxWidth, maxHeight, onDownload)
    } else {
        FileCard(info, idToken, maxWidth, onDownload)
    }
}

@Composable
private fun ImageAttachment(
    info: Attachments.Info,
    idToken: String,
    maxWidth: Dp,
    maxHeight: Dp,
    onDownload: (() -> Unit)?,
) {
    val auto = info.attId == null || info.size <= Attachments.AUTO_LOAD_LIMIT
    var media  by remember(info.url) { mutableStateOf(MediaCache.peek(info.url)) }
    var failed by remember(info.url) { mutableStateOf(false) }

    LaunchedEffect(info.url) {
        if (media == null && auto) {
            media = MediaCache.load(info, idToken)
            if (media == null) failed = true
        }
    }

    val m = media
    when {
        m != null -> {
            var idx by remember(m) { mutableStateOf(0) }
            LaunchedEffect(m) {
                if (m.frames.size > 1) {
                    while (isActive) {
                        delay(m.durationsMs[idx].toLong())
                        idx = (idx + 1) % m.frames.size
                    }
                }
            }
            Image(
                bitmap             = m.frames[idx.coerceIn(0, m.frames.size - 1)],
                contentDescription = "Görsel",
                modifier           = Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale       = ContentScale.Fit,
            )
        }
        failed || !auto -> FileCard(info, idToken, maxWidth, onDownload)
        else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppColors.Accent)
    }
}

@Composable
private fun FileCard(
    info: Attachments.Info,
    idToken: String,
    maxWidth: Dp,
    onDownload: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var opening  by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error    by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .widthIn(max = maxWidth.coerceAtLeast(220.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.BgSidebar)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when (info.kind) {
                Attachments.Kind.Video -> Icons.Default.Movie
                Attachments.Kind.Audio -> Icons.Default.MusicNote
                Attachments.Kind.Image -> Icons.Default.Image
                Attachments.Kind.File  -> Icons.Default.InsertDriveFile
            },
            contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(26.dp),
        )
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                info.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    error.isNotEmpty() -> error
                    opening            -> "Hazırlanıyor… %${(progress * 100).toInt()}"
                    else               -> info.sizeText
                },
                fontSize = 11.sp,
                color = if (error.isNotEmpty()) AppColors.Danger else AppColors.TextMuted,
            )
        }
        if (info.kind != Attachments.Kind.File) {
            IconButton(
                enabled = !opening,
                onClick = {
                    scope.launch {
                        opening = true; error = ""; progress = 0f
                        try {
                            val f = Attachments.fileForOpen(info, idToken) { progress = it }
                            DownloadManager.openFile(f)
                        } catch (e: Exception) {
                            error = e.message ?: "Açılamadı"
                        }
                        opening = false
                    }
                },
                modifier = Modifier.size(30.dp),
            ) { Icon(Icons.Default.PlayArrow, "Aç", tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp)) }
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Download, "İndir", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Video eki: küçük resim + oynat düğmesi (küçük resim hazır olana dek dosya kartı gösterilir). */
@Composable
private fun VideoAttachment(
    info: Attachments.Info,
    idToken: String,
    maxWidth: Dp,
    maxHeight: Dp,
    onDownload: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var thumb by remember(info.url) { mutableStateOf(MediaThumbs.peek("a_${info.attId ?: info.url.hashCode().toUInt()}")) }
    var opening  by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error    by remember { mutableStateOf("") }

    LaunchedEffect(info.url) {
        if (thumb == null) thumb = MediaThumbs.forAttachment(info, idToken)
    }

    val t = thumb
    if (t == null) { FileCard(info, idToken, maxWidth, onDownload); return }

    fun play() {
        if (opening) return
        scope.launch {
            opening = true; error = ""; progress = 0f
            try { DownloadManager.openFile(Attachments.fileForOpen(info, idToken) { progress = it }) }
            catch (e: Exception) { error = e.message ?: "Açılamadı" }
            opening = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight)
                .clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.Black)
                .clickable { play() },
            contentAlignment = Alignment.Center,
        ) {
            Image(t, "Video", Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight), contentScale = ContentScale.Fit)
            Box(
                Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                if (opening) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                else Icon(Icons.Default.PlayArrow, "Oynat", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(28.dp))
            }
            if (onDownload != null) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                ) { Icon(Icons.Default.Download, "İndir", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp)) }
            }
            Text(
                info.sizeText, fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            if (error.isNotEmpty()) error else if (opening) "Hazırlanıyor… %${(progress * 100).toInt()}" else info.name,
            fontSize = 11.sp, color = if (error.isNotEmpty()) AppColors.Danger else AppColors.TextMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxWidth),
        )
    }
}

/** Alıntı çubuğu/alıntı kutusu için küçük ek önizlemesi (görsel, GIF'in ilk karesi, video karesi). */
@Composable
fun AttachmentThumb(url: String, idToken: String, size: Dp = 40.dp) {
    val info = remember(url) { Attachments.parse(url) }
    var bmp by remember(url) {
        mutableStateOf<ImageBitmap?>(
            when (info.kind) {
                Attachments.Kind.Image -> MediaCache.peek(url)?.frames?.firstOrNull()
                Attachments.Kind.Video -> MediaThumbs.peek("a_${info.attId ?: info.url.hashCode().toUInt()}")
                else -> null
            }
        )
    }
    LaunchedEffect(url) {
        if (bmp != null) return@LaunchedEffect
        when (info.kind) {
            Attachments.Kind.Image ->
                if (info.attId == null || info.size <= Attachments.AUTO_LOAD_LIMIT)
                    bmp = MediaCache.load(info, idToken)?.frames?.firstOrNull()
            Attachments.Kind.Video -> bmp = MediaThumbs.forAttachment(info, idToken)
            else -> {}
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(AppColors.BgElevated),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(
            when (info.kind) {
                Attachments.Kind.Video -> Icons.Default.Movie
                Attachments.Kind.Audio -> Icons.Default.MusicNote
                Attachments.Kind.Image -> Icons.Default.Image
                Attachments.Kind.File  -> Icons.Default.InsertDriveFile
            },
            null, tint = AppColors.Accent, modifier = Modifier.size(size / 2),
        )
    }
}
