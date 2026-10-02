package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateTransitionsTest {
    @Test
    fun incomingCallCanEnterConnectingExactlyOnce() {
        val incoming = VoiceCallUiState(
            phase = VoiceCallPhase.INCOMING,
            conversationId = 42L,
            isIncoming = true,
            statusMessage = "incoming",
        )

        val connecting = CallStateTransitions.beginAccept(incoming)

        assertEquals(VoiceCallPhase.CONNECTING, connecting?.phase)
        assertEquals(42L, connecting?.conversationId)
        assertEquals("connecting", connecting?.statusMessage)
        assertNull(CallStateTransitions.beginAccept(requireNotNull(connecting)))
    }

    @Test
    fun nonIncomingCallsCannotEnterAcceptPath() {
        VoiceCallPhase.entries
            .filterNot { it == VoiceCallPhase.INCOMING }
            .forEach { phase ->
                assertNull(CallStateTransitions.beginAccept(VoiceCallUiState(phase = phase)))
            }
    }
}
