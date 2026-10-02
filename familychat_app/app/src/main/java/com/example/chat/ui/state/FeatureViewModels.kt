package com.example.chat

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShellViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    var uiState by mutableStateOf(ShellUiState())
        private set
    private var appForeground = false
    private var backgroundStartedAt = 0L
    private var recoveryJob: Job? = null
    private var pendingForceReconnect = false
    private var pendingConversationRefresh = false

    init {
        viewModelScope.launch {
            container.authSessionManager.state.collect { auth ->
                when (auth.phase) {
                    AuthSessionPhase.AUTHENTICATING -> {
                        uiState = uiState.copy(
                            isLoggedIn = false,
                            isRestoringSession = true,
                            error = null,
                        )
                        withContext(Dispatchers.IO) { container.prepareHomeFeatures() }
                    }
                    AuthSessionPhase.AUTHENTICATED -> {
                        if (uiState.isLoggedIn) {
                            uiState = uiState.copy(
                                language = container.userPreferencesStore.state.value.language,
                                error = null,
                            )
                            return@collect
                        }
                        uiState = uiState.copy(isRestoringSession = true, error = null)
                        withContext(Dispatchers.IO) { container.prepareHomeFeatures() }
                        if (container.authSessionManager.state.value.phase == AuthSessionPhase.AUTHENTICATED) {
                            uiState = uiState.copy(
                                isLoggedIn = true,
                                isRestoringSession = false,
                                language = container.userPreferencesStore.state.value.language,
                                error = null,
                            )
                            container.applicationScope.launch {
                                delay(350L)
                                if (container.authSessionManager.state.value.phase == AuthSessionPhase.AUTHENTICATED) {
                                    container.prepareChatFeatures()
                                }
                            }
                        }
                    }
                    else -> uiState = uiState.copy(
                        isLoggedIn = false,
                        isRestoringSession = false,
                        language = container.userPreferencesStore.state.value.language,
                        error = auth.error,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.userPreferencesStore.state.collect { preferences ->
                uiState = uiState.copy(language = preferences.language)
            }
        }
        viewModelScope.launch {
            container.networkMonitor.available.collect {
                if (container.authSessionManager.state.value.isLoggedIn) {
                    FamilyChatDiagnostics.sampled("network_available_recovery", 2_000L)
                    scheduleRealtimeRecovery(
                        forceSocket = true,
                        refreshConversations = appForeground,
                    )
                }
            }
        }
    }

    fun onAppForeground() {
        if (appForeground) return
        val now = SystemClock.elapsedRealtime()
        val backgroundDuration = if (backgroundStartedAt > 0L) now - backgroundStartedAt else 0L
        appForeground = true
        backgroundStartedAt = 0L
        FamilyChatDiagnostics.setAppForeground(true)
        FamilyChatDiagnostics.sampled(
            "app_foreground",
            2_000L,
            "background_ms" to backgroundDuration,
        )
        if (container.authSessionManager.state.value.isLoggedIn) {
            container.realtimeGateway.setPresence("foreground")
            scheduleRealtimeRecovery(
                forceSocket = backgroundDuration >= 20_000L,
                refreshConversations = true,
            )
            container.callCoordinator.onAppForeground()
        }
    }

    fun onAppBackground() {
        if (!appForeground) return
        appForeground = false
        backgroundStartedAt = SystemClock.elapsedRealtime()
        FamilyChatDiagnostics.setAppForeground(false)
        FamilyChatDiagnostics.sampled("app_background", 2_000L)
        container.realtimeGateway.setPresence("background")
        container.callCoordinator.onAppBackground()
    }

    private fun scheduleRealtimeRecovery(forceSocket: Boolean, refreshConversations: Boolean) {
        pendingForceReconnect = pendingForceReconnect || forceSocket
        pendingConversationRefresh = pendingConversationRefresh || refreshConversations
        if (recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            do {
                val forceNow = pendingForceReconnect
                val refreshNow = pendingConversationRefresh
                pendingForceReconnect = false
                pendingConversationRefresh = false
                when (container.authSessionManager.prepareForegroundSession()) {
                    SessionRefreshOutcome.TRUSTED,
                    SessionRefreshOutcome.TRANSIENT_FAILURE -> {
                        container.realtimeGateway.setPresence(
                            if (appForeground) "foreground" else "background"
                        )
                        container.realtimeGateway.connect(force = forceNow)
                        container.pushCoordinator.registerCurrentDevice()
                        if (refreshNow && appForeground) {
                            container.conversationManager.refresh(showIndicator = false)
                        }
                    }
                    SessionRefreshOutcome.PENDING,
                    SessionRefreshOutcome.EXPIRED -> Unit
                }
            } while (pendingForceReconnect || pendingConversationRefresh)
        }
    }

    fun checkForUpdates() = container.settingsManager.checkStable()
    fun clearRelationshipMessage() = container.conversationManager.clearRelationshipMessage()
    fun reportError(message: String) {
        uiState = uiState.copy(error = message)
        FamilyChatDiagnostics.event("shell_error", "error" to message.take(160))
    }
}

class AuthViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    private val manager = container.authSessionManager
    var uiState by mutableStateOf(manager.state.value.toUiState(container.preferences))
        private set

    init {
        viewModelScope.launch {
            manager.state.collect { state -> uiState = state.toUiState(container.preferences) }
        }
    }

    fun login(userCode: String, password: String) = manager.login(userCode, password)
    fun requestRegistration(username: String, password: String, confirmPassword: String) =
        manager.requestRegistration(username, password, confirmPassword)
    fun checkDeviceApprovalNow() = manager.checkDeviceApprovalNow()
    fun cancelPendingDeviceLogin() = manager.cancelPendingDeviceLogin()
    fun clearError() = manager.clearError()
    fun updateLanguage(language: AppLanguage) {
        container.userPreferencesStore.setLanguage(language)
        uiState = uiState.copy(language = language)
    }
}

private fun AuthSessionState.toUiState(preferences: ChatPreferences) = AuthUiState(
    serverUrl = preferences.serverUrl,
    language = AppLanguage.fromStored(preferences.language),
    displayMode = AppDisplayMode.fromStored(preferences.displayMode),
    dynamicColorsEnabled = preferences.dynamicColorsEnabled,
    textSize = AppTextSize.fromStored(preferences.textSize),
    isLoading = isLoading,
    isLoggedIn = isLoggedIn,
    me = me,
    registrationMessage = registrationMessage,
    registrationUserCode = registrationUserCode,
    deviceApprovalPending = phase == AuthSessionPhase.PENDING_DEVICE,
    pendingDeviceId = pendingDeviceId,
    error = error,
)

class ConversationViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    private val manager = container.conversationManager
    var uiState by mutableStateOf(manager.state.value)
        private set

    init {
        viewModelScope.launch {
            manager.state.collect { uiState = it }
        }
    }

    fun openConversation(conversationId: Long, forceRefresh: Boolean = false) =
        manager.openConversation(conversationId, forceRefresh)
    fun refreshNow(reconnectIfNeeded: Boolean = true, showIndicator: Boolean = true) =
        manager.refresh(showIndicator)
    fun lookupUserByCode(userCode: String) = manager.lookupUserByCode(userCode)
    fun requestContact(userCode: String) = manager.requestContact(userCode)
    fun reviewContactRequest(requestId: Long, approve: Boolean) = manager.reviewContactRequest(requestId, approve)
    fun createGroup(title: String) = manager.createGroup(title)
    fun lookupGroupByCode(groupCode: String) = manager.lookupGroupByCode(groupCode)
    fun requestJoinGroup(groupCode: String) = manager.requestJoinGroup(groupCode)
    fun reviewGroupJoinRequest(requestId: Long, approve: Boolean) = manager.reviewGroupJoinRequest(requestId, approve)
    fun clearRelationshipMessage() = manager.clearRelationshipMessage()
}

class ChatViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    private val manager = container.chatManager
    var uiState by mutableStateOf(manager.state.value)
        private set

    init {
        viewModelScope.launch {
            manager.state.collect { uiState = it }
        }
    }

    fun refreshCurrentConversationManage() = manager.refreshManage()
    fun clearCurrentConversationHistory() = manager.clearCurrentConversationHistory()
    fun refreshNow(reconnectIfNeeded: Boolean = true, showIndicator: Boolean = true) =
        manager.refresh(showIndicator)
    fun loadOlder() = manager.loadOlder()
    fun sendText(text: String, reply: ReplyPreview?) = manager.sendText(text, reply)
    fun uploadAttachment(
        kind: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        reply: ReplyPreview?,
        durationMs: Long = 0L,
    ) = manager.uploadAttachment(kind, fileName, mime, bytes, reply, durationMs)
    fun uploadAttachmentFromUri(
        kind: String,
        fileName: String,
        mime: String,
        uri: Uri,
        reply: ReplyPreview?,
        durationMs: Long = 0L,
        richContent: Boolean = false,
    ) = manager.uploadAttachmentFromUri(kind, fileName, mime, uri, reply, durationMs, richContent)
    fun setConversationVisible(conversationId: Long, visible: Boolean) =
        manager.setConversationVisible(conversationId, visible)
    fun markReadLatest() = manager.markReadLatest()
    fun retryMessage(messageId: Long) = manager.retryMessage(messageId)
    fun deleteLocalPendingMessage(messageId: Long) = manager.deleteLocalPendingMessage(messageId)
    fun recallMessage(message: ChatMessage) = manager.recallMessage(message)
    fun buildReplyPreview(message: ChatMessage): String = manager.buildReplyPreview(message)
    suspend fun resolveMessageText(message: ChatMessage): String = manager.resolveMessageText(message)
    suspend fun prepareMessageDecryption(message: ChatMessage) = manager.prepareMessageDecryption(message)
    suspend fun materializeAttachment(
        context: Context,
        message: ChatMessage,
        exportToDownloads: Boolean = true,
    ): DecryptedAttachment = manager.materializeAttachment(context, message, exportToDownloads)
    suspend fun loadDraft(conversationId: Long): String = manager.loadDraft(conversationId)
    fun saveDraft(conversationId: Long, value: String) = manager.saveDraft(conversationId, value)
    fun clearDownloadProgress() = manager.clearDownloadProgress()
    fun reportError(message: String) = manager.reportError(message)
    fun clearError() = manager.clearError()
}

class CallViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    private val manager = container.callCoordinator
    var uiState by mutableStateOf(manager.state.value)
        private set

    init {
        viewModelScope.launch {
            manager.state.collect { uiState = it }
        }
    }

    fun startVoiceCall(conversationId: Long) = manager.start(conversationId)
    fun acceptIncomingCall() = manager.accept()
    fun declineIncomingCall() = manager.decline()
    fun endVoiceCall() = manager.end()
    fun toggleVoiceCallMute() = manager.toggleMute()
    fun setVoiceCallAudioRoute(route: VoiceAudioRoute) = manager.selectAudioRoute(route)
    fun dismissVoiceCallStatus() = manager.dismissTerminal()
    fun onAppForeground() = manager.onAppForeground()
    fun onAppBackground() = manager.onAppBackground()
    fun clearError() = manager.clearError()
}

class SettingsViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    private val manager = container.settingsManager
    var uiState by mutableStateOf(manager.state.value)
        private set

    init {
        viewModelScope.launch { manager.state.collect { uiState = it } }
    }

    fun updateE2eeEnabled(value: Boolean) = manager.setE2eeEnabled(value)
    fun updateLanguage(value: AppLanguage) = manager.setLanguage(value)
    fun updateDisplayMode(value: AppDisplayMode) = manager.setDisplayMode(value)
    fun updateDynamicColorsEnabled(value: Boolean) = manager.setDynamicColorsEnabled(value)
    fun updateTextSize(value: AppTextSize) = manager.setTextSize(value)
    fun updateBlogNotificationsEnabled(value: Boolean) = manager.setBlogNotificationsEnabled(value)
    fun refreshMyDevices() = manager.refreshDevices()
    fun approveMyDevice(deviceId: String) = manager.approveDevice(deviceId)
    fun removeMyDevice(deviceId: String) = manager.removeDevice(deviceId)
    fun checkForUpdates() = manager.checkStable()
    fun checkForPrerelease() = manager.checkPrerelease()
    fun runPushHealthCheck() = manager.runPushHealthCheck()
    suspend fun downloadLatestAppRelease(context: Context): DecryptedAttachment =
        manager.download(context, AppReleaseChannel.STABLE)
    suspend fun downloadLatestPrerelease(context: Context): DecryptedAttachment =
        manager.download(context, AppReleaseChannel.PRERELEASE)
    fun clearDownloadedUpdate() = manager.clearDownloadedPackages()
    fun clearDownloadProgress() = manager.clearDownloadedPackages()
    fun reportError(message: String) = manager.reportError(message)
}

class ManagementViewModel internal constructor(
    private val container: AppContainer,
) : ViewModel() {
    private val manager = container.managementManager
    var uiState by mutableStateOf(manager.state.value)
        private set

    init {
        viewModelScope.launch { manager.state.collect { uiState = it } }
    }

    fun openConversation(conversationId: Long) = manager.selectConversation(conversationId)
    fun refreshCurrentConversationManage() = manager.refreshManage()
    fun changeUsername(username: String) = manager.changeUsername(username)
    fun uploadAvatar(fileName: String, mime: String, bytes: ByteArray) = manager.uploadAvatar(fileName, mime, bytes)
    fun changePassword(password: String) = manager.changePassword(password)
    fun requestAccountDeletion() = manager.requestAccountDeletion()
    fun logout(clearSavedLogin: Boolean = false) = manager.logout(clearSavedLogin)
    fun requestContact(userCode: String) = manager.requestContact(userCode)
    fun deleteCurrentDirectConversation() = manager.deleteDirectConversation()
    fun clearCurrentConversationHistory() = manager.clearConversationHistory()
    fun changeCurrentGroupTitle(title: String) = manager.changeGroupTitle(title)
    fun setCurrentGroupExpiration(ttlMs: Long) = manager.setGroupExpiration(ttlMs)
    fun uploadCurrentGroupAvatar(fileName: String, mime: String, bytes: ByteArray) =
        manager.uploadGroupAvatar(fileName, mime, bytes)
    fun addCurrentGroupMember(userCode: String) = manager.addGroupMember(userCode)
    fun removeCurrentGroupMember(userCode: String) = manager.removeGroupMember(userCode)
    fun transferCurrentGroupOwner(userCode: String) = manager.transferGroupOwner(userCode)
    fun requestCurrentGroupAdmin(userCode: String) = manager.requestGroupAdmin(userCode)
    fun removeCurrentGroupAdmin(userCode: String) = manager.removeGroupAdmin(userCode)
    fun reviewGroupAdminRequest(requestId: Long, approve: Boolean) = manager.reviewGroupAdminRequest(requestId, approve)
    fun reviewGroupJoinRequest(requestId: Long, approve: Boolean) = manager.reviewGroupJoinRequest(requestId, approve)
    fun leaveCurrentGroupConversation() = manager.leaveGroup()
    fun deleteCurrentGroupConversation() = manager.deleteGroup()
    fun clearRelationshipMessage() = manager.clearMessage()
}

internal fun <T : ViewModel> featureViewModelFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
