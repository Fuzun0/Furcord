package com.furcord.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.DmRepository

@Composable
fun ServerRail(
    servers: List<Pair<String, String>>,      // (id, isim)
    activeServerId: String?,                  // null = ana sayfa
    version: String,
    onHome: () -> Unit,
    onServerClick: (id: String, name: String) -> Unit,
    onOpenDm: () -> Unit,
    idToken: String = "",
    voiceServerId: String? = null,            // ses kanalına bağlı olunan sunucu
) {
    val unread by DmRepository.unreadCount.collectAsState()

    // Ikonu henüz bilinmeyen sunucuların görselini çek (ortak haritaya yazılır → şerit, lobi, sunucu ekranı güncellenir)
    LaunchedEffect(servers, idToken) {
        if (idToken.isBlank()) return@LaunchedEffect
        servers.forEach { (sid, _) ->
            if (ServerIcons.urls[sid].isNullOrEmpty()) {
                runCatching { com.furcord.auth.FirestoreClient.getServerImageUrl(sid, idToken) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { ServerIcons.urls[sid] = it }
            }
        }
    }

    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(AppColors.BgDeepest)
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Ana sayfa
        RailButton(selected = activeServerId == null, tooltip = "Ana Sayfa", onClick = onHome) {
            FurcordLogoIcon(size = 26.dp)
        }

        // Ayırıcı çizgi
        Box(
            Modifier.width(32.dp).height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(AppColors.Outline)
        )

        // Sunucular
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(servers, key = { it.first }) { (id, name) ->
                val iconBmp = rememberCachedImage(ServerIcons.urls[id] ?: "")
                RailButton(
                    selected = id == activeServerId,
                    tooltip = if (id == voiceServerId) "$name  (ses bağlantısı aktif)" else name,
                    onClick = { onServerClick(id, name) },
                    connected = id == voiceServerId,
                ) {
                    if (iconBmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = iconBmp,
                            contentDescription = name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Text(
                            text = name.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // DM butonu (eski sağ alttaki "+" butonunun yerine)
        RailButton(selected = false, tooltip = "Mesajlar", onClick = onOpenDm) {
            BadgedBox(badge = {
                if (unread > 0) {
                    Badge(containerColor = AppColors.Danger) {
                        Text(if (unread > 9) "9+" else "$unread", fontSize = 9.sp, color = Color.White)
                    }
                }
            }) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = "Mesajlar",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Sürüm yazısı (artık avatarın üstüne binmiyor)
        Text("v$version", fontSize = 9.sp, color = AppColors.TextSubtle)
    }
}

@Composable
private fun RailButton(
    selected: Boolean,
    tooltip: String? = null,
    onClick: () -> Unit,
    connected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    val corner by animateDpAsState(if (selected || hovered) 16.dp else 24.dp, tween(150))
    val bg by animateColorAsState(
        when {
            selected -> AppColors.Accent
            hovered  -> AppColors.BgActive
            else     -> AppColors.BgElevated
        }, tween(150)
    )
    val barHeight by animateDpAsState(
        when { selected -> 28.dp; hovered -> 14.dp; else -> 0.dp }, tween(150)
    )

    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
        // Sol gösterge çubuğu
        Box(
            Modifier.width(4.dp).height(barHeight)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(Color.White)
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .clip(RoundedCornerShape(corner))
                .background(bg)
                .hoverable(src)
                .clickable(interactionSource = src, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (hovered && tooltip != null) {
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize,
                        ) = androidx.compose.ui.unit.IntOffset(
                            anchorBounds.right + 14,
                            anchorBounds.center.y - popupContentSize.height / 2,
                        )
                    },
                ) {
                    Text(
                        text = tooltip,
                        color = AppColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.Small))
                            .background(AppColors.BgDeepest)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        // Ses kanalına bağlı sunucu işareti: sağ altta yeşil nokta
        if (connected) {
            Box(
                Modifier.align(Alignment.Center).offset(x = 18.dp, y = 18.dp).size(14.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(AppColors.BgDeepest),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppColors.Online))
            }
        }
    }
}