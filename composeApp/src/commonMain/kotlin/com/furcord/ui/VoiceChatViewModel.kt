package com.furcord.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.furcord.platform.AudioRecorder
import com.furcord.platform.PeerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceChatUiState(
    val roomIdInput: String = "",
    val isConnected: Boolean = false,
    val isMuted: Boolean = false,
    val statusMessage: String = "Disconnected",
)

class VoiceChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceChatUiState())
    val uiState: StateFlow<VoiceChatUiState> = _uiState.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private val audioRecorder = AudioRecorder()

    /** Called whenever the user types in the Room ID field. */
    fun onRoomIdChanged(value: String) {
        _uiState.update { it.copy(roomIdInput = value) }
    }

    /** Attempt to join the room whose ID is currently in the text field. */
    fun connect() {
        val roomId = _uiState.value.roomIdInput.trim()
        if (roomId.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Please enter a Room ID.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Connecting to '$roomId'…") }
            try {
                val clientId = generateClientId()
                val conn = PeerConnection(roomId, clientId)
                conn.connect()
                peerConnection = conn

                audioRecorder.startRecording()

                _uiState.update {
                    it.copy(
                        isConnected = true,
                        isMuted = false,
                        statusMessage = "Connected to room '$roomId'"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Connection failed: ${e.message}")
                }
            }
        }
    }

    /** Disconnect from the current room and release resources. */
    fun disconnect() {
        audioRecorder.stopRecording()
        peerConnection?.close()
        peerConnection = null

        _uiState.update {
            it.copy(
                isConnected = false,
                isMuted = false,
                statusMessage = "Disconnected"
            )
        }
    }

    /** Toggle the microphone mute state. */
    fun toggleMute() {
        val nowMuted = !_uiState.value.isMuted
        if (nowMuted) audioRecorder.stopRecording() else audioRecorder.startRecording()
        _uiState.update { it.copy(isMuted = nowMuted) }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun generateClientId(): String =
        "client-${(100_000..999_999).random()}"
}
