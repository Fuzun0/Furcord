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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*

fun main() = application {
    val icon  = painterResource("furcord_logo.png")
    val state = rememberWindowState(placement = WindowPlacement.Maximized)
    Window(
        onCloseRequest = ::exitApplication,
        title          = "Furcord \u2014 Voice Chat",
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
                        text      = "Furcord \u2014 Voice Chat",
                        color     = Color(0xFF96989D),
                        fontSize  = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))

                    // Küçült ──────────────────────────────────────────────────
                    TitleBarButton(
                        onClick      = { state.isMinimized = true },
                        hoverColor   = Color(0xFF3A3B3D),
                    ) { Text("\u2014", color = Color(0xFFDCDDDE), fontSize = 12.sp) }

                    // Büyüt / Geri yükle ──────────────────────────────────────
                    TitleBarButton(
                        onClick    = {
                            state.placement = if (state.placement == WindowPlacement.Maximized)
                                WindowPlacement.Floating else WindowPlacement.Maximized
                        },
                        hoverColor = Color(0xFF3A3B3D),
                    ) {
                        Text(
                            text     = if (state.placement == WindowPlacement.Maximized) "\u2750" else "\u25A1",
                            color    = Color(0xFFDCDDDE),
                            fontSize = 12.sp,
                        )
                    }

                    // Kapat ──────────────────────────────────────────────────
                    TitleBarButton(
                        onClick    = ::exitApplication,
                        hoverColor = Color(0xFFED4245),
                    ) { Text("\u00D7", color = Color(0xFFDCDDDE), fontSize = 14.sp) }
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
