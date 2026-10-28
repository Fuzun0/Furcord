package com.furcord

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import java.awt.GraphicsEnvironment

fun main() = application {
    val icon = painterResource("furcord_logo.png")

    // Görev çubuğu hariç kullanılabilir ekran alanı. AWT'nin mantıksal pikselleri
    // (DPI ölçeği uygulanmış) Compose'daki dp ile birebir aynıdır.
    val usable   = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val fullPos  = remember { WindowPosition(usable.x.dp, usable.y.dp) }
    val fullSize = remember { DpSize(usable.width.dp, usable.height.dp) }

    // Native "maximize" yerine pencereyi kendimiz ölçülendiriyoruz: çerçevesiz pencerede
    // native maximize hem görev çubuğunu örter hem de ölçekli ekranlarda taşar.
    val state = rememberWindowState(
        placement = WindowPlacement.Floating,
        position  = fullPos,
        size      = fullSize,
    )
    var windowVisible by remember { mutableStateOf(true) }

    val isFilled = state.size == fullSize &&
        state.position.x == usable.x.dp && state.position.y == usable.y.dp

    fun fillScreen() {
        state.position = fullPos
        state.size     = fullSize
    }

    fun restoreDown() {
        val w = (usable.width  * 0.8).toInt()
        val h = (usable.height * 0.8).toInt()
        state.size     = DpSize(w.dp, h.dp)
        state.position = WindowPosition((usable.x + (usable.width - w) / 2).dp, (usable.y + (usable.height - h) / 2).dp)
    }

    fun showWindow(fill: Boolean) {
        windowVisible = true
        state.isMinimized = false
        if (fill) fillScreen()
    }

    // ── Sistem tepsisi (saat yanındaki simge) ─────────────────────────────────
    Tray(
        icon     = icon,
        tooltip  = "Furcord",
        onAction = { showWindow(fill = false) },          // çift tık: olduğu gibi aç
        menu     = {
            Item("Uygulamayı Büyüt", onClick = { showWindow(fill = true) })
            Separator()
            Item("Uygulamadan Çık", onClick = { exitApplication() })
        },
    )

    Window(
        visible        = windowVisible,
        onCloseRequest = { windowVisible = false },       // çarpı: kapatma, tepsiye küçült
        title          = "Furcord — Voice Chat",
        icon           = icon,
        state          = state,
        undecorated    = true,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Koyu özel başlık çubuğu ────────────────────────────────────
            WindowDraggableArea(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Color(0xFF1E1F22)),
            ) {
                Row(
                    modifier          = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text      = "Furcord — Voice Chat",
                        color     = Color(0xFF96989D),
                        fontSize  = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))

                    // Küçült ──────────────────────────────────────────────────
                    TitleBarButton(
                        onClick      = { state.isMinimized = true },
                        hoverColor   = Color(0xFF3A3B3D),
                    ) { Text("—", color = Color(0xFFDCDDDE), fontSize = 12.sp) }

                    // Büyüt / Geri yükle ──────────────────────────────────────
                    TitleBarButton(
                        onClick    = { if (isFilled) restoreDown() else fillScreen() },
                        hoverColor = Color(0xFF3A3B3D),
                    ) {
                        Text(
                            text     = if (isFilled) "❐" else "□",
                            color    = Color(0xFFDCDDDE),
                            fontSize = 12.sp,
                        )
                    }

                    // Kapat: tepsiye küçült ───────────────────────────────────
                    TitleBarButton(
                        onClick    = { windowVisible = false },
                        hoverColor = Color(0xFFED4245),
                    ) { Text("×", color = Color(0xFFDCDDDE), fontSize = 14.sp) }
                }
            }

            // ── Uygulama içeriği ───────────────────────────────────────────
            App()
        }
    }
}

@Composable
private fun TitleBarButton(
    onClick:    () -> Unit,
    hoverColor: Color,
    content:    @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered     by interaction.collectIsHoveredAsState()
    Box(
        modifier          = Modifier
            .size(46.dp, 32.dp)
            .background(if (hovered) hoverColor else Color.Transparent)
            .hoverable(interaction)
            .clickable(indication = null, interactionSource = interaction, onClick = onClick),
        contentAlignment  = Alignment.Center,
    ) { content() }
}
