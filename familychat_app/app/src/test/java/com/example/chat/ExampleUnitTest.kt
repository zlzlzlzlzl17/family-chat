package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun sendingMessageStaysSendingEvenIfReceiptsExist() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENDING,
            conversationKind = "direct",
            participantIdentities = listOf("12345678"),
            deliveryStates = mapOf("12345678" to 42L),
            readStates = mapOf("12345678" to 42L),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.SENDING, state)
    }

    @Test
    fun failedMessageStaysFailedEvenIfReceiptsExist() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.FAILED,
            conversationKind = "direct",
            participantIdentities = listOf("12345678"),
            deliveryStates = mapOf("12345678" to 42L),
            readStates = mapOf("12345678" to 42L),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.FAILED, state)
    }

    @Test
    fun directMessageIsSentBeforeDelivery() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "direct",
            participantIdentities = listOf("12345678"),
            deliveryStates = mapOf("12345678" to 41L),
            readStates = emptyMap(),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.SENT, state)
    }

    @Test
    fun directMessageIsDeliveredAfterPermanentIdReceipt() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "direct",
            participantIdentities = listOf("12345678"),
            deliveryStates = mapOf("12345678" to 42L),
            readStates = emptyMap(),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.DELIVERED, state)
    }

    @Test
    fun directMessageIsReadAfterReadReceipt() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "direct",
            participantIdentities = listOf("12345678"),
            deliveryStates = emptyMap(),
            readStates = mapOf("12345678" to 42L),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.READ, state)
    }

    @Test
    fun groupDeliveryAloneKeepsSingleTick() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "group",
            participantIdentities = listOf("12345678", "87654321"),
            deliveryStates = mapOf("12345678" to 42L, "87654321" to 42L),
            readStates = emptyMap(),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.SENT, state)
    }

    @Test
    fun groupMessageIsDeliveredWhenAnyParticipantReads() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "group",
            participantIdentities = listOf("12345678", "87654321"),
            deliveryStates = emptyMap(),
            readStates = mapOf("12345678" to 42L, "87654321" to 41L),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.DELIVERED, state)
    }

    @Test
    fun groupMessageIsReadWhenEveryParticipantReads() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "group",
            participantIdentities = listOf("12345678", "87654321"),
            deliveryStates = emptyMap(),
            readStates = mapOf("12345678" to 42L, "87654321" to 42L),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.READ, state)
    }

    @Test
    fun missingParticipantsFallsBackToSent() {
        val state = resolveMessageDeliveryStatus(
            localSendState = LocalSendState.SENT,
            conversationKind = "direct",
            participantIdentities = emptyList(),
            deliveryStates = emptyMap(),
            readStates = emptyMap(),
            messageId = 42L,
            isOutgoing = true
        )

        assertEquals(MessageDeliveryStatus.SENT, state)
    }
}
