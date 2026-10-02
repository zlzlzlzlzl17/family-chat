package com.example.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("family_chat_secure_tokens", Context.MODE_PRIVATE)
    private val keyStore: KeyStore
        get() = AndroidKeyStoreProvider.instance
    private val alias = "familychat_session_tokens_v1"

    private fun key(): javax.crypto.SecretKey {
        (keyStore.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    fun put(name: String, value: String) {
        if (value.isBlank()) {
            preferences.edit().remove(name).commit()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(payload)
        encrypted.copyInto(payload, cipher.iv.size)
        check(preferences.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "secure_token_write_failed"
        }
    }

    fun get(name: String): String {
        val raw = preferences.getString(name, "").orEmpty()
        if (raw.isBlank()) return ""
        return runCatching {
            val payload = Base64.decode(raw, Base64.NO_WRAP)
            require(payload.size > 12)
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.onFailure { error ->
            preferences.edit().remove(name).commit()
            FamilyChatDiagnostics.event(
                "secure_token_decrypt_failed",
                "token_kind" to name,
                "error" to error.javaClass.simpleName,
            )
        }.getOrDefault("")
    }

    fun remove(name: String) {
        preferences.edit().remove(name).commit()
    }

    fun contains(name: String): Boolean = preferences.contains(name)

    fun validateOrReset(): Boolean {
        val values = preferences.all.mapNotNull { (name, value) -> (value as? String)?.let { name to it } }
        if (values.isEmpty()) return true
        val valid = values.all { (name, encoded) ->
            runCatching {
                val payload = Base64.decode(encoded, Base64.NO_WRAP)
                require(payload.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
                }
                cipher.doFinal(payload.copyOfRange(12, payload.size))
                name
            }.isSuccess
        }
        if (!valid) destroyAll()
        return valid
    }

    fun destroyAll() {
        preferences.edit().clear().commit()
        runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
    }
}

object SessionRefreshCoordinator {
    private val refreshLock = Any()

    fun refresh(context: Context, serverUrl: String): LoginResult = synchronized(refreshLock) {
        val appContext = context.applicationContext
        val preferences = ChatPreferences(appContext)
        val refreshToken = preferences.refreshToken
        require(refreshToken.isNotBlank()) { "session_expired" }

        val result = ChatApi().refreshSession(serverUrl, refreshToken, preferences.deviceId)
        preferences.token = result.token
        if (result.refreshToken.isNotBlank()) preferences.refreshToken = result.refreshToken
        preferences.userCode = result.userCode
        preferences.username = result.username
        preferences.saveLogin(result.userCode)
        result
    }
}
