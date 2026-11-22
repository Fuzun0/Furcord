package com.furcord.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.download.DownloadManager
import com.furcord.download.DownloadManager.Quality
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput


private val TealBtn = Color(0xFF1A7A78)
private val BlueBtn = Color(0xFF1877F2)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloaderScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url      by remember { mutableStateOf("") }
    var quality  by remember { mutableStateOf(Quality.P720) }
    var busy     by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status   by remember { mutableStateOf("") }
    var isError  by remember { mutableStateOf(false) }
    var files    by remember { mutableStateOf<List<DownloadManager.MediaFile>>(emptyList()) }
    var toDelete by remember { mutableStateOf<DownloadManager.MediaFile?>(null) }

    // Ekran açılınca klasördeki mevcut dosyaları listele
    LaunchedEffect(Unit) { files = DownloadManager.listMedia() }

    Column(Modifier.fillMaxSize().background(AppColors.BgDeepest)) {
        // Üst çubuk
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(AppColors.BgSidebar).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Geri", tint = AppColors.TextPrimary)
            }
            Text("Video / MP3 İndirici", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        }
                // Silme onayı
        toDelete?.let { m ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                containerColor = AppColors.BgSidebar,
                title = { Text("Dosyayı sil", color = AppColors.TextPrimary, fontSize = 17.sp) },
                text = {
                    Text(
                        "\"${m.file.name}\" Geri Dönüşüm Kutusu'na taşınacak. Emin misiniz?",
                        color = AppColors.TextMuted, fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            DownloadManager.deleteFile(m.file)
                            files = DownloadManager.listMedia()
                            toDelete = null
                        }
                    }) { Text("Sil", color = AppColors.Danger) }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Vazgeç") } },
            )
        }

        HorizontalDivider(color = AppColors.Outline)

        // ── Form (sabit) ─────────────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Video URL", fontSize = 16.sp, color = AppColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = { Text("Paste video link here ..", color = AppColors.TextMuted) },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
                        focusedBorderColor = BlueBtn, unfocusedBorderColor = BlueBtn,
                    ),
                )
                Button(
                    onClick = {
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .getData(DataFlavor.stringFlavor) as? String
                        }.getOrNull()?.let { url = it.trim() }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BlueBtn),
                    modifier = Modifier.height(56.dp),
                ) { Text("Paste") }
            }

            Text("Choose Quality", fontSize = 16.sp, color = AppColors.TextPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Quality.entries.forEach { q ->
                    val selected = q == quality
                    Button(
                        onClick = { quality = q }, enabled = !busy,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TealBtn, disabledContainerColor = TealBtn.copy(alpha = 0.5f)),
                        border = if (selected) BorderStroke(2.dp, BlueBtn) else null,
                        modifier = Modifier.width(130.dp).height(44.dp),
                    ) { Text(q.label, fontSize = 15.sp, color = Color.White) }
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        busy = true; isError = false; progress = 0f; status = "Hazırlanıyor…"
                        val err = DownloadManager.download(url.trim(), quality, { progress = it }, { status = it })
                        if (err == null) {
                            progress = 1f
                            status = "Tamamlandı — Masaüstündeki \"Furcord Medya\" klasörüne kaydedildi"
                            files = DownloadManager.listMedia()      // listeyi yenile
                        } else {
                            isError = true; status = err
                        }
                        busy = false
                    }
                },
                enabled = !busy && url.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlueBtn),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Download, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("İndir", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            // İlerleme çubuğu + yüzde
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (progress > 0f) LinearProgressIndicator(progress = { progress }, Modifier.weight(1f))
                    else LinearProgressIndicator(Modifier.weight(1f))
                    Text("%${(progress * 100).toInt()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                }
            }
            if (status.isNotEmpty()) {
                Text(status, fontSize = 13.sp, color = if (isError) AppColors.Danger else AppColors.TextMuted)
            }
        }

        HorizontalDivider(color = AppColors.Outline)

        // ── İndirilenler listesi ────────────────────────────────────────────
        Text(
            "İndirilenler (${files.size})",
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        )
        if (files.isEmpty()) {
            Text(
                "Henüz indirilen dosya yok.",
                fontSize = 13.sp, color = AppColors.TextMuted,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.file.absolutePath }) { m ->
                    MediaRow(m = m, onDelete = { toDelete = m })
                }
            }
        }
    }
}

@Composable
private fun MediaRow(m: DownloadManager.MediaFile, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.BgSidebar)
                .clickable { DownloadManager.openFile(m.file) }      // sol tık: oynat
                .pointerInput(m.file.absolutePath) {                  // sağ tık: menü
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                menuOpen = true
                            }
                        }
                    }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (m.isAudio) Icons.Default.MusicNote else Icons.Default.Movie,
                contentDescription = null, tint = AppColors.Accent,
            )
            Text(
                m.file.name,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, color = AppColors.TextPrimary,
            )
            Text("${m.sizeText}  ·  ${m.dateText}", fontSize = 12.sp, color = AppColors.TextMuted)
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Color(0xFF111214),
        ) {
            DropdownMenuItem(
                text = { Text("🗑 Sil", color = AppColors.Danger, fontSize = 13.sp) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

