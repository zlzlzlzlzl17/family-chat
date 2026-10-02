package com.example.chat

import java.io.File

internal data class BlogAuthor(
    val userCode: String,
    val username: String,
    val color: String,
    val avatarUrl: String,
)

internal data class BlogMedia(
    val id: Long,
    val kind: String,
    val mimeType: String,
    val originalName: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val url: String,
)

internal data class BlogPost(
    val id: Long,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val author: BlogAuthor,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
    val isAnnouncement: Boolean,
    val media: List<BlogMedia>,
)

internal data class BlogComment(
    val id: Long,
    val postId: Long,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val author: BlogAuthor,
)

internal data class BlogPage<T>(
    val items: List<T>,
    val nextCursor: String,
)

internal data class BlogUploadMedia(
    val file: File,
    val originalName: String,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
)

internal data class BlogPendingMedia(
    val uri: String,
    val displayName: String,
    val mimeType: String,
)

internal data class BlogUiState(
    val serverUrl: String = "",
    val currentUserCode: String = "",
    val posts: List<BlogPost> = emptyList(),
    val selectedPostId: Long = 0L,
    val comments: List<BlogComment> = emptyList(),
    val nextCursor: String = "",
    val isRefreshing: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isPublishing: Boolean = false,
    val uploadProgress: Float = 0f,
    val error: String? = null,
) {
    val selectedPost: BlogPost?
        get() = posts.firstOrNull { it.id == selectedPostId }
}
