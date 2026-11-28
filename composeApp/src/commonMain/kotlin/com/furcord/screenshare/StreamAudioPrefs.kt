package com.furcord.screenshare

import kotlinx.coroutines.flow.MutableStateFlow

/** Yayın sesi ayarları (ortak: arayüz ve masaüstü motoru okur). */
object StreamAudioPrefs {
    /** Yayıncı: bilgisayarın sesini yayına ekle (yeni yayın başlarken okunur). */
    val shareSystemAudio = MutableStateFlow(true)

    /** İzleyici: yayın sesinin seviyesi, 0..2 (1 = normal). Yalnızca yayından gelen sesi etkiler. */
    val volume = MutableStateFlow(1f)
}
