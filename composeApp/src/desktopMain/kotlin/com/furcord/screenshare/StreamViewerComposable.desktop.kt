package com.furcord.screenshare

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier

@Composable
actual fun StreamViewerComposable(
    isSelfView:     Boolean,
    peerVolume:     Float,
    onVolumeChange: (Float) -> Unit,
    onStop:         () -> Unit,
    isPiPMode:      Boolean,
    onTogglePiP:    (() -> Unit)?,
    modifier:       Modifier,
) {
    // Yayıncı kendi önizlemesini ana pencerede izlerken pencere yakalamadan dışlanır →
    // yayında iç içe ayna oluşmaz, önizleme izleyicilerin gördüğünün aynısı olur.
    DisposableEffect(isSelfView) {
        if (isSelfView) CaptureExclusion.setAll(true)
        onDispose { if (isSelfView) CaptureExclusion.setAll(false) }
    }

    StreamViewer(
        frameFlow      = if (isSelfView) ScreenShareManager.localFrame
                         else            ScreenShareManager.receiverFrame,
        peerVolume     = peerVolume,
        onVolumeChange = onVolumeChange,
        onStop         = onStop,
        isSelfView     = isSelfView,
        isPiPMode      = isPiPMode,
        onTogglePiP    = onTogglePiP,
        modifier       = modifier,
    )
}
