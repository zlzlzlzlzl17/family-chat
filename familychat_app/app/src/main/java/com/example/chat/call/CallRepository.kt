package com.example.chat

internal class CallRepository(
    private val api: ChatApi,
) {
    fun configuration(serverUrl: String, token: String): CallConfig =
        api.callConfig(serverUrl, token)
}
