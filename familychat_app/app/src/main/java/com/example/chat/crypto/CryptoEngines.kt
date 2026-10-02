package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
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
import java.io.File
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
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChatCrypto {
    data class EncryptedFileResult(
        val salt: String,
        val iv: String,
        val version: Int = 3,
    )

    private const val GCM_TAG_BITS = 128
    private const val KEY_BYTES = 32
    private val random = SecureRandom()

    fun randomFileKey(): String =
        b64(ByteArray(KEY_BYTES).also(random::nextBytes))

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    fun textAad(conversationId: Long): String =
        "familychat:v2:text:conversation=$conversationId:kind=text"

    fun attachmentMetaAad(conversationId: Long, kind: String): String =
        "familychat:v2:attachment-meta:conversation=$conversationId:kind=$kind"

    fun attachmentFileAad(conversationId: Long, kind: String): String =
        "familychat:v2:attachment-file:conversation=$conversationId:kind=$kind"

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: ByteArray, length: Int = KEY_BYTES): ByteArray {
        return CryptoPrimitives.hkdfSha256(inputKeyMaterial, info = info, length = length)
    }

    private fun initAeadCipher(mode: Int, key: SecretKeySpec, iv: ByteArray, aad: String): Cipher =
        CryptoPrimitives.aesGcmCipher(mode, key.encoded, iv, aad)

    private fun rawAeadKey(fileKey: String, purpose: String, aad: String): SecretKeySpec {
        val keyBytes = hkdfSha256(fromB64(fileKey), "familychat:v3:$purpose:$aad".toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptTextWithKey(fileKey: String, plaintext: String, aad: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = initAeadCipher(Cipher.ENCRYPT_MODE, rawAeadKey(fileKey, "text", aad), iv, aad)
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val payload = JSONObject()
            .put("v", 3)
            .put("kdf", "HKDF-SHA256")
            .put("aead", "AES-256-GCM")
            .put("iv", b64(iv))
            .put("ct", b64(encrypted))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptTextWithKey(fileKey: String, payload: String, aad: String): String {
        val json = JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))
        val cipher = initAeadCipher(Cipher.DECRYPT_MODE, rawAeadKey(fileKey, "text", aad), fromB64(json.getString("iv")), aad)
        return String(cipher.doFinal(fromB64(json.getString("ct"))), StandardCharsets.UTF_8)
    }

    fun encryptBinaryFileWithKey(fileKey: String, sourceFile: File, targetFile: File, aad: String): EncryptedFileResult {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = initAeadCipher(Cipher.ENCRYPT_MODE, rawAeadKey(fileKey, "attachment-file", aad), iv, aad)
        targetFile.parentFile?.mkdirs()
        try {
            sourceFile.inputStream().use { input ->
                CipherOutputStream(targetFile.outputStream(), cipher).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
        return EncryptedFileResult(salt = "", iv = b64(iv), version = 3)
    }

    fun decryptBinaryFileWithKey(
        fileKey: String,
        iv: String,
        sourceFile: File,
        targetFile: File,
        aad: String,
    ) {
        targetFile.parentFile?.mkdirs()
        try {
            val cipher = initAeadCipher(
                Cipher.DECRYPT_MODE,
                rawAeadKey(fileKey, "attachment-file", aad),
                fromB64(iv),
                aad
            )
            sourceFile.inputStream().use { input ->
                CipherInputStream(input, cipher).use { decrypted ->
                    targetFile.outputStream().use { output ->
                        decrypted.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
    }
}

object DirectMessageCrypto {
    private const val SCHEME_V1 = "familychat-direct-dr-v1"
    private const val SCHEME_V2 = "familychat-direct-dr-v2"
    private const val SCHEME_MULTI_V3 = "familychat-direct-multi-v3"
    private const val KEY_ALG = "EC-P256-X3DH-DR-v2"
    private const val LEGACY_KEY_ALG = "EC-P256-X3DH-DR-v1"
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val MAX_RATCHET_STEPS = 1000
    private const val ONE_TIME_PREKEY_MIN = 20
    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sha256Hex(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        return hkdfSha256(inputKeyMaterial, ByteArray(KEY_BYTES), info, length)
    }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        return CryptoPrimitives.hkdfSha256(
            inputKeyMaterial,
            salt = salt.copyOf(KEY_BYTES),
            info = info,
            length = length,
        )
    }

    private fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }

    private fun publicKeyFromB64(value: String) =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(fromB64(value)))

    private fun privateKeyFromB64(value: String) =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(fromB64(value)))

    private fun ecdh(privateKey: PrivateKey, publicKey: java.security.PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    private fun initCipher(mode: Int, keyBytes: ByteArray, iv: ByteArray, aad: String): Cipher =
        CryptoPrimitives.aesGcmCipher(mode, keyBytes, iv, aad)

    private fun payloadJson(payload: String): JSONObject =
        JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))

    fun isDirectPayload(payload: String): Boolean =
        runCatching {
            val scheme = payloadJson(payload).optString("scheme")
            scheme == SCHEME_V1 || scheme == SCHEME_V2 || scheme == SCHEME_MULTI_V3
        }.getOrDefault(false)

    private data class LocalEcdhIdentity(
        val publicKey: String,
        val privateKey: String,
        val signature: String,
    )

    private fun randomId(): String {
        val bytes = ByteArray(12).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun generateStoredKeyPair(prefs: ChatPreferences, publicKeyKey: String, privateKeyKey: String): Pair<String, String> {
        var publicKey = prefs.cryptoValue(publicKeyKey)
        var privateKey = prefs.cryptoValue(privateKeyKey)
        if (publicKey.isBlank() || privateKey.isBlank()) {
            val pair = generateEcKeyPair()
            publicKey = b64(pair.public.encoded)
            privateKey = b64(pair.private.encoded)
            prefs.putCryptoValue(publicKeyKey, publicKey)
            prefs.putCryptoValue(privateKeyKey, privateKey)
        }
        return publicKey to privateKey
    }

    private fun localEcdhIdentity(prefs: ChatPreferences): LocalEcdhIdentity {
        val deviceId = prefs.deviceId
        val (publicKey, privateKey) = generateStoredKeyPair(
            prefs,
            "direct_identity_ecdh_public_$deviceId",
            "direct_identity_ecdh_private_$deviceId"
        )
        val signature = DeviceIdentityManager.signWithLocalIdentity(prefs, fromB64(publicKey))
        prefs.putCryptoValue("direct_identity_ecdh_signature_$deviceId", signature)
        return LocalEcdhIdentity(publicKey, privateKey, signature)
    }

    private fun parseIdList(raw: String): MutableList<String> =
        runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.optString(index) }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()
        }.getOrDefault(mutableListOf())

    private fun persistIdList(prefs: ChatPreferences, key: String, ids: List<String>) {
        val array = JSONArray()
        ids.distinct().forEach { array.put(it) }
        prefs.putCryptoValue(key, array.toString())
    }

    private fun ensureOneTimePreKeys(prefs: ChatPreferences): List<LocalDirectOneTimePreKey> {
        val deviceId = prefs.deviceId
        val idsKey = "direct_otk_ids_$deviceId"
        val ids = parseIdList(prefs.cryptoValue(idsKey))
            .filter { prefs.cryptoValue("direct_otk_private_${deviceId}_$it").isNotBlank() }
            .toMutableList()
        while (ids.size < ONE_TIME_PREKEY_MIN) {
            val id = randomId()
            val pair = generateEcKeyPair()
            prefs.putCryptoValue("direct_otk_public_${deviceId}_$id", b64(pair.public.encoded))
            prefs.putCryptoValue("direct_otk_private_${deviceId}_$id", b64(pair.private.encoded))
            ids.add(id)
        }
        persistIdList(prefs, idsKey, ids)
        return ids.mapNotNull { id ->
            val publicKey = prefs.cryptoValue("direct_otk_public_${deviceId}_$id")
            if (publicKey.isBlank()) return@mapNotNull null
            LocalDirectOneTimePreKey(
                id = id,
                publicKey = publicKey,
                signature = DeviceIdentityManager.signWithLocalIdentity(prefs, fromB64(publicKey))
            )
        }
    }

    fun ensureLocalPreKey(prefs: ChatPreferences): LocalDirectPreKey {
        val deviceId = prefs.deviceId
        val publicKeyKey = "direct_prekey_public_$deviceId"
        val privateKeyKey = "direct_prekey_private_$deviceId"
        val signatureKey = "direct_prekey_signature_$deviceId"
        val identity = localEcdhIdentity(prefs)
        val (publicKey, _) = generateStoredKeyPair(prefs, publicKeyKey, privateKeyKey)
        val signature = DeviceIdentityManager.signWithLocalIdentity(prefs, fromB64(publicKey)).also {
            prefs.putCryptoValue(signatureKey, it)
        }
        return LocalDirectPreKey(
            deviceId = deviceId,
            keyAlg = KEY_ALG,
            identityEcdhPublic = identity.publicKey,
            identityEcdhSignature = identity.signature,
            signedPrekeyPublic = publicKey,
            signedPrekeySignature = signature,
            oneTimePreKeys = ensureOneTimePreKeys(prefs)
        )
    }

    private fun localPreKeyPrivate(prefs: ChatPreferences): PrivateKey {
        ensureLocalPreKey(prefs)
        return privateKeyFromB64(prefs.cryptoValue("direct_prekey_private_${prefs.deviceId}"))
    }

    private fun localIdentityEcdhPrivate(prefs: ChatPreferences): PrivateKey {
        localEcdhIdentity(prefs)
        return privateKeyFromB64(prefs.cryptoValue("direct_identity_ecdh_private_${prefs.deviceId}"))
    }

    fun localIdentityEcdhPublic(prefs: ChatPreferences): String =
        localEcdhIdentity(prefs).publicKey

    fun ecdhWithLocalIdentity(prefs: ChatPreferences, peerPublicKey: String): ByteArray =
        ecdh(localIdentityEcdhPrivate(prefs), publicKeyFromB64(peerPublicKey))

    fun ephemeralEcdh(peerPublicKey: String): Pair<String, ByteArray> {
        val pair = generateEcKeyPair()
        return b64(pair.public.encoded) to ecdh(pair.private, publicKeyFromB64(peerPublicKey))
    }

    private fun localOneTimePreKeyPrivate(prefs: ChatPreferences, id: String): PrivateKey? {
        if (id.isBlank()) return null
        val raw = prefs.cryptoValue("direct_otk_private_${prefs.deviceId}_$id")
        return raw.takeIf { it.isNotBlank() }?.let(::privateKeyFromB64)
    }

    private fun supportsV2(bundle: DirectPreKeyBundle): Boolean =
        bundle.prekeyAlg == KEY_ALG &&
            bundle.identityEcdhPublic.isNotBlank() &&
            bundle.identityEcdhSignature.isNotBlank()

    private fun verifyBundleV1(bundle: DirectPreKeyBundle) {
        val isValid = (bundle.prekeyAlg == LEGACY_KEY_ALG || bundle.prekeyAlg == KEY_ALG) &&
            bundle.signedPrekeyPublic.isNotBlank() &&
            bundle.identityPublicKey.isNotBlank() &&
            DeviceIdentityManager.verifySignature(
                bundle.identityPublicKey,
                fromB64(bundle.signedPrekeyPublic),
                bundle.signedPrekeySignature
            )
        require(isValid) { "bad_direct_prekey_signature" }
    }

    private fun verifyBundleV2(bundle: DirectPreKeyBundle) {
        val signedPrekeyValid = DeviceIdentityManager.verifySignature(
            bundle.identityPublicKey,
            fromB64(bundle.signedPrekeyPublic),
            bundle.signedPrekeySignature
        )
        val identityEcdhValid = DeviceIdentityManager.verifySignature(
            bundle.identityPublicKey,
            fromB64(bundle.identityEcdhPublic),
            bundle.identityEcdhSignature
        )
        val oneTimePrekeyValid = bundle.oneTimePrekeyPublic.isBlank() ||
            DeviceIdentityManager.verifySignature(
                bundle.identityPublicKey,
                fromB64(bundle.oneTimePrekeyPublic),
                bundle.oneTimePrekeySignature
            )
        require(
            supportsV2(bundle) &&
                bundle.signedPrekeyPublic.isNotBlank() &&
                signedPrekeyValid &&
                identityEcdhValid &&
                oneTimePrekeyValid
        ) { "bad_direct_prekey_signature" }
    }

    private fun rootInfoV1(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        ephemeralPublic: String,
        recipientPrekeyPublic: String,
    ): String =
        "familychat:direct:v1:root|conversation=$conversationId|from=$senderDeviceId|to=$recipientDeviceId|eph=$ephemeralPublic|spk=$recipientPrekeyPublic"

    private fun chainInfoV1(rootInfo: String): String = "$rootInfo|chain"

    private fun aadV1(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        ephemeralPublic: String,
        counter: Long,
    ): String =
        "familychat:direct:v1:aad|conversation=$conversationId|from=$senderDeviceId|to=$recipientDeviceId|eph=$ephemeralPublic|n=$counter"

    private fun sendStateKey(conversationId: Long, recipientDeviceId: String): String =
        "direct_send_${conversationId}_$recipientDeviceId"

    private fun receiveStateKey(conversationId: Long, senderDeviceId: String, ephemeralPublic: String): String =
        "direct_recv_${conversationId}_${senderDeviceId}_${sha256Hex(ephemeralPublic)}"

    private fun messageKeyName(prefix: String, conversationId: Long, peerDeviceId: String, ephemeralPublic: String, counter: Long): String =
        "${prefix}_${conversationId}_${peerDeviceId}_${sha256Hex(ephemeralPublic)}_$counter"

    private fun decodeState(raw: String): JSONObject? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun bundleKey(conversationId: Long): String = "direct_peer_bundle_v2_$conversationId"

    fun rememberRecipientBundle(prefs: ChatPreferences, conversationId: Long, bundle: DirectPreKeyBundle) {
        prefs.putCryptoValue(
            bundleKey(conversationId),
            JSONObject()
                .put("user_code", bundle.userCode)
                .put("username", bundle.username)
                .put("device_id", bundle.deviceId)
                .put("device_name", bundle.deviceName)
                .put("identity_key_alg", bundle.identityKeyAlg)
                .put("identity_public_key", bundle.identityPublicKey)
                .put("identity_fingerprint", bundle.identityFingerprint)
                .put("prekey_alg", bundle.prekeyAlg)
                .put("identity_ecdh_public", bundle.identityEcdhPublic)
                .put("identity_ecdh_signature", bundle.identityEcdhSignature)
                .put("signed_prekey_public", bundle.signedPrekeyPublic)
                .put("signed_prekey_signature", bundle.signedPrekeySignature)
                .put("one_time_prekey_id", bundle.oneTimePrekeyId)
                .put("one_time_prekey_public", bundle.oneTimePrekeyPublic)
                .put("one_time_prekey_signature", bundle.oneTimePrekeySignature)
                .put("updated_at", bundle.updatedAt)
                .toString()
        )
    }

    fun cachedRecipientBundle(prefs: ChatPreferences, conversationId: Long): DirectPreKeyBundle? =
        decodeState(prefs.cryptoValue(bundleKey(conversationId)))?.let(::bundleFromJson)

    fun adoptRecipientBundle(
        prefs: ChatPreferences,
        conversationId: Long,
        bundle: DirectPreKeyBundle,
    ): Boolean {
        val previous = cachedRecipientBundle(prefs, conversationId)
        val changed = previous != null && (
            previous.deviceId != bundle.deviceId ||
                previous.identityFingerprint != bundle.identityFingerprint ||
                previous.identityPublicKey != bundle.identityPublicKey ||
                previous.identityEcdhPublic != bundle.identityEcdhPublic ||
                previous.signedPrekeyPublic != bundle.signedPrekeyPublic
            )
        if (changed) {
            clearSessionState(prefs, conversationId, previous.deviceId)
            clearSessionState(prefs, conversationId, bundle.deviceId)
        }
        rememberRecipientBundle(prefs, conversationId, bundle)
        return changed
    }

    fun invalidateRecipientSession(
        prefs: ChatPreferences,
        conversationId: Long,
        affectedDeviceId: String = "",
    ) {
        val cachedDeviceId = cachedRecipientBundle(prefs, conversationId)?.deviceId.orEmpty()
        cachedDeviceId.takeIf { it.isNotBlank() }?.let {
            clearSessionState(prefs, conversationId, it)
        }
        affectedDeviceId.takeIf { it.isNotBlank() && it != cachedDeviceId }?.let {
            clearSessionState(prefs, conversationId, it)
        }
        prefs.removeCryptoValue(bundleKey(conversationId))
    }

    private fun clearSessionState(
        prefs: ChatPreferences,
        conversationId: Long,
        peerDeviceId: String,
    ) {
        prefs.removeCryptoValuesWithPrefix(sessionStatePrefix(conversationId, peerDeviceId))
        prefs.removeCryptoValue(activeSendSessionKey(conversationId, peerDeviceId))
        prefs.removeCryptoValue(legacySessionStateKey(conversationId, peerDeviceId))
        prefs.removeCryptoValue(sendStateKey(conversationId, peerDeviceId))
    }

    private fun bundleFromJson(item: JSONObject): DirectPreKeyBundle =
        DirectPreKeyBundle(
            userCode = item.optString("user_code"),
            username = item.optString("username"),
            deviceId = item.optString("device_id"),
            deviceName = item.optString("device_name"),
            identityKeyAlg = item.optString("identity_key_alg"),
            identityPublicKey = item.optString("identity_public_key"),
            identityFingerprint = item.optString("identity_fingerprint"),
            prekeyAlg = item.optString("prekey_alg"),
            identityEcdhPublic = item.optString("identity_ecdh_public"),
            identityEcdhSignature = item.optString("identity_ecdh_signature"),
            signedPrekeyPublic = item.optString("signed_prekey_public"),
            signedPrekeySignature = item.optString("signed_prekey_signature"),
            oneTimePrekeyId = item.optString("one_time_prekey_id"),
            oneTimePrekeyPublic = item.optString("one_time_prekey_public"),
            oneTimePrekeySignature = item.optString("one_time_prekey_signature"),
            updatedAt = item.optLong("updated_at", 0L)
        )

    private fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun kdfRoot(rootKey: ByteArray, dhSecret: ByteArray): Pair<ByteArray, ByteArray> {
        return DirectRatchetKdf.rootStep(rootKey, dhSecret)
    }

    private fun x3dhInfo(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        senderIdentityEcdhPublic: String,
        recipientIdentityEcdhPublic: String,
        recipientSignedPrekeyPublic: String,
        recipientOneTimePrekeyPublic: String,
    ): String = DirectRatchetKdf.x3dhInfo(
        conversationId,
        senderDeviceId,
        recipientDeviceId,
        senderIdentityEcdhPublic,
        recipientIdentityEcdhPublic,
        recipientSignedPrekeyPublic,
        recipientOneTimePrekeyPublic,
    )

    private fun legacySessionStateKey(conversationId: Long, peerDeviceId: String): String =
        "direct_session_v2_${conversationId}_$peerDeviceId"

    private fun sessionStatePrefix(conversationId: Long, peerDeviceId: String): String =
        "direct_session_v2_${conversationId}_${peerDeviceId}_"

    private fun sessionStateKey(conversationId: Long, peerDeviceId: String, sessionId: String): String =
        "direct_session_v2_${conversationId}_${peerDeviceId}_$sessionId"

    private fun activeSendSessionKey(conversationId: Long, peerDeviceId: String): String =
        "direct_active_send_session_v2_${conversationId}_$peerDeviceId"

    private fun storeSessionState(
        prefs: ChatPreferences,
        conversationId: Long,
        peerDeviceId: String,
        state: JSONObject,
    ) {
        val sessionId = state.optString("session_id")
        if (sessionId.isNotBlank()) {
            prefs.putCryptoValue(sessionStateKey(conversationId, peerDeviceId, sessionId), state.toString())
        }
    }

    private fun loadSessionState(
        prefs: ChatPreferences,
        conversationId: Long,
        peerDeviceId: String,
        sessionId: String,
    ): JSONObject? {
        decodeState(prefs.cryptoValue(sessionStateKey(conversationId, peerDeviceId, sessionId)))?.let {
            return it
        }
        val legacy = decodeState(prefs.cryptoValue(legacySessionStateKey(conversationId, peerDeviceId)))
            ?.takeIf {
                it.optString("scheme") == SCHEME_V2 &&
                    it.optString("peer_device_id") == peerDeviceId &&
                    it.optString("session_id") == sessionId
            }
        if (legacy != null) {
            storeSessionState(prefs, conversationId, peerDeviceId, legacy)
        }
        return legacy
    }

    private fun loadActiveSendSession(
        prefs: ChatPreferences,
        conversationId: Long,
        peerDeviceId: String,
    ): JSONObject? {
        val activeSessionId = prefs.cryptoValue(activeSendSessionKey(conversationId, peerDeviceId))
        if (activeSessionId.isNotBlank()) {
            loadSessionState(prefs, conversationId, peerDeviceId, activeSessionId)?.let { return it }
        }
        return null
    }

    private fun messageKeyNameV2(prefix: String, conversationId: Long, peerDeviceId: String, sessionId: String, dhPublic: String, counter: Long): String =
        "${prefix}_${conversationId}_${peerDeviceId}_${sessionId}_${sha256Hex(dhPublic)}_$counter"

    private fun aadV2(
        conversationId: Long,
        sessionId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        dhPublic: String,
        previousSendCount: Long,
        counter: Long,
    ): String = DirectRatchetKdf.aad(
        conversationId,
        sessionId,
        senderDeviceId,
        recipientDeviceId,
        dhPublic,
        previousSendCount,
        counter,
    )

    private fun deriveMessageKey(chainKey: ByteArray): ByteArray =
        DirectRatchetKdf.messageKey(chainKey)

    private fun nextChainKey(chainKey: ByteArray): ByteArray =
        DirectRatchetKdf.nextChainKey(chainKey)

    private fun createInitiatorSession(prefs: ChatPreferences, conversationId: Long, recipient: DirectPreKeyBundle): JSONObject {
        verifyBundleV2(recipient)
        val senderDeviceId = prefs.deviceId
        val senderIdentity = localEcdhIdentity(prefs)
        val senderIdentityPrivate = privateKeyFromB64(senderIdentity.privateKey)
        val x3dhEphemeral = generateEcKeyPair()
        val ratchet = generateEcKeyPair()
        val recipientIdentityPublic = publicKeyFromB64(recipient.identityEcdhPublic)
        val recipientSignedPrekeyPublic = publicKeyFromB64(recipient.signedPrekeyPublic)
        val recipientOneTimePrekeyPublic = recipient.oneTimePrekeyPublic.takeIf { it.isNotBlank() }?.let(::publicKeyFromB64)
        val material = concat(
            ecdh(senderIdentityPrivate, recipientSignedPrekeyPublic),
            ecdh(x3dhEphemeral.private, recipientIdentityPublic),
            ecdh(x3dhEphemeral.private, recipientSignedPrekeyPublic),
            recipientOneTimePrekeyPublic?.let { ecdh(x3dhEphemeral.private, it) } ?: ByteArray(0)
        )
        val x3dhEphemeralPublic = b64(x3dhEphemeral.public.encoded)
        val rootKey = DirectRatchetKdf.initialRoot(
            material,
            x3dhInfo(
                conversationId,
                senderDeviceId,
                recipient.deviceId,
                senderIdentity.publicKey,
                recipient.identityEcdhPublic,
                recipient.signedPrekeyPublic,
                recipient.oneTimePrekeyPublic
            )
        )
        val (nextRoot, sendChain) = kdfRoot(rootKey, ecdh(ratchet.private, recipientSignedPrekeyPublic))
        val sessionId = sha256Hex(
            "familychat:direct:v2:session|$conversationId|$senderDeviceId|${recipient.deviceId}|${senderIdentity.publicKey}|${recipient.identityEcdhPublic}|$x3dhEphemeralPublic|${recipient.oneTimePrekeyId}"
        ).take(32)
        return JSONObject()
            .put("scheme", SCHEME_V2)
            .put("session_id", sessionId)
            .put("peer_device_id", recipient.deviceId)
            .put("root_key", b64(nextRoot))
            .put("send_chain_key", b64(sendChain))
            .put("recv_chain_key", "")
            .put("send_count", 0L)
            .put("recv_count", 0L)
            .put("previous_send_count", 0L)
            .put("local_dh_public", b64(ratchet.public.encoded))
            .put("local_dh_private", b64(ratchet.private.encoded))
            .put("remote_dh_public", recipient.signedPrekeyPublic)
            .put("initial", true)
            .put("sender_identity_ecdh_public", senderIdentity.publicKey)
            .put("sender_identity_ecdh_signature", senderIdentity.signature)
            .put("x3dh_ephemeral_public", x3dhEphemeralPublic)
            .put("recipient_identity_ecdh_public", recipient.identityEcdhPublic)
            .put("recipient_signed_prekey_public", recipient.signedPrekeyPublic)
            .put("recipient_one_time_prekey_id", recipient.oneTimePrekeyId)
            .put("recipient_one_time_prekey_public", recipient.oneTimePrekeyPublic)
    }

    private fun x3dhRecipientRoot(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): ByteArray {
        val senderDeviceId = payload.getString("sender_device_id")
        val senderIdentityEcdhPublic = payload.getString("sender_identity_ecdh_public")
        val recipientIdentityEcdhPublic = payload.optString("recipient_identity_ecdh_public")
        val recipientSignedPrekeyPublic = payload.getString("recipient_signed_prekey_public")
        val recipientOneTimePrekeyId = payload.optString("recipient_one_time_prekey_id")
        val recipientOneTimePrekeyPublic = payload.optString("recipient_one_time_prekey_public")
        val x3dhEphemeralPublic = payload.getString("x3dh_ephemeral_public")
        val senderIdentityPublic = publicKeyFromB64(senderIdentityEcdhPublic)
        val x3dhEphemeral = publicKeyFromB64(x3dhEphemeralPublic)
        val signedPrekeyPrivate = localPreKeyPrivate(prefs)
        val identityPrivate = localIdentityEcdhPrivate(prefs)
        val oneTimePrivate = localOneTimePreKeyPrivate(prefs, recipientOneTimePrekeyId)
        val material = concat(
            ecdh(signedPrekeyPrivate, senderIdentityPublic),
            ecdh(identityPrivate, x3dhEphemeral),
            ecdh(signedPrekeyPrivate, x3dhEphemeral),
            oneTimePrivate?.let { ecdh(it, x3dhEphemeral) } ?: ByteArray(0)
        )
        return DirectRatchetKdf.initialRoot(
            material,
            x3dhInfo(
                message.conversationId,
                senderDeviceId,
                prefs.deviceId,
                senderIdentityEcdhPublic,
                recipientIdentityEcdhPublic,
                recipientSignedPrekeyPublic,
                recipientOneTimePrekeyPublic
            )
        )
    }

    private fun createReceiverSession(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): JSONObject {
        require(payload.optBoolean("init", false)) { "direct_session_required" }
        val senderDeviceId = payload.getString("sender_device_id")
        val sessionId = payload.getString("session_id")
        val senderRatchetPublic = payload.getString("dh_pub")
        val rootKey = x3dhRecipientRoot(prefs, message, payload)
        val signedPrekeyPrivate = localPreKeyPrivate(prefs)
        val (rootAfterRecv, recvChain) = kdfRoot(rootKey, ecdh(signedPrekeyPrivate, publicKeyFromB64(senderRatchetPublic)))
        val replyRatchet = generateEcKeyPair()
        val (rootAfterSend, sendChain) = kdfRoot(rootAfterRecv, ecdh(replyRatchet.private, publicKeyFromB64(senderRatchetPublic)))
        return JSONObject()
            .put("scheme", SCHEME_V2)
            .put("session_id", sessionId)
            .put("peer_device_id", senderDeviceId)
            .put("root_key", b64(rootAfterSend))
            .put("send_chain_key", b64(sendChain))
            .put("recv_chain_key", b64(recvChain))
            .put("send_count", 0L)
            .put("recv_count", 0L)
            .put("previous_send_count", 0L)
            .put("local_dh_public", b64(replyRatchet.public.encoded))
            .put("local_dh_private", b64(replyRatchet.private.encoded))
            .put("remote_dh_public", senderRatchetPublic)
            .put("initial", false)
    }

    private fun ratchetForNewRemote(state: JSONObject, newRemoteDhPublic: String) {
        val localPrivate = privateKeyFromB64(state.getString("local_dh_private"))
        val (rootAfterRecv, recvChain) = kdfRoot(
            fromB64(state.getString("root_key")),
            ecdh(localPrivate, publicKeyFromB64(newRemoteDhPublic))
        )
        val newLocal = generateEcKeyPair()
        val (rootAfterSend, sendChain) = kdfRoot(rootAfterRecv, ecdh(newLocal.private, publicKeyFromB64(newRemoteDhPublic)))
        state
            .put("root_key", b64(rootAfterSend))
            .put("recv_chain_key", b64(recvChain))
            .put("send_chain_key", b64(sendChain))
            .put("previous_send_count", state.optLong("send_count", 0L))
            .put("send_count", 0L)
            .put("recv_count", 0L)
            .put("local_dh_public", b64(newLocal.public.encoded))
            .put("local_dh_private", b64(newLocal.private.encoded))
            .put("remote_dh_public", newRemoteDhPublic)
            .put("initial", false)
    }

    fun encryptText(
        prefs: ChatPreferences,
        conversationId: Long,
        recipient: DirectPreKeyBundle,
        plaintext: String,
    ): String {
        if (supportsV2(recipient)) {
            return encryptTextV2(prefs, conversationId, recipient, plaintext)
        }
        return encryptTextV1(prefs, conversationId, recipient, plaintext)
    }

    /** Encrypts one content key independently for every trusted recipient device. */
    fun encryptTextForDevices(
        prefs: ChatPreferences,
        conversationId: Long,
        recipients: List<DirectPreKeyBundle>,
        plaintext: String,
    ): String {
        val uniqueRecipients = recipients
            .filter { it.deviceId.isNotBlank() && it.deviceId != prefs.deviceId }
            .distinctBy(DirectPreKeyBundle::deviceId)
        require(uniqueRecipients.isNotEmpty()) { "peer_prekey_unavailable" }

        val contentId = ByteArray(16).also(random::nextBytes).let(::b64)
        val contentKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        val contentIv = ByteArray(12).also(random::nextBytes)
        val contentAad = multiContentAad(
            conversationId = conversationId,
            senderDeviceId = prefs.deviceId,
            contentId = contentId,
        )
        val contentCiphertext = initCipher(
            Cipher.ENCRYPT_MODE,
            contentKey,
            contentIv,
            contentAad,
        ).doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val envelopes = JSONArray()
        uniqueRecipients.forEach { recipient ->
            val wrappedKey = encryptText(
                prefs = prefs,
                conversationId = conversationId,
                recipient = recipient,
                plaintext = JSONObject()
                    .put("v", 1)
                    .put("content_id", contentId)
                    .put("content_key", b64(contentKey))
                    .toString(),
            )
            envelopes.put(
                JSONObject()
                    .put("recipient_user_code", recipient.userCode)
                    .put("recipient_device_id", recipient.deviceId)
                    .put("payload", wrappedKey)
            )
        }
        return b64(
            JSONObject()
                .put("v", 3)
                .put("scheme", SCHEME_MULTI_V3)
                .put("sender_device_id", prefs.deviceId)
                .put("content_id", contentId)
                .put("iv", b64(contentIv))
                .put("ct", b64(contentCiphertext))
                .put("envelopes", envelopes)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun encryptTextV2(
        prefs: ChatPreferences,
        conversationId: Long,
        recipient: DirectPreKeyBundle,
        plaintext: String,
    ): String {
        val senderDeviceId = prefs.deviceId
        val state = loadActiveSendSession(prefs, conversationId, recipient.deviceId)?.takeIf {
            it.optString("scheme") == SCHEME_V2 &&
                it.optString("peer_device_id") == recipient.deviceId &&
                it.optString("send_chain_key").isNotBlank()
        } ?: createInitiatorSession(prefs, conversationId, recipient)
        val sessionId = state.getString("session_id")
        val counter = state.optLong("send_count", 0L)
        val previousSendCount = state.optLong("previous_send_count", 0L)
        val dhPublic = state.getString("local_dh_public")
        val chainKey = fromB64(state.getString("send_chain_key"))
        val messageKey = deriveMessageKey(chainKey)
        val nextChain = nextChainKey(chainKey)
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = aadV2(conversationId, sessionId, senderDeviceId, recipient.deviceId, dhPublic, previousSendCount, counter)
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, messageKey, iv, aad)
            .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        prefs.putMessageCryptoKey(
            messageKeyNameV2("direct_v2_sent_key", conversationId, recipient.deviceId, sessionId, dhPublic, counter),
            b64(messageKey)
        )
        state.put("send_chain_key", b64(nextChain)).put("send_count", counter + 1L)
        storeSessionState(prefs, conversationId, recipient.deviceId, state)
        prefs.putCryptoValue(activeSendSessionKey(conversationId, recipient.deviceId), sessionId)
        rememberRecipientBundle(prefs, conversationId, recipient)

        val payload = JSONObject()
            .put("v", 2)
            .put("scheme", SCHEME_V2)
            .put("session_id", sessionId)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_device_id", recipient.deviceId)
            .put("dh_pub", dhPublic)
            .put("pn", previousSendCount)
            .put("n", counter)
            .put("iv", b64(iv))
            .put("ct", b64(cipherText))
        if (state.optBoolean("initial", false)) {
            payload
                .put("init", true)
                .put("sender_identity_ecdh_public", state.getString("sender_identity_ecdh_public"))
                .put("sender_identity_ecdh_signature", state.getString("sender_identity_ecdh_signature"))
                .put("x3dh_ephemeral_public", state.getString("x3dh_ephemeral_public"))
                .put("recipient_identity_ecdh_public", state.getString("recipient_identity_ecdh_public"))
                .put("recipient_signed_prekey_public", state.getString("recipient_signed_prekey_public"))
                .put("recipient_one_time_prekey_id", state.optString("recipient_one_time_prekey_id"))
                .put("recipient_one_time_prekey_public", state.optString("recipient_one_time_prekey_public"))
        }
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun encryptTextV1(
        prefs: ChatPreferences,
        conversationId: Long,
        recipient: DirectPreKeyBundle,
        plaintext: String,
    ): String {
        verifyBundleV1(recipient)
        val senderDeviceId = prefs.deviceId
        val stateKey = sendStateKey(conversationId, recipient.deviceId)
        val existing = decodeState(prefs.cryptoValue(stateKey))
        val state = if (
            existing == null ||
            existing.optString("recipient_prekey_public") != recipient.signedPrekeyPublic ||
            existing.optString("recipient_device_id") != recipient.deviceId
        ) {
            val ephemeral = generateEcKeyPair()
            val ephemeralPublic = b64(ephemeral.public.encoded)
            val shared = ecdh(ephemeral.private, publicKeyFromB64(recipient.signedPrekeyPublic))
            val rootInfo = rootInfoV1(
                conversationId,
                senderDeviceId,
                recipient.deviceId,
                ephemeralPublic,
                recipient.signedPrekeyPublic
            )
            JSONObject()
                .put("recipient_device_id", recipient.deviceId)
                .put("recipient_prekey_public", recipient.signedPrekeyPublic)
                .put("ephemeral_public", ephemeralPublic)
                .put("chain_key", b64(hkdfSha256(shared, chainInfoV1(rootInfo))))
                .put("counter", 0L)
        } else {
            existing
        }

        val counter = state.optLong("counter", 0L)
        val chainKey = fromB64(state.getString("chain_key"))
        val messageKey = hkdfSha256(chainKey, "familychat:direct:v1:message-key")
        val nextChainKey = hkdfSha256(chainKey, "familychat:direct:v1:next-chain")
        val ephemeralPublic = state.getString("ephemeral_public")
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = aadV1(conversationId, senderDeviceId, recipient.deviceId, ephemeralPublic, counter)
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, messageKey, iv, aad)
            .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        prefs.putMessageCryptoKey(
            messageKeyName("direct_sent_key", conversationId, recipient.deviceId, ephemeralPublic, counter),
            b64(messageKey)
        )
        state.put("chain_key", b64(nextChainKey)).put("counter", counter + 1L)
        prefs.putCryptoValue(stateKey, state.toString())

        val payload = JSONObject()
            .put("v", 1)
            .put("scheme", SCHEME_V1)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_device_id", recipient.deviceId)
            .put("ephemeral_public", ephemeralPublic)
            .put("recipient_prekey_public", state.getString("recipient_prekey_public"))
            .put("counter", counter)
            .put("iv", b64(iv))
            .put("ct", b64(cipherText))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptText(prefs: ChatPreferences, message: ChatMessage): String {
        val payload = payloadJson(message.payload)
        return when (payload.optString("scheme")) {
            SCHEME_MULTI_V3 -> decryptMultiDeviceText(prefs, message, payload)
            SCHEME_V2 -> decryptTextV2(prefs, message, payload)
            SCHEME_V1 -> decryptTextV1(prefs, message, payload)
            else -> throw IllegalStateException("not_direct_ratchet_payload")
        }
    }

    private fun decryptMultiDeviceText(
        prefs: ChatPreferences,
        message: ChatMessage,
        payload: JSONObject,
    ): String {
        val envelopes = payload.optJSONArray("envelopes") ?: throw IllegalStateException("direct_envelope_missing")
        val senderDeviceId = payload.optString("sender_device_id")
        val selected = (0 until envelopes.length())
            .mapNotNull { envelopes.optJSONObject(it) }
            .firstOrNull { it.optString("recipient_device_id") == prefs.deviceId }
            ?: if (senderDeviceId == prefs.deviceId) envelopes.optJSONObject(0) else null
            ?: throw IllegalStateException("message_not_for_this_device")
        val wrappedPayload = selected.optString("payload")
        require(wrappedPayload.isNotBlank()) { "direct_envelope_missing" }
        val keyJson = JSONObject(decryptText(prefs, message.copy(payload = wrappedPayload)))
        val contentId = payload.getString("content_id")
        require(keyJson.optString("content_id") == contentId) { "direct_content_id_mismatch" }
        val contentKey = fromB64(keyJson.getString("content_key"))
        val aad = multiContentAad(message.conversationId, senderDeviceId, contentId)
        val plain = initCipher(
            Cipher.DECRYPT_MODE,
            contentKey,
            fromB64(payload.getString("iv")),
            aad,
        ).doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun multiContentAad(
        conversationId: Long,
        senderDeviceId: String,
        contentId: String,
    ): String =
        "familychat:direct:multi:v3|conversation=$conversationId|sender=$senderDeviceId|content=$contentId"

    fun decryptText(prefs: ChatPreferences, conversationId: Long, payloadText: String): String =
        decryptText(
            prefs,
            ChatMessage(
                id = 0L,
                conversationId = conversationId,
                ts = 0L,
                expiresAt = 0L,
                username = "",
                color = "",
                kind = "text",
                payload = payloadText,
                e2ee = true,
                replyTo = null,
                mentions = emptyList()
            )
        )

    private fun decryptTextV2(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): String {
        val senderDeviceId = payload.getString("sender_device_id")
        val recipientDeviceId = payload.getString("recipient_device_id")
        val sessionId = payload.getString("session_id")
        val dhPublic = payload.getString("dh_pub")
        val counter = payload.optLong("n", 0L)
        val previousSendCount = payload.optLong("pn", 0L)
        val aad = aadV2(message.conversationId, sessionId, senderDeviceId, recipientDeviceId, dhPublic, previousSendCount, counter)
        val messageKey = if (senderDeviceId == prefs.deviceId) {
            prefs.messageCryptoKey(
                messageKeyNameV2("direct_v2_sent_key", message.conversationId, recipientDeviceId, sessionId, dhPublic, counter),
                message.expiresAt,
            )
                .takeIf { it.isNotBlank() }
                ?.let(::fromB64)
                ?: throw IllegalStateException("direct_message_key_unavailable")
        } else {
            require(recipientDeviceId == prefs.deviceId) { "message_not_for_this_device" }
            val cachedKey = prefs.messageCryptoKey(
                messageKeyNameV2("direct_v2_recv_key", message.conversationId, senderDeviceId, sessionId, dhPublic, counter),
                message.expiresAt,
            )
            if (cachedKey.isNotBlank()) {
                fromB64(cachedKey)
            } else {
                val state = loadSessionState(prefs, message.conversationId, senderDeviceId, sessionId)
                    ?: createReceiverSession(prefs, message, payload)
                if (state.optString("remote_dh_public") != dhPublic) {
                    val oldRecvChain = state.optString("recv_chain_key")
                    val oldRecvCount = state.optLong("recv_count", 0L)
                    val oldRemoteDh = state.optString("remote_dh_public")
                    var chain = oldRecvChain.takeIf { it.isNotBlank() }?.let(::fromB64)
                    var skippedCounter = oldRecvCount
                    while (chain != null && oldRemoteDh.isNotBlank() && skippedCounter < previousSendCount && skippedCounter - oldRecvCount <= MAX_RATCHET_STEPS) {
                        val skippedKey = deriveMessageKey(chain)
                        prefs.putMessageCryptoKey(
                            messageKeyNameV2("direct_v2_recv_key", message.conversationId, senderDeviceId, sessionId, oldRemoteDh, skippedCounter),
                            b64(skippedKey),
                            message.expiresAt,
                        )
                        chain = nextChainKey(chain)
                        skippedCounter += 1L
                    }
                    ratchetForNewRemote(state, dhPublic)
                }
                var currentCounter = state.optLong("recv_count", 0L)
                require(counter >= currentCounter && counter - currentCounter <= MAX_RATCHET_STEPS) {
                    "direct_ratchet_counter_out_of_range"
                }
                var chainKey = fromB64(state.getString("recv_chain_key"))
                var selectedKey: ByteArray? = null
                while (currentCounter <= counter) {
                    val key = deriveMessageKey(chainKey)
                    prefs.putMessageCryptoKey(
                        messageKeyNameV2("direct_v2_recv_key", message.conversationId, senderDeviceId, sessionId, dhPublic, currentCounter),
                        b64(key),
                        message.expiresAt,
                    )
                    if (currentCounter == counter) selectedKey = key
                    chainKey = nextChainKey(chainKey)
                    currentCounter += 1L
                }
                state.put("recv_chain_key", b64(chainKey)).put("recv_count", currentCounter)
                storeSessionState(prefs, message.conversationId, senderDeviceId, state)
                selectedKey ?: throw IllegalStateException("direct_message_key_unavailable")
            }
        }

        val plain = initCipher(
            Cipher.DECRYPT_MODE,
            messageKey,
            fromB64(payload.getString("iv")),
            aad
        ).doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun decryptTextV1(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): String {
        val senderDeviceId = payload.getString("sender_device_id")
        val recipientDeviceId = payload.getString("recipient_device_id")
        val ephemeralPublic = payload.getString("ephemeral_public")
        val counter = payload.optLong("counter", 0L)
        val aad = aadV1(message.conversationId, senderDeviceId, recipientDeviceId, ephemeralPublic, counter)
        val messageKey = if (senderDeviceId == prefs.deviceId) {
            prefs.messageCryptoKey(
                messageKeyName("direct_sent_key", message.conversationId, recipientDeviceId, ephemeralPublic, counter),
                message.expiresAt,
            )
                .takeIf { it.isNotBlank() }
                ?.let(::fromB64)
                ?: throw IllegalStateException("direct_message_key_unavailable")
        } else {
            require(recipientDeviceId == prefs.deviceId) { "message_not_for_this_device" }
            val cachedKey = prefs.messageCryptoKey(
                messageKeyName("direct_recv_key", message.conversationId, senderDeviceId, ephemeralPublic, counter),
                message.expiresAt,
            )
            if (cachedKey.isNotBlank()) {
                fromB64(cachedKey)
            } else {
                val stateKey = receiveStateKey(message.conversationId, senderDeviceId, ephemeralPublic)
                val state = decodeState(prefs.cryptoValue(stateKey)) ?: run {
                    val recipientPrekeyPublic = payload.getString("recipient_prekey_public")
                    val shared = ecdh(localPreKeyPrivate(prefs), publicKeyFromB64(ephemeralPublic))
                    val rootInfo = rootInfoV1(
                        message.conversationId,
                        senderDeviceId,
                        recipientDeviceId,
                        ephemeralPublic,
                        recipientPrekeyPublic
                    )
                    JSONObject()
                        .put("sender_device_id", senderDeviceId)
                        .put("ephemeral_public", ephemeralPublic)
                        .put("chain_key", b64(hkdfSha256(shared, chainInfoV1(rootInfo))))
                        .put("counter", 0L)
                }
                var currentCounter = state.optLong("counter", 0L)
                require(counter >= currentCounter && counter - currentCounter <= MAX_RATCHET_STEPS) {
                    "direct_ratchet_counter_out_of_range"
                }
                var chainKey = fromB64(state.getString("chain_key"))
                var selectedKey: ByteArray? = null
                while (currentCounter <= counter) {
                    val key = hkdfSha256(chainKey, "familychat:direct:v1:message-key")
                    prefs.putMessageCryptoKey(
                        messageKeyName("direct_recv_key", message.conversationId, senderDeviceId, ephemeralPublic, currentCounter),
                        b64(key),
                        message.expiresAt,
                    )
                    if (currentCounter == counter) selectedKey = key
                    chainKey = hkdfSha256(chainKey, "familychat:direct:v1:next-chain")
                    currentCounter += 1L
                }
                state.put("chain_key", b64(chainKey)).put("counter", currentCounter)
                prefs.putCryptoValue(stateKey, state.toString())
                selectedKey ?: throw IllegalStateException("direct_message_key_unavailable")
            }
        }

        val plain = initCipher(
            Cipher.DECRYPT_MODE,
            messageKey,
            fromB64(payload.getString("iv")),
            aad
        ).doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
    }

    fun forgetMessageKey(prefs: ChatPreferences, message: ChatMessage): Boolean {
        if (message.id <= 0L || !isDirectPayload(message.payload)) return false
        val payload = runCatching { payloadJson(message.payload) }.getOrNull() ?: return false
        if (payload.optString("scheme") == SCHEME_MULTI_V3) {
            val envelopes = payload.optJSONArray("envelopes") ?: return false
            var removed = false
            for (index in 0 until envelopes.length()) {
                val wrapped = envelopes.optJSONObject(index)?.optString("payload").orEmpty()
                if (wrapped.isNotBlank()) {
                    removed = forgetMessageKey(prefs, message.copy(payload = wrapped)) || removed
                }
            }
            return removed
        }
        val senderDeviceId = payload.optString("sender_device_id")
        val recipientDeviceId = payload.optString("recipient_device_id")
        val storageKey = when (payload.optString("scheme")) {
            SCHEME_V2 -> {
                val sessionId = payload.optString("session_id")
                val dhPublic = payload.optString("dh_pub")
                val counter = payload.optLong("n", 0L)
                if (senderDeviceId == prefs.deviceId) {
                    messageKeyNameV2(
                        "direct_v2_sent_key",
                        message.conversationId,
                        recipientDeviceId,
                        sessionId,
                        dhPublic,
                        counter,
                    )
                } else {
                    if (recipientDeviceId != prefs.deviceId) return false
                    messageKeyNameV2(
                        "direct_v2_recv_key",
                        message.conversationId,
                        senderDeviceId,
                        sessionId,
                        dhPublic,
                        counter,
                    )
                }
            }
            SCHEME_V1 -> {
                val ephemeralPublic = payload.optString("ephemeral_public")
                val counter = payload.optLong("counter", 0L)
                if (senderDeviceId == prefs.deviceId) {
                    messageKeyName(
                        "direct_sent_key",
                        message.conversationId,
                        recipientDeviceId,
                        ephemeralPublic,
                        counter,
                    )
                } else {
                    if (recipientDeviceId != prefs.deviceId) return false
                    messageKeyName(
                        "direct_recv_key",
                        message.conversationId,
                        senderDeviceId,
                        ephemeralPublic,
                        counter,
                    )
                }
            }
            else -> return false
        }
        prefs.removeMessageCryptoKey(storageKey)
        return true
    }
}

object GroupSenderKeyCrypto {
    private const val SCHEME = "familychat-group-sender-v1"
    private const val ENVELOPE_SCHEME = "familychat-group-sender-envelope-v1"
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        return CryptoPrimitives.hkdfSha256(inputKeyMaterial, info = info, length = length)
    }

    private fun initCipher(mode: Int, keyBytes: ByteArray, iv: ByteArray, aad: String): Cipher =
        CryptoPrimitives.aesGcmCipher(mode, keyBytes, iv, aad)

    private fun keyId(): String =
        ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun senderKeyName(conversationId: Long, senderUserCode: String, deviceId: String, epoch: Long, keyId: String): String =
        "group_sender_key_${conversationId}_${senderUserCode}_${deviceId}_${epoch}_$keyId"

    private fun ownKeyRefName(conversationId: Long, epoch: Long): String =
        "group_sender_own_ref_${conversationId}_$epoch"

    private fun sendCounterName(conversationId: Long, epoch: Long, keyId: String): String =
        "group_sender_counter_${conversationId}_${epoch}_$keyId"

    private fun aad(
        conversationId: Long,
        senderUserCode: String,
        deviceId: String,
        epoch: Long,
        keyId: String,
        counter: Long,
    ): String = GroupSenderKeyKdf.aad(
        conversationId,
        senderUserCode,
        deviceId,
        epoch,
        keyId,
        counter,
    )

    private fun messageKey(senderKey: String, counter: Long): ByteArray =
        GroupSenderKeyKdf.messageKey(fromB64(senderKey), counter)

    private fun envelopeAad(
        conversationId: Long,
        senderUserCode: String,
        senderDeviceId: String,
        recipientUserCode: String,
        recipientDeviceId: String,
        epoch: Long,
        keyId: String,
        ephemeralPublic: String,
    ): String = GroupSenderKeyKdf.envelopeAad(
        conversationId,
        senderUserCode,
        senderDeviceId,
        recipientUserCode,
        recipientDeviceId,
        epoch,
        keyId,
        ephemeralPublic,
    )

    private fun envelopeKey(sharedSecret: ByteArray, aad: String): ByteArray =
        GroupSenderKeyKdf.envelopeKey(sharedSecret, aad)

    private fun envelopeSignatureData(
        conversationId: Long,
        senderUserCode: String,
        senderDeviceId: String,
        recipientUserCode: String,
        recipientDeviceId: String,
        epoch: Long,
        keyId: String,
        ephemeralPublic: String,
        iv: String,
        ct: String,
    ): ByteArray =
        "familychat:group-sender-envelope-sign:v1|conversation=$conversationId|sender=$senderUserCode|senderDevice=$senderDeviceId|recipient=$recipientUserCode|recipientDevice=$recipientDeviceId|epoch=$epoch|key=$keyId|eph=$ephemeralPublic|iv=$iv|ct=$ct"
            .toByteArray(StandardCharsets.UTF_8)

    fun isGroupPayload(payload: String): Boolean =
        runCatching {
            val json = JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))
            json.optString("scheme") == SCHEME
        }.getOrDefault(false)

    fun hasSenderKeyForPayload(
        prefs: ChatPreferences,
        conversationId: Long,
        payloadText: String,
    ): Boolean =
        runCatching {
            val payload = JSONObject(String(fromB64(payloadText), StandardCharsets.UTF_8))
            if (payload.optString("scheme") != SCHEME) return@runCatching false
            val senderUserCode = payload.getString("sender_user_code")
            val deviceId = payload.getString("device_id")
            val epoch = payload.optLong("epoch", 1L)
            val keyId = payload.getString("key_id")
            prefs.cryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, keyId)).isNotBlank()
        }.getOrDefault(false)

    private fun ensureLocalSenderKey(
        prefs: ChatPreferences,
        conversationId: Long,
        epoch: Long,
        senderUserCode: String,
    ): Pair<String, String> {
        val deviceId = prefs.deviceId
        val existingRef = runCatching { JSONObject(prefs.cryptoValue(ownKeyRefName(conversationId, epoch))) }.getOrNull()
        val existingKeyId = existingRef?.optString("key_id").orEmpty()
        val existingRaw = existingKeyId.takeIf { it.isNotBlank() }
            ?.let { prefs.cryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, it)) }
            .orEmpty()
        val keyId = existingKeyId.takeIf { existingRaw.isNotBlank() } ?: keyId()
        val senderKey = existingRaw.ifBlank { b64(ByteArray(KEY_BYTES).also(random::nextBytes)) }
        prefs.putCryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, keyId), senderKey)
        prefs.putCryptoValue(ownKeyRefName(conversationId, epoch), JSONObject().put("key_id", keyId).toString())
        return keyId to senderKey
    }

    private fun verifiedGroupDevice(device: GroupKeyDevice): Boolean =
        runCatching {
            device.identityPublicKey.isNotBlank() &&
                device.identityEcdhPublic.isNotBlank() &&
                device.identityEcdhSignature.isNotBlank() &&
                DeviceIdentityManager.verifySignature(
                    device.identityPublicKey,
                    fromB64(device.identityEcdhPublic),
                    device.identityEcdhSignature
                )
        }.getOrDefault(false)

    private fun encryptEnvelope(
        prefs: ChatPreferences,
        conversationId: Long,
        senderUserCode: String,
        senderUsername: String,
        epoch: Long,
        keyId: String,
        senderKey: String,
        recipient: GroupKeyDevice,
    ): GroupSenderKeyEnvelope? {
        if (!verifiedGroupDevice(recipient)) return null
        val senderDeviceId = prefs.deviceId
        val (ephemeralPublic, sharedSecret) = DirectMessageCrypto.ephemeralEcdh(recipient.identityEcdhPublic)
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = envelopeAad(
            conversationId,
            senderUserCode,
            senderDeviceId,
            recipient.userCode,
            recipient.deviceId,
            epoch,
            keyId,
            ephemeralPublic
        )
        val plain = JSONObject()
            .put("sender_key", senderKey)
            .put("sender_user_code", senderUserCode)
            .put("sender_username", senderUsername)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_user_code", recipient.userCode)
            .put("recipient_device_id", recipient.deviceId)
            .put("epoch", epoch)
            .put("key_id", keyId)
            .toString()
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, envelopeKey(sharedSecret, aad), iv, aad)
            .doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = b64(iv)
        val ctB64 = b64(cipherText)
        val signature = DeviceIdentityManager.signWithLocalIdentity(
            prefs,
            envelopeSignatureData(
                conversationId,
                senderUserCode,
                senderDeviceId,
                recipient.userCode,
                recipient.deviceId,
                epoch,
                keyId,
                ephemeralPublic,
                ivB64,
                ctB64
            )
        )
        val wrapped = JSONObject()
            .put("v", 1)
            .put("scheme", ENVELOPE_SCHEME)
            .put("conversation_id", conversationId)
            .put("sender_user_code", senderUserCode)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_user_code", recipient.userCode)
            .put("recipient_device_id", recipient.deviceId)
            .put("epoch", epoch)
            .put("key_id", keyId)
            .put("ephemeral_public", ephemeralPublic)
            .put("iv", ivB64)
            .put("ct", ctB64)
            .put("sig", signature)
            .toString()
        return GroupSenderKeyEnvelope(
            recipientUserCode = recipient.userCode,
            recipientDeviceId = recipient.deviceId,
            wrappedKey = b64(wrapped.toByteArray(StandardCharsets.UTF_8))
        )
    }

    fun buildSenderKeyUpload(
        prefs: ChatPreferences,
        conversationId: Long,
        epoch: Long,
        senderUserCode: String,
        senderUsername: String,
        recipientDevices: List<GroupKeyDevice>,
    ): LocalGroupSenderKey {
        DirectMessageCrypto.localIdentityEcdhPublic(prefs)
        val (keyId, senderKey) = ensureLocalSenderKey(prefs, conversationId, epoch, senderUserCode)
        val envelopes = recipientDevices
            .distinctBy { "${it.userCode}:${it.deviceId}" }
            .mapNotNull { device ->
                encryptEnvelope(
                    prefs = prefs,
                    conversationId = conversationId,
                    senderUserCode = senderUserCode,
                    senderUsername = senderUsername,
                    epoch = epoch,
                    keyId = keyId,
                    senderKey = senderKey,
                    recipient = device
                )
            }
        if (envelopes.isEmpty()) throw IllegalStateException("group_key_devices_unavailable")
        return LocalGroupSenderKey(conversationId, prefs.deviceId, epoch, keyId, envelopes)
    }

    fun importSenderKeys(prefs: ChatPreferences, items: List<GroupSenderKeyBundle>): Int {
        var imported = 0
        for (item in items) {
            val envelope = runCatching { JSONObject(String(fromB64(item.wrappedKey), StandardCharsets.UTF_8)) }.getOrNull() ?: continue
            if (envelope.optString("scheme") != ENVELOPE_SCHEME) continue
            val recipientDeviceId = envelope.optString("recipient_device_id")
            if (recipientDeviceId != prefs.deviceId) continue
            val senderUserCode = envelope.optString("sender_user_code")
            val senderDeviceId = envelope.optString("sender_device_id")
            val epoch = envelope.optLong("epoch", 1L)
            val keyId = envelope.optString("key_id")
            val ephemeralPublic = envelope.optString("ephemeral_public")
            val iv = envelope.optString("iv")
            val ct = envelope.optString("ct")
            val signature = envelope.optString("sig")
            if (
                senderUserCode != item.senderUserCode ||
                senderDeviceId != item.deviceId ||
                epoch != item.epoch ||
                keyId != item.keyId ||
                item.senderIdentityPublicKey.isBlank() ||
                ephemeralPublic.isBlank() ||
                iv.isBlank() ||
                ct.isBlank() ||
                signature.isBlank()
            ) continue
            val recipientUserCode = envelope.optString("recipient_user_code")
            val signatureOk = runCatching {
                DeviceIdentityManager.verifySignature(
                    item.senderIdentityPublicKey,
                    envelopeSignatureData(
                        item.conversationId,
                        senderUserCode,
                        senderDeviceId,
                        recipientUserCode,
                        recipientDeviceId,
                        epoch,
                        keyId,
                        ephemeralPublic,
                        iv,
                        ct
                    ),
                    signature
                )
            }.getOrDefault(false)
            if (!signatureOk) continue
            val plain = runCatching {
                val aad = envelopeAad(
                    item.conversationId,
                    senderUserCode,
                    senderDeviceId,
                    recipientUserCode,
                    recipientDeviceId,
                    epoch,
                    keyId,
                    ephemeralPublic
                )
                val sharedSecret = DirectMessageCrypto.ecdhWithLocalIdentity(prefs, ephemeralPublic)
                val decrypted = initCipher(Cipher.DECRYPT_MODE, envelopeKey(sharedSecret, aad), fromB64(iv), aad)
                    .doFinal(fromB64(ct))
                String(decrypted, StandardCharsets.UTF_8)
            }.getOrNull() ?: continue
            val json = runCatching { JSONObject(plain) }.getOrNull() ?: continue
            val senderKey = json.optString("sender_key")
            if (senderKey.isBlank()) continue
            prefs.putCryptoValue(
                senderKeyName(item.conversationId, senderUserCode, senderDeviceId, epoch, keyId),
                senderKey
            )
            imported += 1
        }
        return imported
    }

    fun encryptText(
        prefs: ChatPreferences,
        conversationId: Long,
        senderUserCode: String,
        senderUsername: String,
        epoch: Long,
        plaintext: String,
    ): String {
        val (keyId, senderKey) = ensureLocalSenderKey(prefs, conversationId, epoch, senderUserCode)
        val senderDeviceId = prefs.deviceId
        val counterKey = sendCounterName(conversationId, epoch, keyId)
        val counter = prefs.cryptoValue(counterKey).toLongOrNull() ?: 0L
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = aad(conversationId, senderUserCode, senderDeviceId, epoch, keyId, counter)
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, messageKey(senderKey, counter), iv, aad)
            .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        prefs.putCryptoValue(counterKey, (counter + 1L).toString())
        val payload = JSONObject()
            .put("v", 1)
            .put("scheme", SCHEME)
            .put("conversation_id", conversationId)
            .put("sender_user_code", senderUserCode)
            .put("sender_username", senderUsername)
            .put("device_id", senderDeviceId)
            .put("epoch", epoch)
            .put("key_id", keyId)
            .put("n", counter)
            .put("iv", b64(iv))
            .put("ct", b64(cipherText))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptText(prefs: ChatPreferences, conversationId: Long, payloadText: String): String {
        val payload = JSONObject(String(fromB64(payloadText), StandardCharsets.UTF_8))
        require(payload.optString("scheme") == SCHEME) { "not_group_sender_payload" }
        val senderUserCode = payload.getString("sender_user_code")
        val deviceId = payload.getString("device_id")
        val epoch = payload.optLong("epoch", 1L)
        val keyId = payload.getString("key_id")
        val counter = payload.optLong("n", 0L)
        val senderKey = prefs.cryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, keyId))
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("group_sender_key_unavailable")
        val aad = aad(conversationId, senderUserCode, deviceId, epoch, keyId, counter)
        val plain = initCipher(Cipher.DECRYPT_MODE, messageKey(senderKey, counter), fromB64(payload.getString("iv")), aad)
            .doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
    }
}
