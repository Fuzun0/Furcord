package com.furcord.platform

// TODO: Replace with real microphone capture (AVAudioEngine or AVCaptureSession).
actual class AudioRecorder actual constructor() {

    private var _isRecording: Boolean = false
    actual val isRecording: Boolean get() = _isRecording

    actual fun startRecording() {
        // TODO: Start AVAudioEngine input node.
        _isRecording = true
    }

    actual fun stopRecording() {
        // TODO: Stop and detach AVAudioEngine.
        _isRecording = false
    }
}
