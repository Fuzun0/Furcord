package com.furcord.screenshare

import androidx.compose.ui.graphics.ImageBitmap
import com.furcord.livekit.LiveKitRoom
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop ScreenShareManager — tüm iş LiveKitRoom'a delege edilir.
 *
 * Çağıran kod (ViewModel veya UI), start() / startReceiver() çağrısından ÖNCE
 * [currentRoomName] ve [currentIdentity] değerlerini ayarlamalıdır.
 */
actual object ScreenShareManager {

    /** Kanala özgü oda adı — örn. "server-$serverId-ch-$channelId" */
    var currentRoomName : String = ""

    /** Mevcut kullanıcının Firebase UID'si */
    var currentIdentity : String = ""

    /** Görünen ad (opsiyonel, varsayılan olarak identity kullanılır) */
    var currentDisplayName: String = ""

    // ── Peer listesi — LiveKit SFU ile anlamsız, backward-compat için tutuldu ─
    actual fun setPeers(peers: List<Pair<String, Int>>) { /* SFU kendi yönlendirmeyi yapar */ }

    // ── Yayın ────────────────────────────────────────────────────────────────

    actual fun start() {
        if (currentRoomName.isBlank() || currentIdentity.isBlank()) return
        LiveKitRoom.joinAndPublish(
            roomName    = currentRoomName,
            identity    = currentIdentity,
            displayName = currentDisplayName.ifBlank { currentIdentity }
        )
    }

    actual fun stop() = LiveKitRoom.stopPublisher()

    actual val isActive: Boolean get() = LiveKitRoom.isPublishing

    actual val localFrame: StateFlow<ImageBitmap?> get() = LiveKitRoom.localFrame

    // ── Alma ─────────────────────────────────────────────────────────────────

    actual fun startReceiver() {
        if (currentRoomName.isBlank() || currentIdentity.isBlank()) return
        LiveKitRoom.joinAndSubscribe(
            roomName    = currentRoomName,
            identity    = currentIdentity,
            displayName = currentDisplayName.ifBlank { currentIdentity }
        )
    }

    actual fun stopReceiver() = LiveKitRoom.stopSubscriber()

    actual val receiverFrame: StateFlow<ImageBitmap?> get() = LiveKitRoom.remoteFrame
}


