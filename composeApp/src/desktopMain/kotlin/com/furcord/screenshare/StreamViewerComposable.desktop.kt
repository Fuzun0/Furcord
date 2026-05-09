package com.furcord.screenshare

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun StreamViewerComposable(
    isSelfView:     Boolean,
    peerVolume:     Float,
    onVolumeChange: (Float) -> Unit,
    onStop:         () -> Unit,
    modifier:       Modifier,
) {
    StreamViewer(
        frameFlow      = if (isSelfView) ScreenShareManager.localFrame
                         else            ScreenShareManager.receiverFrame,
        peerVolume     = peerVolume,
        onVolumeChange = onVolumeChange,
        onStop         = onStop,
        isSelfView     = isSelfView,
        modifier       = modifier,
    )
}
