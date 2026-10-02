package com.example.chat

/** Rejects late async results after the user has switched conversations. */
internal class ConversationLoadGate {
    private var generation = 0L
    private var conversationId = 0L

    @Synchronized
    fun begin(nextConversationId: Long): Long {
        generation += 1L
        conversationId = nextConversationId
        return generation
    }

    @Synchronized
    fun accepts(expectedConversationId: Long, expectedGeneration: Long): Boolean =
        conversationId == expectedConversationId && generation == expectedGeneration
}

internal data class CallReconnectPolicy(
    val gracePeriodMs: Long = 25_000L,
    val minimumIceRestartIntervalMs: Long = 4_000L,
) {
    fun canReconnect(phase: VoiceCallPhase): Boolean =
        phase != VoiceCallPhase.IDLE && phase != VoiceCallPhase.ENDED && phase != VoiceCallPhase.FAILED

    fun reconnectPhase(current: VoiceCallPhase, startedAt: Long): VoiceCallPhase =
        if (startedAt > 0L || current == VoiceCallPhase.ACTIVE) VoiceCallPhase.ACTIVE else VoiceCallPhase.CONNECTING

    fun shouldFailAfterTimeout(phase: VoiceCallPhase): Boolean = canReconnect(phase)

    fun canRequestIceRestart(inFlight: Boolean, lastRestartAt: Long, now: Long): Boolean =
        !inFlight && now - lastRestartAt >= minimumIceRestartIntervalMs
}
