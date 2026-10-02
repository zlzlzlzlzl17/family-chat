package com.example.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal data class SyncSession(
    val ownerId: String,
    val serverUrl: String,
    val accessToken: String,
) {
    val isReady: Boolean
        get() = ownerId.isNotBlank() && serverUrl.isNotBlank() && accessToken.isNotBlank()
}

internal fun interface SyncSessionProvider {
    fun current(): SyncSession
}

internal fun interface OutboxSender {
    suspend fun send(pending: PendingOutgoing): ChatMessage
}

internal data class SyncEngineMetrics(
    val phase: SyncPhase = SyncPhase.IDLE,
    val queuedMessages: Int = 0,
    val retryingMessages: Int = 0,
    val pendingReceipts: Int = 0,
    val lastSuccessfulSyncAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastErrorClass: String = "",
)

/**
 * Owns durable message synchronization. Network responses and local sends are committed to Room
 * before UI observers see them, so Room remains the sole message source of truth.
 */
internal class MessageSyncEngine(
    private val local: LocalChatRepository,
    private val remote: MessageRepository,
    private val sessionProvider: SyncSessionProvider,
    private val outboxSender: OutboxSender,
    parentScope: CoroutineScope? = null,
) : Closeable {
    private val ownedJob = SupervisorJob()
    private val scope = parentScope ?: CoroutineScope(ownedJob + Dispatchers.IO)
    private val conversationLocks = ConcurrentHashMap<Long, Mutex>()
    private val outboxDrainLock = Mutex()
    private val receiptFlushLock = Mutex()
    private val mutableMetrics = MutableStateFlow(SyncEngineMetrics())
    private var retryJob: Job? = null
    private var retryAt = Long.MAX_VALUE
    private var receiptRetryJob: Job? = null
    private var recoveryJob: Job? = null

    val metrics: StateFlow<SyncEngineMetrics> = mutableMetrics.asStateFlow()

    fun observeConversation(conversationId: Long) =
        local.observeConversation(sessionProvider.current().ownerId, conversationId)

    fun observeCursor(conversationId: Long) =
        local.observeSyncCursor(sessionProvider.current().ownerId, conversationId)

    suspend fun syncLatest(conversationId: Long, pageSize: Int = DEFAULT_PAGE_SIZE) {
        val session = sessionProvider.current()
        if (!session.isReady || conversationId <= 0L) return
        conversationLock(conversationId).withLock {
            local.updateSyncState(session.ownerId, conversationId, SyncPhase.SYNCING)
            publishPhase(SyncPhase.SYNCING)
            try {
                var cursor = local.syncCursor(session.ownerId, conversationId)
                val initialLoad = cursor.newestMessageId <= 0L
                var sinceId = cursor.newestMessageId.takeIf { it > 0L }
                var hasMore = true
                var pages = 0
                while (hasMore && pages < MAX_INCREMENTAL_PAGES) {
                    val page = remote.history(
                        serverUrl = session.serverUrl,
                        token = session.accessToken,
                        conversationId = conversationId,
                        sinceId = sinceId,
                        limit = pageSize,
                    )
                    val delivery = remote.conversationDeliveryStates(
                        session.serverUrl,
                        session.accessToken,
                        conversationId,
                    ).associate { identity(it.userCode, it.username) to it.lastDeliveredMessageId }
                    val read = remote.conversationReadStates(
                        session.serverUrl,
                        session.accessToken,
                        conversationId,
                    ).associate { identity(it.userCode, it.username) to it.lastReadMessageId }
                    local.mergeRemotePage(
                        ownerId = session.ownerId,
                        conversationId = conversationId,
                        messages = page.items,
                        deliveryStates = delivery,
                        readStates = read,
                        hasMoreBefore = if (initialLoad) page.hasMore else cursor.hasMoreBefore,
                    )
                    val newest = page.items.maxOfOrNull(ChatMessage::id) ?: 0L
                    if (newest <= (sinceId ?: 0L)) break
                    sinceId = newest
                    hasMore = page.hasMore
                    pages += 1
                    cursor = local.syncCursor(session.ownerId, conversationId)
                    if (initialLoad) break
                }
                recordSuccess()
            } catch (error: Throwable) {
                recordFailure(session.ownerId, conversationId, error)
                throw error
            }
        }
    }

    suspend fun loadOlder(conversationId: Long, pageSize: Int = DEFAULT_PAGE_SIZE): Boolean {
        val session = sessionProvider.current()
        if (!session.isReady || conversationId <= 0L) return false
        return conversationLock(conversationId).withLock {
            val cursor = local.syncCursor(session.ownerId, conversationId)
            if (!cursor.hasMoreBefore) return@withLock false
            local.updateSyncState(session.ownerId, conversationId, SyncPhase.SYNCING)
            publishPhase(SyncPhase.SYNCING)
            try {
                val beforeId = cursor.oldestMessageId.takeIf { it > 0L }
                val page = remote.history(
                    serverUrl = session.serverUrl,
                    token = session.accessToken,
                    conversationId = conversationId,
                    beforeId = beforeId,
                    limit = pageSize,
                )
                val delivery = remote.conversationDeliveryStates(
                    session.serverUrl,
                    session.accessToken,
                    conversationId,
                ).associate { identity(it.userCode, it.username) to it.lastDeliveredMessageId }
                val read = remote.conversationReadStates(
                    session.serverUrl,
                    session.accessToken,
                    conversationId,
                ).associate { identity(it.userCode, it.username) to it.lastReadMessageId }
                local.mergeRemotePage(
                    ownerId = session.ownerId,
                    conversationId = conversationId,
                    messages = page.items,
                    deliveryStates = delivery,
                    readStates = read,
                    hasMoreBefore = page.hasMore,
                )
                recordSuccess()
                page.hasMore
            } catch (error: Throwable) {
                recordFailure(session.ownerId, conversationId, error)
                throw error
            }
        }
    }

    suspend fun enqueue(pending: PendingOutgoing) {
        val session = sessionProvider.current()
        if (!session.isReady) return
        local.putOutbox(session.ownerId, pending.copy(nextAttemptAt = 0L))
        updateQueueMetrics()
        drainOutbox()
    }

    suspend fun drainOutbox() {
        val session = sessionProvider.current()
        if (!session.isReady) return
        outboxDrainLock.withLock {
            while (true) {
                val due = local.dueOutbox(session.ownerId, System.currentTimeMillis(), OUTBOX_BATCH_SIZE)
                if (due.isEmpty()) break
                for (pending in due) {
                    val attempt = pending.attemptCount + 1
                    local.markOutboxAttempt(
                        session.ownerId,
                        pending.message.id,
                        attempt,
                        0L,
                        OutboxState.SENDING,
                    )
                    runCatching { outboxSender.send(pending) }
                        .onSuccess { confirmed ->
                            local.mergeRemotePage(
                                ownerId = session.ownerId,
                                conversationId = confirmed.conversationId,
                                messages = listOf(confirmed),
                                deliveryStates = emptyMap(),
                                readStates = emptyMap(),
                                hasMoreBefore = local.syncCursor(session.ownerId, confirmed.conversationId).hasMoreBefore,
                            )
                            local.deleteOutbox(session.ownerId, pending.message.id)
                        }
                        .onFailure { error ->
                            val permanent = attempt >= MAX_OUTBOX_ATTEMPTS
                            val nextAttemptAt = if (permanent) {
                                Long.MAX_VALUE
                            } else {
                                System.currentTimeMillis() + SyncRetryPolicy.delayMs(attempt, pending.message.id)
                            }
                            local.markOutboxAttempt(
                                ownerId = session.ownerId,
                                localMessageId = pending.message.id,
                                attemptCount = attempt,
                                nextAttemptAt = nextAttemptAt,
                                state = if (permanent) OutboxState.FAILED else OutboxState.RETRY_WAIT,
                                error = error.javaClass.simpleName,
                            )
                            if (!permanent) scheduleRetry(nextAttemptAt)
                        }
                }
                updateQueueMetrics()
            }
            local.nextOutboxAttemptAt(session.ownerId)
                ?.takeIf { it < Long.MAX_VALUE }
                ?.let(::scheduleRetry)
        }
    }

    suspend fun queueDelivered(conversationId: Long, participantIdentity: String, messageId: Long) {
        queueReceipt(conversationId, participantIdentity, deliveredMessageId = messageId, readMessageId = 0L)
    }

    suspend fun queueRead(conversationId: Long, participantIdentity: String, messageId: Long) {
        queueReceipt(conversationId, participantIdentity, deliveredMessageId = messageId, readMessageId = messageId)
    }

    suspend fun flushReceipts() {
        val session = sessionProvider.current()
        if (!session.isReady) return
        receiptFlushLock.withLock {
            val pending = local.pendingReceipts(session.ownerId)
            var failed = 0
            for (receipt in pending) {
                val conversationId = receipt.conversationId
                if (conversationId <= 0L) continue
                runCatching {
                    if (receipt.deliveredPending && receipt.deliveredMessageId > 0L) {
                        remote.markDelivered(
                            session.serverUrl,
                            session.accessToken,
                            conversationId,
                            receipt.deliveredMessageId,
                        )
                    }
                    if (receipt.readPending && receipt.readMessageId > 0L) {
                        remote.markRead(
                            session.serverUrl,
                            session.accessToken,
                            conversationId,
                            receipt.readMessageId,
                        )
                    }
                    local.acknowledgeReceipt(
                        session.ownerId,
                        conversationId,
                        receipt.participantIdentity,
                    )
                }.onFailure {
                    failed += 1
                    FamilyChatDiagnostics.sampled(
                        "receipt_flush_failed",
                        10_000L,
                        "conversation_id" to conversationId,
                        "error" to it.javaClass.simpleName,
                        "reason" to it.message.orEmpty().take(120),
                    )
                }
            }
            mutableMetrics.value = mutableMetrics.value.copy(pendingReceipts = local.pendingReceipts(session.ownerId).size)
            if (failed > 0) scheduleReceiptRetry()
        }
    }

    fun onNetworkAvailable() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            publishPhase(SyncPhase.SYNCING)
            FamilyChatDiagnostics.event("sync_recovery_started")
            try {
                drainOutbox()
                flushReceipts()
            } finally {
                publishPhase(SyncPhase.IDLE)
                FamilyChatDiagnostics.event("sync_recovery_completed")
            }
        }
    }

    fun onNetworkUnavailable() {
        publishPhase(SyncPhase.OFFLINE)
    }

    private suspend fun queueReceipt(
        conversationId: Long,
        participantIdentity: String,
        deliveredMessageId: Long,
        readMessageId: Long,
    ) {
        val session = sessionProvider.current()
        if (!session.isReady || conversationId <= 0L || participantIdentity.isBlank()) return
        local.queueReceipt(
            ownerId = session.ownerId,
            conversationId = conversationId,
            participantIdentity = participantIdentity,
            deliveredMessageId = deliveredMessageId,
            readMessageId = readMessageId,
        )
        mutableMetrics.value = mutableMetrics.value.copy(
            pendingReceipts = local.pendingReceipts(session.ownerId).size,
        )
    }

    @Synchronized
    private fun scheduleRetry(at: Long) {
        if (retryJob?.isActive == true && at >= retryAt) return
        retryJob?.cancel()
        retryAt = at
        retryJob = scope.launch {
            delay(max(0L, at - System.currentTimeMillis()))
            try {
                drainOutbox()
            } finally {
                synchronized(this@MessageSyncEngine) {
                    if (retryAt == at) {
                        retryAt = Long.MAX_VALUE
                        retryJob = null
                    }
                }
            }
        }
    }

    @Synchronized
    private fun scheduleReceiptRetry() {
        if (receiptRetryJob?.isActive == true) return
        receiptRetryJob = scope.launch {
            delay(RECEIPT_RETRY_DELAY_MS)
            try {
                flushReceipts()
            } finally {
                synchronized(this@MessageSyncEngine) {
                    receiptRetryJob = null
                }
            }
        }
    }

    private suspend fun updateQueueMetrics() {
        val session = sessionProvider.current()
        if (!session.isReady) return
        val queued = local.dueOutbox(session.ownerId, Long.MAX_VALUE, Int.MAX_VALUE)
        mutableMetrics.value = mutableMetrics.value.copy(
            queuedMessages = queued.size,
            retryingMessages = queued.count { it.attemptCount > 0 },
        )
    }

    private suspend fun recordFailure(ownerId: String, conversationId: Long, error: Throwable) {
        local.updateSyncState(ownerId, conversationId, SyncPhase.RETRYING, error.javaClass.simpleName)
        val current = mutableMetrics.value
        mutableMetrics.value = current.copy(
            phase = SyncPhase.RETRYING,
            lastFailureAt = System.currentTimeMillis(),
            consecutiveFailures = current.consecutiveFailures + 1,
            lastErrorClass = error.javaClass.simpleName,
        )
    }

    private fun recordSuccess() {
        mutableMetrics.value = mutableMetrics.value.copy(
            phase = SyncPhase.IDLE,
            lastSuccessfulSyncAt = System.currentTimeMillis(),
            consecutiveFailures = 0,
            lastErrorClass = "",
        )
    }

    private fun publishPhase(phase: SyncPhase) {
        mutableMetrics.value = mutableMetrics.value.copy(phase = phase)
    }

    private fun conversationLock(conversationId: Long): Mutex =
        conversationLocks.getOrPut(conversationId) { Mutex() }

    override fun close() {
        retryJob?.cancel()
        receiptRetryJob?.cancel()
        recoveryJob?.cancel()
        ownedJob.cancel()
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_INCREMENTAL_PAGES = 20
        private const val OUTBOX_BATCH_SIZE = 20
        private const val MAX_OUTBOX_ATTEMPTS = 8
        private const val RECEIPT_RETRY_DELAY_MS = 5_000L

        private fun identity(userCode: String, username: String): String =
            userCode.trim().ifBlank { username.trim() }
    }
}
