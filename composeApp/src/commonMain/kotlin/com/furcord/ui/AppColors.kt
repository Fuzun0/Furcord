package com.furcord.ui

import androidx.compose.ui.graphics.Color

/**
 * Furcord Discord-inspired dark color palette.
 * Tüm UI bileşenlerinde bu sabitler kullanılmalıdır.
 */
object AppColors {
    // ── Backgrounds ───────────────────────────────────────────────────────────
    /** En koyu arka plan — sunucu listesi, başlık çubuğu */
    val BgDeepest    = Color(0xFF1E1F22)
    /** Sidebar arka planı — kanal listesi, arkadaş listesi */
    val BgSidebar    = Color(0xFF2B2D31)
    /** Ana içerik alanı — sohbet ekranı, lobi */
    val BgMain       = Color(0xFF313338)
    /** Hafif vurgu arka planı — hover, seçili alan */
    val BgElevated   = Color(0xFF35373C)
    /** Aktif / seçili öğe arka planı */
    val BgActive     = Color(0xFF404249)
    /** Input alanı arka planı */
    val BgInput      = Color(0xFF383A40)
    /** Daha koyu sidebar varyantı (FloatingDm sol panel) */
    val BgDark       = Color(0xFF232428)

    // ── Borders / Dividers ────────────────────────────────────────────────────
    val Outline      = Color(0xFF3F4147)

    // ── Text ─────────────────────────────────────────────────────────────────
    /** Birincil metin — kullanıcı adları, kanal adları */
    val TextPrimary  = Color(0xFFF2F3F5)
    /** İkincil metin — mesaj içerikleri */
    val TextSecondary = Color(0xFFDCDDDE)
    /** Soluk metin — timestamp, etiketler, placeholder */
    val TextMuted    = Color(0xFF8E9297)
    /** Çok soluk metin — zaman damgası */
    val TextTimestamp = Color(0xFF949BA4)
    /** Devre dışı / ek bilgi */
    val TextSubtle   = Color(0xFF6D6F78)

    // ── Accent / Brand ────────────────────────────────────────────────────────
    /** Mor-viyole — logo ile uyumlu, butonlar, vurgu, seçim */
    val Accent       = Color(0xFF7C5CF6)
    /** Yeşil — çevrimiçi, kendin, ses bağlantısı */
    val Online       = Color(0xFF23A55A)
    /** Kırmızı — okunmamış, hata */
    val Danger       = Color(0xFFED4245)
    /** Sarı — uyarı */
    val Warning      = Color(0xFFFAA81A)

    // ── Self message name color ───────────────────────────────────────────────
    val SelfName     = Color(0xFF23A55A)
}
