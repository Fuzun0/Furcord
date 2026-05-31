package com.furcord

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    val icon = painterResource("furcord_logo.png")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Furcord — Voice Chat",
        icon  = icon,
        state = WindowState(placement = WindowPlacement.Maximized),
    ) {
        App()
    }
}
