package com.example.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Owns appearance, device security, and release-management state. */
internal class SettingsManager(
    private val preferences: ChatPreferences,
    private val preferenceStore: UserPreferencesStore,
    private val auth: AuthSessionManager,
    private val devices: DeviceRepository,
    private val updates: UpdateCoordinator,
    private val push: PushCoordinator,
    private val realtime: RealtimeGateway,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            realtime.events.collect { raw ->
                val type = runCatching { JSONObject(raw).optString("type") }.getOrDefault("")
                if (type == "devices_changed" || type == "direct_peer_keys_changed") refreshDevices()
            }
        }
        scope.launch {
            preferenceStore.state.collect { preferenceState ->
                mutableState.update {
                    it.copy(
                        language = preferenceState.language,
                        displayMode = preferenceState.displayMode,
                        dynamicColorsEnabled = preferenceState.dynamicColorsEnabled,
                        textSize = preferenceState.textSize,
                        e2eeEnabled = preferenceState.e2eeEnabled,
                        blogNotificationsEnabled = preferenceState.blogNotificationsEnabled,
                    )
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
                        refreshDevices()
                    }
                    AuthSessionPhase.SIGNED_OUT -> mutableState.value = initialState()
                    else -> Unit
                }
            }
        }
    }

    fun setLanguage(value: AppLanguage) = preferenceStore.setLanguage(value)
    fun setDisplayMode(value: AppDisplayMode) = preferenceStore.setDisplayMode(value)
    fun setTextSize(value: AppTextSize) = preferenceStore.setTextSize(value)
    fun setDynamicColorsEnabled(value: Boolean) = preferenceStore.setDynamicColorsEnabled(value)
    fun setE2eeEnabled(value: Boolean) = preferenceStore.setE2eeEnabled(value)
    fun setBlogNotificationsEnabled(value: Boolean) {
        preferenceStore.setBlogNotificationsEnabled(value)
        push.registerCurrentDevice()
    }

    fun refreshDevices() {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    devices.deviceIdentities(session.serverUrl, session.accessToken) to
                        devices.myDevices(session.serverUrl, session.accessToken)
                }
            }.onSuccess { (identities, ownDevices) ->
                mutableState.update { it.copy(deviceIdentities = identities, myDevices = ownDevices, error = null) }
            }.onFailure(::reportError)
        }
    }

    fun approveDevice(deviceId: String) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || deviceId.isBlank() || deviceId == preferences.deviceId) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    devices.approveDevice(session.serverUrl, session.accessToken, deviceId)
                }
            }.onSuccess {
                mutableState.update { state ->
                    state.copy(relationshipMessage = if (state.language == AppLanguage.ZH) "设备已批准" else "Device approved")
                }
                refreshDevices()
            }.onFailure(::reportError)
        }
    }

    fun removeDevice(deviceId: String) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || deviceId.isBlank() || deviceId == preferences.deviceId) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    devices.deleteDevice(session.serverUrl, session.accessToken, deviceId, preferences.deviceId)
                }
            }.onSuccess {
                mutableState.update { state ->
                    state.copy(relationshipMessage = if (state.language == AppLanguage.ZH) "设备已移除" else "Device removed")
                }
                refreshDevices()
            }.onFailure(::reportError)
        }
    }

    fun checkStable() = check(AppReleaseChannel.STABLE)
    fun checkPrerelease() = check(AppReleaseChannel.PRERELEASE)

    private fun check(channel: AppReleaseChannel) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            val strings = stringsFor(mutableState.value.language)
            mutableState.update { state ->
                when (channel) {
                    AppReleaseChannel.STABLE -> state.copy(isCheckingUpdate = true, updateStatus = strings.checkingUpdate, error = null)
                    AppReleaseChannel.PRERELEASE -> state.copy(isCheckingPrerelease = true, prereleaseStatus = strings.checkingUpdate, error = null)
                }
            }
            runCatching { updates.check(session.serverUrl, session.accessToken, channel, strings) }
                .onSuccess { result ->
                    mutableState.update { state ->
                        when (channel) {
                            AppReleaseChannel.STABLE -> state.copy(
                                latestAppRelease = result.release,
                                downloadedUpdate = result.downloaded,
                                isCheckingUpdate = false,
                                updateStatus = result.status,
                            )
                            AppReleaseChannel.PRERELEASE -> state.copy(
                                latestPrerelease = result.release,
                                downloadedPrerelease = result.downloaded,
                                isCheckingPrerelease = false,
                                prereleaseStatus = result.status,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        when (channel) {
                            AppReleaseChannel.STABLE -> state.copy(isCheckingUpdate = false, error = error.message ?: "Unable to check update")
                            AppReleaseChannel.PRERELEASE -> state.copy(isCheckingPrerelease = false, error = error.message ?: "Unable to check beta")
                        }
                    }
                }
        }
    }

    suspend fun download(context: Context, channel: AppReleaseChannel): DecryptedAttachment {
        val state = mutableState.value
        val release = (if (channel == AppReleaseChannel.STABLE) state.latestAppRelease else state.latestPrerelease)
            ?: throw IllegalStateException(stringsFor(state.language).noReleaseUploaded)
        val attachment = updates.download(
            context = context,
            serverUrl = preferences.serverUrl,
            release = release,
            channel = channel,
            strings = stringsFor(state.language),
        ) { done, total ->
            mutableState.update {
                it.copy(
                    downloadProgress = TransferProgress(
                        label = release.fileName,
                        progress = if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                        bytesDone = done,
                        totalBytes = total,
                    )
                )
            }
        }
        mutableState.update {
            if (channel == AppReleaseChannel.STABLE) {
                it.copy(downloadedUpdate = attachment, downloadProgress = null, updateStatus = stringsFor(it.language).updateDownloaded)
            } else {
                it.copy(downloadedPrerelease = attachment, downloadProgress = null, prereleaseStatus = stringsFor(it.language).updateDownloaded)
            }
        }
        return attachment
    }

    fun clearDownloadedPackages() {
        mutableState.update { it.copy(downloadedUpdate = null, downloadedPrerelease = null, downloadProgress = null) }
    }

    fun runPushHealthCheck() {
        if (mutableState.value.isRunningPushHealthCheck) return
        scope.launch {
            val zh = mutableState.value.language == AppLanguage.ZH
            mutableState.update {
                it.copy(
                    isRunningPushHealthCheck = true,
                    pushHealthStatus = if (zh) "正在测试服务器和推送…" else "Testing server and push delivery…",
                    error = null,
                )
            }
            runCatching { push.runHealthCheck() }
                .onSuccess { result ->
                    val status = when {
                        !result.serverReachable -> if (result.reason == "missing_session" || result.reason == "unauthorized") {
                            if (zh) "登录状态已失效，请重新登录后测试。" else "Your session has expired. Sign in and test again."
                        } else {
                            if (zh) "无法连接服务器，请检查网络后重试。" else "Could not reach the server. Check your connection and try again."
                        }
                        !result.requestAccepted -> when (result.reason) {
                            "no_fcm_tokens" -> if (zh) "服务器连接正常，但当前设备的推送令牌尚未生效，请稍后重试。" else "Server connection is working, but this device's push token is not active yet. Try again shortly."
                            "missing_fcm_config" -> if (zh) "服务器连接正常，但 FCM 服务尚未配置。" else "Server connection is working, but FCM is not configured."
                            "oauth_failed" -> if (zh) "服务器连接正常，但 FCM 授权暂时失败。" else "Server connection is working, but FCM authorization failed."
                            "all_tokens_failed" -> if (zh) "服务器连接正常，但 FCM 未能向已登记设备提交测试推送。" else "Server connection is working, but FCM could not dispatch to the registered devices."
                            "unauthorized" -> if (zh) "服务器连接正常，但登录状态已失效。" else "Server connection is working, but the session has expired."
                            "session_refresh_failed" -> if (zh) "服务器连接正常，但刷新登录状态失败，请稍后重试。" else "Server connection is working, but the session could not be refreshed. Try again later."
                            else -> if (zh) "服务器连接正常，但本次 FCM 测试未能提交。" else "Server connection is working, but this FCM test could not be dispatched."
                        }
                        result.pushReceived -> if (zh) {
                            "测试成功：服务器 ${result.requestRoundTripMs} ms，推送 ${result.pushRoundTripMs} ms。"
                        } else {
                            "Test passed: server ${result.requestRoundTripMs} ms, push ${result.pushRoundTripMs} ms."
                        }
                        else -> if (zh) {
                            "服务器已发送，但 30 秒内未收到推送。请检查系统通知、后台和电池设置。"
                        } else {
                            "Server dispatched the push, but it was not received within 30 seconds. Check notification, background, and battery settings."
                        }
                    }
                    mutableState.update { it.copy(isRunningPushHealthCheck = false, pushHealthStatus = status) }
                }
                .onFailure { error ->
                    // The result of the self-test belongs to the self-test row,
                    // not to a screen-level banner. Reporting the failure through
                    // `error` put it at the bottom of About while the row that
                    // started it went blank, so the outcome appeared detached
                    // from the thing the user had just tapped.
                    FamilyChatDiagnostics.event(
                        "push_health_check_failed",
                        "type" to error.javaClass.name,
                        "detail" to error.message.orEmpty(),
                    )
                    mutableState.update {
                        it.copy(
                            isRunningPushHealthCheck = false,
                            pushHealthStatus = error.message ?: "request_failed",
                        )
                    }
                }
        }
    }

    fun clearMessage() = mutableState.update { it.copy(relationshipMessage = null) }
    fun reportError(message: String) = mutableState.update { it.copy(error = message, downloadProgress = null) }
    private fun reportError(error: Throwable) = reportError(error.message ?: error.javaClass.simpleName)

    private fun initialState(): SettingsUiState {
        val appearance = preferenceStore.state.value
        return SettingsUiState(
            serverUrl = preferences.serverUrl,
            e2eeEnabled = appearance.e2eeEnabled,
            language = appearance.language,
            displayMode = appearance.displayMode,
            dynamicColorsEnabled = appearance.dynamicColorsEnabled,
            textSize = appearance.textSize,
            blogNotificationsEnabled = appearance.blogNotificationsEnabled,
        )
    }
}
