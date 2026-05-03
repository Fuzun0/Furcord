package com.furcord.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage
import java.util.Base64

actual fun decodeBase64ToBitmap(base64: String): ImageBitmap? = try {
    val bytes = Base64.getDecoder().decode(base64)
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) { null }
