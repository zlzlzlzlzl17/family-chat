package com.example.chat

import androidx.compose.runtime.Immutable

enum class MessageDeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

fun resolveMessageDeliveryStatus(
    localSendState: LocalSendState,
    conversationKind: String,
    participantIdentities: List<String>,
    deliveryStates: Map<String, Long>,
    readStates: Map<String, Long>,
    messageId: Long,
    isOutgoing: Boolean,
): MessageDeliveryStatus {
    if (!isOutgoing) return MessageDeliveryStatus.SENT
    when (localSendState) {
        LocalSendState.SENDING -> return MessageDeliveryStatus.SENDING
        LocalSendState.FAILED -> return MessageDeliveryStatus.FAILED
        LocalSendState.SENT -> Unit
    }

    if (messageId <= 0L || participantIdentities.isEmpty()) return MessageDeliveryStatus.SENT
    val participants = participantIdentities.filter { it.isNotBlank() }.distinct()
    if (participants.isEmpty()) return MessageDeliveryStatus.SENT

    val deliveredToAll = participants.all { (deliveryStates[it] ?: 0L) >= messageId }
    val readByAll = participants.all { (readStates[it] ?: 0L) >= messageId }
    val readByAny = participants.any { (readStates[it] ?: 0L) >= messageId }

    if (readByAll) return MessageDeliveryStatus.READ
    if (conversationKind.equals("group", ignoreCase = true)) {
        return if (readByAny) MessageDeliveryStatus.DELIVERED else MessageDeliveryStatus.SENT
    }
    return if (deliveredToAll) MessageDeliveryStatus.DELIVERED else MessageDeliveryStatus.SENT
}

@Immutable
data class ReplyPreview(val id: Long, val username: String, val color: String, val preview: String)

enum class LocalSendState {
    SENT,
    SENDING,
    FAILED,
}

@Immutable
data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val ts: Long,
    val expiresAt: Long,
    val userCode: String = "",
    val username: String,
    val color: String,
    val kind: String,
    val payload: String,
    val e2ee: Boolean,
    val replyTo: ReplyPreview?,
    val mentions: List<String>,
    val localSendState: LocalSendState = LocalSendState.SENT,
    val localAttachmentPath: String = "",
    val localAttachmentName: String = "",
    val localAttachmentMime: String = "",
    val localAttachmentSize: Long = 0L,
    val localAttachmentDurationMs: Long = 0L,
    val clientMessageId: String = "",
)

@Immutable
data class TransferProgress(
    val label: String,
    val progress: Float,
    val bytesDone: Long,
    val totalBytes: Long,
    val messageId: Long = 0L,
    val conversationId: Long = 0L,
)
