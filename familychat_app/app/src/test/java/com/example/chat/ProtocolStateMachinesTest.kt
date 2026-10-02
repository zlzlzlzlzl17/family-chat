package com.example.chat

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolStateMachinesTest {
    @Test
    fun x3dhInitiatorAndRecipientBuildIdenticalSharedSecretMaterial() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val initiatorIdentity = generator.generateKeyPair()
        val initiatorEphemeral = generator.generateKeyPair()
        val recipientIdentity = generator.generateKeyPair()
        val recipientSignedPrekey = generator.generateKeyPair()
        val recipientOneTimePrekey = generator.generateKeyPair()

        val initiatorMaterial = concat(
            dh(initiatorIdentity.private, recipientSignedPrekey.public),
            dh(initiatorEphemeral.private, recipientIdentity.public),
            dh(initiatorEphemeral.private, recipientSignedPrekey.public),
            dh(initiatorEphemeral.private, recipientOneTimePrekey.public),
        )
        val recipientMaterial = concat(
            dh(recipientSignedPrekey.private, initiatorIdentity.public),
            dh(recipientIdentity.private, initiatorEphemeral.public),
            dh(recipientSignedPrekey.private, initiatorEphemeral.public),
            dh(recipientOneTimePrekey.private, initiatorEphemeral.public),
        )
        val transcript = transcript(12L, "phone-b")

        assertArrayEquals(initiatorMaterial, recipientMaterial)
        assertArrayEquals(
            DirectRatchetKdf.initialRoot(initiatorMaterial, transcript),
            DirectRatchetKdf.initialRoot(recipientMaterial, transcript),
        )
    }

    @Test
    fun x3dhTranscriptBindsConversationAndDevices() {
        val first = transcript(conversationId = 12L, recipientDevice = "phone-b")
        val otherConversation = transcript(conversationId = 13L, recipientDevice = "phone-b")
        val otherDevice = transcript(conversationId = 12L, recipientDevice = "phone-c")

        assertNotEquals(first, otherConversation)
        assertNotEquals(first, otherDevice)
        assertFalse(
            DirectRatchetKdf.initialRoot(ByteArray(128) { it.toByte() }, first)
                .contentEquals(DirectRatchetKdf.initialRoot(ByteArray(128) { it.toByte() }, otherDevice))
        )
    }

    @Test
    fun doubleRatchetPeersDeriveSameOrderedKeysAndEraseOldChainPosition() {
        var sender = DoubleRatchetChain(ByteArray(32) { (it * 3).toByte() })
        var receiver = DoubleRatchetChain(sender.chainKey.copyOf())

        repeat(8) {
            val sent = sender.step()
            val received = receiver.step()
            assertArrayEquals(sent.messageKey, received.messageKey)
            sender = sent.next
            receiver = received.next
        }

        assertArrayEquals(sender.chainKey, receiver.chainKey)
        assertFalse(sender.step().messageKey.contentEquals(DoubleRatchetChain(ByteArray(32) { (it * 3).toByte() }).step().messageKey))
    }

    @Test
    fun doubleRatchetSkippedKeysDecryptOutOfOrderWithoutReusingKeys() {
        var sender = DoubleRatchetChain(ByteArray(32) { (it * 7).toByte() })
        val generated = buildMap<Long, ByteArray> {
            repeat(6) {
                val step = sender.step()
                put(step.next.counter - 1L, step.messageKey)
                sender = step.next
            }
        }
        val arrivalOrder = listOf(4L, 1L, 5L, 0L, 3L, 2L)
        val consumed = arrivalOrder.map { generated.getValue(it) }

        assertEquals(arrivalOrder.size, consumed.map(::hexDigest).toSet().size)
        arrivalOrder.forEach { counter ->
            assertArrayEquals(generated.getValue(counter), consumed[arrivalOrder.indexOf(counter)])
        }
    }

    @Test
    fun reinstallCreatesDifferentDeviceFingerprintAndCannotReuseOldSessionRoot() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val oldIdentity = generator.generateKeyPair()
        val replacementIdentity = generator.generateKeyPair()
        val transcript = transcript(12L, "replacement-device")
        val peerMaterial = ByteArray(96) { it.toByte() }

        assertNotEquals(hexDigest(oldIdentity.public.encoded), hexDigest(replacementIdentity.public.encoded))
        assertFalse(
            DirectRatchetKdf.initialRoot(peerMaterial + oldIdentity.public.encoded, transcript)
                .contentEquals(DirectRatchetKdf.initialRoot(peerMaterial + replacementIdentity.public.encoded, transcript))
        )
    }

    @Test
    fun groupEpochRotationInvalidatesPreviousSenderKey() {
        val previous = ByteArray(32) { 0x31 }
        val rotated = CryptoPrimitives.hkdfSha256(previous, info = "familychat:group-epoch:9")
        val previousMessageKey = GroupSenderKeyKdf.messageKey(previous, 1L)
        val rotatedMessageKey = GroupSenderKeyKdf.messageKey(rotated, 1L)

        assertFalse(previous.contentEquals(rotated))
        assertFalse(previousMessageKey.contentEquals(rotatedMessageKey))
    }

    @Test
    fun rootRatchetSeparatesDifferentDhSecrets() {
        val root = ByteArray(32) { 0x31 }
        val first = DirectRatchetKdf.rootStep(root, ByteArray(32) { 0x11 })
        val second = DirectRatchetKdf.rootStep(root, ByteArray(32) { 0x12 })

        assertFalse(first.first.contentEquals(second.first))
        assertFalse(first.second.contentEquals(second.second))
    }

    @Test
    fun directMessageAadRejectsConversationOrCounterTampering() {
        val chain = ByteArray(32) { 0x42 }
        val key = DirectRatchetKdf.messageKey(chain)
        val iv = ByteArray(12) { it.toByte() }
        val aad = DirectRatchetKdf.aad(8L, "session", "device-a", "device-b", "dh", 0L, 4L)
        val encrypted = CryptoPrimitives.aesGcmCipher(Cipher.ENCRYPT_MODE, key, iv, aad)
            .doFinal("ratcheted message".toByteArray())

        assertThrows(AEADBadTagException::class.java) {
            CryptoPrimitives.aesGcmCipher(
                Cipher.DECRYPT_MODE,
                key,
                iv,
                DirectRatchetKdf.aad(9L, "session", "device-a", "device-b", "dh", 0L, 4L),
            ).doFinal(encrypted)
        }
    }

    @Test
    fun senderKeySeparatesCountersEpochsAndRecipients() {
        val senderKey = ByteArray(32) { 0x55 }
        assertFalse(
            GroupSenderKeyKdf.messageKey(senderKey, 1L)
                .contentEquals(GroupSenderKeyKdf.messageKey(senderKey, 2L))
        )
        assertNotEquals(
            GroupSenderKeyKdf.aad(3L, "10000001", "phone-a", 7L, "key", 1L),
            GroupSenderKeyKdf.aad(3L, "10000001", "phone-a", 8L, "key", 1L),
        )
        assertNotEquals(
            GroupSenderKeyKdf.envelopeAad(3L, "10000001", "phone-a", "10000002", "phone-b", 8L, "key", "eph"),
            GroupSenderKeyKdf.envelopeAad(3L, "10000001", "phone-a", "10000002", "phone-c", 8L, "key", "eph"),
        )
    }

    @Test
    fun groupSenderCiphertextIsBoundToEpochAndCounter() {
        val senderKey = ByteArray(32) { 0x55 }
        val key = GroupSenderKeyKdf.messageKey(senderKey, 3L)
        val iv = ByteArray(12) { (it + 2).toByte() }
        val aad = GroupSenderKeyKdf.aad(77L, "10000001", "phone-a", 9L, "key-1", 3L)
        val ciphertext = CryptoPrimitives.aesGcmCipher(Cipher.ENCRYPT_MODE, key, iv, aad)
            .doFinal("group message".toByteArray())

        assertArrayEquals(
            "group message".toByteArray(),
            CryptoPrimitives.aesGcmCipher(Cipher.DECRYPT_MODE, key, iv, aad).doFinal(ciphertext),
        )
        assertThrows(AEADBadTagException::class.java) {
            CryptoPrimitives.aesGcmCipher(
                Cipher.DECRYPT_MODE,
                key,
                iv,
                GroupSenderKeyKdf.aad(77L, "10000001", "phone-a", 10L, "key-1", 3L),
            ).doFinal(ciphertext)
        }
    }

    private fun transcript(conversationId: Long, recipientDevice: String): String =
        DirectRatchetKdf.x3dhInfo(
            conversationId = conversationId,
            senderDeviceId = "phone-a",
            recipientDeviceId = recipientDevice,
            senderIdentityEcdhPublic = "ika",
            recipientIdentityEcdhPublic = "ikb",
            recipientSignedPrekeyPublic = "spkb",
            recipientOneTimePrekeyPublic = "opkb",
        )

    private fun dh(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    private fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        parts.forEach { output.write(it) }
        output.toByteArray()
    }

    private fun hexDigest(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
