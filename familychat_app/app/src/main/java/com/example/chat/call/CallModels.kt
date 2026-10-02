package com.example.chat

data class CallConfig(
    val iceServers: List<CallIceServerConfig>,
)

data class PendingIncomingCallInvite(
    val conversationId: Long,
    val peerUserCode: String = "",
    val peerUsername: String,
    val createdAt: Long,
)
