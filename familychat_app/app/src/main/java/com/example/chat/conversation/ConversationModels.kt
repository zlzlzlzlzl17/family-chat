package com.example.chat

import androidx.compose.runtime.Immutable

@Immutable
data class ChatUser(
    val userCode: String = "",
    val username: String,
    val color: String,
    val avatarUrl: String = "",
    val isAdmin: Boolean,
)

@Immutable
data class ConversationSummary(
    val id: Long,
    val kind: String,
    val slug: String,
    val groupCode: String,
    val title: String,
    val avatarUrl: String,
    val directUserCode: String,
    val directUsername: String,
    val lastMessageTs: Long,
    val lastMessagePreview: String,
    val unreadCount: Int,
    val lastReadMessageId: Long,
)

data class ConversationReadState(
    val userCode: String = "",
    val username: String,
    val lastReadMessageId: Long,
)

data class ConversationDeliveryState(
    val userCode: String = "",
    val username: String,
    val lastDeliveredMessageId: Long,
)

data class UserLookupResult(
    val user: ChatUser,
    val conversationId: Long,
    val isContact: Boolean,
    val outgoingPending: Boolean,
    val incomingRequestId: Long,
    val incomingPending: Boolean,
)

data class ContactRequestInfo(
    val id: Long,
    val direction: String,
    val status: String,
    val user: ChatUser,
    val createdAt: Long,
)

data class GroupLookupResult(
    val conversationId: Long,
    val groupCode: String,
    val title: String,
    val avatarUrl: String,
    val isMember: Boolean,
    val pending: Boolean,
    val pendingRequestId: Long,
)

data class GroupJoinRequestInfo(
    val id: Long,
    val direction: String,
    val status: String,
    val groupCode: String,
    val title: String,
    val avatarUrl: String,
    val requester: ChatUser,
    val createdAt: Long,
)

data class GroupMemberInfo(
    val user: ChatUser,
    val role: String,
    val joinedAt: Long,
)

data class GroupAdminRequestInfo(
    val id: Long,
    val conversationId: Long,
    val status: String,
    val requesterUserCode: String,
    val requesterUsername: String,
    val targetUserCode: String,
    val targetUsername: String,
    val createdAt: Long,
)

data class ConversationManageInfo(
    val id: Long,
    val kind: String,
    val title: String,
    val groupCode: String,
    val avatarUrl: String,
    val messageTtlMs: Long,
    val ownRole: String,
    val canManage: Boolean,
    val canManageOwner: Boolean,
    val adminCount: Int,
    val adminLimit: Int,
    val keyEpoch: Long,
    val keyDeviceCount: Int,
    val keyReadyDeviceCount: Int,
    val members: List<GroupMemberInfo>,
    val pendingJoinRequests: List<GroupJoinRequestInfo>,
    val pendingAdminRequests: List<GroupAdminRequestInfo>,
)

data class HistoryPage(
    val items: List<ChatMessage>,
    val hasMore: Boolean,
)
