package com.example.chat

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Conversation message owner. UI observes only [state], which is derived from Room and feature
 * stores; network callbacks never mutate an in-memory message list.
 */
internal class ChatManager(
    private val application: Application,
    private val preferences: ChatPreferences,
    private val auth: AuthSessionManager,
    private val conversationManager: ConversationManager,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val local: LocalChatRepository,
    private val crypto: CryptoManager,
    private val attachmentManager: AttachmentManager,
    private val realtime: RealtimeGateway,
    private val selectionStore: ConversationSelectionStore,
    private val connectionStore: RealtimeConnectionStore,
    private val userPreferences: UserPreferencesStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(initialState())
    private val localMessageIds = AtomicLong(-System.currentTimeMillis() * 1_000L)
    private val resolvedText = ConcurrentHashMap<Long, String>()
    private var roomObserver: Job? = null
    private var cursorObserver: Job? = null
    private var visibleConversationId = 0L
    private var hasMoreLocalMessages = false
    private var hasMoreServerMessages = true
    private var conversationLoadStartedAt = 0L
    private var summaryRefreshJob: Job? = null
    private val syncEngine = MessageSyncEngine(
        local = local,
        remote = messageRepository,
        sessionProvider = SyncSessionProvider(auth::sessionSnapshot),
        outboxSender = OutboxSender(::sendOutboxItem),
        parentScope = scope,
    )

    val state: StateFlow<ChatScreenUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            auth.state.collect { authState ->
                when (authState.phase) {
                    AuthSessionPhase.AUTHENTICATED -> mutableState.update {
                        it.copy(
                            serverUrl = preferences.serverUrl,
                            e2eeEnabled = preferences.e2eeEnabled,
                            language = AppLanguage.fromStored(preferences.language),
                            me = authState.me,
                            isLoading = false,
                            error = null,
                        )
                    }
                    AuthSessionPhase.SIGNED_OUT -> {
                        roomObserver?.cancel()
                        cursorObserver?.cancel()
                        visibleConversationId = 0L
                        mutableState.value = initialState()
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            userPreferences.state.collect { preferencesState ->
                mutableState.update {
                    it.copy(
                        language = preferencesState.language,
                        e2eeEnabled = preferencesState.e2eeEnabled,
                    )
                }
            }
        }
        scope.launch {
            conversationManager.state.collect { conversations ->
                mutableState.update {
                    it.copy(
                        serverUrl = conversations.serverUrl,
                        language = conversations.language,
                        me = conversations.me ?: it.me,
                        users = conversations.users,
                        conversations = conversations.conversations,
                        relationshipMessage = conversations.relationshipMessage,
                    )
                }
            }
        }
        scope.launch {
            connectionStore.status.collect { status ->
                mutableState.update {
                    it.copy(
                        isConnected = status == ConnectionStatus.CONNECTED,
                        connectionStatus = status,
                    )
                }
                if (status == ConnectionStatus.CONNECTED) syncEngine.onNetworkAvailable()
                else if (status == ConnectionStatus.OFFLINE) syncEngine.onNetworkUnavailable()
            }
        }
        scope.launch {
            selectionStore.selection.collect { selection ->
                observeConversation(selection.conversationId)
                if (selection.conversationId > 0L) {
                    prepareGroupKeys(selection.conversationId)
                    refreshManage(selection.conversationId)
                    runCatching { syncEngine.syncLatest(selection.conversationId) }
                        .onFailure(::reportError)
                }
            }
        }
        scope.launch {
            attachmentManager.uploadProgress.collect { progress ->
                mutableState.update { it.copy(uploadProgress = progress) }
            }
        }
        scope.launch {
            attachmentManager.downloadProgress.collect { progress ->
                mutableState.update { it.copy(downloadProgress = progress) }
            }
        }
        scope.launch {
            syncEngine.metrics.collect { metrics ->
                mutableState.update {
                    it.copy(isRefreshing = metrics.phase == SyncPhase.SYNCING || metrics.phase == SyncPhase.RETRYING)
                }
            }
        }
        scope.launch {
            realtime.events.collect { raw -> handleRealtimeEvent(raw) }
        }
    }

    fun refresh(showIndicator: Boolean = true) {
        val conversationId = mutableState.value.currentConversationId
        if (conversationId <= 0L) return
        if (showIndicator) mutableState.update { it.copy(isRefreshing = true) }
        scope.launch {
            runCatching {
                syncEngine.syncLatest(conversationId)
                refreshManage(conversationId)
                conversationManager.refresh(showIndicator = false)
            }.onFailure(::reportError)
        }
    }

    fun loadOlder() {
        val conversationId = mutableState.value.currentConversationId
        if (conversationId <= 0L || mutableState.value.isLoadingOlder || !mutableState.value.hasMoreBefore) return
        mutableState.update { it.copy(isLoadingOlder = true) }
        scope.launch {
            local.expandMessageWindow(auth.sessionSnapshot().ownerId, conversationId)
            runCatching { syncEngine.loadOlder(conversationId) }
                .onFailure(::reportError)
            mutableState.update { it.copy(isLoadingOlder = false) }
        }
    }

    fun sendText(text: String, reply: ReplyPreview?) {
        val trimmed = text.trim()
        val snapshot = mutableState.value
        val conversation = snapshot.conversations.firstOrNull { it.id == snapshot.currentConversationId } ?: return
        val me = snapshot.me ?: return
        if (trimmed.isBlank()) return
        scope.launch {
            runCatching {
                val payload = if (preferences.e2eeEnabled) {
                    crypto.encryptText(conversation, me, trimmed)
                } else {
                    trimmed
                }
                val clientMessageId = UUID.randomUUID().toString()
                val message = ChatMessage(
                    id = localMessageIds.decrementAndGet(),
                    conversationId = conversation.id,
                    ts = System.currentTimeMillis(),
                    expiresAt = 0L,
                    userCode = me.userCode,
                    username = me.username,
                    color = me.color,
                    kind = "text",
                    payload = payload,
                    e2ee = preferences.e2eeEnabled,
                    replyTo = reply,
                    mentions = extractMentions(trimmed, snapshot.users),
                    localSendState = LocalSendState.SENDING,
                    clientMessageId = clientMessageId,
                )
                val pending = PendingOutgoing(
                    action = PendingAction.Text(trimmed, reply),
                    message = message,
                    clientMessageId = clientMessageId,
                )
                syncEngine.enqueue(pending)
            }.onFailure(::reportError)
        }
    }

    fun uploadAttachment(
        kind: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        reply: ReplyPreview?,
        durationMs: Long = 0L,
    ) {
        scope.launch {
            runCatching { attachmentManager.stageBytes(fileName, bytes) }
                .onSuccess { file -> enqueueAttachment(kind, fileName, mime, file, reply, durationMs, false) }
                .onFailure(::reportError)
        }
    }

    fun uploadAttachmentFromUri(
        kind: String,
        fileName: String,
        mime: String,
        uri: Uri,
        reply: ReplyPreview?,
        durationMs: Long = 0L,
        richContent: Boolean = false,
    ) {
        scope.launch {
            runCatching { attachmentManager.stageUri(fileName, uri) }
                .onSuccess { file -> enqueueAttachment(kind, fileName, mime, file, reply, durationMs, richContent) }
                .onFailure(::reportError)
        }
    }

    fun setConversationVisible(conversationId: Long, visible: Boolean) {
        if (visible) {
            visibleConversationId = conversationId
            markReadLatest()
        } else if (visibleConversationId == conversationId) {
            visibleConversationId = 0L
        }
    }

    fun markReadLatest() {
        val snapshot = mutableState.value
        val conversationId = snapshot.currentConversationId
        if (conversationId <= 0L || visibleConversationId != conversationId) return
        val latest = snapshot.messages.lastOrNull { it.id > 0L }?.id ?: return
        val identity = snapshot.me?.userCode?.ifBlank { snapshot.me.username }.orEmpty()
        if (identity.isBlank()) return
        if ((snapshot.currentConversationReadStates[identity] ?: 0L) >= latest) return
        scope.launch {
            local.mergeReceipt(
                ownerId = auth.sessionSnapshot().ownerId,
                conversationId = conversationId,
                participantIdentity = identity,
                deliveredMessageId = latest,
                readMessageId = latest,
            )
            syncEngine.queueRead(conversationId, identity, latest)
            syncEngine.flushReceipts()
            realtime.send(
                JSONObject()
                    .put("type", "read")
                    .put("conversation_id", conversationId)
                    .put("last_read_message_id", latest),
            )
            conversationManager.markConversationReadLocally(conversationId, latest)
        }
    }

    fun retryMessage(messageId: Long) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            local.retryOutbox(session.ownerId, messageId)
            syncEngine.drainOutbox()
        }
    }

    fun deleteLocalPendingMessage(messageId: Long) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            val pending = local.loadConversation(session.ownerId, mutableState.value.currentConversationId)
                .outbox.firstOrNull { it.message.id == messageId }
            (pending?.action as? PendingAction.Attachment)?.let { action ->
                listOf(action.localFilePath, action.encryptedFilePath)
                    .filter(String::isNotBlank)
                    .forEach { File(it).delete() }
            }
            local.deleteOutbox(session.ownerId, messageId)
        }
    }

    fun recallMessage(message: ChatMessage) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || message.id <= 0L) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    messageRepository.recallMessage(session.serverUrl, session.accessToken, message.id)
                }
                syncEngine.syncLatest(message.conversationId)
            }.onFailure(::reportError)
        }
    }

    fun clearCurrentConversationHistory() {
        val session = auth.sessionSnapshot()
        val conversationId = mutableState.value.currentConversationId
        if (!session.isReady || conversationId <= 0L) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    messageRepository.clearConversationHistory(session.serverUrl, session.accessToken, conversationId)
                }
                local.clearConversation(session.ownerId, conversationId)
                preferences.removeMessageCryptoKeysForConversation(conversationId)
                refreshManage(conversationId)
            }.onFailure(::reportError)
        }
    }

    fun refreshManage() {
        val conversationId = mutableState.value.currentConversationId
        if (conversationId > 0L) refreshManage(conversationId)
    }

    fun buildReplyPreview(message: ChatMessage): String = when (message.kind) {
        "image", "photo" -> "Image"
        "audio" -> "Voice note"
        "file" -> "File"
        "attachment_cleared" -> "Attachment removed"
        "recalled" -> "Message recalled"
        "text" -> resolvedText[message.id]?.take(80)
            ?: if (message.e2ee) "Encrypted message" else message.payload.take(80)
        else -> message.kind
    }

    suspend fun resolveMessageText(message: ChatMessage): String = crypto.resolveText(message).also {
        resolvedText[message.id] = it
    }

    suspend fun prepareMessageDecryption(message: ChatMessage) =
        crypto.prepareMessageDecryption(message)

    suspend fun materializeAttachment(
        context: Context,
        message: ChatMessage,
        exportToDownloads: Boolean,
    ): DecryptedAttachment = attachmentManager.materialize(context, message, exportToDownloads)

    suspend fun loadDraft(conversationId: Long): String {
        val ownerId = auth.sessionSnapshot().ownerId
        return if (ownerId.isBlank()) "" else withContext(Dispatchers.IO) {
            local.loadDraft(ownerId, conversationId)
        }
    }

    fun saveDraft(conversationId: Long, value: String) {
        val ownerId = auth.sessionSnapshot().ownerId
        if (ownerId.isBlank() || conversationId <= 0L) return
        scope.launch { local.saveDraft(ownerId, conversationId, value) }
    }

    fun clearDownloadProgress() = attachmentManager.clearDownloadProgress()

    // Same leak ManagementManager had: a throwable with no message fell back to
    // its Java class name, which then went straight to the UI. The type belongs
    // in diagnostics; the user gets a sentence.
    fun reportError(error: Throwable) {
        FamilyChatDiagnostics.event(
            "chat_feature_error_type",
            "type" to error.javaClass.name,
            "detail" to error.message.orEmpty(),
        )
        reportError(error.message ?: "request_failed")
    }

    fun reportError(message: String) {
        mutableState.update { it.copy(error = message) }
        FamilyChatDiagnostics.event("chat_feature_error", "error" to message.take(160))
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    private fun prepareGroupKeys(conversationId: Long) {
        val conversation = conversationManager.state.value.conversations
            .firstOrNull { it.id == conversationId }
            ?: mutableState.value.conversations.firstOrNull { it.id == conversationId }
        if (conversation?.kind != "group") return
        scope.launch {
            runCatching {
                crypto.ensureGroupSenderKeys(
                    conversationId = conversationId,
                    uploadOwnKey = false,
                    forceRefresh = true,
                )
            }.onSuccess { epoch ->
                FamilyChatDiagnostics.event(
                    "group_keys_prepared",
                    "conversation_id" to conversationId,
                    "epoch" to epoch,
                )
            }.onFailure { error ->
                FamilyChatDiagnostics.event(
                    "group_keys_prepare_failed",
                    "conversation_id" to conversationId,
                    "error" to (error.message ?: error.javaClass.simpleName).take(80),
                )
            }
        }
    }

    private fun observeConversation(conversationId: Long) {
        roomObserver?.cancel()
        cursorObserver?.cancel()
        resolvedText.clear()
        hasMoreLocalMessages = false
        hasMoreServerMessages = true
        if (conversationId <= 0L) {
            mutableState.update {
                it.copy(
                    currentConversationId = 0L,
                    messages = emptyList(),
                    currentConversationDeliveryStates = emptyMap(),
                    currentConversationReadStates = emptyMap(),
                    currentConversationManage = null,
                )
            }
            return
        }
        val ownerId = auth.sessionSnapshot().ownerId
        conversationLoadStartedAt = System.currentTimeMillis()
        mutableState.update {
            it.copy(
                currentConversationId = conversationId,
                messages = emptyList(),
                currentConversationDeliveryStates = emptyMap(),
                currentConversationReadStates = emptyMap(),
                currentConversationManage = null,
                isLoading = true,
            )
        }
        roomObserver = scope.launch {
            local.observeConversation(ownerId, conversationId).collect { persisted ->
                val loadStartedAt = conversationLoadStartedAt
                if (loadStartedAt > 0L) {
                    conversationLoadStartedAt = 0L
                    FamilyChatDiagnostics.event(
                        "chat_local_ready",
                        "conversation_id" to conversationId,
                        "duration_ms" to (System.currentTimeMillis() - loadStartedAt),
                        "confirmed_count" to persisted.messages.size,
                        "pending_count" to persisted.outbox.size,
                    )
                }
                hasMoreLocalMessages = persisted.hasMoreLocal
                val confirmedClientIds = persisted.messages.map(ChatMessage::clientMessageId)
                    .filter(String::isNotBlank)
                    .toSet()
                val pendingMessages = persisted.outbox
                    .map(PendingOutgoing::message)
                    .filterNot { it.clientMessageId.isNotBlank() && it.clientMessageId in confirmedClientIds }
                val messages = (persisted.messages + pendingMessages)
                    .distinctBy { message ->
                        message.clientMessageId.takeIf(String::isNotBlank) ?: "id:${message.id}"
                    }
                    .sortedWith(compareBy<ChatMessage> { it.ts }.thenBy { it.id })
                    .filter { it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis() }
                mutableState.update { current ->
                    if (current.currentConversationId != conversationId) current else current.copy(
                        messages = messages,
                        currentConversationDeliveryStates = persisted.deliveryStates,
                        currentConversationReadStates = persisted.readStates,
                        hasMoreBefore = hasMoreServerMessages || persisted.hasMoreLocal,
                        isLoading = false,
                    )
                }
                if (visibleConversationId == conversationId) markReadLatest()
            }
        }
        cursorObserver = scope.launch {
            local.observeSyncCursor(ownerId, conversationId).collect { cursor ->
                hasMoreServerMessages = cursor.hasMoreBefore
                mutableState.update { current ->
                    if (current.currentConversationId != conversationId) current else current.copy(
                        hasMoreBefore = cursor.hasMoreBefore || hasMoreLocalMessages,
                        isRefreshing = cursor.phase == SyncPhase.SYNCING || cursor.phase == SyncPhase.RETRYING,
                    )
                }
            }
        }
    }

    private suspend fun enqueueAttachment(
        kind: String,
        fileName: String,
        mime: String,
        file: File,
        reply: ReplyPreview?,
        durationMs: Long,
        richContent: Boolean,
    ) {
        val snapshot = mutableState.value
        val conversation = snapshot.conversations.firstOrNull { it.id == snapshot.currentConversationId }
            ?: return file.delete().let { Unit }
        val me = snapshot.me ?: return file.delete().let { Unit }
        val display = attachmentManager.displayMeta(file, kind, mime, richContent)
        val clientMessageId = UUID.randomUUID().toString()
        val message = ChatMessage(
            id = localMessageIds.decrementAndGet(),
            conversationId = conversation.id,
            ts = System.currentTimeMillis(),
            expiresAt = 0L,
            userCode = me.userCode,
            username = me.username,
            color = me.color,
            kind = kind,
            payload = JSONObject()
                .put("display", JSONObject().put("width", display.width).put("height", display.height).put("sticker", display.sticker))
                .toString(),
            e2ee = true,
            replyTo = reply,
            mentions = emptyList(),
            localSendState = LocalSendState.SENDING,
            localAttachmentPath = file.absolutePath,
            localAttachmentName = fileName,
            localAttachmentMime = mime,
            localAttachmentSize = file.length(),
            localAttachmentDurationMs = durationMs,
            clientMessageId = clientMessageId,
        )
        syncEngine.enqueue(
            PendingOutgoing(
                action = PendingAction.Attachment(kind, fileName, mime, file.absolutePath, reply, durationMs, richContent),
                message = message,
                clientMessageId = clientMessageId,
            ),
        )
    }

    private suspend fun sendOutboxItem(pending: PendingOutgoing): ChatMessage {
        val session = auth.sessionSnapshot()
        check(session.isReady) { "session_expired" }
        val conversation = mutableState.value.conversations.firstOrNull { it.id == pending.message.conversationId }
            ?: withContext(Dispatchers.IO) {
                conversationRepository.conversations(session.serverUrl, session.accessToken)
                    .firstOrNull { it.id == pending.message.conversationId }
            }
            ?: throw IllegalStateException("conversation_not_found")
        val me = auth.state.value.me ?: throw IllegalStateException("session_expired")
        val confirmed = when (val action = pending.action) {
            is PendingAction.Text -> withContext(Dispatchers.IO) {
                messageRepository.sendText(
                    serverUrl = session.serverUrl,
                    token = session.accessToken,
                    conversationId = pending.message.conversationId,
                    payload = pending.message.payload,
                    e2ee = pending.message.e2ee,
                    replyJson = action.reply?.toWireJson().orEmpty(),
                    mentions = pending.message.mentions,
                    clientMessageId = pending.clientMessageId,
                )
            }.also { crypto.rememberText(it, action.text) }
            is PendingAction.Attachment -> attachmentManager.send(pending, conversation, me)
        }
        scheduleSummaryRefresh()
        return confirmed
    }

    private fun refreshManage(conversationId: Long) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || conversationId <= 0L) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationRepository.conversationManage(session.serverUrl, session.accessToken, conversationId)
                }
            }.onSuccess { manage ->
                mutableState.update { current ->
                    if (current.currentConversationId == conversationId) current.copy(currentConversationManage = manage) else current
                }
            }
        }
    }

    private fun handleRealtimeEvent(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "chat", "message_recalled", "conversation_history_deleted", "conversation_removed" -> {
                scheduleSummaryRefresh()
                val conversationId = json.optLong("conversation_id", json.optJSONObject("message")?.optLong("conversation_id") ?: 0L)
                if (visibleConversationId == conversationId) markReadLatest()
            }
        }
    }

    private fun scheduleSummaryRefresh() {
        summaryRefreshJob?.cancel()
        summaryRefreshJob = scope.launch {
            delay(250L)
            conversationManager.refresh(showIndicator = false)
        }
    }

    private fun extractMentions(text: String, users: List<ChatUser>): List<String> {
        val allowed = users.map(ChatUser::username).toSet()
        return Regex("@([A-Za-z0-9_.-]{1,32})")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter(allowed::contains)
            .distinct()
            .toList()
    }

    private fun initialState(): ChatScreenUiState {
        val conversationSnapshot = conversationManager.state.value
        return ChatScreenUiState(
            serverUrl = conversationSnapshot.serverUrl.ifBlank { preferences.serverUrl },
            language = conversationSnapshot.language,
            e2eeEnabled = preferences.e2eeEnabled,
            me = conversationSnapshot.me ?: auth.state.value.me,
            users = conversationSnapshot.users,
            conversations = conversationSnapshot.conversations,
            currentConversationId = selectionStore.selection.value.conversationId,
            relationshipMessage = conversationSnapshot.relationshipMessage,
            connectionStatus = connectionStore.status.value,
            isConnected = connectionStore.status.value == ConnectionStatus.CONNECTED,
        )
    }
}
