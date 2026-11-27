package com.furcord.screenshare

/**
 * Yayın kalitesi — yayın sırasında da değiştirilebilir.
 * [kbps] hedef bit hızıdır; JPEG kalitesi bu bütçeye göre otomatik ayarlanır.
 */
enum class StreamQuality(
    val label: String,
    val desc: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val fps: Int,
    val kbps: Int,
) {
    LOW   ("Düşük", "480p • 60 fps",           854,  480, 60,  5_000),
    MEDIUM("Orta",  "720p • 60 fps",          1280,  720, 60, 12_000),
    HIGH  ("Yüksek", "1080p • 60 fps",        1920, 1080, 60, 20_000),
    SOURCE("2K",    "1440p • 60 fps",         2560, 1440, 60, 28_000),
}

/** Paylaşılabilir kaynak: bir monitör ya da bir uygulama penceresi. */
data class ShareSource(
    val id: Long,
    val title: String,
    /** true = tüm ekran (monitör), false = tek pencere */
    val isScreen: Boolean,
)
