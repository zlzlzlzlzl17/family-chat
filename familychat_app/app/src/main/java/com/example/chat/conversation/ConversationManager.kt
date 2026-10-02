package com.example.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Owns the conversation list and relationship operations. Room is the list's display source. */
internal class ConversationManager(
    private val preferences: ChatPreferences,
    private val auth: AuthSessionManager,
    private val repository: ConversationRepository,
    private val local: LocalChatRepository,
    private val selectionStore: ConversationSelectionStore,
    private val connectionStore: RealtimeConnectionStore,
    private val userPreferences: UserPreferencesStore,
    private val realtime: RealtimeGateway,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(initialState())
    private var summaryObserver: Job? = null
    private var activeOwnerId = ""

    val state: StateFlow<ConversationUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            auth.state.collect { authState ->
                when (authState.phase) {
                    AuthSessionPhase.AUTHENTICATED -> {
                        mutableState.value = mutableState.value.copy(
                            isLoggedIn = true,
                            me = authState.me,
                            error = null,
                        )
                        observeRoom(auth.sessionSnapshot().ownerId)
                        refresh(showIndicator = mutableState.value.conversations.isEmpty())
                    }
                    AuthSessionPhase.SIGNED_OUT -> {
                        summaryObserver?.cancel()
                        activeOwnerId = ""
                        mutableState.value = initialState()
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            realtime.events.collect { raw ->
                val type = runCatching { JSONObject(raw).optString("type") }.getOrDefault("")
                if (type in SUMMARY_REFRESH_EVENTS) refresh(showIndicator = false)
            }
        }
        scope.launch {
            userPreferences.state.collect { preferences ->
                mutableState.value = mutableState.value.copy(language = preferences.language)
            }
        }
        scope.launch {
            connectionStore.status.collect { status ->
                mutableState.value = mutableState.value.copy(
                    connectionStatus = status,
                    isConnected = status == ConnectionStatus.CONNECTED,
                )
            }
        }
        scope.launch {
            selectionStore.selection.collect { selected ->
                mutableState.value = mutableState.value.copy(currentConversationId = selected.conversationId)
            }
        }
    }

    fun openConversation(conversationId: Long, forceRefresh: Boolean = false) {
        selectionStore.select(conversationId, forceRefresh)
        if (forceRefresh) scope.launch { refresh(showIndicator = false) }
    }

    fun refresh(showIndicator: Boolean = true) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            if (showIndicator) mutableState.value = mutableState.value.copy(isRefreshing = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val users = async { repository.users(session.serverUrl, session.accessToken) }
                        val conversations = async { repository.conversations(session.serverUrl, session.accessToken) }
                        val contacts = async { repository.contactRequests(session.serverUrl, session.accessToken) }
                        val groups = async { repository.groupJoinRequests(session.serverUrl, session.accessToken) }
                        RemoteConversationSnapshot(
                            users = users.await(),
                            conversations = conversations.await(),
                            contactRequests = contacts.await(),
                            groupJoinRequests = groups.await(),
                        )
                    }
                }
            }.onSuccess { snapshot ->
                withContext(Dispatchers.IO) {
                    local.saveSummaries(session.ownerId, snapshot.conversations)
                }
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    users = snapshot.users,
                    contactRequests = snapshot.contactRequests,
                    groupJoinRequests = snapshot.groupJoinRequests,
                    isRefreshing = false,
                    error = null,
                )
            }.onFailure { error ->
                // showIndicator distinguishes "the user asked for this" from a
                // background sync triggered by a socket event or a reconnect.
                //
                // A background failure is not something anyone can act on: the
                // app retries, and connection state is already shown in the home
                // bar subtitle. Surfacing it put a red banner on the screen for a
                // refresh nobody requested. It goes to diagnostics instead, where
                // it is useful when someone is actually investigating.
                FamilyChatDiagnostics.event(
                    "conversation_refresh_failed",
                    "user_initiated" to showIndicator,
                    "type" to error.javaClass.name,
                    "detail" to error.message.orEmpty(),
                )
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = if (showIndicator) error.message ?: "request_failed" else null,
                )
            }
        }
    }

    fun lookupUserByCode(userCode: String) = launchRelationshipAction {
        val session = requireSession()
        val result = withContext(Dispatchers.IO) {
            repository.lookupUser(session.serverUrl, session.accessToken, userCode.trim())
        }
        mutableState.value = mutableState.value.copy(userLookupResult = result, relationshipMessage = null)
    }

    fun requestContact(userCode: String) = launchRelationshipAction(refreshAfter = true) {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            repository.requestContact(session.serverUrl, session.accessToken, userCode.trim())
        }
        mutableState.value = mutableState.value.copy(relationshipMessage = "Contact request sent")
    }

    fun reviewContactRequest(requestId: Long, approve: Boolean) = launchRelationshipAction(refreshAfter = true) {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            repository.reviewContactRequest(session.serverUrl, session.accessToken, requestId, approve)
        }
        mutableState.value = mutableState.value.copy(
            relationshipMessage = if (approve) "Contact request approved" else "Contact request declined",
        )
    }

    fun createGroup(title: String) = launchRelationshipAction(refreshAfter = true) {
        val session = requireSession()
        val conversationId = withContext(Dispatchers.IO) {
            repository.createGroup(session.serverUrl, session.accessToken, title.trim())
        }
        selectionStore.select(conversationId, forceRefresh = true)
        mutableState.value = mutableState.value.copy(relationshipMessage = "Group created")
    }

    fun lookupGroupByCode(groupCode: String) = launchRelationshipAction {
        val session = requireSession()
        val result = withContext(Dispatchers.IO) {
            repository.lookupGroup(session.serverUrl, session.accessToken, groupCode.trim())
        }
        mutableState.value = mutableState.value.copy(groupLookupResult = result, relationshipMessage = null)
    }

    fun requestJoinGroup(groupCode: String) = launchRelationshipAction(refreshAfter = true) {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            repository.requestJoinGroup(session.serverUrl, session.accessToken, groupCode.trim())
        }
        mutableState.value = mutableState.value.copy(relationshipMessage = "Group join request sent")
    }

    fun reviewGroupJoinRequest(requestId: Long, approve: Boolean) = launchRelationshipAction(refreshAfter = true) {
        val session = requireSession()
        withContext(Dispatchers.IO) {
            repository.reviewGroupJoinRequest(session.serverUrl, session.accessToken, requestId, approve)
        }
        mutableState.value = mutableState.value.copy(
            relationshipMessage = if (approve) "Group request approved" else "Group request declined",
        )
    }

    fun clearRelationshipMessage() {
        mutableState.value = mutableState.value.copy(relationshipMessage = null)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun markConversationReadLocally(conversationId: Long, messageId: Long) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || conversationId <= 0L) return
        scope.launch {
            val summaries = mutableState.value.conversations.map { summary ->
                if (summary.id != conversationId) summary else summary.copy(
                    unreadCount = 0,
                    lastReadMessageId = maxOf(summary.lastReadMessageId, messageId),
                )
            }
            withContext(Dispatchers.IO) { local.saveSummaries(session.ownerId, summaries) }
        }
    }

    private fun observeRoom(ownerId: String) {
        if (ownerId.isBlank() || activeOwnerId == ownerId && summaryObserver?.isActive == true) return
        summaryObserver?.cancel()
        activeOwnerId = ownerId
        summaryObserver = scope.launch {
            local.observeSummaries(ownerId).collect { summaries ->
                mutableState.value = mutableState.value.copy(
                    conversations = summaries,
                    isLoading = false,
                )
            }
        }
    }

    private fun launchRelationshipAction(
        refreshAfter: Boolean = false,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            runCatching { action() }
                .onSuccess { if (refreshAfter) refresh(showIndicator = false) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        relationshipMessage = error.message ?: "Request failed",
                    )
                }
        }
    }

    private fun requireSession(): SyncSession = auth.sessionSnapshot().also {
        check(it.isReady) { "session_expired" }
    }

    private fun initialState() = ConversationUiState(
        serverUrl = preferences.serverUrl,
        language = AppLanguage.fromStored(preferences.language),
        connectionStatus = connectionStore.status.value,
        isConnected = connectionStore.status.value == ConnectionStatus.CONNECTED,
    )

    private data class RemoteConversationSnapshot(
        val users: List<ChatUser>,
        val conversations: List<ConversationSummary>,
        val contactRequests: List<ContactRequestInfo>,
        val groupJoinRequests: List<GroupJoinRequestInfo>,
    )

    private companion object {
        val SUMMARY_REFRESH_EVENTS = setOf(
            "chat",
            "message_recalled",
            "conversation_updated",
            "conversation_members_changed",
            "conversation_removed",
            "conversation_history_deleted",
            "history_deleted",
            "user_updated",
            "user_deleted",
            "contact_request_updated",
            "group_join_request_updated",
            "group_key_epoch_rotated",
        )
    }
}
