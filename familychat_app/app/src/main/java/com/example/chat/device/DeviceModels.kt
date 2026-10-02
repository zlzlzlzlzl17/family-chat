package com.example.chat

data class DeviceIdentityInfo(
    val deviceId: String,
    val deviceName: String,
    val keyAlg: String,
    val publicKey: String,
    val safetyCode: String,
)

data class DevicePublicIdentity(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val keyAlg: String,
    val publicKey: String,
    val updatedAt: Long,
    val lastSeenAt: Long = 0L,
    val status: String = "trusted",
    val manufacturer: String = "",
    val model: String = "",
) {
    val safetyCode: String
        get() = DeviceIdentityManager.safetyCode(userCode, deviceId, publicKey)
}

data class LocalDirectPreKey(
    val deviceId: String,
    val keyAlg: String,
    val identityEcdhPublic: String,
    val identityEcdhSignature: String,
    val signedPrekeyPublic: String,
    val signedPrekeySignature: String,
    val oneTimePreKeys: List<LocalDirectOneTimePreKey> = emptyList(),
)

data class LocalDirectOneTimePreKey(
    val id: String,
    val publicKey: String,
    val signature: String,
)
