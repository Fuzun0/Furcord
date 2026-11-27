package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Emoji seçici: sık kullanılanlar + kategoriler. */
private val EMOJI_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Sık Kullanılan" to listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉"),
    "Yüzler" to ("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😎 🤓 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕").split(" "),
    "El ve Kalp" to ("👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 💪 👏 🙌 👐 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 💗 💯 💢 💥 💫 ✨").split(" "),
    "Eğlence" to ("🎉 🎊 🎁 🏆 🥇 🎮 🎯 🎲 🎵 🎶 🎧 🎤 🍕 🍔 🍟 🌭 🍿 🍩 🍪 🍫 🍺 🍻 ☕ 🥤 🐶 🐱 🐭 🐸 🐵 🦊 🐼 🐧 🦄 🔥 ⭐ 🌟 ⚡ 🌈 ☀️ 🌙 💀 👻 👀 🧠 🚀 💎").split(" "),
)

/** Mesajın altında görünen tepki çipleri (emoji + sayı). Benim tepkim vurgulanır. */
@Composable
fun ReactionChips(
    reactions: Map<String, List<String>>,
    myUid: String,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.entries.sortedBy { it.key }.forEach { (emoji, uids) ->
            val mine = myUid in uids
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (mine) AppColors.Accent.copy(alpha = 0.22f) else AppColors.BgElevated)
                    .border(1.dp, if (mine) AppColors.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onToggle(emoji) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(emoji, fontSize = 14.sp)
                Text(
                    "${uids.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (mine) AppColors.TextPrimary else AppColors.TextMuted,
                )
            }
        }
    }
}

/** Emoji seçme menüsü — bir emojiye basınca [onPick] çağrılır ve menü kapanır. */
@Composable
fun EmojiPickerMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111214),
    ) {
        Column(
            Modifier.width(320.dp).heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EMOJI_GROUPS.forEach { (title, list) ->
                Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = AppColors.TextMuted, letterSpacing = 0.8.sp)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEach { e ->
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                                .clickable { onPick(e); onDismiss() },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
            }
        }
    }
}

/** Yanıtlanan/alıntılanan mesajın kısa metni: metin yoksa ekin türüne göre etiket. */
fun quotePreviewText(text: String, attachmentUrl: String): String {
    if (text.isNotBlank()) return text
    if (attachmentUrl.isBlank()) return ""
    return when (com.furcord.download.Attachments.parse(attachmentUrl).kind) {
        com.furcord.download.Attachments.Kind.Image -> "🖼️ Görsel"
        com.furcord.download.Attachments.Kind.Video -> "🎬 Video"
        com.furcord.download.Attachments.Kind.Audio -> "🎵 Ses"
        com.furcord.download.Attachments.Kind.File  -> "📎 Dosya"
    }
}
