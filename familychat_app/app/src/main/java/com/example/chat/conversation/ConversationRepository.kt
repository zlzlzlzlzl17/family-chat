package com.example.chat

internal class ConversationRepository(
    private val api: ChatApi,
) {
    fun users(serverUrl: String, token: String): List<ChatUser> = api.users(serverUrl, token)

    fun conversations(serverUrl: String, token: String): List<ConversationSummary> =
        api.conversations(serverUrl, token)

    fun lookupUser(serverUrl: String, token: String, userCode: String): UserLookupResult =
        api.lookupUser(serverUrl, token, userCode)

    fun contactRequests(serverUrl: String, token: String): List<ContactRequestInfo> =
        api.contactRequests(serverUrl, token)

    fun requestContact(serverUrl: String, token: String, userCode: String): Long =
        api.requestContact(serverUrl, token, userCode)

    fun reviewContactRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean): Long =
        api.reviewContactRequest(serverUrl, token, requestId, approve)

    fun createGroup(serverUrl: String, token: String, title: String): Long =
        api.createGroup(serverUrl, token, title)

    fun lookupGroup(serverUrl: String, token: String, groupCode: String): GroupLookupResult =
        api.lookupGroup(serverUrl, token, groupCode)

    fun groupJoinRequests(serverUrl: String, token: String): List<GroupJoinRequestInfo> =
        api.groupJoinRequests(serverUrl, token)

    fun requestJoinGroup(serverUrl: String, token: String, groupCode: String): Long =
        api.requestJoinGroup(serverUrl, token, groupCode)

    fun reviewGroupJoinRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean): Long =
        api.reviewGroupJoinRequest(serverUrl, token, requestId, approve)

    fun conversationManage(serverUrl: String, token: String, conversationId: Long): ConversationManageInfo =
        api.conversationManage(serverUrl, token, conversationId)

    fun changeGroupTitle(serverUrl: String, token: String, conversationId: Long, title: String) =
        api.changeGroupTitle(serverUrl, token, conversationId, title)

    fun setGroupExpiration(serverUrl: String, token: String, conversationId: Long, ttlMs: Long) =
        api.setGroupExpiration(serverUrl, token, conversationId, ttlMs)

    fun removeGroupMember(serverUrl: String, token: String, conversationId: Long, userCode: String) =
        api.removeGroupMember(serverUrl, token, conversationId, userCode)

    fun addGroupMember(serverUrl: String, token: String, conversationId: Long, userCode: String) =
        api.addGroupMember(serverUrl, token, conversationId, userCode)

    fun transferGroupOwner(serverUrl: String, token: String, conversationId: Long, userCode: String) =
        api.transferGroupOwner(serverUrl, token, conversationId, userCode)

    fun requestGroupAdmin(serverUrl: String, token: String, conversationId: Long, userCode: String): String =
        api.requestGroupAdmin(serverUrl, token, conversationId, userCode)

    fun removeGroupAdmin(serverUrl: String, token: String, conversationId: Long, userCode: String) =
        api.removeGroupAdmin(serverUrl, token, conversationId, userCode)

    fun reviewGroupAdminRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean) =
        api.reviewGroupAdminRequest(serverUrl, token, requestId, approve)

    fun deleteDirectConversation(serverUrl: String, token: String, conversationId: Long) =
        api.deleteDirectConversation(serverUrl, token, conversationId)

    fun deleteGroupConversation(serverUrl: String, token: String, conversationId: Long) =
        api.deleteGroupConversation(serverUrl, token, conversationId)

    fun leaveGroupConversation(serverUrl: String, token: String, conversationId: Long) =
        api.leaveGroupConversation(serverUrl, token, conversationId)

    fun uploadConversationAvatar(
        serverUrl: String,
        token: String,
        conversationId: Long,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): String = api.uploadConversationAvatar(
        serverUrl,
        token,
        conversationId,
        fileName,
        mime,
        bytes,
        onProgress,
    )
}
