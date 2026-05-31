package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop actual — ScreenEngine'i wrap eder.
 * P2P UDP üzerinde 854×480 JPEG@30fps ekran paylaşımı.
 */
actual object ScreenShareManager {
    /** Peer (ip, port) listesi — VoiceEngine peer'ları ile aynı adresler kullanılır. */
    actual fun setPeers(peers: List<Pair<String, Int>>) {
        // ScreenEngine VoiceEngine.peers'ı doğrudan kullandığından
        // ayrı peer listesi gerektirmez. Şimdilik no-op.
    }

    actual fun start()  = ScreenEngine.start()
    actual fun stop()   = ScreenEngine.stop()

    actual val isActive: Boolean
        get() = ScreenEngine.isActive

    actual val localFrame: StateFlow<ImageBitmap?>
        get() = ScreenEngine.localFrame

    actual fun startReceiver() = ScreenEngine.startReceiver()
    actual fun stopReceiver()  = ScreenEngine.stopReceiver()

    actual val receiverFrame: StateFlow<ImageBitmap?>
        get() = ScreenEngine.receiverFrame

    actual val broadcastingUidHash: StateFlow<Int>
        get() = ScreenEngine.broadcastingUidHash
}
