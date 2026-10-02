package com.example.chat

internal class CryptoSessionRepository(
    private val api: ChatApi,
) {
    fun directPreKeys(serverUrl: String, token: String, conversationId: Long): List<DirectPreKeyBundle> =
        api.directPreKeys(serverUrl, token, conversationId)

    fun groupSenderKeys(
        serverUrl: String,
        token: String,
        conversationId: Long,
        deviceId: String,
    ): GroupSenderKeyState = api.groupSenderKeys(serverUrl, token, conversationId, deviceId)

    fun registerGroupSenderKey(serverUrl: String, token: String, key: LocalGroupSenderKey) =
        api.registerGroupSenderKey(serverUrl, token, key)
}
