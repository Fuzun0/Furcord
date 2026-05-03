package com.furcord.platform

import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

actual fun pickImageAsBase64(): String? {
    var selectedFile: File? = null
    val latch = CountDownLatch(1)
    SwingUtilities.invokeLater {
        val dialog = FileDialog(null as Frame?, "Profil fotografi sec", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".webp")
        }
        dialog.isVisible = true
        val dir  = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) selectedFile = File(dir, file)
        latch.countDown()
    }
    latch.await()
    val file = selectedFile ?: return null
    return try {
        val original = ImageIO.read(file) ?: return null
        val size = 128
        val out  = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g    = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(original, 0, 0, size, size, null)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(out, "jpg", baos)
        Base64.getEncoder().encodeToString(baos.toByteArray())
    } catch (_: Exception) { null }
}