package com.example.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class BlogManager(
    private val auth: AuthSessionManager,
    private val repository: BlogRepository,
    private val realtime: RealtimeGateway,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(BlogUiState())
    val state: StateFlow<BlogUiState> = mutableState.asStateFlow()
    private var postsJob: Job? = null
    private var commentsJob: Job? = null
    private var activeOwnerId = ""
    private var realtimeRefreshJob: Job? = null

    init {
        scope.launch {
            auth.state.collect { authState ->
                if (authState.phase == AuthSessionPhase.AUTHENTICATED) bindSession()
                else unbindSession()
            }
        }
        scope.launch {
            realtime.events.collect { payload ->
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: return@collect
                val type = event.optString("type")
                if (!type.startsWith("blog_")) return@collect
                val session = auth.sessionSnapshot()
                if (!session.isReady) return@collect
                when (type) {
                    "blog_post_deleted" -> {
                        val postId = event.optLong("post_id")
                        if (postId > 0L) repository.removeLocalPost(session.ownerId, postId)
                    }
                    "blog_cleared" -> {
                        repository.clearLocal(session.ownerId)
                        mutableState.update { it.copy(selectedPostId = 0L, comments = emptyList(), nextCursor = "") }
                        refreshAfterRealtimeEvent(session, 0L, refreshComments = false)
                    }
                    "blog_comment_deleted" -> {
                        val commentId = event.optLong("comment_id")
                        if (commentId > 0L) repository.removeLocalComment(session.ownerId, commentId)
                        refreshAfterRealtimeEvent(session, event.optLong("post_id"), refreshComments = true)
                    }
                    "blog_comment_created" -> {
                        val postId = event.optJSONObject("item")?.optLong("post_id") ?: 0L
                        refreshAfterRealtimeEvent(session, postId, refreshComments = true)
                    }
                    else -> refreshAfterRealtimeEvent(session, event.optLong("post_id"), refreshComments = false)
                }
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || (mutableState.value.isRefreshing && !silent)) return
        scope.launch {
            if (!silent) mutableState.update { it.copy(isRefreshing = true, error = null) }
            runCatching { repository.refresh(session) }
                .onSuccess { page -> mutableState.update { it.copy(nextCursor = page.nextCursor) } }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "blog_refresh_failed") } }
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun loadOlder() {
        val cursor = mutableState.value.nextCursor
        val session = auth.sessionSnapshot()
        if (!session.isReady || cursor.isBlank() || mutableState.value.isLoadingOlder) return
        scope.launch {
            mutableState.update { it.copy(isLoadingOlder = true) }
            runCatching { repository.refresh(session, cursor) }
                .onSuccess { page -> mutableState.update { it.copy(nextCursor = page.nextCursor) } }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "blog_refresh_failed") } }
            mutableState.update { it.copy(isLoadingOlder = false) }
        }
    }

    fun publish(body: String, media: List<BlogPendingMedia>, onPublished: (Long) -> Unit) {
        val session = auth.sessionSnapshot()
        if (!session.isReady || mutableState.value.isPublishing) return
        scope.launch {
            mutableState.update { it.copy(isPublishing = true, uploadProgress = 0f, error = null) }
            runCatching {
                repository.create(session, body.trim(), media) { done, total ->
                    mutableState.update {
                        it.copy(uploadProgress = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f)
                    }
                }
            }.onSuccess { post ->
                mutableState.update { it.copy(isPublishing = false, uploadProgress = 1f) }
                dispatchUi { onPublished(post.id) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(isPublishing = false, uploadProgress = 0f, error = error.message ?: "blog_publish_failed")
                }
            }
        }
    }

    fun selectPost(postId: Long) {
        mutableState.update { it.copy(selectedPostId = postId, comments = emptyList()) }
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        commentsJob?.cancel()
        commentsJob = scope.launch {
            repository.observeComments(session.ownerId, postId).collect { comments ->
                mutableState.update { it.copy(comments = comments) }
            }
        }
        scope.launch {
            runCatching { repository.post(session, postId) }
            runCatching { repository.refreshComments(session, postId) }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "blog_comments_failed") } }
        }
    }

    fun toggleLike(postId: Long) {
        val session = auth.sessionSnapshot()
        val post = mutableState.value.posts.firstOrNull { it.id == postId } ?: return
        if (!session.isReady) return
        val desired = !post.likedByMe
        scope.launch {
            repository.setLiked(session, postId, desired)
                .also { repository.post(session, postId) }
        }
    }

    fun addComment(postId: Long, body: String) {
        val session = auth.sessionSnapshot()
        val normalized = body.trim()
        if (!session.isReady || normalized.isBlank()) return
        scope.launch {
            runCatching { repository.createComment(session, postId, normalized) }
                .onSuccess {
                    val count = mutableState.value.comments.size
                    repository.post(session, postId)
                    repository.refreshComments(session, postId)
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "blog_comment_failed") } }
        }
    }

    fun deletePost(postId: Long, onDeleted: () -> Unit = {}) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        scope.launch {
            runCatching { repository.deletePost(session, postId) }
                .onSuccess {
                    mutableState.update { it.copy(selectedPostId = 0L, comments = emptyList()) }
                    dispatchUi(onDeleted)
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "blog_delete_failed") } }
        }
    }

    fun deleteComment(commentId: Long) {
        val session = auth.sessionSnapshot()
        val postId = mutableState.value.selectedPostId
        if (!session.isReady || postId <= 0L) return
        scope.launch {
            runCatching { repository.deleteComment(session, commentId) }
                .onSuccess { repository.post(session, postId) }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "blog_comment_delete_failed") } }
        }
    }

    suspend fun cachedMedia(media: BlogMedia, forceRefresh: Boolean = false): java.io.File {
        val initialSession = auth.sessionSnapshot()
        return try {
            repository.cachedMedia(initialSession, media, forceRefresh)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (
                error.requiresAccessTokenRefresh() &&
                auth.refreshAccessTokenAfterUnauthorized(initialSession.accessToken, "blog_media")
            ) {
                runCatching {
                    repository.cachedMedia(auth.sessionSnapshot(), media, forceRefresh)
                }.getOrElse { retryError ->
                    if (retryError is CancellationException) throw retryError
                    logMediaFailure(media, retryError)
                    throw retryError
                }
            } else {
                logMediaFailure(media, error)
                throw error
            }
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun dispatchUi(action: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { action() }
    }

    private fun logMediaFailure(media: BlogMedia, error: Throwable) {
        FamilyChatDiagnostics.event(
            "blog_media_load_failed",
            "media_id" to media.id,
            "status" to (error as? ChatApiException)?.statusCode,
            "error" to error.javaClass.simpleName,
            "reason" to error.message.orEmpty(),
        )
    }

    private fun refreshAfterRealtimeEvent(session: SyncSession, postId: Long, refreshComments: Boolean) {
        realtimeRefreshJob?.cancel()
        realtimeRefreshJob = scope.launch {
            delay(180)
            refresh(silent = true)
            if (refreshComments && postId > 0L && mutableState.value.selectedPostId == postId) {
                runCatching { repository.refreshComments(session, postId) }
            }
        }
    }

    private fun bindSession() {
        val session = auth.sessionSnapshot()
        if (!session.isReady || activeOwnerId == session.ownerId) return
        activeOwnerId = session.ownerId
        mutableState.update {
            it.copy(
                serverUrl = session.serverUrl,
                currentUserCode = auth.state.value.me?.userCode.orEmpty(),
            )
        }
        postsJob?.cancel()
        postsJob = scope.launch {
            repository.observePosts(session.ownerId).collect { posts ->
                mutableState.update { it.copy(posts = posts) }
            }
        }
        refresh(silent = true)
    }

    private fun unbindSession() {
        postsJob?.cancel()
        commentsJob?.cancel()
        postsJob = null
        commentsJob = null
        activeOwnerId = ""
        mutableState.value = BlogUiState()
    }
}
