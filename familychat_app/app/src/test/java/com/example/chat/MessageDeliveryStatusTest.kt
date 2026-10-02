package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Receipt state has regressed several times in this project, and until now it had
 * no coverage at all. These tests pin the contract of
 * [resolveMessageDeliveryStatus] so that session, presence or FCM changes cannot
 * quietly alter what the ticks mean.
 *
 * Intended user-facing model:
 *   one grey tick   = sent
 *   two grey ticks  = delivered
 *   two blue ticks  = actually read
 */
class MessageDeliveryStatusTest {

    private fun resolve(
        localSendState: LocalSendState = LocalSendState.SENT,
        conversationKind: String = "direct",
        participants: List<String> = listOf("peer-a"),
        delivered: Map<String, Long> = emptyMap(),
        read: Map<String, Long> = emptyMap(),
        messageId: Long = 100L,
        isOutgoing: Boolean = true,
    ) = resolveMessageDeliveryStatus(
        localSendState = localSendState,
        conversationKind = conversationKind,
        participantIdentities = participants,
        deliveryStates = delivered,
        readStates = read,
        messageId = messageId,
        isOutgoing = isOutgoing,
    )

    // --- short circuits -----------------------------------------------------

    @Test
    fun incomingMessagesNeverShowSenderReceipts() {
        assertEquals(
            MessageDeliveryStatus.SENT,
            resolve(isOutgoing = false, read = mapOf("peer-a" to 999L)),
        )
    }

    @Test
    fun inFlightAndFailedSendsOutrankAnyRemoteState() {
        assertEquals(
            MessageDeliveryStatus.SENDING,
            resolve(localSendState = LocalSendState.SENDING, read = mapOf("peer-a" to 999L)),
        )
        assertEquals(
            MessageDeliveryStatus.FAILED,
            resolve(localSendState = LocalSendState.FAILED, read = mapOf("peer-a" to 999L)),
        )
    }

    @Test
    fun unacknowledgedMessageIdFallsBackToSent() {
        assertEquals(MessageDeliveryStatus.SENT, resolve(messageId = 0L))
        assertEquals(MessageDeliveryStatus.SENT, resolve(messageId = -1L))
    }

    @Test
    fun missingOrBlankParticipantsFallBackToSent() {
        assertEquals(MessageDeliveryStatus.SENT, resolve(participants = emptyList()))
        assertEquals(MessageDeliveryStatus.SENT, resolve(participants = listOf("", "   ")))
    }

    // --- direct conversations -----------------------------------------------

    @Test
    fun directChatShowsDeliveredOnlyWhenThePeerHasReceivedIt() {
        assertEquals(MessageDeliveryStatus.SENT, resolve(delivered = emptyMap()))
        assertEquals(
            MessageDeliveryStatus.DELIVERED,
            resolve(delivered = mapOf("peer-a" to 100L)),
        )
    }

    @Test
    fun directChatShowsReadOnlyWhenThePeerHasReadIt() {
        assertEquals(
            MessageDeliveryStatus.READ,
            resolve(delivered = mapOf("peer-a" to 100L), read = mapOf("peer-a" to 100L)),
        )
    }

    @Test
    fun readOutranksDeliveredEvenWithoutADeliveryReceipt() {
        // A read receipt implies delivery, so a missing delivery entry must not
        // downgrade the state.
        assertEquals(
            MessageDeliveryStatus.READ,
            resolve(delivered = emptyMap(), read = mapOf("peer-a" to 100L)),
        )
    }

    @Test
    fun laterWatermarksStillCoverEarlierMessages() {
        // States are watermarks, not per-message flags: a peer at message 500 has
        // necessarily seen message 100.
        assertEquals(
            MessageDeliveryStatus.READ,
            resolve(messageId = 100L, read = mapOf("peer-a" to 500L)),
        )
    }

    @Test
    fun aWatermarkBelowTheMessageDoesNotCount() {
        assertEquals(
            MessageDeliveryStatus.SENT,
            resolve(messageId = 100L, delivered = mapOf("peer-a" to 99L)),
        )
    }

    // --- group conversations ------------------------------------------------

    @Test
    fun groupShowsReadOnlyWhenEveryMemberHasRead() {
        val members = listOf("a", "b", "c")
        assertEquals(
            MessageDeliveryStatus.READ,
            resolve(
                conversationKind = "group",
                participants = members,
                read = mapOf("a" to 100L, "b" to 100L, "c" to 100L),
            ),
        )
        assertEquals(
            MessageDeliveryStatus.DELIVERED,
            resolve(
                conversationKind = "group",
                participants = members,
                read = mapOf("a" to 100L, "b" to 100L),
            ),
        )
    }

    @Test
    fun groupDeliveryIsDerivedFromReadsNotDeliveries() {
        // Deliberate asymmetry with direct chats, pinned here because it is
        // surprising: a group message delivered to everyone but read by nobody
        // stays on one tick. Changing this is a product decision, not a bug fix -
        // if this test fails, that decision was made by accident.
        assertEquals(
            MessageDeliveryStatus.SENT,
            resolve(
                conversationKind = "group",
                participants = listOf("a", "b"),
                delivered = mapOf("a" to 100L, "b" to 100L),
                read = emptyMap(),
            ),
        )
    }

    @Test
    fun groupKindMatchIsCaseInsensitive() {
        assertEquals(
            MessageDeliveryStatus.SENT,
            resolve(
                conversationKind = "GROUP",
                participants = listOf("a", "b"),
                delivered = mapOf("a" to 100L, "b" to 100L),
            ),
        )
    }

    @Test
    fun duplicateParticipantsDoNotBlockCompletion() {
        assertEquals(
            MessageDeliveryStatus.READ,
            resolve(
                conversationKind = "group",
                participants = listOf("a", "a", "b"),
                read = mapOf("a" to 100L, "b" to 100L),
            ),
        )
    }
}
