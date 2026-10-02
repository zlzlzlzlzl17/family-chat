package com.example.chat

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

internal class AttachmentRepository(
    private val api: ChatApi,
) {
    fun upload(
        serverUrl: String,
        token: String,
        conversationId: Long,
        kind: String,
        payloadJson: String,
        replyJson: String,
        clientMessageId: String,
        encryptedFile: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ChatMessage = api.uploadAttachment(
        serverUrl = serverUrl,
        token = token,
        conversationId = conversationId,
        kind = kind,
        payloadJson = payloadJson,
        replyJson = replyJson,
        clientMessageId = clientMessageId,
        encryptedFile = encryptedFile,
        onProgress = onProgress,
    )

    suspend fun uploadResumable(
        serverUrl: String,
        token: String,
        conversationId: Long,
        kind: String,
        payloadJson: String,
        replyJson: String,
        clientMessageId: String,
        encryptedFile: File,
        checksumSha256: String,
        onSession: (suspend (String, Long) -> Unit)? = null,
        onProgress: (suspend (Long, Long) -> Unit)? = null,
    ): ChatMessage {
        val initial = api.beginAttachmentUpload(
            serverUrl = serverUrl,
            token = token,
            conversationId = conversationId,
            kind = kind,
            payloadJson = payloadJson,
            replyJson = replyJson,
            clientMessageId = clientMessageId,
            totalSize = encryptedFile.length(),
            checksumSha256 = checksumSha256,
        )
        initial.completedMessage?.let { return it }
        require(initial.uploadId.isNotBlank()) { "upload_session_missing" }
        var offset = initial.offset.coerceIn(0L, encryptedFile.length())
        onSession?.invoke(initial.uploadId, offset)
        onProgress?.invoke(offset, encryptedFile.length())
        RandomAccessFile(encryptedFile, "r").use { source ->
            source.seek(offset)
            val buffer = ByteArray(512 * 1024)
            while (offset < encryptedFile.length()) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), encryptedFile.length() - offset).toInt())
                if (read <= 0) break
                offset = api.uploadAttachmentChunk(
                    serverUrl = serverUrl,
                    token = token,
                    uploadId = initial.uploadId,
                    offset = offset,
                    bytes = if (read == buffer.size) buffer else buffer.copyOf(read),
                )
                onSession?.invoke(initial.uploadId, offset)
                onProgress?.invoke(offset, encryptedFile.length())
            }
        }
        check(offset == encryptedFile.length()) { "upload_incomplete" }
        return api.completeAttachmentUpload(serverUrl, token, initial.uploadId, clientMessageId)
    }

    fun materialize(
        context: Context,
        serverUrl: String,
        message: ChatMessage,
        preferences: ChatPreferences,
        exportToDownloads: Boolean,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): DecryptedAttachment = resolveAttachment(
        context = context,
        api = api,
        serverUrl = serverUrl,
        message = message,
        prefs = preferences,
        onProgress = onProgress,
        exportToDownloads = exportToDownloads,
    )
}
