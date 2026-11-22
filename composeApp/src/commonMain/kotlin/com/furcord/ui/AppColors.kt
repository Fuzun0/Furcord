package com.furcord.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppColors {
    // Arka planlar: koyudan açığa
    val BgDeepest     = Color(0xFF0F1013)   // sunucu rayı
    val BgDark        = Color(0xFF141519)   // alt kullanıcı kartı
    val BgSidebar     = Color(0xFF191A1E)   // kanal listesi, üye paneli
    val BgMain        = Color(0xFF1E1F24)   // sohbet alanı
    val BgElevated    = Color(0xFF26272D)   // hover
    val BgActive      = Color(0xFF2F3139)   // seçili öğe
    val BgInput       = Color(0xFF25262C)   // mesaj kutusu

    val Outline       = Color(0xFF2A2B31)

    val TextPrimary   = Color(0xFFF2F3F5)
    val TextSecondary = Color(0xFFD4D6DA)
    val TextMuted     = Color(0xFF8E9297)
    val TextTimestamp = Color(0xFF7D828A)
    val TextSubtle    = Color(0xFF666A72)

    // Tek vurgu rengi (logoyla uyumlu mor)
    val Accent        = Color(0xFF7C5CF6)
    val Online        = Color(0xFF23A55A)   // sadece çevrimiçi / başarı
    val Danger        = Color(0xFFED4245)   // sadece sil / hata
    val Warning       = Color(0xFFFAA81A)
    val SelfName      = Color(0xFF23A55A)
}

/** Tüm uygulamada aynı köşe yarıçapları */
object Radius {
    val Small  = 6.dp
    val Medium = 10.dp
    val Large  = 14.dp
}