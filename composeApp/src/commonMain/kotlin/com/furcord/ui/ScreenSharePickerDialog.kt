package com.furcord.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.furcord.ui.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.furcord.screenshare.ScreenShareManager
import com.furcord.screenshare.StreamAudioPrefs
import com.furcord.screenshare.ShareSource
import com.furcord.screenshare.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discord tarzı "Ekranını paylaş" penceresi:
 * tüm ekran ya da tek bir uygulama penceresi seç, kalite seç, yayını başlat.
 */
@Composable
fun ScreenSharePickerDialog(
    onDismiss: () -> Unit,
    onStart: (ShareSource, StreamQuality) -> Unit,
) {
    var sources by remember { mutableStateOf<List<ShareSource>?>(null) }
    var tab by remember { mutableIntStateOf(0) }                 // 0 = Ekranlar, 1 = Pencereler
    var selected by remember { mutableStateOf<ShareSource?>(null) }
    var quality by remember { mutableStateOf(ScreenShareManager.quality.value) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { ScreenShareManager.listSources() }
        sources = list
        selected = list.firstOrNull { it.isScreen }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(Radius.Large))
                .background(AppColors.BgSidebar)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Ekranını paylaş", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)

            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = AppColors.Accent,
            ) {
                listOf("Ekranlar", "Uygulama pencereleri").forEachIndexed { i, title ->
                    Tab(
                        selected = tab == i,
                        onClick = {
                            tab = i
                            selected = sources?.firstOrNull { it.isScreen == (i == 0) }
                        },
                        text = { Text(title, fontSize = 13.sp) },
                        selectedContentColor = AppColors.Accent,
                        unselectedContentColor = AppColors.TextMuted,
                    )
                }
            }

            val list = sources
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 200.dp, max = 340.dp)) {
                if (list == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = AppColors.Accent, strokeWidth = 3.dp)
                } else {
                    val shown = list.filter { it.isScreen == (tab == 0) }.let { if (tab == 1) it.take(24) else it }
                    if (shown.isEmpty()) {
                        Text(
                            if (tab == 0) "Ekran bulunamadı." else "Paylaşılabilir pencere bulunamadı.",
                            color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(220.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(shown, key = { "${it.isScreen}-${it.id}" }) { src ->
                                SourceCard(src, selected = selected?.id == src.id && selected?.isScreen == src.isScreen) {
                                    selected = src
                                }
                            }
                        }
                    }
                }
            }

            if (tab == 1) {
                Text(
                    "İpucu: Tam ekran (özel mod) çalışan oyunlar pencere olarak yakalanamayabilir; bu durumda “Ekranlar” sekmesini kullan.",
                    fontSize = 11.sp, color = AppColors.TextSubtle,
                )
            }

            // ── Kalite ───────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yayın kalitesi", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamQuality.entries.forEach { q ->
                        val sel = q == quality
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.Medium))
                                .background(if (sel) AppColors.Accent.copy(alpha = 0.15f) else AppColors.BgElevated)
                                .border(1.dp, if (sel) AppColors.Accent else Color.Transparent, RoundedCornerShape(Radius.Medium))
                                .clickable { quality = q }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(q.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (sel) AppColors.Accent else AppColors.TextPrimary)
                            Text(q.desc, fontSize = 11.sp, color = AppColors.TextMuted)
                        }
                    }
                }
                Text("Kaliteyi yayın sırasında da değiştirebilirsin.", fontSize = 11.sp, color = AppColors.TextSubtle)
            }

            // ── Bilgisayar sesi ──────────────────────────────────────────────
            val shareAudio by StreamAudioPrefs.shareSystemAudio.collectAsState()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bilgisayar sesini paylaş", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                    Text(
                        "Oyun/müzik gibi bilgisayarda çalan ses izleyicilere gider. Furcord sesleri (sesli sohbet) dahil edilmez; pencere paylaşırken yalnızca o uygulamanın sesi gider.",
                        fontSize = 11.sp, color = AppColors.TextSubtle,
                    )
                }
                Switch(checked = shareAudio, onCheckedChange = { StreamAudioPrefs.shareSystemAudio.value = it })
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("İptal", color = AppColors.TextMuted) }
                Button(
                    enabled = selected != null,
                    onClick = { selected?.let { onStart(it, quality) } },
                    shape = RoundedCornerShape(Radius.Medium),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                ) { Text("Yayına başla", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SourceCard(source: ShareSource, selected: Boolean, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, source.id, source.isScreen) {
        value = withContext(Dispatchers.IO) { ScreenShareManager.thumbnail(source) }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.Medium))
            .background(if (selected) AppColors.Accent.copy(alpha = 0.12f) else AppColors.BgElevated)
            .border(2.dp, if (selected) AppColors.Accent else Color.Transparent, RoundedCornerShape(Radius.Medium))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Radius.Small))
                .background(AppColors.BgDeepest),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp, source.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Icon(
                    if (source.isScreen) Icons.Outlined.DesktopWindows else Icons.Outlined.Window,
                    null, tint = AppColors.TextSubtle, modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            source.title, fontSize = 12.sp, color = AppColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
