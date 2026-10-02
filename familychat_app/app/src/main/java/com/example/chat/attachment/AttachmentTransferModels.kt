package com.example.chat

internal data class RemoteAttachmentUpload(
    val uploadId: String,
    val offset: Long,
    val completedMessage: ChatMessage? = null,
)
