package com.furcord.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.furcord.download.DownloadManager
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.abs

private const val MIN_LEN = 0.5          // saniye — seçimin en kısa uzunluğu
private const val FILM_FRAMES = 10
private const val PEAK_COUNT = 200

/**
 * İndirilen bir video/ses dosyasını kırpma penceresi.
 * Telefon / Clipchamp kırpıcılarındaki gibi: film şeridi (ya da dalga formu) üzerinde
 * iki tutamaç, seçili bölge vurgulu, dışı karartılmış; başlangıç/bitiş alanları elle de girilebilir.
 */
@Composable
fun MediaTrimDialog(
    file: File,
    onDismiss: () -> Unit,
    onSaved: (File) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ext = file.extension.lowercase()
    val videoExt = ext in DownloadManager.TRIM_VIDEO_EXT

    // Bu pencereye ait geçici klasör (küçük resimler, önizleme kareleri, yarım çıktı)
    val tempDir = remember { Files.createTempDirectory("furcord-trim").toFile().apply { deleteOnExit() } }

    var status   by remember { mutableStateOf("Hazırlanıyor…") }
    var loadErr  by remember { mutableStateOf<String?>(null) }
    var duration by remember { mutableStateOf(0.0) }
    var hasVideo by remember { mutableStateOf(false) }
    val frames   = remember { mutableStateListOf<ImageBitmap?>() }
    var peaks    by remember { mutableStateOf<FloatArray?>(null) }

    var start by remember { mutableStateOf(0.0) }
    var end   by remember { mutableStateOf(0.0) }
    var startText by remember { mutableStateOf("") }
    var endText   by remember { mutableStateOf("") }
    var startErr  by remember { mutableStateOf(false) }
    var endErr    by remember { mutableStateOf(false) }

    var previewTime by remember { mutableStateOf(0.0) }
    var preview     by remember { mutableStateOf<ImageBitmap?>(null) }

    var overwrite by remember { mutableStateOf(false) }
    var saving    by remember { mutableStateOf(false) }
    var saveProg  by remember { mutableStateOf(0f) }
    var saveErr   by remember { mutableStateOf<String?>(null) }
    var saveJob   by remember { mutableStateOf<Job?>(null) }

    val ready = duration > 0.0 && loadErr == null
    val ffmpeg = DownloadManager.ffmpegExe.absolutePath

    fun setStart(v: Double, fromText: Boolean = false) {
        start = v.coerceIn(0.0, (end - MIN_LEN).coerceAtLeast(0.0))
        if (!fromText) { startText = formatTime(start, duration); startErr = false }
        previewTime = start
    }
    fun setEnd(v: Double, fromText: Boolean = false) {
        end = v.coerceIn((start + MIN_LEN).coerceAtMost(duration), duration)
        if (!fromText) { endText = formatTime(end, duration); endErr = false }
        previewTime = (end - 0.05).coerceAtLeast(0.0)
    }

    // Kapanışta geçici dosyaları temizle (süreçler sonlanabilsin diye biraz bekleyerek)
    DisposableEffect(Unit) {
        onDispose {
            saveJob?.cancel()
            Thread {
                Thread.sleep(800)
                tempDir.deleteRecursively()
            }.apply { isDaemon = true }.start()
        }
    }

    // 1) ffmpeg'i hazırla → 2) süreyi oku → 3) film şeridi / dalga formu
    LaunchedEffect(file) {
        val err = DownloadManager.ensureFfmpeg { status = it }
        if (err != null) { loadErr = err; return@LaunchedEffect }
        status = "Dosya inceleniyor…"
        val info = probe(ffmpeg, file)
        if (info == null || info.duration <= 0.0) {
            loadErr = "Dosyanın süresi okunamadı. Dosya bozuk ya da desteklenmiyor olabilir."
            return@LaunchedEffect
        }
        if (info.duration < MIN_LEN + 0.1) {
            loadErr = "Dosya kırpılamayacak kadar kısa."
            return@LaunchedEffect
        }
        duration = info.duration
        hasVideo = videoExt && info.hasVideo
        start = 0.0; end = info.duration
        startText = formatTime(0.0, info.duration); endText = formatTime(info.duration, info.duration)
        previewTime = 0.0

        if (hasVideo) {
            frames.clear(); repeat(FILM_FRAMES) { frames.add(null) }
            for (i in 0 until FILM_FRAMES) {
                val t = info.duration * (i + 0.5) / FILM_FRAMES
                val out = File(tempDir, "thumb_$i.jpg")
                frames[i] = grabFrame(ffmpeg, file, t, out, 160)
            }
        } else {
            peaks = computePeaks(ffmpeg, file, info.duration)
        }
    }

    // Önizleme karesi: sürüklerken 150 ms debounce; yeni istek eskisini iptal eder (LaunchedEffect)
    LaunchedEffect(previewTime, hasVideo, ready) {
        if (!hasVideo || !ready) return@LaunchedEffect
        delay(150)
        val out = File(tempDir, "preview.jpg")
        grabFrame(ffmpeg, file, previewTime.coerceAtMost((duration - 0.05).coerceAtLeast(0.0)), out, 640)
            ?.let { preview = it }
    }

    fun close() {
        saveJob?.cancel()
        onDismiss()
    }

    fun save() {
        if (saving || !ready) return
        saveErr = null
        saving = true
        saveProg = 0f
        saveJob = scope.launch {
            val target = if (overwrite) file else trimmedName(file)
            val tmpOut = File(tempDir, "out_${System.nanoTime()}.${file.extension}")
            val err = try {
                runTrim(ffmpeg, file, tmpOut, start, end) { saveProg = it }
                    ?: withContext(Dispatchers.IO) {
                        try {
                            Files.move(tmpOut.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            target.setLastModified(System.currentTimeMillis())
                            null
                        } catch (e: Exception) {
                            if (overwrite) "Orijinal dosya değiştirilemedi. Dosya başka bir programda açık olabilir."
                            else "Dosya kaydedilemedi: ${e.message}"
                        }
                    }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { tmpOut.delete() }
                saving = false
                throw e
            } finally {
                if (tmpOut.exists()) withContext(NonCancellable + Dispatchers.IO) { tmpOut.delete() }
            }
            saving = false
            if (err == null) onSaved(target) else saveErr = err
        }
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !saving),
    ) {
        Column(
            Modifier
                .width(820.dp)
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(Radius.Large))
                .background(AppColors.BgSidebar)
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) {
                        if (saving) saveJob?.cancel() else close()
                        true
                    } else false
                }
                .focusRequester(rootFocus)
                .focusable()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Başlık ───────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.ContentCut, null, tint = AppColors.Accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Kırp", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Text(
                        file.name, fontSize = 12.sp, color = AppColors.TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { close() }) {
                    Icon(Icons.Outlined.Close, "Kapat", tint = AppColors.TextMuted)
                }
            }

            if (!ready) {
                // ── Yükleniyor / hata ───────────────────────────────────────
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    val e = loadErr
                    if (e != null) {
                        Text(e, color = AppColors.Danger, fontSize = 13.sp)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                            Text(status, color = AppColors.TextMuted, fontSize = 13.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { close() }) { Text("Kapat", color = AppColors.TextMuted) }
                }
                return@Column
            }

            // Gövde kaydırılabilir; butonlar altta sabit kalır (pencere küçükse de görünür)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            // ── Önizleme ─────────────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth().height(if (hasVideo) 240.dp else 110.dp)
                    .clip(RoundedCornerShape(Radius.Medium)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (hasVideo) {
                    val p = preview
                    if (p != null) Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    Text(
                        formatTime(previewTime, duration),
                        fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .clip(RoundedCornerShape(Radius.Small)).background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.MusicNote, null, tint = AppColors.Accent, modifier = Modifier.size(36.dp))
                        Text("Ses dosyası", fontSize = 12.sp, color = AppColors.TextMuted)
                    }
                }
            }

            // ── Zaman çizelgesi (film şeridi / dalga formu + tutamaçlar) ──────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Timeline(
                    duration = duration,
                    start = start, end = end,
                    hasVideo = hasVideo,
                    frames = frames,
                    peaks = peaks,
                    enabled = !saving,
                    onStart = { setStart(it) },
                    onEnd = { setEnd(it) },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(0.0, duration), fontSize = 11.sp, color = AppColors.TextSubtle, modifier = Modifier.weight(1f))
                    Text(formatTime(duration, duration), fontSize = 11.sp, color = AppColors.TextSubtle)
                }
            }

            // ── Başlangıç / bitiş alanları + süre ─────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeField("Başlangıç", startText, startErr, enabled = !saving, modifier = Modifier.width(150.dp)) { txt ->
                    startText = txt
                    val v = parseTime(txt)
                    startErr = v == null || v < 0.0 || v > end - MIN_LEN
                    if (!startErr) setStart(v!!, fromText = true)
                }
                TimeField("Bitiş", endText, endErr, enabled = !saving, modifier = Modifier.width(150.dp)) { txt ->
                    endText = txt
                    val v = parseTime(txt)
                    endErr = v == null || v > duration + 0.001 || v < start + MIN_LEN
                    if (!endErr) setEnd(v!!.coerceAtMost(duration), fromText = true)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Seçili süre", fontSize = 11.sp, color = AppColors.TextMuted)
                    Text(
                        formatTime(end - start, duration), fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                    )
                }
            }
            if (startErr || endErr) {
                Text(
                    "Geçersiz zaman. Biçim: dd:ss.s (ör. 01:23.5). Seçim en az ${MIN_LEN} sn olmalı ve dosya süresini aşmamalı.",
                    fontSize = 12.sp, color = AppColors.Danger,
                )
            }

            HorizontalDivider(color = AppColors.Outline)

            // ── Kaydetme seçenekleri ─────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.Small))
                        .clickable(enabled = !saving) { overwrite = !overwrite }.padding(end = 8.dp),
                ) {
                    Checkbox(
                        checked = overwrite, onCheckedChange = { overwrite = it }, enabled = !saving,
                        colors = CheckboxDefaults.colors(checkedColor = AppColors.Accent, uncheckedColor = AppColors.TextMuted),
                    )
                    Text("Orijinalin üzerine yaz", fontSize = 13.sp, color = AppColors.TextPrimary)
                }
                Text(
                    if (overwrite) "Orijinal dosya kırpılmış haliyle değiştirilecek. Bu işlem geri alınamaz."
                    else "Yeni dosya olarak kaydedilecek: ${trimmedName(file).name}",
                    fontSize = 12.sp,
                    color = if (overwrite) AppColors.Warning else AppColors.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            if (saving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (saveProg > 0f) LinearProgressIndicator(progress = { saveProg }, Modifier.weight(1f), color = AppColors.Accent)
                    else LinearProgressIndicator(Modifier.weight(1f), color = AppColors.Accent)
                    Text("%${(saveProg * 100).toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                }
            }
            saveErr?.let { Text(it, fontSize = 12.sp, color = AppColors.Danger) }
            }

            // ── Butonlar ─────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (saving) {
                    TextButton(onClick = { saveJob?.cancel() }) { Text("İptal", color = AppColors.Danger) }
                } else {
                    TextButton(onClick = { close() }) { Text("Vazgeç", color = AppColors.TextMuted) }
                }
                Button(
                    onClick = { save() },
                    enabled = !saving && !startErr && !endErr,
                    shape = RoundedCornerShape(Radius.Small),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                ) {
                    Icon(Icons.Outlined.ContentCut, null, Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(if (saving) "Kırpılıyor…" else "Kaydet", color = Color.White)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Zaman çizelgesi
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Timeline(
    duration: Double,
    start: Double,
    end: Double,
    hasVideo: Boolean,
    frames: List<ImageBitmap?>,
    peaks: FloatArray?,
    enabled: Boolean,
    onStart: (Double) -> Unit,
    onEnd: (Double) -> Unit,
) {
    val curStart by rememberUpdatedState(start)
    val curEnd by rememberUpdatedState(end)
    val curOnStart by rememberUpdatedState(onStart)
    val curOnEnd by rememberUpdatedState(onEnd)

    Box(
        Modifier
            .fillMaxWidth()
            .height(if (hasVideo) 56.dp else 72.dp)
            .clip(RoundedCornerShape(Radius.Small))
            .background(AppColors.BgDeepest)
            .pointerHoverIcon(if (enabled) PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)) else PointerIcon.Default)
            .pointerInput(duration, enabled) {
                if (!enabled || duration <= 0.0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    fun timeAt(x: Float) = (x / w).coerceIn(0f, 1f) * duration
                    val t0 = timeAt(down.position.x)
                    // En yakın tutamacı seç; tıklanan yere taşı, sonra sürükle
                    val dragStart = abs(t0 - curStart) <= abs(t0 - curEnd)
                    if (dragStart) curOnStart(t0) else curOnEnd(t0)
                    down.consume()
                    drag(down.id) { change ->
                        val t = timeAt(change.position.x)
                        if (dragStart) curOnStart(t) else curOnEnd(t)
                        change.consume()
                    }
                }
            },
    ) {
        // Arka plan: film şeridi ya da dalga formu
        if (hasVideo) {
            Row(Modifier.fillMaxSize()) {
                frames.forEach { bmp ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(AppColors.BgElevated)) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        } else {
            val p = peaks
            if (p == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Dalga formu hazırlanıyor…", fontSize = 11.sp, color = AppColors.TextSubtle)
                }
            } else {
                Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                    val n = p.size
                    if (n == 0) return@Canvas
                    val step = size.width / n
                    val mid = size.height / 2f
                    val barW = (step * 0.6f).coerceAtLeast(1f)
                    val sx = (start / duration * size.width).toFloat()
                    val ex = (end / duration * size.width).toFloat()
                    for (i in 0 until n) {
                        val x = i * step + step / 2f
                        val h = (p[i] * mid).coerceAtLeast(1f)
                        drawLine(
                            color = if (x in sx..ex) AppColors.Accent else AppColors.TextSubtle,
                            start = Offset(x, mid - h), end = Offset(x, mid + h), strokeWidth = barW,
                        )
                    }
                }
            }
        }

        // Ön plan: seçim dışını karart, seçimi çerçevele, tutamaçlar
        Canvas(Modifier.fillMaxSize()) {
            if (duration <= 0.0) return@Canvas
            val w = size.width
            val h = size.height
            val sx = (start / duration * w).toFloat()
            val ex = (end / duration * w).toFloat()
            val dim = Color.Black.copy(alpha = 0.6f)
            if (sx > 0f) drawRect(dim, Offset.Zero, Size(sx, h))
            if (ex < w) drawRect(dim, Offset(ex, 0f), Size(w - ex, h))

            val hw = 10.dp.toPx()
            val border = 2.dp.toPx()
            val handleColor = AppColors.Accent
            // Üst/alt çerçeve
            drawRect(handleColor, Offset(sx, 0f), Size((ex - sx).coerceAtLeast(0f), border))
            drawRect(handleColor, Offset(sx, h - border), Size((ex - sx).coerceAtLeast(0f), border))
            // Tutamaçlar (seçim içine doğru)
            val lx = sx.coerceAtMost(w - hw)
            val rx = (ex - hw).coerceAtLeast(0f)
            val cr = CornerRadius(3.dp.toPx())
            drawRoundRect(handleColor, Offset(lx, 0f), Size(hw, h), cr)
            drawRoundRect(handleColor, Offset(rx, 0f), Size(hw, h), cr)
            // Tutamaç ortasındaki kavrama çizgileri
            val gripH = h * 0.35f
            val gripW = 1.5.dp.toPx()
            listOf(lx, rx).forEach { x ->
                drawLine(
                    Color.White, Offset(x + hw / 2f, h / 2f - gripH / 2f), Offset(x + hw / 2f, h / 2f + gripH / 2f),
                    strokeWidth = gripW,
                )
            }
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    isError: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        modifier = modifier,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
            disabledTextColor = AppColors.TextMuted,
            focusedBorderColor = AppColors.Accent, unfocusedBorderColor = AppColors.Outline,
            focusedLabelColor = AppColors.Accent, unfocusedLabelColor = AppColors.TextMuted,
            cursorColor = AppColors.Accent,
            errorBorderColor = AppColors.Danger, errorLabelColor = AppColors.Danger,
        ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Zaman biçimi
// ─────────────────────────────────────────────────────────────────────────────

/** 83.45 → "01:23.4" (bir saatten uzun dosyalarda "1:01:23.4"). */
private fun formatTime(sec: Double, total: Double): String {
    val tenths = (sec.coerceAtLeast(0.0) * 10).toLong()
    val h = tenths / 36000
    val m = (tenths / 600) % 60
    val s = (tenths / 10) % 60
    val t = tenths % 10
    return if (total >= 3600 || h > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%d", h, m, s, t)
    else String.format(Locale.ROOT, "%02d:%02d.%d", m + h * 60, s, t)
}

/** "ss.s", "dd:ss.s" ya da "s:dd:ss.s" biçimini saniyeye çevirir; geçersizse null. */
private fun parseTime(text: String): Double? {
    val parts = text.trim().replace(',', '.').split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) return null
    val secs = parts.last().toDoubleOrNull() ?: return null
    if (secs < 0 || (parts.size > 1 && secs >= 60)) return null
    var total = secs
    var mul = 60.0
    for (i in parts.size - 2 downTo 0) {
        val v = parts[i].toIntOrNull() ?: return null
        if (v < 0 || (i > 0 && v >= 60)) return null
        total += v * mul
        mul *= 60
    }
    return total
}

private fun ffTime(sec: Double) = String.format(Locale.ROOT, "%.3f", sec.coerceAtLeast(0.0))

/** "<ad> (kırpılmış).<uzantı>" — varsa "(kırpılmış 2)" ... */
private fun trimmedName(src: File): File {
    val dir = src.parentFile
    val base = src.nameWithoutExtension
    val ext = src.extension.let { if (it.isEmpty()) "" else ".$it" }
    var f = File(dir, "$base (kırpılmış)$ext")
    var i = 2
    while (f.exists()) { f = File(dir, "$base (kırpılmış $i)$ext"); i++ }
    return f
}

// ─────────────────────────────────────────────────────────────────────────────
// ffmpeg yardımcıları
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ffmpeg'i argüman listesiyle başlatır, [block] ile çıktıyı işler.
 * Coroutine iptal edilirse süreç hemen öldürülür (bloklu okumalar da böylece çözülür).
 */
private suspend fun <T> runProcess(
    cmd: List<String>,
    configure: (ProcessBuilder) -> Unit = {},
    block: (Process) -> T,
): T = withContext(Dispatchers.IO) {
    val pb = ProcessBuilder(cmd)
    configure(pb)
    val p = pb.start()
    val killer = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (p.isAlive) p.destroyForcibly() }
    }
    try {
        val r = runInterruptible { block(p) }
        ensureActive()
        r
    } finally {
        killer.cancel()
        if (p.isAlive) p.destroyForcibly()
    }
}

private class ProbeInfo(val duration: Double, val hasVideo: Boolean)

/** `ffmpeg -hide_banner -i dosya` stderr çıktısından süreyi ve video akışını okur. */
private suspend fun probe(ffmpeg: String, file: File): ProbeInfo? = runCatching {
    val text = runProcess(
        listOf(ffmpeg, "-hide_banner", "-nostdin", "-i", file.absolutePath),
        configure = { it.redirectErrorStream(true) },
    ) { p -> p.inputStream.bufferedReader(Charsets.UTF_8).readText().also { p.waitFor() } }
    val m = Regex("""Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""").find(text) ?: return@runCatching null
    val d = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
    val hasVideo = text.lineSequence().any { it.contains("Stream #") && it.contains("Video:") && !it.contains("attached pic") }
    ProbeInfo(d, hasVideo)
}.getOrElse { if (it is CancellationException) throw it else null }

/** Tek kare yakalar (hızlı arama için -ss girişten önce), küçültüp JPEG olarak okur. */
private suspend fun grabFrame(ffmpeg: String, file: File, t: Double, out: File, width: Int): ImageBitmap? = runCatching {
    out.delete()
    val code = runProcess(
        listOf(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
            "-ss", ffTime(t), "-i", file.absolutePath,
            "-frames:v", "1", "-an", "-sn", "-vf", "scale=$width:-2", "-q:v", "4", "-y", out.absolutePath,
        ),
        configure = { it.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD) },
    ) { p -> p.waitFor() }
    if (code != 0 || !out.exists()) return@runCatching null
    withContext(Dispatchers.IO) { out.inputStream().buffered().use { loadImageBitmap(it) } }
}.getOrElse { if (it is CancellationException) throw it else null }

/** Sesi mono 8 kHz s16le PCM olarak stdout'tan okuyup [PEAK_COUNT] tepe değeri (0..1) hesaplar. */
private suspend fun computePeaks(ffmpeg: String, file: File, duration: Double): FloatArray? = runCatching {
    val expected = (duration * 8000).toLong().coerceAtLeast(1)
    val peaks = FloatArray(PEAK_COUNT)
    runProcess(
        listOf(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-i", file.absolutePath,
            "-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", "-",
        ),
        configure = { it.redirectError(ProcessBuilder.Redirect.DISCARD) },
    ) { p ->
        val input = p.inputStream.buffered(64 * 1024)
        val buf = ByteArray(32 * 1024)
        var sample = 0L
        var carry = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            var i = 0
            if (carry >= 0 && n > 0) {
                val v = (buf[0].toInt() shl 8) or (carry and 0xFF)
                val idx = ((sample * PEAK_COUNT) / expected).toInt().coerceIn(0, PEAK_COUNT - 1)
                val a = abs(v.toShort().toInt()) / 32768f
                if (a > peaks[idx]) peaks[idx] = a
                sample++; carry = -1; i = 1
            }
            while (i + 1 < n) {
                val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                val idx = ((sample * PEAK_COUNT) / expected).toInt().coerceIn(0, PEAK_COUNT - 1)
                val a = abs(v) / 32768f
                if (a > peaks[idx]) peaks[idx] = a
                sample++; i += 2
            }
            if (i < n) carry = buf[i].toInt()
        }
        p.waitFor()
    }
    // Normalize: en yüksek tepe 1.0 olsun (sessiz kayıtlar da okunur görünsün)
    val max = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
    for (i in peaks.indices) peaks[i] = (peaks[i] / max).coerceIn(0f, 1f)
    peaks
}.getOrElse { if (it is CancellationException) throw it else null }

/** Çıktı uzantısına göre kodlayıcı argümanları (kare doğruluğunda kesim için yeniden kodlama). */
private fun codecArgs(ext: String): List<String> = when (ext) {
    "mp4", "mov" -> listOf(
        "-map", "0:v:0?", "-map", "0:a?",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
    )
    "mkv" -> listOf(
        "-map", "0:v:0?", "-map", "0:a?",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k",
    )
    "webm" -> listOf(
        "-map", "0:v:0?", "-map", "0:a?",
        "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
        "-c:a", "libopus", "-b:a", "128k",
    )
    "mp3"  -> listOf("-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-q:a", "2")
    "m4a"  -> listOf("-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
    "wav"  -> listOf("-map", "0:a:0", "-vn", "-c:a", "pcm_s16le")
    "ogg"  -> listOf("-map", "0:a:0", "-vn", "-c:a", "libvorbis", "-q:a", "5")
    "opus" -> listOf("-map", "0:a:0", "-vn", "-c:a", "libopus", "-b:a", "128k")
    "flac" -> listOf("-map", "0:a:0", "-vn", "-c:a", "flac")
    else   -> emptyList()
}

/**
 * [start]–[end] aralığını [out] dosyasına yeniden kodlayarak keser.
 * İlerlemeyi `-progress pipe:1` çıktısından okur. Başarıda null, hatada mesaj döner.
 * İptal edilirse süreç öldürülür (yarım dosyayı çağıran siler).
 */
private suspend fun runTrim(
    ffmpeg: String,
    input: File,
    out: File,
    start: Double,
    end: Double,
    onProgress: (Float) -> Unit,
): String? {
    val len = (end - start).coerceAtLeast(0.001)
    val cmd = buildList {
        addAll(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y"))
        addAll(listOf("-ss", ffTime(start), "-to", ffTime(end), "-i", input.absolutePath))
        addAll(listOf("-map_metadata", "0"))
        addAll(codecArgs(input.extension.lowercase()))
        addAll(listOf("-progress", "pipe:1", "-nostats", out.absolutePath))
    }
    val errLines = ArrayDeque<String>()
    val code = try {
        runProcess(cmd) { p ->
            // stderr'i ayrı iş parçacığında boşalt (dolarsa ffmpeg kilitlenir)
            val errThread = Thread {
                runCatching {
                    p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (line.isNotBlank()) synchronized(errLines) {
                            errLines.addLast(line); if (errLines.size > 6) errLines.removeFirst()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val us = when {
                    line.startsWith("out_time_us=") -> line.substringAfter('=').toLongOrNull()
                    line.startsWith("out_time_ms=") -> line.substringAfter('=').toLongOrNull()   // ffmpeg burada da µs yazar
                    else -> null
                }
                if (us != null && us >= 0) onProgress((us / 1_000_000.0 / len).toFloat().coerceIn(0f, 1f))
                if (line == "progress=end") onProgress(1f)
            }
            val c = p.waitFor()
            errThread.join(2000)
            c
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return "ffmpeg çalıştırılamadı: ${e.message}"
    }
    if (code != 0 || !out.exists() || out.length() == 0L) {
        val last = synchronized(errLines) { errLines.lastOrNull() }
        return "Kırpma başarısız" + (last?.let { ": $it" } ?: ".")
    }
    return null
}
