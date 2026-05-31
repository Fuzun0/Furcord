package com.furcord.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furcord.auth.AuthResult
import com.furcord.auth.AuthUser
import com.furcord.auth.FirebaseAuth
import kotlinx.coroutines.launch

@Composable
private fun GoogleLetter() {
    Text(
        text = "G",
        color = Color(0xFF4285F4),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun AuthScreen(onAuthenticated: (AuthUser) -> Unit) {
    val scope   = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier  = Modifier.width(380.dp),
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier            = Modifier.padding(horizontal = 32.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FurcordLogoIcon(size = 64.dp)
                Text(
                    text       = "Furcord'a Hoş Geldin",
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface,
                    textAlign  = TextAlign.Center,
                )
                Text(
                    text      = "Devam etmek için Google hesabınla giriş yap.",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick  = {
                        if (!loading) {
                            error = ""
                            loading = true
                            scope.launch {
                                val result = FirebaseAuth.signInWithGoogle()
                                loading = false
                                when (result) {
                                    is AuthResult.Success -> onAuthenticated(result.user)
                                    is AuthResult.Failure -> error = result.message
                                }
                            }
                        }
                    },
                    enabled  = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Tarayıcıda giriş yapılıyor…")
                    } else {
                        GoogleLetter()
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text       = "Google ile Giriş Yap",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 15.sp,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = error.isNotEmpty(),
                    enter   = fadeIn(),
                    exit    = fadeOut(),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(8.dp),
                        color    = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text      = error,
                            color     = MaterialTheme.colorScheme.onErrorContainer,
                            style     = MaterialTheme.typography.bodySmall,
                            modifier  = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Text(
                    text      = "Giriş yaparak kullanım koşullarını kabul etmiş olursunuz.",
                    style     = MaterialTheme.typography.labelSmall,
                    color     = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}