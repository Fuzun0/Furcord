package com.furcord.screenshare

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-agnostic entry point for the stream viewer composable.
 *
 * - On Desktop: delegates to [StreamViewer] in desktopMain.
 * - On other platforms: shows a placeholder.
 *
 * @param isSelfView    True when showing the broadcaster's own preview.
 * @param peerVolume    Current volume (0f–2f). Ignored when [isSelfView] is true.
 * @param onVolumeChange Callback when volume slider changes.
 * @param onStop        Called when the user dismisses the viewer.
 */
@Composable
expect fun StreamViewerComposable(
    isSelfView:     Boolean,
    peerVolume:     Float,
    onVolumeChange: (Float) -> Unit,
    onStop:         () -> Unit,
    modifier:       Modifier = Modifier,
)
