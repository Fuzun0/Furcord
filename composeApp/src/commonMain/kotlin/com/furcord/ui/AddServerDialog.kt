package com.furcord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * "Sunucu ekle" penceresi — Oluştur / Katıl sekmeleri.
 *
 * @param onSearch  Davet koduyla sunucu arar → (id, isim) veya null (bulunamadı). Hata fırlatabilir.
 * @param onCreate  Sunucu oluşturur → hata mesajı döner, başarılıysa null.
 * @param onJoin    Bulunan sunucuya katılır.
 */
@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onSearch: suspend (inviteCode: String) -> Pair<String, String>?,
    onCreate: suspend (name: String) -> String?,
    onJoin: (serverId: String, serverName: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }          // 0 = Oluştur, 1 = Katıl
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun submit() {
        val value = text.trim()
        if (value.isEmpty() || loading) return
        val result = found
        if (tab == 1 && result != null) {
            onJoin(result.first, result.second)
            return
        }
        scope.launch {
            loading = true
            error = ""
            try {
                if (tab == 0) {
                    error = onCreate(value) ?: ""
                    if (error.isEmpty()) onDismiss()
                } else {
                    found = onSearch(value)
                    if (found == null) error = "Sunucu bulunamadı. Davet kodunu kontrol edin."
                }
            } catch (e: Exception) {
                error = "Bağlantı hatası: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(Radius.Large))
                .background(AppColors.BgSidebar)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sunucu ekle", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)

            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = AppColors.Accent,
            ) {
                listOf("Oluştur", "Katıl").forEachIndexed { i, title ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i; text = ""; error = ""; found = null },
                        text = { Text(title, fontSize = 13.sp) },
                        selectedContentColor = AppColors.Accent,
                        unselectedContentColor = AppColors.TextMuted,
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = ""; found = null },
                singleLine = true,
                placeholder = {
                    Text(
                        if (tab == 0) "Sunucuna bir isim ver" else "Davet kodunu gir",
                        color = AppColors.TextSubtle, fontSize = 13.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.Medium),
                isError = error.isNotEmpty(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedContainerColor = AppColors.BgInput,
                    unfocusedContainerColor = AppColors.BgInput,
                    focusedBorderColor = AppColors.Accent,
                    unfocusedBorderColor = AppColors.Outline,
                ),
            )

            if (error.isNotEmpty()) {
                Text(error, fontSize = 12.sp, color = AppColors.Danger)
            }

            val result = found
            if (tab == 1 && result != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.Medium))
                        .background(AppColors.BgElevated)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(Radius.Small)).background(AppColors.Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            result.second.take(1).uppercase(),
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        )
                    }
                    Text(
                        result.second,
                        fontSize = 14.sp, color = AppColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !loading) {
                    Text("İptal", color = AppColors.TextMuted)
                }
                Button(
                    onClick = { submit() },
                    enabled = text.isNotBlank() && !loading,
                    shape = RoundedCornerShape(Radius.Medium),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(
                            when {
                                tab == 0 -> "Oluştur"
                                found != null -> "Katıl"
                                else -> "Ara"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
