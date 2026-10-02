package com.example.chat

import android.app.Application
import com.example.chat.message.SecureMessageContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.system.measureTimeMillis

/** Application-scoped dependency graph. Feature ViewModels own behavior; this object only builds dependencies. */
internal class AppContainer(application: Application) {
    private val appContext = application.applicationContext
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferences: ChatPreferences by lazy { ChatPreferences(appContext) }
    val userPreferencesStore by lazy { UserPreferencesStore(preferences) }
    val networkMonitor by lazy { NetworkMonitor(appContext) }
    val api: ChatApi by lazy { ChatApi() }
    val authRepository: AuthRepository by lazy { AuthRepository(api) }
    val deviceRepository: DeviceRepository by lazy { DeviceRepository(api) }
    val cryptoSessionRepository: CryptoSessionRepository by lazy { CryptoSessionRepository(api) }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(api) }
    val messageRepository: MessageRepository by lazy { MessageRepository(api) }
    val attachmentRepository: AttachmentRepository by lazy { AttachmentRepository(api) }
    val callRepository: CallRepository by lazy { CallRepository(api) }
    val updateRepository: UpdateRepository by lazy { UpdateRepository(api) }
    val blogApi: BlogApi by lazy { BlogApi() }
    val localBlogStore: LocalBlogStore by lazy { LocalBlogStore(appContext) }
    val blogRepository: BlogRepository by lazy { BlogRepository(appContext, blogApi, localBlogStore) }
    val localChatRepository: LocalChatRepository by lazy { LocalChatRepository(appContext) }
    val securityLifecycleManager by lazy {
        SecurityLifecycleManager(application, preferences, localChatRepository)
    }
    val secureMessageContentRepository: SecureMessageContentRepository by lazy { SecureMessageContentRepository(
        localStore = localChatRepository,
        preferences = preferences,
        ownerIdProvider = {
            preferences.userCode.ifBlank { preferences.username }.trim().lowercase()
        },
    ) }
    val pushCoordinator: PushCoordinator by lazy { PushCoordinator(appContext) }
    val updateCoordinator: UpdateCoordinator by lazy { UpdateCoordinator(application, updateRepository) }
    val authSessionManager: AuthSessionManager by lazy { AuthSessionManager(
        application = application,
        preferences = preferences,
        authRepository = authRepository,
        deviceRepository = deviceRepository,
        securityLifecycle = { securityLifecycleManager },
        scope = applicationScope,
    ).also(AuthSessionManager::initialize) }
    val cryptoManager: CryptoManager by lazy { CryptoManager(
        preferences = preferences,
        auth = authSessionManager,
        repository = cryptoSessionRepository,
        secureContent = secureMessageContentRepository,
    ) }
    val conversationSelectionStore by lazy { ConversationSelectionStore() }
    val realtimeConnectionStore by lazy { RealtimeConnectionStore() }
    val realtimeGateway by lazy { RealtimeGateway(
        auth = authSessionManager,
        repository = messageRepository,
        local = localChatRepository,
        connectionStore = realtimeConnectionStore,
        scope = applicationScope,
    ) }
    val blogManager by lazy { BlogManager(
        auth = authSessionManager,
        repository = blogRepository,
        realtime = realtimeGateway,
        scope = applicationScope,
    ) }
    val conversationManager by lazy { ConversationManager(
        preferences = preferences,
        auth = authSessionManager,
        repository = conversationRepository,
        local = localChatRepository,
        selectionStore = conversationSelectionStore,
        connectionStore = realtimeConnectionStore,
        userPreferences = userPreferencesStore,
        realtime = realtimeGateway,
        scope = applicationScope,
    ) }
    val attachmentManager by lazy { AttachmentManager(
        application = application,
        preferences = preferences,
        auth = authSessionManager,
        repository = attachmentRepository,
        crypto = cryptoManager,
        local = localChatRepository,
    ) }
    val chatManager by lazy { ChatManager(
        application = application,
        preferences = preferences,
        auth = authSessionManager,
        conversationManager = conversationManager,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        local = localChatRepository,
        crypto = cryptoManager,
        attachmentManager = attachmentManager,
        realtime = realtimeGateway,
        selectionStore = conversationSelectionStore,
        connectionStore = realtimeConnectionStore,
        userPreferences = userPreferencesStore,
        scope = applicationScope,
    ) }
    val callCoordinator by lazy { CallCoordinator(
        application = application,
        preferences = preferences,
        auth = authSessionManager,
        conversations = conversationManager,
        repository = callRepository,
        realtime = realtimeGateway,
        connectionStore = realtimeConnectionStore,
        userPreferences = userPreferencesStore,
        scope = applicationScope,
    ) }
    val settingsManager by lazy { SettingsManager(
        preferences = preferences,
        preferenceStore = userPreferencesStore,
        auth = authSessionManager,
        devices = deviceRepository,
        updates = updateCoordinator,
        push = pushCoordinator,
        realtime = realtimeGateway,
        scope = applicationScope,
    ) }
    val managementManager by lazy { ManagementManager(
        preferences = preferences,
        preferenceStore = userPreferencesStore,
        auth = authSessionManager,
        authRepository = authRepository,
        conversationRepository = conversationRepository,
        conversationManager = conversationManager,
        settingsManager = settingsManager,
        chatManager = chatManager,
        selectionStore = conversationSelectionStore,
        local = localChatRepository,
        realtime = realtimeGateway,
        scope = applicationScope,
    ) }

    @Synchronized
    fun prepareHomeFeatures() {
        if (homeFeaturesPrepared) return
        val duration = measureTimeMillis {
            measureFeature("local_chat") { localChatRepository }
            measureFeature("conversations") { conversationManager }
            measureFeature("calls") { callCoordinator }
            measureFeature("settings") { settingsManager }
        }
        homeFeaturesPrepared = true
        FamilyChatDiagnostics.event("home_features_prepared", "duration_ms" to duration)
    }

    @Volatile
    private var homeFeaturesPrepared = false

    @Synchronized
    fun prepareChatFeatures() {
        if (chatFeaturesPrepared) return
        val duration = measureTimeMillis {
            cryptoManager
            attachmentManager
            chatManager
        }
        chatFeaturesPrepared = true
        FamilyChatDiagnostics.event("chat_features_prepared", "duration_ms" to duration)
    }

    val areChatFeaturesPrepared: Boolean
        get() = chatFeaturesPrepared

    @Volatile
    private var chatFeaturesPrepared = false

    @Synchronized
    fun prepareManagementFeatures() {
        if (managementFeaturesPrepared) return
        val duration = measureTimeMillis { managementManager }
        managementFeaturesPrepared = true
        FamilyChatDiagnostics.event("management_features_prepared", "duration_ms" to duration)
    }

    val areManagementFeaturesPrepared: Boolean
        get() = managementFeaturesPrepared

    @Volatile
    private var managementFeaturesPrepared = false

    private inline fun measureFeature(name: String, block: () -> Any) {
        val duration = measureTimeMillis { block() }
        FamilyChatDiagnostics.event(
            "feature_prepared",
            "feature" to name,
            "duration_ms" to duration,
        )
    }

    fun messageSyncEngine(
        sessionProvider: SyncSessionProvider,
        outboxSender: OutboxSender,
        scope: CoroutineScope,
    ): MessageSyncEngine = MessageSyncEngine(
        local = localChatRepository,
        remote = messageRepository,
        sessionProvider = sessionProvider,
        outboxSender = outboxSender,
        parentScope = scope,
    )
}
