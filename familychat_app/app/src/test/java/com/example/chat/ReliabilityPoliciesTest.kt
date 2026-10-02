package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityPoliciesTest {
    @Test
    fun conversationSwitchRejectsLateResultEvenWhenReturningToSameConversation() {
        val gate = ConversationLoadGate()
        val firstA = gate.begin(10L)
        val b = gate.begin(20L)
        val secondA = gate.begin(10L)

        assertFalse(gate.accepts(10L, firstA))
        assertFalse(gate.accepts(20L, b))
        assertTrue(gate.accepts(10L, secondA))
    }

    @Test
    fun transientCallDisconnectKeepsActiveCallAndAllowsDelayedFailure() {
        val policy = CallReconnectPolicy(gracePeriodMs = 25_000L)

        assertTrue(policy.canReconnect(VoiceCallPhase.ACTIVE))
        assertEquals(VoiceCallPhase.ACTIVE, policy.reconnectPhase(VoiceCallPhase.ACTIVE, 123L))
        assertTrue(policy.shouldFailAfterTimeout(VoiceCallPhase.ACTIVE))
        assertFalse(policy.shouldFailAfterTimeout(VoiceCallPhase.ENDED))
    }

    @Test
    fun iceRestartIsRateLimited() {
        val policy = CallReconnectPolicy(minimumIceRestartIntervalMs = 4_000L)

        assertFalse(policy.canRequestIceRestart(inFlight = true, lastRestartAt = 0L, now = 5_000L))
        assertFalse(policy.canRequestIceRestart(inFlight = false, lastRestartAt = 2_000L, now = 5_000L))
        assertTrue(policy.canRequestIceRestart(inFlight = false, lastRestartAt = 1_000L, now = 5_000L))
    }
}
