package com.furcord.platform

// TODO: Replace with real microphone capture (AudioRecord or MediaRecorder).
actual class AudioRecorder actual constructor() {

    private var _isRecording: Boolean = false
    actual val isRecording: Boolean get() = _isRecording

    actual fun startRecording() {
        // TODO: Acquire AudioRecord, start capturing PCM frames.
        _isRecording = true
    }

    actual fun stopRecording() {
        // TODO: Stop AudioRecord and release resources.
        _isRecording = false
    }
}
