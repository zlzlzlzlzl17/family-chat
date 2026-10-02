package com.example.chat

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

object DeviceIdentityManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALG = "EC-P256-SHA256"
    private const val ALIAS_PREFIX = "familychat_identity_"

    fun localIdentity(preferences: ChatPreferences, userCode: String): DeviceIdentityInfo {
        val deviceId = preferences.deviceId
        val publicKey = publicKeyBase64(deviceId)
        return DeviceIdentityInfo(
            deviceId = deviceId,
            deviceName = deviceName(),
            keyAlg = KEY_ALG,
            publicKey = publicKey,
            safetyCode = safetyCode(userCode, deviceId, publicKey),
        )
    }

    private fun aliasFor(deviceId: String): String = "$ALIAS_PREFIX$deviceId"

    private fun publicKeyBase64(deviceId: String): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val alias = aliasFor(deviceId)
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        val certificate = keyStore.getCertificate(alias)
            ?: throw IllegalStateException("device_identity_unavailable")
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun signWithLocalIdentity(preferences: ChatPreferences, data: ByteArray): String {
        publicKeyBase64(preferences.deviceId)
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(aliasFor(preferences.deviceId), null) as? PrivateKey
            ?: throw IllegalStateException("device_identity_unavailable")
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    fun destroyLocalIdentity(deviceId: String) {
        if (deviceId.isBlank()) return
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val alias = aliasFor(deviceId)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }.onFailure { error ->
            FamilyChatDiagnostics.event(
                "device_identity_destroy_failed",
                "error" to error.javaClass.simpleName,
            )
        }
    }

    fun verifySignature(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.NO_WRAP)))
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(data)
            }.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        }.getOrDefault(false)

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return "$maker $model".trim().replace(Regex("\\s+"), " ").ifBlank { "Android device" }
    }

    fun safetyCode(userCode: String, deviceId: String, publicKey: String): String {
        val input = "familychat-device-v1|$userCode|$deviceId|$publicKey"
            .toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest
            .take(16)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            .chunked(4)
            .joinToString(" ")
    }
}
