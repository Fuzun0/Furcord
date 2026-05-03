package com.furcord.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a Base64-encoded JPEG/PNG string to an [ImageBitmap], or returns null on failure. */
expect fun decodeBase64ToBitmap(base64: String): ImageBitmap?
