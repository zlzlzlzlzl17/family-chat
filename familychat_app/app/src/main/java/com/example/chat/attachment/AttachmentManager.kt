package com.example.chat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class AttachmentDisplayMeta(
    val width: Int = 0,
    val height: Int = 0,
    val sticker: Boolean = false,
)

/** Owns attachment staging, encryption, transfer persistence and materialization. */
internal class AttachmentManager(
    private val application: Application,
    private val preferences: ChatPreferences,
    private val auth: AuthSessionManager,
    private val repository: AttachmentRepository,
    private val crypto: CryptoManager,
    private val local: LocalChatRepository,
) {
    private val mutableUploadProgress = MutableStateFlow<TransferProgress?>(null)
    private val mutableDownloadProgress = MutableStateFlow<TransferProgress?>(null)

    val uploadProgress: StateFlow<TransferProgress?> = mutableUploadProgress.asStateFlow()
    val downloadProgress: StateFlow<TransferProgress?> = mutableDownloadProgress.asStateFlow()

    suspend fun stageBytes(fileName: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val target = stagedFile(fileName)
        target.writeBytes(bytes)
        target
    }

    suspend fun stageUri(fileName: String, uri: Uri): File = withContext(Dispatchers.IO) {
        val target = stagedFile(fileName)
        try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw IOException("Unable to open attachment")
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun displayMeta(file: File, kind: String, mime: String, richContent: Boolean): AttachmentDisplayMeta =
        withContext(Dispatchers.IO) { analyzeDisplayMeta(file, kind, mime, richContent) }

    suspend fun send(
        pending: PendingOutgoing,
        conversation: ConversationSummary,
        me: ChatUser,
    ): ChatMessage {
        var action = pending.action as? PendingAction.Attachment
            ?: throw IllegalArgumentException("not_attachment_outbox_item")
        val session = auth.sessionSnapshot()
        check(session.isReady) { "session_expired" }
        val source = File(action.localFilePath)
        var encryptedFile = action.encryptedFilePath.takeIf(String::isNotBlank)?.let(::File)
        var payload = action.preparedPayload
        var checksum = action.checksumSha256
        if (encryptedFile?.exists() != true || payload.isBlank() || checksum.isBlank()) {
            check(source.exists()) { "attachment_not_available" }
            val display = displayMeta(source, action.kind, action.mime, action.richContent)
            val outboxDirectory = File(application.filesDir, "outbox").apply { mkdirs() }
            encryptedFile = File(outboxDirectory, "encrypted-${pending.clientMessageId}.enc")
            val fileKey = ChatCrypto.randomFileKey()
            val encrypted = withContext(Dispatchers.IO) {
                ChatCrypto.encryptBinaryFileWithKey(
                    fileKey = fileKey,
                    sourceFile = source,
                    targetFile = encryptedFile,
                    aad = ChatCrypto.attachmentFileAad(conversation.id, action.kind),
                )
            }
            val wrapped = crypto.wrapFileKey(conversation, me, fileKey)
            val metadata = JSONObject()
                .put("name", action.fileName)
                .put("mime", action.mime)
                .put("size", source.length())
                .put("durationMs", action.durationMs)
                .put("width", display.width)
                .put("height", display.height)
                .put("sticker", display.sticker)
            payload = JSONObject()
                .put("v", 3)
                .put(
                    "display",
                    JSONObject()
                        .put("width", display.width)
                        .put("height", display.height)
                        .put("sticker", display.sticker),
                )
                .put(
                    "enc",
                    JSONObject()
                        .put("v", 3)
                        .put("kdf", "HKDF-SHA256")
                        .put("aead", "AES-256-GCM")
                        .put("stream", "file-key-gcm")
                        .put("iv", encrypted.iv),
                )
                .put(
                    "key_wrap",
                    JSONObject().put("scheme", wrapped.scheme).put("payload", wrapped.payload),
                )
                .put(
                    "meta",
                    ChatCrypto.encryptTextWithKey(
                        fileKey,
                        metadata.toString(),
                        ChatCrypto.attachmentMetaAad(conversation.id, action.kind),
                    ),
                )
                .toString()
            checksum = sha256(encryptedFile)
            action = action.copy(
                encryptedFilePath = encryptedFile.absolutePath,
                preparedPayload = payload,
                checksumSha256 = checksum,
            )
            local.updateOutboxPayload(
                session.ownerId,
                pending.copy(
                    action = action,
                    message = pending.message.copy(payload = payload),
                ),
            )
        }
        val uploadFile = requireNotNull(encryptedFile).also { check(it.exists()) { "encrypted_attachment_missing" } }
        val total = uploadFile.length()
        val now = System.currentTimeMillis()
        val transfer = PersistedAttachmentTransfer(
            transferId = pending.clientMessageId,
            clientMessageId = pending.clientMessageId,
            localMessageId = pending.message.id,
            conversationId = pending.message.conversationId,
            direction = "upload",
            localFilePath = uploadFile.absolutePath,
            remotePath = "",
            checksumSha256 = checksum,
            bytesTransferred = 0L,
            totalBytes = total,
            state = AttachmentTransferState.TRANSFERRING,
            attemptCount = pending.attemptCount + 1,
            nextAttemptAt = 0L,
            lastError = "",
            createdAt = pending.message.ts,
            updatedAt = now,
        )
        local.upsertAttachmentTransfer(session.ownerId, transfer)
        mutableUploadProgress.value = TransferProgress(
            label = action.fileName,
            progress = 0f,
            bytesDone = 0L,
            totalBytes = total,
            messageId = pending.message.id,
            conversationId = pending.message.conversationId,
        )

        return try {
            val confirmed = withContext(Dispatchers.IO) {
                repository.uploadResumable(
                    serverUrl = session.serverUrl,
                    token = session.accessToken,
                    conversationId = conversation.id,
                    kind = action.kind,
                    payloadJson = payload,
                    replyJson = action.reply?.toWireJson().orEmpty(),
                    clientMessageId = pending.clientMessageId,
                    encryptedFile = uploadFile,
                    checksumSha256 = checksum,
                    onSession = { uploadId, uploaded ->
                        local.upsertAttachmentTransfer(
                            session.ownerId,
                            transfer.copy(
                                remotePath = uploadId,
                                checksumSha256 = checksum,
                                bytesTransferred = uploaded,
                                state = AttachmentTransferState.TRANSFERRING,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        mutableUploadProgress.value = TransferProgress(
                            action.fileName,
                            progress(uploaded, total),
                            uploaded,
                            total,
                            pending.message.id,
                            conversation.id,
                        )
                    },
                ) { done, uploadTotal ->
                    mutableUploadProgress.value = TransferProgress(
                        label = action.fileName,
                        progress = progress(done, uploadTotal),
                        bytesDone = done,
                        totalBytes = uploadTotal,
                        messageId = pending.message.id,
                        conversationId = conversation.id,
                    )
                }
            }
            local.upsertAttachmentTransfer(
                session.ownerId,
                transfer.copy(
                    checksumSha256 = checksum,
                    bytesTransferred = uploadFile.length(),
                    totalBytes = uploadFile.length(),
                    state = AttachmentTransferState.COMPLETE,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            source.delete()
            uploadFile.delete()
            confirmed
        } catch (error: Throwable) {
            local.upsertAttachmentTransfer(
                session.ownerId,
                transfer.copy(
                    state = AttachmentTransferState.RETRY_WAIT,
                    nextAttemptAt = System.currentTimeMillis() + SyncRetryPolicy.delayMs(transfer.attemptCount, pending.message.id),
                    lastError = error.javaClass.simpleName,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            throw error
        } finally {
            mutableUploadProgress.value = null
        }
    }

    suspend fun materialize(
        context: Context,
        message: ChatMessage,
        exportToDownloads: Boolean,
    ): DecryptedAttachment {
        val session = auth.sessionSnapshot()
        check(session.isReady) { "session_expired" }
        mutableDownloadProgress.value = TransferProgress(
            label = message.localAttachmentName.ifBlank { message.kind },
            progress = 0f,
            bytesDone = 0L,
            totalBytes = 0L,
            messageId = message.id,
            conversationId = message.conversationId,
        )
        return try {
            crypto.prepareMessageDecryption(message)
            try {
                materializeOnce(context, session, message, exportToDownloads)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (initialError: Throwable) {
                if (!crypto.isGroupEncryptedMessage(message)) throw initialError
                crypto.prepareMessageDecryption(message, forceRefresh = true)
                materializeOnce(context, session, message, exportToDownloads)
            }
        } finally {
            mutableDownloadProgress.value = null
        }
    }

    private suspend fun materializeOnce(
        context: Context,
        session: SyncSession,
        message: ChatMessage,
        exportToDownloads: Boolean,
    ): DecryptedAttachment = withContext(Dispatchers.IO) {
        repository.materialize(
            context = context,
            serverUrl = session.serverUrl,
            message = message,
            preferences = preferences,
            exportToDownloads = exportToDownloads,
        ) { done, total ->
            mutableDownloadProgress.value = TransferProgress(
                label = message.localAttachmentName.ifBlank { message.kind },
                progress = progress(done, total),
                bytesDone = done,
                totalBytes = total,
                messageId = message.id,
                conversationId = message.conversationId,
            )
        }
    }

    fun clearDownloadProgress() {
        mutableDownloadProgress.value = null
    }

    private fun stagedFile(fileName: String): File {
        val directory = File(application.filesDir, "outbox").apply { mkdirs() }
        val suffix = fileName.substringAfterLast('.', "")
            .takeIf(String::isNotBlank)
            ?.replace(Regex("[^A-Za-z0-9]"), "")
            ?.take(12)
            ?.let { ".$it" }
            ?: ".bin"
        return File.createTempFile("pending_", suffix, directory)
    }

    private fun analyzeDisplayMeta(
        file: File,
        kind: String,
        mime: String,
        richContent: Boolean,
    ): AttachmentDisplayMeta {
        if (kind !in setOf("image", "photo")) return AttachmentDisplayMeta()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth.coerceAtLeast(0)
        val height = bounds.outHeight.coerceAtLeast(0)
        if (width <= 0 || height <= 0) return AttachmentDisplayMeta()
        var sample = 1
        while (width / sample > 192 && height / sample > 192) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
        val transparent = bitmap?.let(::hasTransparency) ?: false
        bitmap?.recycle()
        val aspect = width.toFloat() / height.toFloat()
        val compact = maxOf(width, height) <= 2048 && file.length() in 1L..2_000_000L && aspect in 0.55f..1.85f
        val sticker = richContent && (transparent || compact) && aspect in 0.45f..2.25f &&
            (mime.startsWith("image/") || mime.equals("application/octet-stream", true))
        return AttachmentDisplayMeta(width, height, sticker)
    }

    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val stepX = (bitmap.width / 36).coerceAtLeast(1)
        val stepY = (bitmap.height / 36).coerceAtLeast(1)
        var transparent = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) < 245) transparent += 1
                total += 1
                x += stepX
            }
            y += stepY
        }
        return total > 0 && transparent.toFloat() / total.toFloat() >= 0.02f
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun progress(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

internal fun ReplyPreview.toWireJson(): String = JSONObject()
    .put("id", id)
    .put("username", username)
    .put("color", color)
    .put("preview", preview)
    .toString()
