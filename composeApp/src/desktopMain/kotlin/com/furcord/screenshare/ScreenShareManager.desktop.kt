package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop actual — ScreenEngine'i wrap eder.
 * Ekran / pencere yakalama + UDP üzerinden JPEG akışı.
 */
actual object ScreenShareManager {
    /** Peer listesi VoiceEngine'den doğrudan okunur; ayrı liste gerekmez. */
    actual fun setPeers(peers: List<Pair<String, Int>>) {}

    actual fun start()  = ScreenEngine.start(null, ScreenEngine.quality.value)
    actual fun start(source: ShareSource, quality: StreamQuality) = ScreenEngine.start(source, quality)
    actual fun stop()   = ScreenEngine.stop()

    actual val isActive: Boolean
        get() = ScreenEngine.isActive

    actual fun listSources(): List<ShareSource> = ScreenEngine.listSources()
    actual fun thumbnail(source: ShareSource): ImageBitmap? = ScreenEngine.thumbnail(source)

    actual val quality: StateFlow<StreamQuality>
        get() = ScreenEngine.quality
    actual fun setQuality(q: StreamQuality) = ScreenEngine.setQuality(q)

    actual val sourceTitle: StateFlow<String>
        get() = ScreenEngine.sourceTitle

    actual val localFrame: StateFlow<ImageBitmap?>
        get() = ScreenEngine.localFrame

    actual fun startReceiver()     = ScreenEngine.startReceiver()
    actual fun stopReceiver()       = ScreenEngine.stopReceiver()
    actual fun stopReceiverFull()   = ScreenEngine.stopReceiverFull()

    actual val receiverFrame: StateFlow<ImageBitmap?>
        get() = ScreenEngine.receiverFrame

    actual val broadcastingUidHash: StateFlow<Int>
        get() = ScreenEngine.broadcastingUidHash
}
