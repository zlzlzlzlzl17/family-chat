package com.example.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps the legacy aggregate state internal while exposing independently invalidated
 * snapshots to each navigation surface. This lets the UI migrate screen-by-screen
 * without making message traffic recompose unrelated settings and account pages.
 */
internal class PageUiStateStore(initial: ChatUiState) {
    var shell by mutableStateOf(initial.toShellUiState())
        private set
    var login by mutableStateOf(initial.toAuthUiState())
        private set
    var home by mutableStateOf(initial.toConversationUiState())
        private set
    var chat by mutableStateOf(initial.toChatScreenUiState())
        private set
    var settings by mutableStateOf(initial.toSettingsUiState())
        private set
    var management by mutableStateOf(initial.toManagementUiState())
        private set

    fun publish(next: ChatUiState) {
        next.toShellUiState().takeIf { it != shell }?.let { shell = it }
        next.toAuthUiState().takeIf { it != login }?.let { login = it }
        next.toConversationUiState().takeIf { it != home }?.let { home = it }
        next.toChatScreenUiState().takeIf { it != chat }?.let { chat = it }
        next.toSettingsUiState().takeIf { it != settings }?.let { settings = it }
        next.toManagementUiState().takeIf { it != management }?.let { management = it }
    }
}
