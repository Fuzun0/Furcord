package com.furcord.platform

/**
 * Platform-agnostic contract for capturing microphone audio.
 *
 * Each target (Android / iOS / Desktop) must supply an `actual` implementation.
 */
expect class AudioRecorder() {

    /** Start capturing audio from the default microphone input. */
    fun startRecording()

    /** Stop capturing audio and flush any pending buffers. */
    fun stopRecording()

    /** `true` while the microphone is actively capturing audio. */
    val isRecording: Boolean
}
