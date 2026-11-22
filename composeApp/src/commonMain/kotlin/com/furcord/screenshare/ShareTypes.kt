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
    LOW   ("Düşük", "480p • 15 fps",           854,  480, 15,  2_500),
    MEDIUM("Orta",  "720p • 30 fps",          1280,  720, 30,  8_000),
    SOURCE("Kaynak", "Orijinal (≤1080p) • 30 fps", 1920, 1080, 30, 16_000),
}

/** Paylaşılabilir kaynak: bir monitör ya da bir uygulama penceresi. */
data class ShareSource(
    val id: Long,
    val title: String,
    /** true = tüm ekran (monitör), false = tek pencere */
    val isScreen: Boolean,
)
