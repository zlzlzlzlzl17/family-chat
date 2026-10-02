package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun decryptAttachmentFileKey(message: ChatMessage, prefs: ChatPreferences?): String? {
    val payload = runCatching { JSONObject(message.payload) }.getOrNull() ?: return null
    if (payload.optInt("v", 0) < 3 || !payload.has("key_wrap")) return null
    val wrap = payload.optJSONObject("key_wrap") ?: return null
    val wrappedPayload = wrap.optString("payload")
    if (wrappedPayload.isBlank()) return null
    prefs?.attachmentFileKey(message)?.takeIf { it.isNotBlank() }?.let { cachedKey ->
        forgetDirectAttachmentWrapKey(prefs, message, wrap.optString("scheme"), wrappedPayload)
        return cachedKey
    }
    val plain = when (wrap.optString("scheme")) {
        "direct-dr" -> prefs?.let { DirectMessageCrypto.decryptText(it, message.conversationId, wrappedPayload) }
        "group-sender" -> prefs?.let { GroupSenderKeyCrypto.decryptText(it, message.conversationId, wrappedPayload) }
        else -> null
    } ?: return null
    val fileKey = JSONObject(plain).optString("file_key").takeIf { it.isNotBlank() } ?: return null
    if (prefs != null && message.id > 0L) {
        prefs.putAttachmentFileKey(message, fileKey)
        forgetDirectAttachmentWrapKey(prefs, message, wrap.optString("scheme"), wrappedPayload)
    }
    return fileKey
}

private fun forgetDirectAttachmentWrapKey(
    prefs: ChatPreferences,
    message: ChatMessage,
    scheme: String,
    wrappedPayload: String,
) {
    if (scheme != "direct-dr" || message.id <= 0L) return
    DirectMessageCrypto.forgetMessageKey(
        prefs,
        message.copy(
            kind = "text",
            payload = wrappedPayload,
            e2ee = true,
        ),
    )
}

fun decryptAttachmentMeta(message: ChatMessage, prefs: ChatPreferences?): JSONObject? {
    val payload = runCatching { JSONObject(message.payload) }.getOrNull() ?: return null
    return if (payload.optInt("v", 0) >= 3 && payload.has("key_wrap")) {
        val fileKey = decryptAttachmentFileKey(message, prefs) ?: return null
        JSONObject(
            ChatCrypto.decryptTextWithKey(
                fileKey,
                payload.getString("meta"),
                ChatCrypto.attachmentMetaAad(message.conversationId, message.kind)
            )
        )
    } else {
        null
    }
}

fun resolveAttachment(
    context: Context,
    api: ChatApi,
    serverUrl: String,
    message: ChatMessage,
    prefs: ChatPreferences? = null,
    onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    exportToDownloads: Boolean = true,
): DecryptedAttachment {
    val payload = JSONObject(message.payload)
    val attachment = payload.optJSONObject("attachment")
    val isEncryptedAttachment =
        message.e2ee &&
            attachment != null &&
            payload.has("meta") &&
            payload.has("enc")

    val meta = if (isEncryptedAttachment) {
        decryptAttachmentMeta(message, prefs) ?: throw IllegalStateException("Unable to decrypt attachment")
    } else {
        JSONObject()
            .put("name", payload.optString("name").ifBlank { attachment?.optString("file").orEmpty() })
            .put(
                "mime",
                payload.optString("mime")
                    .ifBlank { attachment?.optString("mime").orEmpty() }
                    .ifBlank { "application/octet-stream" }
            )
            .put("size", payload.optLong("size", 0L))
    }

    val fileName = (meta.optString("name").ifBlank { "${message.kind}.bin" }).replace(Regex("[^A-Za-z0-9._-]"), "_")
    val mime = meta.optString("mime").ifBlank { "application/octet-stream" }
    val declaredSize = meta.optLong("size", 0L)
    val cacheFile = attachmentCacheFile(context, message.id, fileName)
    if (cacheFile.exists()) {
        return DecryptedAttachment(
            name = meta.optString("name").ifBlank { fileName },
            mime = mime,
            size = declaredSize.takeIf { it > 0L } ?: cacheFile.length(),
            file = cacheFile,
            uri = if (exportToDownloads) cachedAttachmentUri(context, message.id, fileName) else null
        )
    }

    if (isEncryptedAttachment) {
        val enc = payload.getJSONObject("enc")
        val encryptedTemp = File.createTempFile("download_", ".enc", context.cacheDir)
        try {
            api.downloadToFile(serverUrl, attachment!!.getString("url"), encryptedTemp, onProgress)
            val fileKey = decryptAttachmentFileKey(message, prefs)
                ?: throw IllegalStateException("Unable to decrypt attachment key")
            ChatCrypto.decryptBinaryFileWithKey(
                fileKey = fileKey,
                iv = enc.getString("iv"),
                sourceFile = encryptedTemp,
                targetFile = cacheFile,
                aad = ChatCrypto.attachmentFileAad(message.conversationId, message.kind)
            )
        } finally {
            encryptedTemp.delete()
        }
    } else {
        val downloadUrl = attachment?.optString("url").orEmpty().ifBlank { payload.optString("url") }
        require(downloadUrl.isNotBlank()) { "Bad attachment" }
        api.downloadToFile(serverUrl, downloadUrl, cacheFile, onProgress)
    }
    val plainBytesForExport = if (exportToDownloads) cacheFile.readBytes() else ByteArray(0)
    val downloadUri = if (exportToDownloads) {
        runCatching {
            ensureAttachmentInDownloads(context, message.id, fileName, mime, plainBytesForExport)
        }.getOrNull()
    } else {
        null
    }
    return DecryptedAttachment(
        name = meta.optString("name").ifBlank { fileName },
        mime = mime,
        size = declaredSize.takeIf { it > 0L } ?: cacheFile.length(),
        file = cacheFile,
        uri = downloadUri
    )
}

fun attachmentCacheFile(context: Context, messageId: Long, fileName: String): File {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    return File(sharedDir, "${messageId}_$fileName")
}

private fun attachmentUriMetaFile(context: Context, messageId: Long, fileName: String): File {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    return File(sharedDir, "${messageId}_$fileName.uri")
}

fun cachedAttachmentUri(context: Context, messageId: Long, fileName: String): Uri? {
    val metaFile = attachmentUriMetaFile(context, messageId, fileName)
    if (!metaFile.exists()) return null
    return runCatching { Uri.parse(metaFile.readText()) }.getOrNull()
}

private fun rememberAttachmentUri(context: Context, messageId: Long, fileName: String, uri: Uri) {
    attachmentUriMetaFile(context, messageId, fileName).writeText(uri.toString())
}

fun ensureAttachmentInDownloads(
    context: Context,
    messageId: Long,
    fileName: String,
    mime: String,
    bytes: ByteArray,
): Uri {
    cachedAttachmentUri(context, messageId, fileName)?.let { return it }
    val uri = saveBytesToDownloads(context, fileName, mime, bytes)
    rememberAttachmentUri(context, messageId, fileName, uri)
    return uri
}

fun saveBytesToDownloads(
    context: Context,
    fileName: String,
    mime: String,
    bytes: ByteArray,
): Uri {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Unable to save file"
        }
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Unable to open download location")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    } else {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Download directory unavailable")
        val outFile = File(downloadsDir, fileName)
        outFile.writeBytes(bytes)
        Uri.fromFile(outFile)
    }
}
