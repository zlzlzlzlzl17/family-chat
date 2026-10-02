package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCachePolicyTest {
    @Test
    fun confirmedMessages_filtersPendingExpiredAndOldOverflow() {
        val now = 10_000L
        val messages = listOf(
            message(id = 4L, ts = 4L),
            message(id = -1L, ts = 5L, state = LocalSendState.FAILED),
            message(id = 2L, ts = 2L),
            message(id = 3L, ts = 3L, expiresAt = now),
            message(id = 1L, ts = 1L),
        )

        val cached = LocalCachePolicy.confirmedMessages(messages, now, limit = 2)

        assertEquals(listOf(2L, 4L), cached.map { it.id })
        assertTrue(cached.all { it.localSendState == LocalSendState.SENT })
    }

    @Test
    fun restoredOutbox_isFailedUntilServerConfirmsIt() {
        val pending = PendingOutgoing(
            action = PendingAction.Text("hello", null),
            message = message(id = -8L, ts = 8L, state = LocalSendState.SENDING),
        )

        val restored = LocalCachePolicy.restoredPending(pending)

        assertEquals(LocalSendState.FAILED, restored.message.localSendState)
        assertEquals("hello", (restored.action as PendingAction.Text).text)
    }

    private fun message(
        id: Long,
        ts: Long,
        expiresAt: Long = 0L,
        state: LocalSendState = LocalSendState.SENT,
    ) = ChatMessage(
        id = id,
        conversationId = 12L,
        ts = ts,
        expiresAt = expiresAt,
        userCode = "12345678",
        username = "test",
        color = "#128C7E",
        kind = "text",
        payload = "ciphertext",
        e2ee = true,
        replyTo = null,
        mentions = emptyList(),
        localSendState = state,
    )
}
