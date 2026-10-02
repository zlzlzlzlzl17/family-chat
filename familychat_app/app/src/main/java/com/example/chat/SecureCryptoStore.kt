package com.example.chat

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/**
 * Keystore-backed storage for protocol private keys and ratchet state.
 *
 * Legacy values are migrated from family_chat_prefs lazily and in a background
 * sweep. The plaintext value is removed only after the encrypted write commits.
 */
internal class SecureCryptoStore(context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val encryptedPrefs = context.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore
        get() = AndroidKeyStoreProvider.instance

    fun get(logicalKey: String): String = synchronized(storeLock) {
        val storageKey = storageKey(logicalKey)
        val encryptedValue = encryptedPrefs.getString(storageKey, null)
        if (encryptedValue != null) {
            val decrypted = decrypt(storageKey, encryptedValue)
            if (decrypted.isSuccess) return@synchronized decrypted.getOrThrow()

            val legacyFallback = legacyPrefs.getString(storageKey, null)
            if (legacyFallback != null && commitEncrypted(storageKey, legacyFallback)) {
                legacyPrefs.edit().remove(storageKey).commit()
                FamilyChatDiagnostics.event(
                    "crypto_store_value_recovered",
                    "key_category" to keyCategory(logicalKey),
                )
                return@synchronized legacyFallback
            }

            FamilyChatDiagnostics.event(
                "crypto_store_decrypt_failed",
                "key_category" to keyCategory(logicalKey),
                "error" to decrypted.exceptionOrNull()?.javaClass?.simpleName.orEmpty(),
            )
            return@synchronized ""
        }

        val legacyValue = legacyPrefs.getString(storageKey, null) ?: return@synchronized ""
        if (commitEncrypted(storageKey, legacyValue)) {
            legacyPrefs.edit().remove(storageKey).commit()
            FamilyChatDiagnostics.sampled("crypto_store_value_migrated", 30_000L)
        }
        legacyValue
    }

    fun put(logicalKey: String, value: String) = synchronized(storeLock) {
        val storageKey = storageKey(logicalKey)
        if (value.isBlank()) {
            encryptedPrefs.edit().remove(storageKey).apply()
            legacyPrefs.edit().remove(storageKey).apply()
            return@synchronized
        }
        if (!commitEncrypted(storageKey, value)) {
            throw IllegalStateException("Unable to persist encrypted crypto state")
        }
        legacyPrefs.edit().remove(storageKey).apply()
    }

    fun putExpiring(logicalKey: String, value: String, expiresAt: Long) {
        put(logicalKey, MessageKeyLifecycle.encode(value, expiresAt))
    }

    fun getExpiring(
        logicalKey: String,
        fallbackExpiresAt: Long,
        now: Long = System.currentTimeMillis(),
    ): String = synchronized(storeLock) {
        val raw = get(logicalKey)
        if (raw.isBlank()) return@synchronized ""
        val decoded = MessageKeyLifecycle.decode(raw)
        if (decoded == null) {
            putExpiring(logicalKey, raw, fallbackExpiresAt)
            return@synchronized raw
        }
        if (MessageKeyLifecycle.isExpired(decoded, now)) {
            remove(logicalKey)
            return@synchronized ""
        }
        decoded.value
    }

    fun remove(logicalKey: String) = synchronized(storeLock) {
        val storageKey = storageKey(logicalKey)
        encryptedPrefs.edit().remove(storageKey).apply()
        legacyPrefs.edit().remove(storageKey).apply()
    }

    fun removeWithPrefix(logicalPrefix: String) = synchronized(storeLock) {
        if (logicalPrefix.isBlank()) return@synchronized
        val storagePrefix = storageKey(logicalPrefix)
        encryptedPrefs.removeMatching(storagePrefix)
        legacyPrefs.removeMatching(storagePrefix)
    }

    fun scheduleLegacyMigration() {
        if (!migrationScheduled.compareAndSet(false, true)) return
        thread(name = "familychat-crypto-migration", isDaemon = true) {
            Thread.sleep(MAINTENANCE_START_DELAY_MS)
            val migrated = runCatching { migrateAllLegacyValues() }
                .onFailure { error ->
                    FamilyChatDiagnostics.event(
                        "crypto_store_migration_failed",
                        "error" to error.javaClass.simpleName,
                    )
                }
                .getOrDefault(0)
            if (migrated > 0) {
                FamilyChatDiagnostics.event("crypto_store_migration_complete", "count" to migrated)
            }
        }
    }

    fun validateOrReset(): Boolean = synchronized(storeLock) {
        val values = encryptedPrefs.all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        if (values.isEmpty()) return@synchronized true
        val valid = values.all { (storageKey, encoded) -> decrypt(storageKey, encoded).isSuccess }
        if (!valid) destroyAllLocked()
        valid
    }

    fun destroyAll() = synchronized(storeLock) { destroyAllLocked() }

    fun scheduleExpiringPrune(logicalPrefixes: List<String>) {
        if (logicalPrefixes.isEmpty() || !pruneScheduled.compareAndSet(false, true)) return
        thread(name = "familychat-key-prune", isDaemon = true) {
            Thread.sleep(MAINTENANCE_START_DELAY_MS)
            runCatching { pruneExpired(logicalPrefixes) }
                .onSuccess { removed ->
                    if (removed > 0) {
                        FamilyChatDiagnostics.event("expired_message_keys_pruned", "count" to removed)
                    }
                }
                .onFailure { error ->
                    FamilyChatDiagnostics.event(
                        "message_key_prune_failed",
                        "error" to error.javaClass.simpleName,
                    )
                }
        }
    }

    fun pruneExpired(
        logicalPrefixes: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Int = synchronized(storeLock) {
        val fallbackExpiresAt = MessageKeyLifecycle.legacyFallbackExpiry(now)
        val logicalKeys = (encryptedPrefs.all.keys + legacyPrefs.all.keys)
            .asSequence()
            .filter { it.startsWith(STORAGE_PREFIX) }
            .map { it.removePrefix(STORAGE_PREFIX) }
            .filter { key -> logicalPrefixes.any(key::startsWith) }
            .distinct()
            .toList()
        var removed = 0
        logicalKeys.forEach { logicalKey ->
            val raw = get(logicalKey)
            val decoded = MessageKeyLifecycle.decode(raw)
            when {
                raw.isBlank() -> Unit
                decoded == null -> putExpiring(logicalKey, raw, fallbackExpiresAt)
                MessageKeyLifecycle.isExpired(decoded, now) -> {
                    remove(logicalKey)
                    removed += 1
                }
            }
        }
        removed
    }

    private fun migrateAllLegacyValues(): Int = synchronized(storeLock) {
        val legacyValues = legacyPrefs.all
            .filterKeys { it.startsWith(STORAGE_PREFIX) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        if (legacyValues.isEmpty()) return@synchronized 0

        val encryptedEditor = encryptedPrefs.edit()
        legacyValues.forEach { (storageKey, value) ->
            val encryptedValue = encryptedPrefs.getString(storageKey, null)
            if (encryptedValue == null || decrypt(storageKey, encryptedValue).isFailure) {
                encryptedEditor.putString(storageKey, encrypt(storageKey, value))
            }
        }
        if (!encryptedEditor.commit()) {
            throw IllegalStateException("Unable to commit encrypted crypto migration")
        }

        val legacyEditor = legacyPrefs.edit()
        legacyValues.forEach { (key, _) -> legacyEditor.remove(key) }
        if (!legacyEditor.commit()) {
            throw IllegalStateException("Unable to remove migrated plaintext crypto state")
        }
        legacyValues.size
    }

    private fun commitEncrypted(storageKey: String, value: String): Boolean = runCatching {
        encryptedPrefs.edit().putString(storageKey, encrypt(storageKey, value)).commit()
    }.getOrElse { error ->
        FamilyChatDiagnostics.event(
            "crypto_store_write_failed",
            "key_category" to keyCategory(storageKey.removePrefix(STORAGE_PREFIX)),
            "error" to error.javaClass.simpleName,
        )
        false
    }

    private fun encrypt(storageKey: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(aad(storageKey))
        }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(payload)
        encrypted.copyInto(payload, cipher.iv.size)
        return FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(storageKey: String, encoded: String): Result<String> = runCatching {
        require(encoded.startsWith(FORMAT_PREFIX))
        val payload = Base64.decode(encoded.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        require(payload.size > IV_SIZE_BYTES)
        val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
        val encrypted = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            updateAAD(aad(storageKey))
        }
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun secretKey(): javax.crypto.SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun destroyAllLocked() {
        encryptedPrefs.edit().clear().commit()
        legacyPrefs.edit().also { editor ->
            legacyPrefs.all.keys.filter { it.startsWith(STORAGE_PREFIX) }.forEach(editor::remove)
        }.commit()
        runCatching { if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS) }
    }

    private fun aad(storageKey: String): ByteArray =
        "familychat:$ENCRYPTED_PREFS_NAME:$storageKey:v1".toByteArray(StandardCharsets.UTF_8)

    private fun storageKey(logicalKey: String): String = STORAGE_PREFIX + logicalKey

    private fun keyCategory(logicalKey: String): String = logicalKey.substringBefore('_').take(24)

    private fun SharedPreferences.removeMatching(prefix: String) {
        val matching = all.keys.filter { it.startsWith(prefix) }
        if (matching.isEmpty()) return
        edit().also { editor -> matching.forEach(editor::remove) }.apply()
    }

    private companion object {
        const val LEGACY_PREFS_NAME = "family_chat_prefs"
        const val ENCRYPTED_PREFS_NAME = "family_chat_secure_crypto"
        const val KEY_ALIAS = "familychat_crypto_state_v1"
        const val STORAGE_PREFIX = "crypto_"
        const val FORMAT_PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val MAINTENANCE_START_DELAY_MS = 15_000L

        val storeLock = Any()
        val migrationScheduled = AtomicBoolean(false)
        val pruneScheduled = AtomicBoolean(false)
    }
}

/** AndroidKeyStore initialization is expensive on some OEMs, so share one loaded instance per process. */
internal object AndroidKeyStoreProvider {
    val instance: KeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val startedAt = System.currentTimeMillis()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.also {
            FamilyChatDiagnostics.event(
                "android_keystore_ready",
                "duration_ms" to (System.currentTimeMillis() - startedAt),
            )
        }
    }
}
