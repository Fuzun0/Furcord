package com.furcord.screenshare

import androidx.compose.runtime.Composable

/**
 * OS-level Picture-in-Picture stream window.
 *
 * On Desktop: opens a secondary always-on-top Window with the stream.
 * On other platforms: no-op.
 */
@Composable
expect fun PiPStreamWindow(
    isSelfView:     Boolean,
    peerVolume:     Float,
    onVolumeChange: (Float) -> Unit,
    onExpand:       () -> Unit,
    onClose:        () -> Unit,
)
