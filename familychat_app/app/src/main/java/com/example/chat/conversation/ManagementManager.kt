package com.example.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Account, peer, and group administration without message-list ownership. */
internal class ManagementManager(
    private val preferences: ChatPreferences,
    private val preferenceStore: UserPreferencesStore,
    private val auth: AuthSessionManager,
    private val authRepository: AuthRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationManager: ConversationManager,
    private val settingsManager: SettingsManager,
    private val chatManager: ChatManager,
    private val selectionStore: ConversationSelectionStore,
    private val local: LocalChatRepository,
    private val realtime: RealtimeGateway,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<ManagementUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            realtime.events.collect { raw ->
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@collect
                if (json.optString("type") in MANAGE_REFRESH_EVENTS) {
                    refreshManage()
                }
            }
        }
        scope.launch {
            auth.state.collect { authState ->
                when (authState.phase) {
                    AuthSessionPhase.AUTHENTICATED -> {
                        val identity = runCatching {
                            DeviceIdentityManager.localIdentity(preferences, authState.me?.userCode.orEmpty())
                        }.getOrNull()
                        mutableState.update {
                            it.copy(isLoggedIn = true, me = authState.me, deviceIdentity = identity, error = null)
                        }
                    }
                    AuthSessionPhase.SIGNED_OUT -> mutableState.value = initialState()
                    else -> Unit
                }
            }
        }
        scope.launch {
            preferenceStore.state.collect { appearance ->
                mutableState.update { it.copy(language = appearance.language) }
            }
        }
        scope.launch {
            conversationManager.state.collect { conversations ->
                mutableState.update {
                    it.copy(
                        me = conversations.me ?: it.me,
                        users = conversations.users,
                        conversations = conversations.conversations,
                        contactRequests = conversations.contactRequests,
                        groupJoinRequests = conversations.groupJoinRequests,
                        relationshipMessage = conversations.relationshipMessage ?: it.relationshipMessage,
                    )
                }
            }
        }
        scope.launch {
            settingsManager.state.collect { settings ->
                mutableState.update {
                    it.copy(
                        deviceIdentity = settings.deviceIdentity ?: it.deviceIdentity,
                        deviceIdentities = settings.deviceIdentities,
                        myDevices = settings.myDevices,
                    )
                }
            }
        }
        scope.launch {
            selectionStore.selection.collect { selection ->
                mutableState.update { it.copy(currentConversationId = selection.conversationId) }
            }
        }
    }

    fun selectConversation(conversationId: Long) {
        if (conversationId <= 0L) return
        selectionStore.select(conversationId)
        refreshManage(conversationId)
    }

    fun refreshManage(conversationId: Long = mutableState.value.currentConversationId) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || conversationId <= 0L) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationRepository.conversationManage(session.serverUrl, session.accessToken, conversationId)
                }
            }.onSuccess { manage ->
                mutableState.update { state ->
                    if (state.currentConversationId == conversationId) state.copy(currentConversationManage = manage, error = null)
                    else state
                }
            }.onFailure(::reportError)
        }
    }

    fun changeUsername(username: String) = launchAccountAction {
        val trimmed = username.trim()
        check(trimmed.isNotBlank()) { "invalid_username" }
        val session = requireSession()
        val user = withContext(Dispatchers.IO) {
            authRepository.changeUsername(session.serverUrl, session.accessToken, trimmed)
        }
        preferences.username = user.username
        auth.replaceCurrentUser(user)
        conversationManager.refresh(showIndicator = false)
        mutableState.update {
            it.copy(
                me = user,
                relationshipMessage = if (it.language == AppLanguage.ZH) "用户名已更新" else "Username updated",
            )
        }
    }

    fun uploadAvatar(fileName: String, mime: String, bytes: ByteArray) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            mutableState.update {
                it.copy(isRefreshing = true, uploadProgress = TransferProgress(fileName, 0f, 0L, bytes.size.toLong()), error = null)
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    authRepository.uploadAvatar(session.serverUrl, session.accessToken, fileName, mime, bytes) { done, total ->
                        mutableState.update {
                            it.copy(uploadProgress = TransferProgress(fileName, progress(done, total), done, total))
                        }
                    }
                    authRepository.me(session.serverUrl, session.accessToken)
                }
            }.onSuccess { user ->
                auth.replaceCurrentUser(user)
                conversationManager.refresh(showIndicator = false)
                mutableState.update { it.copy(me = user, isRefreshing = false, uploadProgress = null) }
            }.onFailure { error ->
                mutableState.update { it.copy(isRefreshing = false, uploadProgress = null, error = error.message ?: "Avatar upload failed") }
            }
        }
    }

    fun changePassword(password: String) = launchAccountAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            authRepository.changePassword(session.serverUrl, session.accessToken, password)
        }
        auth.logout(remote = false)
    }

    fun requestAccountDeletion() = launchAccountAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            authRepository.requestAccountDeletion(session.serverUrl, session.accessToken)
        }
        mutableState.update { it.copy(relationshipMessage = stringsFor(it.language).accountDeletionRequested) }
    }

    fun logout(clearSavedLogin: Boolean) {
        if (clearSavedLogin) preferences.clearSavedLogin()
        auth.logout(remote = true)
    }

    fun requestContact(userCode: String) {
        conversationManager.requestContact(userCode)
    }

    fun deleteDirectConversation() = launchConversationAction(removeLocally = true) { session, conversationId ->
        withContext(Dispatchers.IO) {
            conversationRepository.deleteDirectConversation(session.serverUrl, session.accessToken, conversationId)
        }
    }

    fun clearConversationHistory() = chatManager.clearCurrentConversationHistory()

    fun changeGroupTitle(title: String) = launchGroupAction {
        val session = requireSession()
        val conversationId = requireConversationId()
        withContext(Dispatchers.IO) {
            conversationRepository.changeGroupTitle(session.serverUrl, session.accessToken, conversationId, title.trim())
        }
    }

    fun setGroupExpiration(ttlMs: Long) = launchGroupAction {
        val session = requireSession()
        val conversationId = requireConversationId()
        withContext(Dispatchers.IO) {
            conversationRepository.setGroupExpiration(session.serverUrl, session.accessToken, conversationId, ttlMs)
        }
    }

    fun uploadGroupAvatar(fileName: String, mime: String, bytes: ByteArray) {
        val session = auth.sessionSnapshot()
        val conversationId = mutableState.value.currentConversationId
        if (!session.isReady || conversationId <= 0L) return
        scope.launch {
            mutableState.update { it.copy(uploadProgress = TransferProgress(fileName, 0f, 0L, bytes.size.toLong()), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationRepository.uploadConversationAvatar(
                        session.serverUrl,
                        session.accessToken,
                        conversationId,
                        fileName,
                        mime,
                        bytes,
                    ) { done, total ->
                        mutableState.update {
                            it.copy(uploadProgress = TransferProgress(fileName, progress(done, total), done, total))
                        }
                    }
                }
            }.onSuccess {
                mutableState.update { it.copy(uploadProgress = null) }
                refreshAfterGroupChange(conversationId)
            }.onFailure { error ->
                mutableState.update { it.copy(uploadProgress = null, error = error.message ?: "Avatar upload failed") }
            }
        }
    }

    fun addGroupMember(userCode: String) = launchGroupAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            conversationRepository.addGroupMember(session.serverUrl, session.accessToken, requireConversationId(), userCode)
        }
    }

    fun removeGroupMember(userCode: String) = launchGroupAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            conversationRepository.removeGroupMember(session.serverUrl, session.accessToken, requireConversationId(), userCode)
        }
    }

    fun transferGroupOwner(userCode: String) = launchGroupAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            conversationRepository.transferGroupOwner(session.serverUrl, session.accessToken, requireConversationId(), userCode)
        }
    }

    fun requestGroupAdmin(userCode: String) = launchGroupAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            conversationRepository.requestGroupAdmin(session.serverUrl, session.accessToken, requireConversationId(), userCode)
        }
    }

    fun removeGroupAdmin(userCode: String) = launchGroupAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            conversationRepository.removeGroupAdmin(session.serverUrl, session.accessToken, requireConversationId(), userCode)
        }
    }

    fun reviewGroupAdminRequest(requestId: Long, approve: Boolean) = launchGroupAction {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            conversationRepository.reviewGroupAdminRequest(session.serverUrl, session.accessToken, requestId, approve)
        }
    }

    fun reviewGroupJoinRequest(requestId: Long, approve: Boolean) {
        conversationManager.reviewGroupJoinRequest(requestId, approve)
        scope.launch {
            kotlinx.coroutines.delay(300L)
            refreshManage()
        }
    }

    fun leaveGroup() = launchConversationAction(removeLocally = true) { session, conversationId ->
        withContext(Dispatchers.IO) {
            conversationRepository.leaveGroupConversation(session.serverUrl, session.accessToken, conversationId)
        }
    }

    fun deleteGroup() = launchConversationAction(removeLocally = true) { session, conversationId ->
        withContext(Dispatchers.IO) {
            conversationRepository.deleteGroupConversation(session.serverUrl, session.accessToken, conversationId)
        }
    }

    fun clearMessage() = mutableState.update { it.copy(relationshipMessage = null) }
    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun launchAccountAction(action: suspend () -> Unit) {
        scope.launch {
            mutableState.update { it.copy(isRefreshing = true, error = null) }
            runCatching { action() }
                .onSuccess { mutableState.update { it.copy(isRefreshing = false) } }
                .onFailure { error -> mutableState.update { it.copy(isRefreshing = false, error = error.message ?: "Request failed") } }
        }
    }

    private fun launchGroupAction(action: suspend () -> Unit) {
        val conversationId = mutableState.value.currentConversationId
        scope.launch {
            mutableState.update { it.copy(isRefreshing = true, error = null) }
            runCatching { action() }
                .onSuccess { refreshAfterGroupChange(conversationId) }
                .onFailure { error -> mutableState.update { it.copy(isRefreshing = false, error = error.message ?: "Request failed") } }
        }
    }

    private fun launchConversationAction(
        removeLocally: Boolean,
        action: suspend (SyncSession, Long) -> Unit,
    ) {
        val session = auth.sessionSnapshot()
        val conversationId = mutableState.value.currentConversationId
        if (!session.isReady || conversationId <= 0L) return
        scope.launch {
            mutableState.update { it.copy(isRefreshing = true, error = null) }
            runCatching { action(session, conversationId) }
                .onSuccess {
                    if (removeLocally) {
                        withContext(Dispatchers.IO) { local.deleteConversation(session.ownerId, conversationId) }
                        preferences.removeMessageCryptoKeysForConversation(conversationId)
                    }
                    mutableState.update {
                        it.copy(currentConversationId = 0L, currentConversationManage = null, isRefreshing = false)
                    }
                    selectionStore.select(0L)
                    conversationManager.refresh(showIndicator = false)
                }
                .onFailure { error -> mutableState.update { it.copy(isRefreshing = false, error = error.message ?: "Request failed") } }
        }
    }

    private fun refreshAfterGroupChange(conversationId: Long) {
        conversationManager.refresh(showIndicator = false)
        refreshManage(conversationId)
        mutableState.update { it.copy(isRefreshing = false, relationshipMessage = null) }
    }

    private fun requireSession(): SyncSession = auth.sessionSnapshot().also {
        check(it.isReady) { "session_expired" }
    }

    private fun requireConversationId(): Long = mutableState.value.currentConversationId.also {
        check(it > 0L) { "conversation_not_found" }
    }

    private fun reportError(error: Throwable) {
        // Never surface a Java class name. When a throwable carries no message
        // there is nothing a family member can act on, so this becomes a generic
        // failure that friendlyErrorMessage renders as plain language, and the
        // real type goes to diagnostics where it is actually useful.
        FamilyChatDiagnostics.event(
            "management_error",
            "type" to error.javaClass.name,
            "detail" to error.message.orEmpty(),
        )
        mutableState.update { it.copy(error = error.message ?: "request_failed") }
    }

    private fun progress(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    private fun initialState() = ManagementUiState(
        serverUrl = preferences.serverUrl,
        language = preferenceStore.state.value.language,
    )

    private companion object {
        val MANAGE_REFRESH_EVENTS = setOf(
            "conversation_updated",
            "conversation_members_changed",
            "conversation_history_deleted",
            "group_key_epoch_rotated",
            "devices_changed",
            "user_updated",
            "user_deleted",
            "group_join_request_updated",
        )
    }
}
