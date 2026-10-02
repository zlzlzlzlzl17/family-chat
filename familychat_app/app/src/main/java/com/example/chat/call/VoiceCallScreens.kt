package com.example.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.chat.ui.theme.FullShape
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun VoiceCallScreen(
    callState: VoiceCallUiState,
    strings: AppStrings,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectAudioRoute: (VoiceAudioRoute) -> Unit,
    onDismissTerminal: () -> Unit,
    onMinimize: () -> Unit,
) {
    var audioOutputMenuExpanded by remember(callState.currentAudioRoute, callState.availableAudioRoutes) { mutableStateOf(false) }
    val dismissAction = {
        when (callState.phase) {
            VoiceCallPhase.INCOMING -> onDecline()
            VoiceCallPhase.ENDED, VoiceCallPhase.FAILED -> onDismissTerminal()
            else -> onMinimize()
        }
    }
    BackHandler { dismissAction() }
    val durationText by produceState(initialValue = "00:00", callState.startedAt, callState.phase) {
        if (callState.phase != VoiceCallPhase.ACTIVE || callState.startedAt <= 0L) {
            value = "00:00"
            return@produceState
        }
        while (true) {
            val elapsedSeconds = ((System.currentTimeMillis() - callState.startedAt) / 1000L).coerceAtLeast(0L)
            val minutes = elapsedSeconds / 60L
            val seconds = elapsedSeconds % 60L
            value = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            delay(1_000L)
        }
    }
    val showRecoveryHint = callState.statusMessage == strings.callReconnecting ||
        callState.statusMessage.contains("reconnect", ignoreCase = true) ||
        callState.statusMessage.contains("\u91cd\u8fde")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0F1824),
                            Color(0xFF091018),
                            Color(0xFF06080C)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (callState.phase != VoiceCallPhase.ENDED && callState.phase != VoiceCallPhase.FAILED) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.08f)
                        ) {
                            IconButton(onClick = onMinimize) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = strings.minimizeCall,
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AvatarBubble(
                        avatarUrl = "",
                        name = callState.peerUsername.ifBlank { strings.voiceCall },
                        colorHex = "#128c7e",
                        size = 96.dp,
                        serverUrl = DEFAULT_SERVER_URL,
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            callState.peerUsername.ifBlank { strings.voiceCall },
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = callState.statusMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFB8C4D6)
                        )
                        AnimatedVisibility(visible = showRecoveryHint) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(190.dp)
                                    .height(3.dp)
                                    .clip(FullShape),
                                color = Color(0xFF6FD7C8),
                                trackColor = Color.White.copy(alpha = 0.16f)
                            )
                        }
                        if (callState.phase == VoiceCallPhase.ACTIVE) {
                            Text(
                                text = durationText,
                                color = Color(0xFF6FD7C8),
                                // Tabular figures: without them the duration
                                // shifts horizontally every second as digit
                                // widths change.
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFeatureSettings = "tnum"
                                )
                            )
                            CallQualitySummary(callState.quality)
                        }
                    }
                }

                when (callState.phase) {
                    VoiceCallPhase.INCOMING -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onDecline,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFF3A1F23),
                                    contentColor = Color(0xFFFFC8CC)
                                )
                            ) {
                                Text(strings.decline)
                            }
                            Button(
                                onClick = onAccept,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(strings.accept)
                            }
                        }
                    }

                    VoiceCallPhase.ENDED, VoiceCallPhase.FAILED -> {
                        Button(
                            onClick = onDismissTerminal,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.cancel)
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onToggleMute,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.1f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (callState.isMuted) strings.unmute else strings.mute
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (callState.isMuted) strings.unmute else strings.mute)
                                }

                                Box(modifier = Modifier.weight(1f)) {
                                    FilledTonalButton(
                                        onClick = { audioOutputMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color.White.copy(alpha = 0.1f),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            when (callState.currentAudioRoute) {
                                                VoiceAudioRoute.BLUETOOTH -> Icons.Default.VolumeOff
                                                VoiceAudioRoute.SPEAKER -> Icons.Default.VolumeUp
                                                VoiceAudioRoute.EARPIECE -> Icons.Default.VolumeOff
                                            },
                                            contentDescription = strings.audioOutput
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.Start,
                                            verticalArrangement = Arrangement.spacedBy(1.dp)
                                        ) {
                                            Text(strings.audioOutput, style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                audioRouteLabel(callState.currentAudioRoute, strings),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = strings.audioOutput)
                                    }
                                    DropdownMenu(
                                        expanded = audioOutputMenuExpanded,
                                        onDismissRequest = { audioOutputMenuExpanded = false }
                                    ) {
                                        callState.availableAudioRoutes.forEach { route ->
                                            DropdownMenuItem(
                                                text = { Text(audioRouteLabel(route, strings)) },
                                                onClick = {
                                                    audioOutputMenuExpanded = false
                                                    onSelectAudioRoute(route)
                                                },
                                                trailingIcon = {
                                                    if (route == callState.currentAudioRoute) {
                                                        // Only marker of which output is active.
                                                        Icon(
                                                            Icons.Default.Done,
                                                            contentDescription = strings.selectedLabel
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = onEnd,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB3261E),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = strings.endCall)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.endCall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallQualitySummary(quality: WebRtcCallQuality) {
    if (!quality.hasMetrics) return
    val text = buildList {
        quality.packetLossPercent?.let { add(String.format(Locale.US, "Loss %.1f%%", it)) }
        quality.jitterMs?.let { add(String.format(Locale.US, "Jitter %.0f ms", it)) }
        quality.roundTripTimeMs?.let { add(String.format(Locale.US, "RTT %.0f ms", it)) }
    }.joinToString("  ")
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.64f),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
internal fun VoiceCallMiniBubbleLayer(
    callState: VoiceCallUiState,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onEnd: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val bubbleWidth = 220.dp
    val bubbleHeight = 90.dp
    val bubbleWidthPx = with(density) { bubbleWidth.roundToPx() }
    val bubbleHeightPx = with(density) { bubbleHeight.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val edgePaddingPx = with(density) { 12.dp.roundToPx() }
    val bottomInsetPx = with(density) { 110.dp.roundToPx() }
    val maxOffsetX = (screenWidthPx - bubbleWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
    val maxOffsetY = (screenHeightPx - bubbleHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
    var offsetX by rememberSaveable(screenWidthPx) {
        mutableIntStateOf(maxOffsetX)
    }
    var offsetY by rememberSaveable(screenHeightPx) {
        mutableIntStateOf((screenHeightPx - bubbleHeightPx - bottomInsetPx).coerceIn(edgePaddingPx, maxOffsetY))
    }

    LaunchedEffect(maxOffsetX, maxOffsetY, edgePaddingPx) {
        offsetX = offsetX.coerceIn(edgePaddingPx, maxOffsetX)
        offsetY = offsetY.coerceIn(edgePaddingPx, maxOffsetY)
    }

    Box(modifier = modifier) {
        VoiceCallMiniBubble(
            callState = callState,
            strings = strings,
            modifier = Modifier
                .offset { IntOffset(offsetX, offsetY) }
                .pointerInput(maxOffsetX, maxOffsetY) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x.roundToInt()).coerceIn(edgePaddingPx, maxOffsetX)
                        offsetY = (offsetY + dragAmount.y.roundToInt()).coerceIn(edgePaddingPx, maxOffsetY)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onExpand() })
                },
            onEnd = onEnd
        )
    }
}

@Composable
private fun VoiceCallMiniBubble(
    callState: VoiceCallUiState,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    onEnd: () -> Unit,
) {
    val durationText by produceState(initialValue = "00:00", callState.startedAt, callState.phase) {
        if (callState.phase != VoiceCallPhase.ACTIVE || callState.startedAt <= 0L) {
            value = callState.statusMessage
            return@produceState
        }
        while (true) {
            val elapsedSeconds = ((System.currentTimeMillis() - callState.startedAt) / 1000L).coerceAtLeast(0L)
            value = String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60L, elapsedSeconds % 60L)
            delay(1_000L)
        }
    }
    ElevatedCard(
        modifier = modifier.widthIn(min = 180.dp, max = 240.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xEE101A27)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarBubble(
                avatarUrl = "",
                name = callState.peerUsername.ifBlank { strings.voiceCall },
                colorHex = "#128c7e",
                size = 42.dp,
                serverUrl = DEFAULT_SERVER_URL,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    callState.peerUsername.ifBlank { strings.voiceCall },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                Text(
                    if (callState.phase == VoiceCallPhase.ACTIVE) durationText else callState.statusMessage,
                    color = Color(0xFFB8C4D6),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    strings.returnToCall,
                    color = Color(0xFF6FD7C8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            FilledTonalButton(
                onClick = onEnd,
                modifier = Modifier.height(38.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF3A1F23),
                    contentColor = Color(0xFFFFC8CC)
                )
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = strings.endCall)
            }
        }
    }
}

private fun audioRouteLabel(route: VoiceAudioRoute, strings: AppStrings): String =
    when (route) {
        VoiceAudioRoute.EARPIECE -> strings.earpiece
        VoiceAudioRoute.SPEAKER -> strings.speaker
        VoiceAudioRoute.BLUETOOTH -> strings.bluetooth
    }
