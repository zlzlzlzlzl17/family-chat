package com.example.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal data class WrappedFileKey(
    val scheme: String,
    val payload: String,
    val groupEpoch: Long = 0L,
)

/** Feature-owned crypto boundary. Private key material remains inside ChatPreferences/SecureCryptoStore. */
internal class CryptoManager(
    private val preferences: ChatPreferences,
    private val auth: AuthSessionManager,
    private val repository: CryptoSessionRepository,
    private val secureContent: com.example.chat.message.SecureMessageContentRepository,
) {
    private val directBundleCache = ConcurrentHashMap<Long, List<DirectPreKeyBundle>>()
    private val groupEpochs = ConcurrentHashMap<Long, Long>()
    private val groupOwnKeyUploadedEpochs = ConcurrentHashMap<Long, Long>()
    private val groupRefreshCompletedAt = ConcurrentHashMap<Long, Long>()
    private val groupLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun encryptText(
        conversation: ConversationSummary,
        me: ChatUser,
        plaintext: String,
    ): String = when (conversation.kind) {
        "direct" -> {
            val recipients = directRecipients(conversation.id)
            withContext(Dispatchers.Default) {
                DirectMessageCrypto.encryptTextForDevices(
                    preferences,
                    conversation.id,
                    recipients,
                    plaintext,
                )
            }
        }
        "group" -> {
            val epoch = ensureGroupSenderKeys(conversation.id)
            withContext(Dispatchers.Default) {
                GroupSenderKeyCrypto.encryptText(
                    preferences,
                    conversation.id,
                    me.userCode.ifBlank { me.username },
                    me.username,
                    epoch,
                    plaintext,
                )
            }
        }
        else -> throw IllegalStateException("unsupported_conversation_kind")
    }

    suspend fun wrapFileKey(
        conversation: ConversationSummary,
        me: ChatUser,
        fileKey: String,
    ): WrappedFileKey {
        val keyPlain = JSONObject().put("file_key", fileKey).toString()
        return when (conversation.kind) {
            "direct" -> WrappedFileKey(
                scheme = "direct-dr",
                payload = withContext(Dispatchers.Default) {
                    DirectMessageCrypto.encryptTextForDevices(
                        preferences,
                        conversation.id,
                        directRecipients(conversation.id),
                        keyPlain,
                    )
                },
            )
            "group" -> {
                val epoch = ensureGroupSenderKeys(conversation.id)
                WrappedFileKey(
                    scheme = "group-sender",
                    payload = withContext(Dispatchers.Default) {
                        GroupSenderKeyCrypto.encryptText(
                            preferences,
                            conversation.id,
                            me.userCode.ifBlank { me.username },
                            me.username,
                            epoch,
                            keyPlain,
                        )
                    },
                    groupEpoch = epoch,
                )
            }
            else -> throw IllegalStateException("unsupported_conversation_kind")
        }
    }

    suspend fun resolveText(message: ChatMessage): String {
        if (!GroupSenderKeyCrypto.isGroupPayload(message.payload)) {
            return secureContent.resolveText(message)
        }

        prepareGroupPayload(message.conversationId, message.payload)
        return try {
            secureContent.resolveText(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (initialError: Throwable) {
            FamilyChatDiagnostics.event(
                "group_decrypt_retry_started",
                "conversation_id" to message.conversationId,
                "message_id" to message.id,
                "reason" to decryptFailureReason(initialError),
            )
            ensureGroupSenderKeys(
                conversationId = message.conversationId,
                uploadOwnKey = false,
                forceRefresh = true,
            )
            secureContent.resolveText(message).also {
                FamilyChatDiagnostics.event(
                    "group_decrypt_retry_succeeded",
                    "conversation_id" to message.conversationId,
                    "message_id" to message.id,
                )
            }
        }
    }

    suspend fun prepareMessageDecryption(message: ChatMessage, forceRefresh: Boolean = false) {
        val payload = groupPayload(message) ?: return
        prepareGroupPayload(message.conversationId, payload, forceRefresh)
    }

    fun isGroupEncryptedMessage(message: ChatMessage): Boolean = groupPayload(message) != null

    suspend fun rememberText(message: ChatMessage, plaintext: String): Boolean =
        secureContent.rememberText(message, plaintext)

    suspend fun refreshDirectRecipients(conversationId: Long): List<DirectPreKeyBundle> {
        val session = auth.sessionSnapshot()
        check(session.isReady) { "session_expired" }
        val bundles = withContext(Dispatchers.IO) {
            repository.directPreKeys(session.serverUrl, session.accessToken, conversationId)
        }.filter { it.deviceId.isNotBlank() }
        check(bundles.isNotEmpty()) { "peer_prekey_unavailable" }
        directBundleCache[conversationId] = bundles
        bundles.forEach { DirectMessageCrypto.adoptRecipientBundle(preferences, conversationId, it) }
        return bundles
    }

    fun invalidateDirectDevice(conversationId: Long, deviceId: String = "") {
        directBundleCache.remove(conversationId)
        DirectMessageCrypto.invalidateRecipientSession(preferences, conversationId, deviceId)
    }

    fun invalidateGroup(conversationId: Long) {
        groupEpochs.remove(conversationId)
        groupOwnKeyUploadedEpochs.remove(conversationId)
        groupRefreshCompletedAt.remove(conversationId)
    }

    suspend fun ensureGroupSenderKeys(
        conversationId: Long,
        uploadOwnKey: Boolean = true,
        forceRefresh: Boolean = false,
    ): Long {
        val refreshRequestedAt = System.nanoTime()
        val me = auth.state.value.me
        val session = auth.sessionSnapshot()
        check(session.isReady) { "session_expired" }
        return groupLocks.getOrPut(conversationId) { Mutex() }.withLock {
            val cachedEpoch = groupEpochs[conversationId]
            if (
                forceRefresh && cachedEpoch != null &&
                (groupRefreshCompletedAt[conversationId] ?: 0L) >= refreshRequestedAt
            ) {
                return@withLock cachedEpoch
            }
            if (
                !forceRefresh && cachedEpoch != null &&
                (!uploadOwnKey || groupOwnKeyUploadedEpochs[conversationId] == cachedEpoch)
            ) {
                return@withLock cachedEpoch
            }

            var state = withContext(Dispatchers.IO) {
                repository.groupSenderKeys(
                    session.serverUrl,
                    session.accessToken,
                    conversationId,
                    preferences.deviceId,
                )
            }
            val imported = withContext(Dispatchers.Default) {
                GroupSenderKeyCrypto.importSenderKeys(preferences, state.items)
            }
            groupEpochs[conversationId] = state.epoch
            groupRefreshCompletedAt[conversationId] = System.nanoTime()
            FamilyChatDiagnostics.event(
                "group_keys_refreshed",
                "conversation_id" to conversationId,
                "epoch" to state.epoch,
                "received" to state.items.size,
                "imported" to imported,
            )

            if (uploadOwnKey && groupOwnKeyUploadedEpochs[conversationId] != state.epoch) {
                val currentUser = me ?: throw IllegalStateException("session_expired")
                repeat(2) {
                    val localKey = withContext(Dispatchers.Default) {
                        GroupSenderKeyCrypto.buildSenderKeyUpload(
                            preferences,
                            conversationId,
                            state.epoch,
                            currentUser.userCode.ifBlank { currentUser.username },
                            currentUser.username,
                            state.devices,
                        )
                    }
                    if (runCatching {
                            withContext(Dispatchers.IO) {
                                repository.registerGroupSenderKey(session.serverUrl, session.accessToken, localKey)
                            }
                        }.isSuccess
                    ) {
                        groupOwnKeyUploadedEpochs[conversationId] = state.epoch
                        return@withLock state.epoch
                    }
                    state = withContext(Dispatchers.IO) {
                        repository.groupSenderKeys(
                            session.serverUrl,
                            session.accessToken,
                            conversationId,
                            preferences.deviceId,
                        )
                    }
                    val retryImported = withContext(Dispatchers.Default) {
                        GroupSenderKeyCrypto.importSenderKeys(preferences, state.items)
                    }
                    groupEpochs[conversationId] = state.epoch
                    groupRefreshCompletedAt[conversationId] = System.nanoTime()
                    FamilyChatDiagnostics.event(
                        "group_keys_refreshed",
                        "conversation_id" to conversationId,
                        "epoch" to state.epoch,
                        "received" to state.items.size,
                        "imported" to retryImported,
                    )
                }
            }
            state.epoch
        }
    }

    private suspend fun prepareGroupPayload(
        conversationId: Long,
        payload: String,
        forceRefresh: Boolean = false,
    ) {
        if (
            forceRefresh ||
            !GroupSenderKeyCrypto.hasSenderKeyForPayload(preferences, conversationId, payload)
        ) {
            ensureGroupSenderKeys(
                conversationId = conversationId,
                uploadOwnKey = false,
                forceRefresh = true,
            )
            check(GroupSenderKeyCrypto.hasSenderKeyForPayload(preferences, conversationId, payload)) {
                "group_sender_key_unavailable_after_refresh"
            }
        }
    }

    private fun groupPayload(message: ChatMessage): String? {
        if (GroupSenderKeyCrypto.isGroupPayload(message.payload)) return message.payload
        val wrapped = runCatching {
            JSONObject(message.payload)
                .optJSONObject("key_wrap")
                ?.takeIf { it.optString("scheme") == "group-sender" }
                ?.optString("payload")
        }.getOrNull().orEmpty()
        return wrapped.takeIf(GroupSenderKeyCrypto::isGroupPayload)
    }

    private fun decryptFailureReason(error: Throwable): String =
        error.message
            ?.takeIf(String::isNotBlank)
            ?.take(80)
            ?: error.javaClass.simpleName.take(80)

    private suspend fun directRecipients(conversationId: Long): List<DirectPreKeyBundle> =
        directBundleCache[conversationId]?.takeIf(List<DirectPreKeyBundle>::isNotEmpty)
            ?: refreshDirectRecipients(conversationId)
}
