package com.example.chat

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class AuthSessionPhase {
    SIGNED_OUT,
    AUTHENTICATING,
    PENDING_DEVICE,
    AUTHENTICATED,
    REGISTRATION_PENDING,
}

internal data class AuthSessionState(
    val phase: AuthSessionPhase = AuthSessionPhase.SIGNED_OUT,
    val me: ChatUser? = null,
    val pendingDeviceId: String = "",
    val registrationMessage: String? = null,
    val registrationUserCode: String = "",
    val error: String? = null,
    val credentialVersion: Long = 0L,
) {
    val isLoading: Boolean
        get() = phase == AuthSessionPhase.AUTHENTICATING
    val isLoggedIn: Boolean
        get() = phase == AuthSessionPhase.AUTHENTICATED
}

/** Owns credentials, refresh, registration and pending-device authentication. */
internal class AuthSessionManager(
    private val application: Application,
    private val preferences: ChatPreferences,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val securityLifecycle: () -> SecurityLifecycleManager,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(
        AuthSessionState(
            registrationUserCode = preferences.approvedRegistrationUserCode
                .ifBlank { preferences.savedLoginUserCode },
        )
    )
    private var initialized = false
    private var deviceApprovalJob: Job? = null
    private var registrationJob: Job? = null
    private var refreshJob: Job? = null
    private var urgentRefreshJob: Job? = null
    private val requestRefreshMutex = Mutex()

    val state: StateFlow<AuthSessionState> = mutableState.asStateFlow()

    fun initialize() {
        if (initialized) return
        initialized = true
        if (preferences.securityResetRequired) {
            preferences.securityResetRequired = false
            mutableState.value = mutableState.value.copy(
                error = "Secure storage was reset. Please log in again.",
            )
            return
        }
        if (!preferences.hasStoredAuthenticationMaterial()) return
        mutableState.value = mutableState.value.copy(phase = AuthSessionPhase.AUTHENTICATING)
        scope.launch {
            preferences.initializeSecureStorage()
            if (preferences.securityResetRequired) {
                preferences.securityResetRequired = false
                mutableState.value = mutableState.value.copy(
                    phase = AuthSessionPhase.SIGNED_OUT,
                    error = "Secure storage was reset. Please log in again.",
                )
                return@launch
            }
            when {
                preferences.token.isNotBlank() -> restoreAccessSession()
                preferences.refreshToken.isNotBlank() -> restoreRefreshSession()
                preferences.registrationRequestToken.isNotBlank() -> startRegistrationPolling()
                else -> mutableState.value = mutableState.value.copy(phase = AuthSessionPhase.SIGNED_OUT)
            }
        }
    }

    fun login(userCode: String, password: String) {
        val normalized = userCode.trim()
        if (!normalized.matches(Regex("^\\d{8}$"))) {
            mutableState.value = mutableState.value.copy(error = "invalid_user_id")
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(
                phase = AuthSessionPhase.AUTHENTICATING,
                error = null,
                registrationMessage = null,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    authRepository.login(
                        preferences.serverUrl,
                        normalized,
                        password,
                        preferences.deviceId,
                    )
                }
            }.onSuccess { result ->
                applyLoginResult(result, resetNotificationCursor = true)
                if (result.deviceStatus == "trusted") {
                    completeTrustedLogin(result.toUser())
                } else {
                    registerPendingIdentity(result)
                    showPendingDevice(result.toUser(), preferences.deviceId)
                }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    phase = AuthSessionPhase.SIGNED_OUT,
                    error = error.message ?: "Login failed",
                )
            }
        }
    }

    fun requestRegistration(username: String, password: String, confirmPassword: String) {
        val strings = stringsFor(AppLanguage.fromStored(preferences.language))
        val normalized = username.trim()
        val validationError = when {
            normalized.isBlank() -> strings.usernameEmpty
            password.length < 8 -> strings.passwordTooShort
            password != confirmPassword -> strings.passwordsDoNotMatch
            else -> null
        }
        if (validationError != null) {
            mutableState.value = mutableState.value.copy(error = validationError, registrationMessage = null)
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(
                phase = AuthSessionPhase.AUTHENTICATING,
                error = null,
                registrationMessage = null,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    authRepository.requestRegistration(preferences.serverUrl, normalized, password)
                }
            }.onSuccess { request ->
                preferences.registrationRequestToken = request.requestToken
                mutableState.value = mutableState.value.copy(
                    phase = AuthSessionPhase.REGISTRATION_PENDING,
                    registrationMessage = strings.registrationPending,
                    registrationUserCode = "",
                    error = null,
                )
                startRegistrationPolling()
            }.onFailure { error ->
                val message = when (error.message) {
                    "invalid_registration_data" -> strings.invalidUsername
                    "username_taken" -> strings.usernameTaken
                    "request_already_pending" -> strings.requestAlreadyPending
                    else -> strings.unableToRegister
                }
                mutableState.value = mutableState.value.copy(
                    phase = AuthSessionPhase.SIGNED_OUT,
                    error = message,
                )
            }
        }
    }

    fun checkDeviceApprovalNow() {
        if (mutableState.value.phase != AuthSessionPhase.PENDING_DEVICE) return
        deviceApprovalJob?.cancel()
        scope.launch {
            when (refreshSession()) {
                SessionRefreshOutcome.TRUSTED -> Unit
                SessionRefreshOutcome.PENDING -> startDeviceApprovalPolling()
                SessionRefreshOutcome.TRANSIENT_FAILURE -> {
                    mutableState.value = mutableState.value.copy(error = "Unable to check device approval")
                    startDeviceApprovalPolling()
                }
                SessionRefreshOutcome.EXPIRED -> expireSession("Session expired")
            }
        }
    }

    fun cancelPendingDeviceLogin() {
        scope.launch {
            val ownerId = sessionSnapshot().ownerId
            val token = preferences.token
            if (token.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) { authRepository.logout(preferences.serverUrl, token) }
                }
            }
            clearJobs()
            securityLifecycle().wipeForLogout(ownerId)
            mutableState.value = AuthSessionState(
                registrationUserCode = preferences.savedLoginUserCode,
            )
        }
    }

    fun logout(remote: Boolean = true) {
        scope.launch {
            val ownerId = sessionSnapshot().ownerId
            val token = preferences.token
            if (remote && token.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) { authRepository.logout(preferences.serverUrl, token) }
                }
            }
            clearJobs()
            securityLifecycle().wipeForLogout(ownerId)
            mutableState.value = AuthSessionState(
                registrationUserCode = preferences.savedLoginUserCode,
            )
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun replaceCurrentUser(user: ChatUser) {
        if (mutableState.value.phase != AuthSessionPhase.AUTHENTICATED) return
        preferences.userCode = user.userCode
        preferences.username = user.username
        mutableState.value = mutableState.value.copy(me = user)
    }

    fun sessionSnapshot(): SyncSession = SyncSession(
        ownerId = preferences.userCode.ifBlank { preferences.username }.trim().lowercase(),
        serverUrl = preferences.serverUrl,
        accessToken = preferences.token,
    )

    suspend fun prepareForegroundSession(): SessionRefreshOutcome {
        if (mutableState.value.phase != AuthSessionPhase.AUTHENTICATED) {
            return SessionRefreshOutcome.EXPIRED
        }
        if (!accessTokenNeedsRefresh(preferences.token)) {
            return SessionRefreshOutcome.TRUSTED
        }
        FamilyChatDiagnostics.event("foreground_session_refresh_started")
        return refreshSession(scheduleNextRefresh = false).also { outcome ->
            FamilyChatDiagnostics.event("foreground_session_refresh_finished", "outcome" to outcome.name)
            if (outcome == SessionRefreshOutcome.EXPIRED) {
                expireSession("Session expired")
            }
        }
    }

    fun requestAccessTokenRefresh(reason: String) {
        if (mutableState.value.phase != AuthSessionPhase.AUTHENTICATED || urgentRefreshJob?.isActive == true) return
        urgentRefreshJob = scope.launch {
            FamilyChatDiagnostics.event("urgent_session_refresh_started", "reason" to reason)
            when (val outcome = refreshSession(scheduleNextRefresh = false)) {
                SessionRefreshOutcome.EXPIRED -> expireSession("Session expired")
                else -> FamilyChatDiagnostics.event(
                    "urgent_session_refresh_finished",
                    "reason" to reason,
                    "outcome" to outcome.name,
                )
            }
        }
    }

    suspend fun refreshAccessTokenAfterUnauthorized(failedAccessToken: String, reason: String): Boolean =
        requestRefreshMutex.withLock {
            if (mutableState.value.phase != AuthSessionPhase.AUTHENTICATED) return@withLock false
            if (preferences.token.isNotBlank() && preferences.token != failedAccessToken) return@withLock true
            FamilyChatDiagnostics.event("request_session_refresh_started", "reason" to reason)
            when (val outcome = refreshSession(scheduleNextRefresh = false)) {
                SessionRefreshOutcome.TRUSTED -> true
                SessionRefreshOutcome.EXPIRED -> {
                    expireSession("Session expired")
                    false
                }
                SessionRefreshOutcome.PENDING,
                SessionRefreshOutcome.TRANSIENT_FAILURE -> {
                    FamilyChatDiagnostics.event(
                        "request_session_refresh_finished",
                        "reason" to reason,
                        "outcome" to outcome.name,
                    )
                    false
                }
            }
        }

    private suspend fun restoreAccessSession() {
        mutableState.value = mutableState.value.copy(phase = AuthSessionPhase.AUTHENTICATING, error = null)
        runCatching {
            withContext(Dispatchers.IO) {
                authRepository.sessionStatus(preferences.serverUrl, preferences.token)
            }
        }.onSuccess { status ->
            if (status.deviceStatus == "trusted") {
                completeTrustedLogin(
                    ChatUser(
                        userCode = status.userCode,
                        username = status.username,
                        color = "#128c7e",
                        isAdmin = false,
                    )
                )
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            authRepository.me(preferences.serverUrl, preferences.token)
                        }
                    }.onSuccess(::replaceCurrentUser)
                }
            } else {
                showPendingDevice(
                    ChatUser(
                        userCode = status.userCode,
                        username = status.username,
                        color = "#128c7e",
                        isAdmin = false,
                    ),
                    status.deviceId,
                )
            }
        }.onFailure { error ->
            if (error.requiresAccessTokenRefresh()) {
                when (refreshSession()) {
                    SessionRefreshOutcome.TRUSTED,
                    SessionRefreshOutcome.PENDING -> Unit
                    SessionRefreshOutcome.TRANSIENT_FAILURE -> restoreCachedSession(error)
                    SessionRefreshOutcome.EXPIRED -> expireSession(error.message ?: "Session expired")
                }
            } else {
                restoreCachedSession(error)
            }
        }
    }

    private suspend fun restoreRefreshSession() {
        when (refreshSession()) {
            SessionRefreshOutcome.TRUSTED,
            SessionRefreshOutcome.PENDING -> Unit
            SessionRefreshOutcome.TRANSIENT_FAILURE -> restoreCachedSession()
            SessionRefreshOutcome.EXPIRED -> expireSession("Session expired")
        }
    }

    private suspend fun refreshSession(
        scheduleNextRefresh: Boolean = true,
    ): SessionRefreshOutcome {
        if (preferences.refreshToken.isBlank()) return SessionRefreshOutcome.EXPIRED
        return runCatching {
            withContext(Dispatchers.IO) {
                SessionRefreshCoordinator.refresh(application, preferences.serverUrl)
            }
        }.map { result ->
            applyLoginResult(result, resetNotificationCursor = false)
            if (result.deviceStatus == "trusted") {
                completeTrustedLogin(result.toUser(), scheduleNextRefresh)
                SessionRefreshOutcome.TRUSTED
            } else {
                registerPendingIdentity(result)
                showPendingDevice(result.toUser(), preferences.deviceId)
                SessionRefreshOutcome.PENDING
            }
        }.getOrElse { error ->
            val outcome = if (error.isDefinitiveSessionFailure()) {
                SessionRefreshOutcome.EXPIRED
            } else {
                SessionRefreshOutcome.TRANSIENT_FAILURE
            }
            FamilyChatDiagnostics.event(
                "session_refresh_failed",
                "outcome" to outcome.name,
                "error" to error.javaClass.simpleName,
                "detail" to error.message.orEmpty(),
            )
            outcome
        }
    }

    private fun completeTrustedLogin(user: ChatUser, scheduleNextRefresh: Boolean = true) {
        preferences.userCode = user.userCode
        preferences.username = user.username
        mutableState.value = mutableState.value.copy(
            phase = AuthSessionPhase.AUTHENTICATED,
            me = user,
            pendingDeviceId = "",
            error = null,
            credentialVersion = mutableState.value.credentialVersion + 1L,
        )
        deviceApprovalJob?.cancel()
        if (scheduleNextRefresh) scheduleRefresh()
    }

    private fun showPendingDevice(user: ChatUser, deviceId: String) {
        mutableState.value = mutableState.value.copy(
            phase = AuthSessionPhase.PENDING_DEVICE,
            me = user,
            pendingDeviceId = deviceId,
            error = null,
        )
        startDeviceApprovalPolling()
    }

    private fun registerPendingIdentity(result: LoginResult) {
        scope.launch {
            runCatching {
                val identity = withContext(Dispatchers.IO) {
                    DeviceIdentityManager.localIdentity(preferences, result.userCode)
                }
                withContext(Dispatchers.IO) {
                    deviceRepository.registerDeviceIdentity(
                        preferences.serverUrl,
                        preferences.token,
                        identity,
                    )
                    deviceRepository.registerDirectPreKey(
                        preferences.serverUrl,
                        preferences.token,
                        DirectMessageCrypto.ensureLocalPreKey(preferences),
                    )
                }
            }
        }
    }

    private fun startDeviceApprovalPolling() {
        if (deviceApprovalJob?.isActive == true || preferences.refreshToken.isBlank()) return
        deviceApprovalJob = scope.launch {
            while (
                preferences.refreshToken.isNotBlank() &&
                mutableState.value.phase == AuthSessionPhase.PENDING_DEVICE
            ) {
                delay(5_000L)
                when (refreshSession()) {
                    SessionRefreshOutcome.TRUSTED -> return@launch
                    SessionRefreshOutcome.EXPIRED -> {
                        expireSession("Session expired")
                        return@launch
                    }
                    SessionRefreshOutcome.PENDING,
                    SessionRefreshOutcome.TRANSIENT_FAILURE -> Unit
                }
            }
        }
    }

    private fun startRegistrationPolling() {
        if (registrationJob?.isActive == true || preferences.registrationRequestToken.isBlank()) return
        registrationJob = scope.launch {
            while (preferences.registrationRequestToken.isNotBlank()) {
                val status = runCatching {
                    withContext(Dispatchers.IO) {
                        authRepository.registrationStatus(
                            preferences.serverUrl,
                            preferences.registrationRequestToken,
                        )
                    }
                }.getOrNull()
                when (status?.status) {
                    "approved" -> {
                        if (status.userCode.isNotBlank()) {
                            preferences.approvedRegistrationUserCode = status.userCode
                            preferences.savedLoginUserCode = status.userCode
                            preferences.registrationRequestToken = ""
                            mutableState.value = AuthSessionState(
                                registrationMessage = if (AppLanguage.fromStored(preferences.language) == AppLanguage.ZH) {
                                    "注册已批准。你的用户 ID：${status.userCode}"
                                } else {
                                    "Registration approved. Your user ID: ${status.userCode}"
                                },
                                registrationUserCode = status.userCode,
                            )
                            return@launch
                        }
                    }
                    "rejected" -> {
                        preferences.registrationRequestToken = ""
                        mutableState.value = AuthSessionState(
                            error = status.reviewNote.ifBlank { "Registration rejected" },
                        )
                        return@launch
                    }
                }
                delay(5_000L)
            }
        }
    }

    private fun scheduleRefresh(initialDelayMs: Long = 10L * 60L * 1_000L) {
        refreshJob?.cancel()
        if (preferences.refreshToken.isBlank()) return
        refreshJob = scope.launch {
            var retryDelayMs = initialDelayMs
            var transientBackoffMs = 30_000L
            while (mutableState.value.phase == AuthSessionPhase.AUTHENTICATED) {
                delay(retryDelayMs)
                when (refreshSession(scheduleNextRefresh = false)) {
                    SessionRefreshOutcome.TRUSTED -> {
                        retryDelayMs = 10L * 60L * 1_000L
                        transientBackoffMs = 30_000L
                    }
                    SessionRefreshOutcome.TRANSIENT_FAILURE -> {
                        retryDelayMs = transientBackoffMs
                        transientBackoffMs = (transientBackoffMs * 2L).coerceAtMost(5L * 60L * 1_000L)
                    }
                    SessionRefreshOutcome.PENDING -> return@launch
                    SessionRefreshOutcome.EXPIRED -> {
                        expireSession("Session expired")
                        return@launch
                    }
                }
            }
        }
    }

    private fun restoreCachedSession(error: Throwable? = null) {
        val userCode = preferences.userCode
        val username = preferences.username
        if (userCode.isBlank() || username.isBlank() || preferences.refreshToken.isBlank()) {
            mutableState.value = mutableState.value.copy(
                phase = AuthSessionPhase.SIGNED_OUT,
                error = error?.message ?: "Unable to restore session",
            )
            return
        }
        FamilyChatDiagnostics.event(
            "session_restored_offline",
            "error" to error?.javaClass?.simpleName.orEmpty(),
            "detail" to error?.message.orEmpty(),
        )
        completeTrustedLogin(
            ChatUser(
                userCode = userCode,
                username = username,
                color = "#128c7e",
                isAdmin = false,
            ),
            scheduleNextRefresh = false,
        )
        scheduleRefresh(initialDelayMs = 30_000L)
    }

    private fun expireSession(message: String) {
        preferences.clearSession()
        mutableState.value = AuthSessionState(
            registrationUserCode = preferences.savedLoginUserCode,
            error = message,
        )
    }

    private fun applyLoginResult(result: LoginResult, resetNotificationCursor: Boolean) {
        preferences.token = result.token
        if (result.refreshToken.isNotBlank()) preferences.refreshToken = result.refreshToken
        preferences.userCode = result.userCode
        preferences.username = result.username
        preferences.saveLogin(result.userCode)
        preferences.approvedRegistrationUserCode = ""
        if (resetNotificationCursor) preferences.lastNotifiedMessageId = 0L
    }

    private fun clearJobs() {
        deviceApprovalJob?.cancel()
        registrationJob?.cancel()
        refreshJob?.cancel()
        urgentRefreshJob?.cancel()
        deviceApprovalJob = null
        registrationJob = null
        refreshJob = null
        urgentRefreshJob = null
    }

    private fun LoginResult.toUser() = ChatUser(
        userCode = userCode,
        username = username,
        color = color,
        avatarUrl = avatarUrl,
        isAdmin = isAdmin,
    )
}
