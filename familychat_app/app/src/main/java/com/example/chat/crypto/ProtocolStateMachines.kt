package com.example.chat

/** Pure, deterministic protocol derivations used by production and JVM tests. */
internal object DirectRatchetKdf {
    private const val KEY_BYTES = 32

    fun x3dhInfo(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        senderIdentityEcdhPublic: String,
        recipientIdentityEcdhPublic: String,
        recipientSignedPrekeyPublic: String,
        recipientOneTimePrekeyPublic: String,
    ): String =
        "familychat:direct:v2:x3dh|conversation=$conversationId|from=$senderDeviceId|to=$recipientDeviceId|ika=$senderIdentityEcdhPublic|ikb=$recipientIdentityEcdhPublic|spkb=$recipientSignedPrekeyPublic|opkb=$recipientOneTimePrekeyPublic"

    fun initialRoot(sharedSecretMaterial: ByteArray, transcript: String): ByteArray =
        CryptoPrimitives.hkdfSha256(sharedSecretMaterial, info = transcript)

    fun rootStep(rootKey: ByteArray, dhSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val output = CryptoPrimitives.hkdfSha256(
            inputKeyMaterial = dhSecret,
            salt = rootKey.copyOf(KEY_BYTES),
            info = "familychat:direct:v2:kdf-root",
            length = KEY_BYTES * 2,
        )
        return output.copyOfRange(0, KEY_BYTES) to output.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
    }

    fun messageKey(chainKey: ByteArray): ByteArray =
        CryptoPrimitives.hkdfSha256(chainKey, info = "familychat:direct:v2:message-key")

    fun nextChainKey(chainKey: ByteArray): ByteArray =
        CryptoPrimitives.hkdfSha256(chainKey, info = "familychat:direct:v2:next-chain")

    fun aad(
        conversationId: Long,
        sessionId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        dhPublic: String,
        previousSendCount: Long,
        counter: Long,
    ): String =
        "familychat:direct:v2:aad|conversation=$conversationId|session=$sessionId|from=$senderDeviceId|to=$recipientDeviceId|dh=$dhPublic|pn=$previousSendCount|n=$counter"
}

internal data class DoubleRatchetChain(
    val chainKey: ByteArray,
    val counter: Long = 0L,
) {
    fun step(): DoubleRatchetStep = DoubleRatchetStep(
        messageKey = DirectRatchetKdf.messageKey(chainKey),
        next = DoubleRatchetChain(DirectRatchetKdf.nextChainKey(chainKey), counter + 1L),
    )

    override fun equals(other: Any?): Boolean =
        other is DoubleRatchetChain && counter == other.counter && chainKey.contentEquals(other.chainKey)

    override fun hashCode(): Int = 31 * chainKey.contentHashCode() + counter.hashCode()
}

internal data class DoubleRatchetStep(
    val messageKey: ByteArray,
    val next: DoubleRatchetChain,
) {
    override fun equals(other: Any?): Boolean =
        other is DoubleRatchetStep && messageKey.contentEquals(other.messageKey) && next == other.next

    override fun hashCode(): Int = 31 * messageKey.contentHashCode() + next.hashCode()
}

internal object GroupSenderKeyKdf {
    fun messageKey(senderKey: ByteArray, counter: Long): ByteArray =
        CryptoPrimitives.hkdfSha256(
            senderKey,
            info = "familychat:group-sender:v1:message-key:$counter",
        )

    fun aad(
        conversationId: Long,
        senderUserCode: String,
        deviceId: String,
        epoch: Long,
        keyId: String,
        counter: Long,
    ): String =
        "familychat:group-sender:v1|conversation=$conversationId|sender=$senderUserCode|device=$deviceId|epoch=$epoch|key=$keyId|n=$counter"

    fun envelopeAad(
        conversationId: Long,
        senderUserCode: String,
        senderDeviceId: String,
        recipientUserCode: String,
        recipientDeviceId: String,
        epoch: Long,
        keyId: String,
        ephemeralPublic: String,
    ): String =
        "familychat:group-sender-envelope:v1|conversation=$conversationId|sender=$senderUserCode|senderDevice=$senderDeviceId|recipient=$recipientUserCode|recipientDevice=$recipientDeviceId|epoch=$epoch|key=$keyId|eph=$ephemeralPublic"

    fun envelopeKey(sharedSecret: ByteArray, aad: String): ByteArray =
        CryptoPrimitives.hkdfSha256(
            sharedSecret,
            info = "familychat:group-sender-envelope:v1:$aad",
        )
}
