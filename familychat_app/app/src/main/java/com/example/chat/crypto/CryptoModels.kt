package com.example.chat

data class DirectPreKeyBundle(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val identityKeyAlg: String,
    val identityPublicKey: String,
    val identityFingerprint: String,
    val prekeyAlg: String,
    val identityEcdhPublic: String,
    val identityEcdhSignature: String,
    val signedPrekeyPublic: String,
    val signedPrekeySignature: String,
    val oneTimePrekeyId: String,
    val oneTimePrekeyPublic: String,
    val oneTimePrekeySignature: String,
    val updatedAt: Long,
)

data class GroupSenderKeyBundle(
    val conversationId: Long,
    val senderUserCode: String,
    val senderUsername: String,
    val deviceId: String,
    val senderDeviceName: String,
    val senderIdentityPublicKey: String,
    val senderIdentityFingerprint: String,
    val recipientDeviceId: String,
    val epoch: Long,
    val keyId: String,
    val wrappedKey: String,
    val updatedAt: Long,
)

data class GroupKeyDevice(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val identityKeyAlg: String,
    val identityPublicKey: String,
    val identityFingerprint: String,
    val prekeyAlg: String,
    val identityEcdhPublic: String,
    val identityEcdhSignature: String,
    val updatedAt: Long,
)

data class GroupSenderKeyState(
    val epoch: Long,
    val items: List<GroupSenderKeyBundle>,
    val devices: List<GroupKeyDevice>,
)

data class GroupSenderKeyEnvelope(
    val recipientUserCode: String,
    val recipientDeviceId: String,
    val wrappedKey: String,
)

data class LocalGroupSenderKey(
    val conversationId: Long,
    val deviceId: String,
    val epoch: Long,
    val keyId: String,
    val envelopes: List<GroupSenderKeyEnvelope>,
)
