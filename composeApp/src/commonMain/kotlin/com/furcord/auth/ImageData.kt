package com.furcord.auth

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Görselleri Firebase Storage yerine doğrudan Firestore alanına (data URL) gömmek için yardımcılar.
 * Storage faturalandırması kapalı/kullanılamıyorken de çalışır.
 *
 * Firestore alan limiti ~1 MB olduğundan görseller küçültülüp sıkıştırılır.
 */
object ImageData {

    /**
     * Ham görsel baytlarını küçültüp `data:image/...;base64,...` biçimine çevirir.
     *
     * @param maxSide  En uzun kenarın üst sınırı (px)
     * @param maxBytes Üretilen görselin (base64 öncesi) hedef üst boyutu
     * @param square   true ise ortadan kare kırpar (sunucu ikonu için)
     */
    fun toDataUrl(raw: ByteArray, maxSide: Int, maxBytes: Int = 450_000, square: Boolean = false): String {
        val original = ImageIO.read(ByteArrayInputStream(raw))
            ?: throw Exception("Görsel okunamadı. Desteklenen biçimler: JPG, PNG, GIF.")

        var img = original
        if (square) {
            val side = minOf(img.width, img.height)
            img = img.getSubimage((img.width - side) / 2, (img.height - side) / 2, side, side)
        }
        var scaled = scale(img, maxSide)

        // Saydam PNG (ikon vb.) → PNG tut; diğerleri JPEG
        if (scaled.colorModel.hasAlpha() && square) {
            val png = encodePng(scaled)
            if (png.size <= maxBytes) return "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        }

        // JPEG: kalite ve boyut yeterince küçülene dek düşür
        var quality = 0.85f
        var side = maxSide
        while (true) {
            val jpg = encodeJpeg(scaled, quality)
            if (jpg.size <= maxBytes || (quality <= 0.4f && side <= 256)) {
                return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpg)
            }
            if (quality > 0.5f) {
                quality -= 0.15f
            } else {
                side = (side * 0.8f).toInt().coerceAtLeast(256)
                scaled = scale(img, side)
                quality = 0.7f
            }
        }
    }

    /** `data:` URL ise doğrudan çözer, değilse ağdan indirir. */
    fun loadBytes(url: String): ByteArray {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) throw Exception("Geçersiz data URL")
            return Base64.getDecoder().decode(url.substring(comma + 1))
        }
        return java.net.URI.create(url).toURL().readBytes()
    }

    private fun scale(src: BufferedImage, maxSide: Int): BufferedImage {
        val longest = maxOf(src.width, src.height)
        val ratio = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val type = if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val out = BufferedImage(w, h, type)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun encodePng(img: BufferedImage): ByteArray {
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray {
        // JPEG alfa kanalını desteklemez → beyaz zemine çiz
        val rgb = if (img.type == BufferedImage.TYPE_INT_RGB) img else {
            val o = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
            val g = o.createGraphics()
            g.color = java.awt.Color(0x1E1F24)
            g.fillRect(0, 0, o.width, o.height)
            g.drawImage(img, 0, 0, null)
            g.dispose()
            o
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val bos = ByteArrayOutputStream()
        writer.output = ImageIO.createImageOutputStream(bos)
        writer.write(null, IIOImage(rgb, null, null), param)
        writer.dispose()
        return bos.toByteArray()
    }
}
