package com.example.chat

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Application-scoped owner of WebRTC, signaling and incoming-call restoration. */
internal class CallCoordinator(
    private val application: Application,
    private val preferences: ChatPreferences,
    private val auth: AuthSessionManager,
    private val conversations: ConversationManager,
    private val repository: CallRepository,
    private val realtime: RealtimeGateway,
    private val connectionStore: RealtimeConnectionStore,
    private val userPreferences: UserPreferencesStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(
        CallUiState(language = AppLanguage.fromStored(preferences.language))
    )
    private var pendingAction: String? = null
    private var terminalDismissJob: Job? = null

    val state: StateFlow<CallUiState> = mutableState.asStateFlow()

    private var latestIceServers: List<CallIceServerConfig> = emptyList()
    private val voiceManagerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VoiceCallManager(application.applicationContext, object : VoiceCallManager.Callbacks {
            override fun onSignal(type: String, payload: JSONObject) {
                realtime.send(payload.put("type", type))
            }

            override fun onStateChanged(state: VoiceCallUiState) {
                applyState(state)
            }

            override fun onError(message: String) {
                mutableState.update { it.copy(error = message) }
            }
        }).also { manager ->
            latestIceServers.takeIf(List<CallIceServerConfig>::isNotEmpty)?.let(manager::updateIceServers)
        }
    }
    private val voiceManager by voiceManagerDelegate

    private val runtimeController = object : VoiceCallRuntime.Controller {
        override fun start(conversationId: Long): Unit = this@CallCoordinator.start(conversationId)
        override fun accept(): Unit = this@CallCoordinator.accept()
        override fun decline(): Unit = this@CallCoordinator.decline()
        override fun end(): Unit = this@CallCoordinator.end()
        override fun toggleMute() = voiceManager.toggleMute()
        override fun toggleSpeaker() = voiceManager.toggleSpeaker()
        override fun selectAudioRoute(route: VoiceAudioRoute) = voiceManager.selectAudioRoute(route)
        override fun syncAudioRouteFromSystem(route: VoiceAudioRoute) = voiceManager.syncSystemAudioRoute(route)
        override fun dismissTerminal() = voiceManager.dismissTerminalState()
    }

    init {
        VoiceCallRuntime.attach(runtimeController)
        restoreRuntimeState()
        scope.launch {
            userPreferences.state.collect { preferencesState ->
                mutableState.update { it.copy(language = preferencesState.language) }
            }
        }
        scope.launch {
            auth.state.collect { authState ->
                mutableState.update {
                    it.copy(language = AppLanguage.fromStored(preferences.language))
                }
                when (authState.phase) {
                    AuthSessionPhase.AUTHENTICATED -> {
                        refreshConfiguration()
                        restorePendingInvite()
                    }
                    AuthSessionPhase.SIGNED_OUT -> {
                        pendingAction = null
                        preferences.setPendingIncomingCall(null)
                        if (voiceManagerDelegate.isInitialized()) voiceManager.release()
                        applyState(VoiceCallUiState())
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            connectionStore.status.collect { status ->
                if (status == ConnectionStatus.CONNECTED) {
                    if (voiceManagerDelegate.isInitialized()) voiceManager.onSignalingReconnected()
                    refreshConfiguration()
                    performPendingAction()
                } else if (status == ConnectionStatus.RECONNECTING || status == ConnectionStatus.OFFLINE) {
                    if (voiceManagerDelegate.isInitialized()) voiceManager.onSignalingDisconnected()
                }
            }
        }
        scope.launch {
            realtime.events.collect(::handleSignal)
        }
    }

    fun start(conversationId: Long) {
        val conversation = conversations.state.value.conversations.firstOrNull { it.id == conversationId }
        if (conversation?.kind != "direct") {
            mutableState.update { it.copy(error = stringsFor(it.language).directCallOnly) }
            return
        }
        val peer = conversations.state.value.users.firstOrNull { user ->
            (conversation.directUserCode.isNotBlank() && user.userCode == conversation.directUserCode) ||
                user.username == conversation.directUsername
        }
        if (peer == null) {
            mutableState.update { it.copy(error = stringsFor(it.language).callUnavailable) }
            return
        }
        if (connectionStore.status.value != ConnectionStatus.CONNECTED) {
            mutableState.update { it.copy(error = stringsFor(it.language).disconnected) }
            realtime.connect()
            return
        }
        voiceManager.startOutgoing(conversationId, peer.userCode, peer.username)
    }

    fun accept() {
        voiceManager.restoreIncomingInvite(mutableState.value.call.copy(statusMessage = "incoming"))
        if (connectionStore.status.value != ConnectionStatus.CONNECTED) {
            pendingAction = "accept"
            realtime.connect()
            return
        }
        voiceManager.acceptIncoming()
    }

    fun decline() {
        voiceManager.restoreIncomingInvite(mutableState.value.call.copy(statusMessage = "incoming"))
        if (connectionStore.status.value != ConnectionStatus.CONNECTED) {
            pendingAction = "reject"
            realtime.connect()
            return
        }
        voiceManager.rejectIncoming()
    }

    fun end() = voiceManager.endCall()
    fun toggleMute() = voiceManager.toggleMute()
    fun selectAudioRoute(route: VoiceAudioRoute) = voiceManager.selectAudioRoute(route)
    fun dismissTerminal() = voiceManager.dismissTerminalState()
    fun clearError() = mutableState.update { it.copy(error = null) }

    fun onAppForeground() {
        realtime.setPresence("foreground")
        realtime.connect()
        restoreRuntimeState()
        restorePendingInvite()
    }

    fun onAppBackground() {
        realtime.setPresence("background")
    }

    private fun refreshConfiguration() {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.configuration(session.serverUrl, session.accessToken)
                }
            }.onSuccess { configuration ->
                latestIceServers = configuration.iceServers
                if (voiceManagerDelegate.isInitialized()) {
                    voiceManager.updateIceServers(configuration.iceServers)
                }
            }
        }
    }

    private fun handleSignal(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "call_invite" -> {
                val conversationId = json.optLong("conversation_id", 0L)
                val peerUserCode = json.optString("user_code")
                val peerUsername = json.optString("username")
                val createdAt = json.optLong("ts", System.currentTimeMillis())
                voiceManager.receiveIncomingInvite(
                    conversationId = json.optLong("conversation_id", 0L),
                    peerUserCode = peerUserCode,
                    peerUsername = peerUsername,
                ) { accepted ->
                    if (accepted) {
                        preferences.setPendingIncomingCall(
                            PendingIncomingCallInvite(
                                conversationId = conversationId,
                                peerUserCode = peerUserCode,
                                peerUsername = peerUsername,
                                createdAt = createdAt,
                            )
                        )
                    }
                }
            }
            "call_accept" -> voiceManager.onRemoteAccepted()
            "call_reject" -> voiceManager.onRemoteRejected()
            "call_busy" -> voiceManager.onRemoteBusy()
            "call_unavailable" -> voiceManager.onRemoteUnavailable()
            "call_hangup" -> voiceManager.onRemoteHangup()
            "call_offer" -> json.optJSONObject("description")?.let(voiceManager::onRemoteOffer)
            "call_answer" -> json.optJSONObject("description")?.let(voiceManager::onRemoteAnswer)
            "call_ice" -> json.optJSONObject("candidate")?.let(voiceManager::onRemoteIceCandidate)
        }
    }

    private fun restoreRuntimeState() {
        val runtime = VoiceCallRuntime.state.value
        if (runtime.phase == VoiceCallPhase.IDLE) return
        if (runtime.phase == VoiceCallPhase.INCOMING) voiceManager.restoreIncomingInvite(runtime)
        applyState(runtime, updateRuntime = false)
    }

    private fun restorePendingInvite() {
        val pending = preferences.pendingIncomingCall() ?: return
        if (System.currentTimeMillis() - pending.createdAt > 35_000L) {
            preferences.setPendingIncomingCall(null)
            NotificationCenter.clearIncomingCallNotification(application)
            return
        }
        voiceManager.restoreIncomingInvite(
            VoiceCallUiState(
                phase = VoiceCallPhase.INCOMING,
                conversationId = pending.conversationId,
                peerUserCode = pending.peerUserCode,
                peerUsername = pending.peerUsername,
                isIncoming = true,
                statusMessage = "incoming",
            )
        )
    }

    private fun performPendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        if (mutableState.value.call.phase != VoiceCallPhase.INCOMING) return
        if (action == "accept") voiceManager.acceptIncoming() else voiceManager.rejectIncoming()
    }

    private fun applyState(state: VoiceCallUiState, updateRuntime: Boolean = true) {
        terminalDismissJob?.cancel()
        val language = AppLanguage.fromStored(preferences.language)
        val localized = state.copy(statusMessage = localizeStatus(state.statusMessage, language))
        mutableState.value = CallUiState(call = localized, language = language)
        if (localized.phase == VoiceCallPhase.INCOMING && localized.conversationId > 0L) {
            preferences.setPendingIncomingCall(
                PendingIncomingCallInvite(
                    localized.conversationId,
                    localized.peerUserCode,
                    localized.peerUsername,
                    System.currentTimeMillis(),
                )
            )
        } else {
            preferences.setPendingIncomingCall(null)
            NotificationCenter.clearIncomingCallNotification(application)
        }
        if (updateRuntime) VoiceCallRuntime.updateState(application, localized)
        if (localized.phase == VoiceCallPhase.ENDED || localized.phase == VoiceCallPhase.FAILED) {
            terminalDismissJob = scope.launch {
                delay(6_000L)
                voiceManager.dismissTerminalState()
            }
        }
    }

    private fun localizeStatus(key: String, language: AppLanguage): String {
        val strings = stringsFor(language)
        return when (key) {
            "calling" -> strings.calling
            "incoming" -> strings.incomingCall
            "connecting" -> strings.callConnecting
            "reconnecting" -> strings.callReconnecting
            "active" -> strings.callActive
            "ended" -> strings.callEnded
            "rejected" -> strings.callRejected
            "busy" -> strings.callBusy
            "unavailable" -> strings.callUnavailable
            "connection_lost" -> strings.callConnectionLost
            "failed" -> strings.callFailed
            else -> key
        }
    }
}
