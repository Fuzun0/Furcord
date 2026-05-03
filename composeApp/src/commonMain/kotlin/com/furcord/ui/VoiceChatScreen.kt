package com.furcord.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.furcord.auth.AuthUser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChatScreen(
    currentUser: AuthUser? = null,
    onSignOut: (() -> Unit)? = null,
) {
    val viewModel = remember { VoiceChatViewModel() }

    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Furcord  —  Voice Chat",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (currentUser != null) {
                        Text(
                            text = currentUser.email,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    if (onSignOut != null) {
                        IconButton(onClick = onSignOut) {
                            Text("⏻", fontSize = 18.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {

            // ── Status card ──────────────────────────────────────────────
            StatusCard(
                isConnected = uiState.isConnected,
                message = uiState.statusMessage,
            )

            Spacer(Modifier.height(8.dp))

            // ── Room ID input ────────────────────────────────────────────
            OutlinedTextField(
                value = uiState.roomIdInput,
                onValueChange = viewModel::onRoomIdChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Room ID") },
                placeholder = { Text("e.g. gaming-lounge-42") },
                singleLine = true,
                enabled = !uiState.isConnected,
                shape = RoundedCornerShape(12.dp),
            )

            // ── Connect / Disconnect button ──────────────────────────────
            Button(
                onClick = {
                    if (uiState.isConnected) viewModel.disconnect()
                    else viewModel.connect()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isConnected)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (uiState.isConnected) "Disconnect" else "Connect",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Mute / Unmute button ─────────────────────────────────────
            MuteButton(
                isMuted = uiState.isMuted,
                enabled = uiState.isConnected,
                onClick = viewModel::toggleMute,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun StatusCard(isConnected: Boolean, message: String) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected)
            MaterialTheme.colorScheme.secondaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 400),
        label = "statusCardColor",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Connection indicator dot
            Surface(
                shape = CircleShape,
                color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                modifier = Modifier.size(12.dp),
            ) {}

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MuteButton(
    isMuted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val buttonColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            isMuted  -> MaterialTheme.colorScheme.errorContainer
            else     -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(durationMillis = 300),
        label = "muteButtonColor",
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            isMuted  -> MaterialTheme.colorScheme.onErrorContainer
            else     -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = tween(durationMillis = 300),
        label = "muteContentColor",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                contentColor = contentColor,
                disabledContainerColor = buttonColor,
                disabledContentColor = contentColor,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                text = if (isMuted) "🔇" else "🎙️",
                fontSize = 40.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = when {
                !enabled -> "Join a room to use the mic"
                isMuted  -> "Muted  —  tap to unmute"
                else     -> "Mic On  —  tap to mute"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.outline,
        )
    }
}
