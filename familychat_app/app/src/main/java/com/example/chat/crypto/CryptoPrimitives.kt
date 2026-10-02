package com.example.chat

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure cryptographic building blocks shared by attachment, direct, and group encryption. */
internal object CryptoPrimitives {
    private const val HASH_BYTES = 32
    private const val GCM_TAG_BITS = 128

    fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray = ByteArray(HASH_BYTES),
        info: ByteArray,
        length: Int = HASH_BYTES,
    ): ByteArray {
        require(length in 1..(255 * HASH_BYTES)) { "invalid_hkdf_length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudorandomKey = mac.doFinal(inputKeyMaterial)
        var previous = ByteArray(0)
        val output = ByteArrayOutputStream(length)
        var counter = 1
        while (output.size() < length) {
            mac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter += 1
        }
        return output.toByteArray().copyOf(length)
    }

    fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray = ByteArray(HASH_BYTES),
        info: String,
        length: Int = HASH_BYTES,
    ): ByteArray = hkdfSha256(
        inputKeyMaterial = inputKeyMaterial,
        salt = salt,
        info = info.toByteArray(StandardCharsets.UTF_8),
        length = length,
    )

    fun aesGcmCipher(
        mode: Int,
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: String,
    ): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        require(keyBytes.size >= 32) { "aes_256_key_required" }
        require(iv.size == 12) { "gcm_iv_must_be_12_bytes" }
        init(mode, SecretKeySpec(keyBytes.copyOf(32), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
    }
}
