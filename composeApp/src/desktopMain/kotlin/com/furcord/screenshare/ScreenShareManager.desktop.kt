package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio-only stub — video / screen-share has been removed.
 * All functions are no-ops; StateFlows always emit null.
 */
actual object ScreenShareManager {
    actual fun setPeers(peers: List<Pair<String, Int>>) {}
    actual fun start() {}
    actual fun stop() {}
    actual val isActive: Boolean get() = false
    actual val localFrame:    StateFlow<ImageBitmap?> = MutableStateFlow(null)
    actual fun startReceiver() {}
    actual fun stopReceiver() {}
    actual val receiverFrame: StateFlow<ImageBitmap?> = MutableStateFlow(null)
}
