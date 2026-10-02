package com.example.chat

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoPrimitivesTest {
    @Test
    fun hkdfMatchesRfc5869Sha256Vector() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val output = CryptoPrimitives.hkdfSha256(ikm, salt, info, 42)

        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"),
            output,
        )
    }

    @Test
    fun gcmRejectsDifferentAssociatedData() {
        val key = ByteArray(32) { index -> index.toByte() }
        val iv = ByteArray(12) { index -> (index + 1).toByte() }
        val plaintext = "message bound to conversation 7".toByteArray()
        val ciphertext = CryptoPrimitives
            .aesGcmCipher(Cipher.ENCRYPT_MODE, key, iv, "conversation=7|message=12")
            .doFinal(plaintext)

        val decrypted = CryptoPrimitives
            .aesGcmCipher(Cipher.DECRYPT_MODE, key, iv, "conversation=7|message=12")
            .doFinal(ciphertext)
        assertArrayEquals(plaintext, decrypted)

        assertThrows(AEADBadTagException::class.java) {
            CryptoPrimitives
                .aesGcmCipher(Cipher.DECRYPT_MODE, key, iv, "conversation=8|message=12")
                .doFinal(ciphertext)
        }
    }

    @Test
    fun perMessageInfoProducesDifferentKeys() {
        val chainKey = ByteArray(32) { 0x42 }

        val first = CryptoPrimitives.hkdfSha256(chainKey, info = "familychat:message-key:1")
        val second = CryptoPrimitives.hkdfSha256(chainKey, info = "familychat:message-key:2")

        assertFalse(first.contentEquals(second))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
