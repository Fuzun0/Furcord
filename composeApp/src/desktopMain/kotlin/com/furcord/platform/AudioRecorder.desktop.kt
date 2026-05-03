package com.furcord.platform

// TODO: Replace with real microphone capture (javax.sound.sampled.TargetDataLine).
actual class AudioRecorder actual constructor() {

    private var _isRecording: Boolean = false
    actual val isRecording: Boolean get() = _isRecording

    actual fun startRecording() {
        // TODO: Open a TargetDataLine and start reading PCM frames on a background thread.
        _isRecording = true
    }

    actual fun stopRecording() {
        // TODO: Stop and close the TargetDataLine.
        _isRecording = false
    }
}
