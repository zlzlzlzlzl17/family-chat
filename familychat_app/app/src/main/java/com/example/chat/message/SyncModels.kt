package com.example.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

internal enum class SyncPhase {
    IDLE,
    SYNCING,
    RETRYING,
    OFFLINE,
}

internal enum class OutboxState {
    QUEUED,
    SENDING,
    RETRY_WAIT,
    FAILED,
}

internal enum class AttachmentTransferState {
    QUEUED,
    TRANSFERRING,
    PAUSED,
    RETRY_WAIT,
    COMPLETE,
    FAILED,
}

@Entity(
    tableName = "local_sync_cursors",
    primaryKeys = ["ownerId", "conversationId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["ownerId", "updatedAt"])],
)
internal data class LocalSyncCursorEntity(
    val ownerId: String,
    val conversationId: Long,
    val newestMessageId: Long,
    val oldestMessageId: Long,
    val hasMoreBefore: Boolean,
    val phase: String,
    val continuationCursor: String,
    val lastSuccessfulSyncAt: Long,
    val lastAttemptAt: Long,
    val lastError: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_receipts",
    primaryKeys = ["ownerId", "conversationId", "participantIdentity"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["ownerId", "conversationId"])],
)
internal data class LocalReceiptEntity(
    val ownerId: String,
    val conversationId: Long,
    val participantIdentity: String,
    val deliveredMessageId: Long,
    val readMessageId: Long,
    val deliveredPending: Boolean,
    val readPending: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_attachment_transfers",
    primaryKeys = ["ownerId", "transferId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["ownerId", "conversationId", "updatedAt"]),
        Index(value = ["ownerId", "state", "nextAttemptAt"]),
        Index(value = ["ownerId", "clientMessageId"]),
    ],
)
internal data class LocalAttachmentTransferEntity(
    val ownerId: String,
    val transferId: String,
    val clientMessageId: String,
    val localMessageId: Long,
    val conversationId: Long,
    val direction: String,
    val localFilePath: String,
    val remotePath: String,
    val checksumSha256: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val state: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ConversationSyncCursor(
    val conversationId: Long,
    val newestMessageId: Long = 0L,
    val oldestMessageId: Long = 0L,
    val hasMoreBefore: Boolean = true,
    val phase: SyncPhase = SyncPhase.IDLE,
    val continuationCursor: String = "",
    val lastSuccessfulSyncAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastError: String = "",
)

internal data class PersistedReceiptState(
    val conversationId: Long,
    val participantIdentity: String,
    val deliveredMessageId: Long,
    val readMessageId: Long,
    val deliveredPending: Boolean,
    val readPending: Boolean,
)

internal data class PersistedAttachmentTransfer(
    val transferId: String,
    val clientMessageId: String,
    val localMessageId: Long,
    val conversationId: Long,
    val direction: String,
    val localFilePath: String,
    val remotePath: String,
    val checksumSha256: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val state: AttachmentTransferState,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
)

internal object SyncRetryPolicy {
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 5L * 60L * 1_000L

    fun delayMs(attempt: Int, jitterSeed: Long = 0L): Long {
        val exponent = attempt.coerceIn(0, 9)
        val raw = (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
        val jitter = if (jitterSeed == 0L) 0L else (kotlin.math.abs(jitterSeed) % (raw / 4L + 1L))
        return (raw + jitter).coerceAtMost(MAX_DELAY_MS)
    }
}

internal fun LocalSyncCursorEntity.toModel() = ConversationSyncCursor(
    conversationId = conversationId,
    newestMessageId = newestMessageId,
    oldestMessageId = oldestMessageId,
    hasMoreBefore = hasMoreBefore,
    phase = runCatching { SyncPhase.valueOf(phase) }.getOrDefault(SyncPhase.IDLE),
    continuationCursor = continuationCursor,
    lastSuccessfulSyncAt = lastSuccessfulSyncAt,
    lastAttemptAt = lastAttemptAt,
    lastError = lastError,
)

internal fun LocalReceiptEntity.toModel() = PersistedReceiptState(
    conversationId = conversationId,
    participantIdentity = participantIdentity,
    deliveredMessageId = deliveredMessageId,
    readMessageId = readMessageId,
    deliveredPending = deliveredPending,
    readPending = readPending,
)

internal fun LocalAttachmentTransferEntity.toModel() = PersistedAttachmentTransfer(
    transferId = transferId,
    clientMessageId = clientMessageId,
    localMessageId = localMessageId,
    conversationId = conversationId,
    direction = direction,
    localFilePath = localFilePath,
    remotePath = remotePath,
    checksumSha256 = checksumSha256,
    bytesTransferred = bytesTransferred,
    totalBytes = totalBytes,
    state = runCatching { AttachmentTransferState.valueOf(state) }
        .getOrDefault(AttachmentTransferState.FAILED),
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun PersistedAttachmentTransfer.toEntity(ownerId: String) = LocalAttachmentTransferEntity(
    ownerId = ownerId,
    transferId = transferId,
    clientMessageId = clientMessageId,
    localMessageId = localMessageId,
    conversationId = conversationId,
    direction = direction,
    localFilePath = localFilePath,
    remotePath = remotePath,
    checksumSha256 = checksumSha256,
    bytesTransferred = bytesTransferred,
    totalBytes = totalBytes,
    state = state.name,
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
