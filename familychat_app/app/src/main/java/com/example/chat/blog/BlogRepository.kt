package com.example.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class BlogRepository(
    private val context: Context,
    private val api: BlogApi,
    private val local: LocalBlogStore,
) {
    fun observePosts(ownerId: String) = local.observePosts(ownerId)

    fun observeComments(ownerId: String, postId: Long) = local.observeComments(ownerId, postId)

    suspend fun refresh(session: SyncSession, cursor: String = ""): BlogPage<BlogPost> = withContext(Dispatchers.IO) {
        val page = api.posts(session.serverUrl, session.accessToken, cursor)
        // A cursor-less page is the newest window and is authoritative for it,
        // so it reconciles - deletions the device never heard about are applied
        // here. A cursored page is one slice of history and only ever adds.
        if (cursor.isBlank()) {
            local.reconcileNewestPage(session.ownerId, page.items, hasMore = page.nextCursor.isNotBlank())
        } else {
            local.upsertPosts(session.ownerId, page.items)
        }
        page
    }

    suspend fun post(session: SyncSession, postId: Long): BlogPost = withContext(Dispatchers.IO) {
        api.post(session.serverUrl, session.accessToken, postId).also {
            local.upsertPost(session.ownerId, it)
        }
    }

    suspend fun create(
        session: SyncSession,
        body: String,
        pending: List<BlogPendingMedia>,
        onProgress: (Long, Long) -> Unit,
    ): BlogPost = withContext(Dispatchers.IO) {
        val uploads = pending.mapIndexed { index, item -> prepareUpload(item, index) }
        try {
            api.createPost(session.serverUrl, session.accessToken, body, uploads, onProgress).also {
                local.upsertPost(session.ownerId, it)
            }
        } finally {
            uploads.forEach { runCatching { it.file.delete() } }
        }
    }

    suspend fun deletePost(session: SyncSession, postId: Long) = withContext(Dispatchers.IO) {
        api.deletePost(session.serverUrl, session.accessToken, postId)
        local.deletePost(session.ownerId, postId)
    }

    suspend fun removeLocalPost(ownerId: String, postId: Long) = withContext(Dispatchers.IO) {
        local.deletePost(ownerId, postId)
    }

    /**
     * Drop the whole cached feed.
     *
     * [refresh] merges what the server returns rather than replacing it, so an
     * emptied server does not empty a device: without this, posts the admin
     * cleared would stay on every phone until each one happened to be deleted
     * individually.
     */
    suspend fun clearLocal(ownerId: String) = withContext(Dispatchers.IO) {
        local.clearOwner(ownerId)
    }

    suspend fun setLiked(session: SyncSession, postId: Long, liked: Boolean): Pair<Boolean, Int> =
        withContext(Dispatchers.IO) {
            api.setLiked(session.serverUrl, session.accessToken, postId, liked).also { (result, count) ->
                local.updateLike(session.ownerId, postId, result, count)
            }
        }

    suspend fun refreshComments(session: SyncSession, postId: Long): List<BlogComment> = withContext(Dispatchers.IO) {
        api.comments(session.serverUrl, session.accessToken, postId).items.also {
            local.replaceComments(session.ownerId, postId, it)
        }
    }

    suspend fun createComment(session: SyncSession, postId: Long, body: String): BlogComment =
        withContext(Dispatchers.IO) {
            api.createComment(session.serverUrl, session.accessToken, postId, body).also {
                local.upsertComment(session.ownerId, it)
            }
        }

    suspend fun deleteComment(session: SyncSession, commentId: Long) = withContext(Dispatchers.IO) {
        api.deleteComment(session.serverUrl, session.accessToken, commentId)
        local.deleteComment(session.ownerId, commentId)
    }

    suspend fun removeLocalComment(ownerId: String, commentId: Long) = withContext(Dispatchers.IO) {
        local.deleteComment(ownerId, commentId)
    }

    suspend fun cachedMedia(
        session: SyncSession,
        media: BlogMedia,
        forceRefresh: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        val extension = media.originalName.substringAfterLast('.', "bin").take(8).replace(Regex("[^A-Za-z0-9]"), "")
        val key = "${session.ownerId}|${session.serverUrl}|${media.id}|${media.fileSize}".sha256()
        val target = File(File(context.cacheDir, "blog_media").apply { mkdirs() }, "$key.$extension")
        if (forceRefresh) target.delete()
        if (target.isFile && target.length() == media.fileSize) target
        else api.downloadMedia(session.serverUrl, session.accessToken, media, target)
    }

    private fun prepareUpload(item: BlogPendingMedia, index: Int): BlogUploadMedia {
        val uri = Uri.parse(item.uri)
        val safeName = item.displayName.ifBlank { "blog-media-$index" }.take(180)
        val extension = safeName.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), "").take(8)
        val target = File(context.cacheDir, "blog_upload_${System.nanoTime()}_$index.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("blog_media_unreadable")
        var width = 0
        var height = 0
        var duration = 0L
        if (item.mimeType.startsWith("image/")) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, options)
            width = options.outWidth.coerceAtLeast(0)
            height = options.outHeight.coerceAtLeast(0)
        } else if (item.mimeType.startsWith("video/")) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(target.absolutePath)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }
            }
        }
        return BlogUploadMedia(target, safeName, item.mimeType, width, height, duration)
    }

    companion object {
        fun pendingMedia(context: Context, uri: Uri): BlogPendingMedia {
            var name = "media"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty().ifBlank { name }
            }
            return BlogPendingMedia(
                uri = uri.toString(),
                displayName = name,
                mimeType = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" },
            )
        }

        private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
