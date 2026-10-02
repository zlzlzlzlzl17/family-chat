package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val DEFAULT_SERVER_URL = BuildConfig.DEFAULT_SERVER_URL
const val APP_CLIENT_HEADER_NAME = "X-FamilyChat-Client"
const val APP_CLIENT_HEADER_VALUE = "android-app"

class ChatPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("family_chat_prefs", Context.MODE_PRIVATE)
    private val secureTokens by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureTokenStore(appContext) }
    private val secureCrypto by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureCryptoStore(appContext) }
    private val pendingIncomingCallKey = "pending_incoming_call"

    init {
        if (plainMaintenanceStarted.compareAndSet(false, true)) {
            prefs.edit()
                .remove("saved_login_password")
                .also { editor -> prefs.all.keys.filter { it.startsWith("draft_text_") }.forEach(editor::remove) }
                .apply()
            if (prefs.getString("saved_login_user_code", "").isNullOrBlank()) {
                val code = prefs.getString("user_code", "").orEmpty()
                if (code.isNotBlank()) {
                    prefs.edit().putString("saved_login_user_code", code).apply()
                }
            }
        }
    }

    internal fun initializeSecureStorage() {
        if (secureStorageInitialized) return
        synchronized(secureStorageInitializationLock) {
            if (secureStorageInitialized) return
            val startedAt = System.currentTimeMillis()
            val legacyToken = prefs.getString("token", "").orEmpty()
            if (legacyToken.isNotBlank() && secureTokens.get("access_token").isBlank()) {
                secureTokens.put("access_token", legacyToken)
            }
            prefs.edit().remove("token").apply()
            // Token integrity gates authentication. Crypto entries validate lazily per message so a
            // large ratchet store never blocks the launch path.
            val healthy = secureTokens.validateOrReset()
            if (!healthy) {
                prefs.edit()
                    .putBoolean("security_reset_required", true)
                    .remove("username")
                    .remove("user_code")
                    .apply()
            } else {
                secureCrypto.scheduleLegacyMigration()
                secureCrypto.scheduleExpiringPrune(MessageKeyLifecycle.storagePrefixes)
            }
            secureStorageInitialized = true
            FamilyChatDiagnostics.event(
                "secure_storage_initialized",
                "duration_ms" to (System.currentTimeMillis() - startedAt),
                "healthy" to healthy,
            )
        }
    }

    internal fun hasStoredAuthenticationMaterial(): Boolean =
        prefs.getString("token", "").orEmpty().isNotBlank() ||
            secureTokens.contains("access_token") ||
            secureTokens.contains("refresh_token") ||
            secureTokens.contains("registration_request_token")

    private fun ensureSecureStorageInitialized() = initializeSecureStorage()

    private fun audioHeardKey(userIdentity: String, messageId: Long): String =
        "audio_heard_${userIdentity}_${messageId}"

    var deviceId: String
        get() {
            val existing = prefs.getString("device_id", "") ?: ""
            if (existing.isNotBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString("device_id", value).apply()

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value).apply()

    var token: String
        get() {
            ensureSecureStorageInitialized()
            return secureTokens.get("access_token")
        }
        set(value) {
            ensureSecureStorageInitialized()
            secureTokens.put("access_token", value)
        }

    var refreshToken: String
        get() {
            ensureSecureStorageInitialized()
            return secureTokens.get("refresh_token")
        }
        set(value) {
            ensureSecureStorageInitialized()
            secureTokens.put("refresh_token", value)
        }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var userCode: String
        get() = prefs.getString("user_code", "") ?: ""
        set(value) = prefs.edit().putString("user_code", value).apply()

    var savedLoginUserCode: String
        get() = prefs.getString("saved_login_user_code", "") ?: ""
        set(value) = prefs.edit().putString("saved_login_user_code", value).apply()

    var registrationRequestToken: String
        get() {
            ensureSecureStorageInitialized()
            return secureTokens.get("registration_request_token")
        }
        set(value) {
            ensureSecureStorageInitialized()
            secureTokens.put("registration_request_token", value)
        }

    var approvedRegistrationUserCode: String
        get() = prefs.getString("approved_registration_user_code", "") ?: ""
        set(value) = prefs.edit().putString("approved_registration_user_code", value).apply()

    var e2eeEnabled: Boolean
        get() = prefs.getBoolean("e2ee_enabled", true)
        set(value) = prefs.edit().putBoolean("e2ee_enabled", value).apply()

    var language: String
        get() = prefs.getString("language", AppLanguage.systemDefault().name) ?: AppLanguage.systemDefault().name
        set(value) = prefs.edit().putString("language", value).apply()

    var displayMode: String
        get() = prefs.getString("display_mode", AppDisplayMode.SYSTEM.name) ?: AppDisplayMode.SYSTEM.name
        set(value) = prefs.edit().putString("display_mode", value).apply()

    var textSize: String
        get() = prefs.getString("text_size", AppTextSize.MEDIUM.name) ?: AppTextSize.MEDIUM.name
        set(value) = prefs.edit().putString("text_size", value).apply()

    var dynamicColorsEnabled: Boolean
        get() = prefs.getBoolean("dynamic_colors_enabled", true)
        set(value) = prefs.edit().putBoolean("dynamic_colors_enabled", value).apply()

    var blogNotificationsEnabled: Boolean
        get() = prefs.getBoolean("blog_notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("blog_notifications_enabled", value).apply()

    var lastNotifiedMessageId: Long
        get() = prefs.getLong("last_notified_message_id", 0L)
        set(value) = prefs.edit().putLong("last_notified_message_id", value).apply()

    /**
     * Per-conversation notification mute.
     *
     * Muting suppresses the notification only. Delivery receipts are still
     * reported and messages are still synced, so muting a noisy family group
     * must never make the sender's ticks stop advancing.
     */
    fun isConversationMuted(conversationId: Long): Boolean =
        conversationId > 0L && prefs.getBoolean(mutedConversationKey(conversationId), false)

    fun setConversationMuted(conversationId: Long, muted: Boolean) {
        if (conversationId <= 0L) return
        val key = mutedConversationKey(conversationId)
        if (muted) {
            prefs.edit().putBoolean(key, true).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    private fun mutedConversationKey(conversationId: Long) = "muted_conversation_$conversationId"

    var lastPushDiagnosticId: String
        get() = prefs.getString("last_push_diagnostic_id", "") ?: ""
        set(value) = prefs.edit().putString("last_push_diagnostic_id", value).apply()

    var lastPushDiagnosticAt: Long
        get() = prefs.getLong("last_push_diagnostic_at", 0L)
        set(value) = prefs.edit().putLong("last_push_diagnostic_at", value).apply()

    fun hasHeardAudioMessage(userIdentity: String, messageId: Long): Boolean {
        if (userIdentity.isBlank() || messageId <= 0L) return false
        return prefs.getBoolean(audioHeardKey(userIdentity, messageId), false)
    }

    fun markAudioMessageHeard(userIdentity: String, messageId: Long) {
        if (userIdentity.isBlank() || messageId <= 0L) return
        prefs.edit().putBoolean(audioHeardKey(userIdentity, messageId), true).apply()
    }

    fun saveLogin(userCode: String) {
        prefs.edit().putString("saved_login_user_code", userCode).apply()
    }

    fun clearSession() {
        ensureSecureStorageInitialized()
        secureTokens.remove("access_token")
        secureTokens.remove("refresh_token")
        prefs.edit().remove("username").remove("user_code").apply()
    }

    var securityResetRequired: Boolean
        get() = prefs.getBoolean("security_reset_required", false)
        set(value) = prefs.edit().putBoolean("security_reset_required", value).apply()

    fun destroySecurityState(clearDeviceId: Boolean = false) {
        secureTokens.destroyAll()
        secureCrypto.destroyAll()
        prefs.edit()
            .remove("username")
            .remove("user_code")
            .remove("pending_incoming_call")
            .remove("last_notified_message_id")
            .remove("last_push_diagnostic_id")
            .remove("last_push_diagnostic_at")
            .apply()
        if (clearDeviceId) prefs.edit().remove("device_id").apply()
    }

    fun clearSavedLogin() {
        prefs.edit().remove("saved_login_user_code").apply()
    }

    fun cryptoValue(key: String): String {
        ensureSecureStorageInitialized()
        return secureCrypto.get(key)
    }

    fun putCryptoValue(key: String, value: String) {
        ensureSecureStorageInitialized()
        secureCrypto.put(key, value)
    }

    fun removeCryptoValue(key: String) {
        ensureSecureStorageInitialized()
        secureCrypto.remove(key)
    }

    fun removeCryptoValuesWithPrefix(prefix: String) {
        ensureSecureStorageInitialized()
        secureCrypto.removeWithPrefix(prefix)
    }

    fun messageCryptoKey(key: String, messageExpiresAt: Long = 0L): String {
        ensureSecureStorageInitialized()
        val now = System.currentTimeMillis()
        return secureCrypto.getExpiring(
            logicalKey = key,
            fallbackExpiresAt = messageExpiresAt.takeIf { it > 0L }
                ?: MessageKeyLifecycle.legacyFallbackExpiry(now),
            now = now,
        )
    }

    fun putMessageCryptoKey(key: String, value: String, messageExpiresAt: Long? = null) {
        ensureSecureStorageInitialized()
        secureCrypto.putExpiring(
            logicalKey = key,
            value = value,
            expiresAt = messageExpiresAt
                ?.let(MessageKeyLifecycle::effectiveExpiry)
                ?: MessageKeyLifecycle.unconfirmedKeyExpiry(),
        )
    }

    fun removeMessageCryptoKey(key: String) {
        ensureSecureStorageInitialized()
        secureCrypto.remove(key)
    }

    fun attachmentFileKey(message: ChatMessage): String {
        if (message.id <= 0L || message.conversationId <= 0L) return ""
        ensureSecureStorageInitialized()
        val now = System.currentTimeMillis()
        return secureCrypto.getExpiring(
            logicalKey = attachmentFileKeyName(message),
            fallbackExpiresAt = message.expiresAt.takeIf { it > 0L }
                ?: MessageKeyLifecycle.legacyFallbackExpiry(now),
            now = now,
        )
    }

    fun putAttachmentFileKey(message: ChatMessage, fileKey: String) {
        if (message.id <= 0L || message.conversationId <= 0L || fileKey.isBlank()) return
        ensureSecureStorageInitialized()
        secureCrypto.putExpiring(
            logicalKey = attachmentFileKeyName(message),
            value = fileKey,
            expiresAt = MessageKeyLifecycle.effectiveExpiry(message.expiresAt),
        )
    }

    private fun attachmentFileKeyName(message: ChatMessage): String =
        "attachment_file_key_${message.conversationId}_${message.id}"

    fun removeMessageCryptoKeysForConversation(conversationId: Long) {
        if (conversationId <= 0L) return
        ensureSecureStorageInitialized()
        MessageKeyLifecycle.storagePrefixes.forEach { prefix ->
            secureCrypto.removeWithPrefix("$prefix${conversationId}_")
        }
    }

    fun removeAllMessageCryptoKeys() {
        ensureSecureStorageInitialized()
        MessageKeyLifecycle.storagePrefixes.forEach(secureCrypto::removeWithPrefix)
    }

    fun pruneExpiredMessageCryptoKeys() {
        ensureSecureStorageInitialized()
        secureCrypto.pruneExpired(MessageKeyLifecycle.storagePrefixes)
    }

    fun pendingIncomingCall(): PendingIncomingCallInvite? {
        val raw = prefs.getString(pendingIncomingCallKey, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PendingIncomingCallInvite(
                conversationId = json.optLong("conversation_id", 0L),
                peerUserCode = json.optString("peer_user_code"),
                peerUsername = json.optString("peer_username"),
                createdAt = json.optLong("created_at", 0L),
            )
        }.getOrNull()?.takeIf { it.conversationId > 0L && it.peerUsername.isNotBlank() }
    }

    fun setPendingIncomingCall(invite: PendingIncomingCallInvite?) {
        if (invite == null) {
            prefs.edit().remove(pendingIncomingCallKey).apply()
            return
        }
        val value = JSONObject()
            .put("conversation_id", invite.conversationId)
            .put("peer_user_code", invite.peerUserCode)
            .put("peer_username", invite.peerUsername)
            .put("created_at", invite.createdAt)
            .toString()
        prefs.edit().putString(pendingIncomingCallKey, value).apply()
    }

    private companion object {
        val plainMaintenanceStarted = AtomicBoolean(false)
        val secureStorageInitializationLock = Any()

        @Volatile
        var secureStorageInitialized = false
    }
}
