package com.furcord.screenshare

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow

/**
 * Yayın karesini çizen hafif yüzey.
 *
 * Kare durumu yalnızca **çizim aşamasında** okunur: yeni kare geldiğinde ne yeniden kompozisyon
 * ne de yeniden yerleşim olur, sadece bu alan yeniden çizilir. Yazılım (CPU) render'ında
 * saniyede 30 kare gelirken arayüzün geri kalanının takılmamasını sağlar.
 *
 * Görüntü oranı korunarak alana sığdırılır (letterbox). Alanın piksel boyutu [ScreenEngine]'e
 * bildirilir; kod çözücü kareyi gereğinden büyük üretmez (küçük pencerede daha az CPU).
 */
@Composable
fun FrameSurface(frameFlow: StateFlow<ImageBitmap?>, modifier: Modifier = Modifier) {
    val frameState = frameFlow.collectAsState()
    Spacer(
        modifier
            .onSizeChanged { ScreenEngine.setViewSize(it.width, it.height) }
            .drawBehind {
                val img = frameState.value ?: return@drawBehind
                val iw = img.width.toFloat()
                val ih = img.height.toFloat()
                if (iw <= 0f || ih <= 0f || size.width <= 0f || size.height <= 0f) return@drawBehind
                val scale = minOf(size.width / iw, size.height / ih)
                val dw = (iw * scale).toInt().coerceAtLeast(1)
                val dh = (ih * scale).toInt().coerceAtLeast(1)
                drawImage(
                    image         = img,
                    dstOffset     = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                    dstSize       = IntSize(dw, dh),
                    filterQuality = FilterQuality.Low,
                )
            },
    )
}
