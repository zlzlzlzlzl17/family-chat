package com.example.chat.message

import com.example.chat.ChatMessage
import com.example.chat.ChatPreferences
import com.example.chat.DirectMessageCrypto
import com.example.chat.FamilyChatDiagnostics
import com.example.chat.GroupSenderKeyCrypto
import com.example.chat.LocalChatRepository
import com.example.chat.LocalDecryptedContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class SecureMessageContentRepository(
    private val localStore: LocalChatRepository,
    private val preferences: ChatPreferences,
    private val ownerIdProvider: () -> String,
) {
    private val messageLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolveText(message: ChatMessage): String {
        if (!message.e2ee) return message.payload
        if (message.id <= 0L) return decryptText(message)

        val lockKey = "${message.conversationId}:${message.id}"
        val mutex = messageLocks.getOrPut(lockKey) { Mutex() }
        return try {
            mutex.withLock {
                val ownerId = ownerIdProvider()
                if (ownerId.isNotBlank()) {
                    val cached = withContext(Dispatchers.IO) {
                        localStore.loadDecryptedContent(ownerId, message, LocalDecryptedContentKind.TEXT)
                    }
                    if (cached.isNotBlank()) {
                        forgetDirectMessageKey(message)
                        return@withLock cached
                    }
                }

                val plainText = decryptText(message)
                if (ownerId.isNotBlank()) {
                    val stored = runCatching {
                        withContext(Dispatchers.IO) {
                            localStore.saveDecryptedContent(
                                ownerId,
                                message,
                                LocalDecryptedContentKind.TEXT,
                                plainText,
                            )
                        }
                    }.onFailure { error ->
                        FamilyChatDiagnostics.event(
                            "decrypted_message_cache_write_failed",
                            "conversation_id" to message.conversationId,
                            "message_id" to message.id,
                            "error" to error.javaClass.simpleName,
                        )
                    }.getOrDefault(false)
                    if (stored) forgetDirectMessageKey(message)
                }
                plainText
            }
        } finally {
            messageLocks.remove(lockKey, mutex)
        }
    }

    suspend fun rememberText(message: ChatMessage, plainText: String): Boolean {
        val ownerId = ownerIdProvider()
        if (ownerId.isBlank() || message.id <= 0L || plainText.isBlank()) return false
        val stored = withContext(Dispatchers.IO) {
            localStore.saveDecryptedContent(
                ownerId,
                message,
                LocalDecryptedContentKind.TEXT,
                plainText,
            )
        }
        if (stored) forgetDirectMessageKey(message)
        return stored
    }

    private suspend fun decryptText(message: ChatMessage): String = withContext(Dispatchers.Default) {
        when {
            DirectMessageCrypto.isDirectPayload(message.payload) ->
                DirectMessageCrypto.decryptText(preferences, message)
            GroupSenderKeyCrypto.isGroupPayload(message.payload) ->
                GroupSenderKeyCrypto.decryptText(preferences, message.conversationId, message.payload)
            else -> throw IllegalStateException("unsupported_encrypted_message")
        }
    }

    private fun forgetDirectMessageKey(message: ChatMessage) {
        if (!DirectMessageCrypto.isDirectPayload(message.payload)) return
        if (DirectMessageCrypto.forgetMessageKey(preferences, message)) {
            FamilyChatDiagnostics.sampled(
                "direct_message_key_discarded_after_cache",
                30_000L,
                "conversation_id" to message.conversationId,
            )
        }
    }
}
