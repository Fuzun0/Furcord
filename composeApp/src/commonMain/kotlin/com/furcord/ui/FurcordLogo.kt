package com.furcord.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import furcord.composeapp.generated.resources.Res
import furcord.composeapp.generated.resources.furcord_logo
import org.jetbrains.compose.resources.painterResource

/**
 * Furcord logosu — PNG görselden yüklenir.
 *
 * @param size      Görselin boyutu (varsayılan 28.dp)
 * @param modifier  Ek modifier
 */
@Composable
fun FurcordLogoIcon(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Image(
        painter            = painterResource(Res.drawable.furcord_logo),
        contentDescription = "Furcord",
        contentScale       = ContentScale.Fit,
        modifier           = modifier.size(size),
    )
}
