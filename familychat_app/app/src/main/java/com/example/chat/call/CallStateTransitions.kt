package com.example.chat

internal object CallStateTransitions {
    fun beginAccept(state: VoiceCallUiState): VoiceCallUiState? =
        state
            .takeIf { it.phase == VoiceCallPhase.INCOMING }
            ?.copy(phase = VoiceCallPhase.CONNECTING, statusMessage = "connecting")
}
