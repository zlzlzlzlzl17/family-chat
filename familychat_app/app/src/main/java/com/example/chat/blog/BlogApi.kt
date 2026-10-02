package com.example.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal class BlogApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun base(url: String) = url.trim().trimEnd('/')

    private fun request(url: String, token: String) = Request.Builder()
        .url(url)
        .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
        .header("Authorization", "Bearer $token")

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    .orEmpty().ifBlank { response.message.ifBlank { "blog_request_failed" } }
                throw ChatApiException(response.code, code)
            }
            return text
        }
    }

    fun posts(serverUrl: String, token: String, cursor: String = "", limit: Int = 20): BlogPage<BlogPost> {
        val suffix = buildString {
            append("?limit=").append(limit.coerceIn(1, 50))
            cursor.toLongOrNull()?.takeIf { it > 0L }?.let { append("&cursor=").append(it) }
        }
        val json = JSONObject(execute(request("${base(serverUrl)}/api/blog/posts$suffix", token).get().build()))
        return BlogPage(parsePosts(json.optJSONArray("items")), json.optString("next_cursor"))
    }

    fun post(serverUrl: String, token: String, postId: Long): BlogPost {
        val json = JSONObject(execute(request("${base(serverUrl)}/api/blog/posts/$postId", token).get().build()))
        return parsePost(json.getJSONObject("item"))
    }

    fun createPost(
        serverUrl: String,
        token: String,
        body: String,
        media: List<BlogUploadMedia>,
        onProgress: (Long, Long) -> Unit,
    ): BlogPost {
        val metadata = JSONArray()
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("body", body)
        media.forEach { item ->
            metadata.put(
                JSONObject()
                    .put("width", item.width)
                    .put("height", item.height)
                    .put("duration_ms", item.durationMs)
            )
            multipart.addFormDataPart(
                "media",
                item.originalName,
                item.file.asRequestBody(item.mimeType.toMediaType()),
            )
        }
        multipart.addFormDataPart("media_metadata", metadata.toString())
        val raw = multipart.build()
        val progressBody = object : RequestBody() {
            override fun contentType() = raw.contentType()
            override fun contentLength() = raw.contentLength()
            override fun writeTo(sink: BufferedSink) {
                var written = 0L
                val forwarding = object : ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        written += byteCount
                        onProgress(written, contentLength())
                    }
                }.buffer()
                raw.writeTo(forwarding)
                forwarding.flush()
            }
        }
        val json = JSONObject(
            execute(request("${base(serverUrl)}/api/blog/posts", token).post(progressBody).build())
        )
        return parsePost(json.getJSONObject("item"))
    }

    fun deletePost(serverUrl: String, token: String, postId: Long) {
        execute(request("${base(serverUrl)}/api/blog/posts/$postId", token).delete().build())
    }

    fun setLiked(serverUrl: String, token: String, postId: Long, liked: Boolean): Pair<Boolean, Int> {
        val builder = request("${base(serverUrl)}/api/blog/posts/$postId/like", token)
        val request = if (liked) builder.put(ByteArray(0).toRequestBody(null)).build() else builder.delete().build()
        val json = JSONObject(execute(request))
        return json.optBoolean("liked") to json.optInt("like_count")
    }

    fun comments(serverUrl: String, token: String, postId: Long): BlogPage<BlogComment> {
        val json = JSONObject(
            execute(request("${base(serverUrl)}/api/blog/posts/$postId/comments?limit=100", token).get().build())
        )
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = List(array.length()) { parseComment(array.getJSONObject(it)) }.reversed()
        return BlogPage(items, json.optString("next_cursor"))
    }

    fun createComment(serverUrl: String, token: String, postId: Long, body: String): BlogComment {
        val payload = JSONObject().put("body", body).toString().toRequestBody("application/json".toMediaType())
        val json = JSONObject(
            execute(request("${base(serverUrl)}/api/blog/posts/$postId/comments", token).post(payload).build())
        )
        return parseComment(json.getJSONObject("item"))
    }

    fun deleteComment(serverUrl: String, token: String, commentId: Long) {
        execute(request("${base(serverUrl)}/api/blog/comments/$commentId", token).delete().build())
    }

    fun downloadMedia(serverUrl: String, token: String, media: BlogMedia, target: File): File {
        val url = if (media.url.startsWith("http")) media.url else base(serverUrl) + media.url
        val request = request(url, token).get().build()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".part")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    .ifBlank { "blog_media_download_failed" }
                throw ChatApiException(response.code, code)
            }
            try {
                response.body?.byteStream()?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IllegalStateException("blog_media_missing")
                if (media.fileSize > 0L && temporary.length() != media.fileSize) {
                    throw IllegalStateException("blog_media_size_mismatch")
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }
        return target
    }

    companion object {
        fun parsePosts(array: JSONArray?): List<BlogPost> {
            val source = array ?: JSONArray()
            return List(source.length()) { parsePost(source.getJSONObject(it)) }
        }

        fun parsePost(json: JSONObject): BlogPost {
            val author = json.optJSONObject("author") ?: JSONObject()
            val media = json.optJSONArray("media") ?: JSONArray()
            return BlogPost(
                id = json.getLong("id"),
                body = json.optString("body"),
                createdAt = json.optLong("created_at"),
                updatedAt = json.optLong("updated_at"),
                author = BlogAuthor(
                    userCode = author.optString("user_code"),
                    username = author.optString("username"),
                    color = author.optString("color"),
                    avatarUrl = author.optString("avatar_url"),
                ),
                likeCount = json.optInt("like_count"),
                commentCount = json.optInt("comment_count"),
                likedByMe = json.optBoolean("liked_by_me"),
                isAnnouncement = json.optBoolean("is_announcement"),
                media = List(media.length()) { index ->
                    val item = media.getJSONObject(index)
                    BlogMedia(
                        id = item.getLong("id"),
                        kind = item.optString("kind"),
                        mimeType = item.optString("mime_type"),
                        originalName = item.optString("original_name"),
                        fileSize = item.optLong("file_size"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        durationMs = item.optLong("duration_ms"),
                        url = item.optString("url"),
                    )
                },
            )
        }

        fun parseComment(json: JSONObject): BlogComment {
            val author = json.optJSONObject("author") ?: JSONObject()
            return BlogComment(
                id = json.getLong("id"),
                postId = json.getLong("post_id"),
                body = json.optString("body"),
                createdAt = json.optLong("created_at"),
                updatedAt = json.optLong("updated_at"),
                author = BlogAuthor(
                    userCode = author.optString("user_code"),
                    username = author.optString("username"),
                    color = author.optString("color"),
                    avatarUrl = author.optString("avatar_url"),
                ),
            )
        }
    }
}
