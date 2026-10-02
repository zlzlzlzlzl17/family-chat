package com.example.chat

import androidx.compose.runtime.Immutable

/** State needed by the activity shell only. */
@Immutable
data class ShellUiState(
    val language: AppLanguage = AppLanguage.systemDefault(),
    val isLoggedIn: Boolean = false,
    val isRestoringSession: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val latestAppRelease: AppReleaseInfo? = null,
    val relationshipMessage: String? = null,
    val voiceCall: VoiceCallUiState = VoiceCallUiState(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    val error: String? = null,
)

@Immutable
data class AuthUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val displayMode: AppDisplayMode = AppDisplayMode.SYSTEM,
    val dynamicColorsEnabled: Boolean = true,
    val textSize: AppTextSize = AppTextSize.MEDIUM,
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val me: ChatUser? = null,
    val registrationMessage: String? = null,
    val registrationUserCode: String = "",
    val deviceApprovalPending: Boolean = false,
    val pendingDeviceId: String = "",
    val error: String? = null,
)

interface ConnectionAwareUiState {
    val me: ChatUser?
    val isConnected: Boolean
    val connectionStatus: ConnectionStatus
    val isRefreshing: Boolean
}

@Immutable
data class ConversationUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    override val isConnected: Boolean = false,
    override val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    override val me: ChatUser? = null,
    val users: List<ChatUser> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val contactRequests: List<ContactRequestInfo> = emptyList(),
    val groupJoinRequests: List<GroupJoinRequestInfo> = emptyList(),
    val userLookupResult: UserLookupResult? = null,
    val groupLookupResult: GroupLookupResult? = null,
    val currentConversationId: Long = 0L,
    override val isRefreshing: Boolean = false,
    val uploadProgress: TransferProgress? = null,
    val downloadProgress: TransferProgress? = null,
    val relationshipMessage: String? = null,
    val error: String? = null,
) : ConnectionAwareUiState

@Immutable
data class ChatScreenUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val e2eeEnabled: Boolean = true,
    val isLoading: Boolean = false,
    override val isConnected: Boolean = false,
    override val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    override val me: ChatUser? = null,
    val users: List<ChatUser> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val deviceIdentities: List<DevicePublicIdentity> = emptyList(),
    val currentConversationManage: ConversationManageInfo? = null,
    val relationshipMessage: String? = null,
    val currentConversationId: Long = 0L,
    val currentConversationDeliveryStates: Map<String, Long> = emptyMap(),
    val currentConversationReadStates: Map<String, Long> = emptyMap(),
    val messages: List<ChatMessage> = emptyList(),
    val hasMoreBefore: Boolean = true,
    val isLoadingOlder: Boolean = false,
    override val isRefreshing: Boolean = false,
    val uploadProgress: TransferProgress? = null,
    val downloadProgress: TransferProgress? = null,
    val error: String? = null,
) : ConnectionAwareUiState

@Immutable
data class SettingsUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val e2eeEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val displayMode: AppDisplayMode = AppDisplayMode.SYSTEM,
    val dynamicColorsEnabled: Boolean = true,
    val textSize: AppTextSize = AppTextSize.MEDIUM,
    val blogNotificationsEnabled: Boolean = true,
    val isLoggedIn: Boolean = false,
    val me: ChatUser? = null,
    val deviceIdentity: DeviceIdentityInfo? = null,
    val deviceIdentities: List<DevicePublicIdentity> = emptyList(),
    val myDevices: List<DevicePublicIdentity> = emptyList(),
    val latestAppRelease: AppReleaseInfo? = null,
    val downloadedUpdate: DecryptedAttachment? = null,
    val isCheckingUpdate: Boolean = false,
    val updateStatus: String? = null,
    val latestPrerelease: AppReleaseInfo? = null,
    val downloadedPrerelease: DecryptedAttachment? = null,
    val isCheckingPrerelease: Boolean = false,
    val prereleaseStatus: String? = null,
    val downloadProgress: TransferProgress? = null,
    val isRunningPushHealthCheck: Boolean = false,
    val pushHealthStatus: String? = null,
    val relationshipMessage: String? = null,
    val error: String? = null,
)

@Immutable
data class ManagementUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val isLoggedIn: Boolean = false,
    val me: ChatUser? = null,
    val users: List<ChatUser> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val contactRequests: List<ContactRequestInfo> = emptyList(),
    val groupJoinRequests: List<GroupJoinRequestInfo> = emptyList(),
    val deviceIdentity: DeviceIdentityInfo? = null,
    val deviceIdentities: List<DevicePublicIdentity> = emptyList(),
    val myDevices: List<DevicePublicIdentity> = emptyList(),
    val currentConversationManage: ConversationManageInfo? = null,
    val relationshipMessage: String? = null,
    val currentConversationId: Long = 0L,
    val isRefreshing: Boolean = false,
    val uploadProgress: TransferProgress? = null,
    val error: String? = null,
)

@Immutable
data class CallUiState(
    val call: VoiceCallUiState = VoiceCallUiState(),
    val language: AppLanguage = AppLanguage.systemDefault(),
    val error: String? = null,
)

internal fun ChatUiState.toShellUiState() = ShellUiState(
    language = language,
    isLoggedIn = isLoggedIn,
    isCheckingUpdate = isCheckingUpdate,
    latestAppRelease = latestAppRelease,
    relationshipMessage = relationshipMessage,
    voiceCall = voiceCall,
    connectionStatus = connectionStatus,
    error = error,
)

internal fun ChatUiState.toAuthUiState() = AuthUiState(
    serverUrl = serverUrl,
    language = language,
    displayMode = displayMode,
    dynamicColorsEnabled = dynamicColorsEnabled,
    textSize = textSize,
    isLoading = isLoading,
    isLoggedIn = isLoggedIn,
    me = me,
    registrationMessage = registrationMessage,
    registrationUserCode = registrationUserCode,
    deviceApprovalPending = deviceApprovalPending,
    pendingDeviceId = pendingDeviceId,
    error = error,
)

internal fun ChatUiState.toConversationUiState() = ConversationUiState(
    serverUrl = serverUrl,
    language = language,
    isLoading = isLoading,
    isLoggedIn = isLoggedIn,
    isConnected = isConnected,
    connectionStatus = connectionStatus,
    me = me,
    users = users,
    conversations = conversations,
    contactRequests = contactRequests,
    groupJoinRequests = groupJoinRequests,
    userLookupResult = userLookupResult,
    groupLookupResult = groupLookupResult,
    currentConversationId = currentConversationId,
    isRefreshing = isRefreshing,
    uploadProgress = uploadProgress,
    downloadProgress = downloadProgress,
    relationshipMessage = relationshipMessage,
    error = error,
)

internal fun ChatUiState.toChatScreenUiState() = ChatScreenUiState(
    serverUrl = serverUrl,
    language = language,
    e2eeEnabled = e2eeEnabled,
    isLoading = isLoading,
    isConnected = isConnected,
    connectionStatus = connectionStatus,
    me = me,
    users = users,
    conversations = conversations,
    deviceIdentities = deviceIdentities,
    currentConversationManage = currentConversationManage,
    relationshipMessage = relationshipMessage,
    currentConversationId = currentConversationId,
    currentConversationDeliveryStates = currentConversationDeliveryStates,
    currentConversationReadStates = currentConversationReadStates,
    messages = messages,
    isRefreshing = isRefreshing,
    uploadProgress = uploadProgress,
    downloadProgress = downloadProgress,
    error = error,
)

internal fun ChatUiState.toSettingsUiState() = SettingsUiState(
    serverUrl = serverUrl,
    e2eeEnabled = e2eeEnabled,
    language = language,
    displayMode = displayMode,
    dynamicColorsEnabled = dynamicColorsEnabled,
    textSize = textSize,
    isLoggedIn = isLoggedIn,
    me = me,
    deviceIdentity = deviceIdentity,
    deviceIdentities = deviceIdentities,
    myDevices = myDevices,
    latestAppRelease = latestAppRelease,
    downloadedUpdate = downloadedUpdate,
    isCheckingUpdate = isCheckingUpdate,
    updateStatus = updateStatus,
    latestPrerelease = latestPrerelease,
    downloadedPrerelease = downloadedPrerelease,
    isCheckingPrerelease = isCheckingPrerelease,
    prereleaseStatus = prereleaseStatus,
    downloadProgress = downloadProgress,
    relationshipMessage = relationshipMessage,
    error = error,
)

internal fun ChatUiState.toManagementUiState() = ManagementUiState(
    serverUrl = serverUrl,
    language = language,
    isLoggedIn = isLoggedIn,
    me = me,
    users = users,
    conversations = conversations,
    contactRequests = contactRequests,
    groupJoinRequests = groupJoinRequests,
    deviceIdentity = deviceIdentity,
    deviceIdentities = deviceIdentities,
    myDevices = myDevices,
    currentConversationManage = currentConversationManage,
    relationshipMessage = relationshipMessage,
    currentConversationId = currentConversationId,
    isRefreshing = isRefreshing,
    uploadProgress = uploadProgress,
    error = error,
)

internal fun ChatUiState.toCallUiState() = CallUiState(
    call = voiceCall,
    language = language,
)
