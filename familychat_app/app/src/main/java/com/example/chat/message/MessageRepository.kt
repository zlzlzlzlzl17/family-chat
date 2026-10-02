package com.example.chat

import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class MessageRepository(
    private val api: ChatApi,
) {
    fun history(
        serverUrl: String,
        token: String,
        conversationId: Long,
        sinceId: Long? = null,
        beforeId: Long? = null,
        limit: Int = 200,
    ): HistoryPage = api.history(serverUrl, token, conversationId, sinceId, beforeId, limit)

    fun sendText(
        serverUrl: String,
        token: String,
        conversationId: Long,
        payload: String,
        e2ee: Boolean,
        replyJson: String,
        mentions: List<String>,
        clientMessageId: String,
    ): ChatMessage = api.sendTextMessage(
        serverUrl = serverUrl,
        token = token,
        conversationId = conversationId,
        payload = payload,
        e2ee = e2ee,
        replyJson = replyJson,
        mentions = mentions,
        clientMessageId = clientMessageId,
    )

    fun conversationReadStates(serverUrl: String, token: String, conversationId: Long): List<ConversationReadState> =
        api.conversationReadStates(serverUrl, token, conversationId)

    fun conversationDeliveryStates(
        serverUrl: String,
        token: String,
        conversationId: Long,
    ): List<ConversationDeliveryState> = api.conversationDeliveryStates(serverUrl, token, conversationId)

    fun markRead(serverUrl: String, token: String, conversationId: Long, messageId: Long) =
        api.markRead(serverUrl, token, conversationId, messageId)

    fun markDelivered(serverUrl: String, token: String, conversationId: Long, messageId: Long) =
        api.markDelivered(serverUrl, token, conversationId, messageId)

    fun clearConversationHistory(serverUrl: String, token: String, conversationId: Long) =
        api.clearConversationHistory(serverUrl, token, conversationId)

    fun deleteHistory(serverUrl: String, token: String) = api.deleteHistory(serverUrl, token)

    fun recallMessage(serverUrl: String, token: String, messageId: Long) =
        api.recallMessage(serverUrl, token, messageId)

    fun connect(serverUrl: String, token: String, listener: WebSocketListener): WebSocket =
        api.connect(serverUrl, token, listener)

}
