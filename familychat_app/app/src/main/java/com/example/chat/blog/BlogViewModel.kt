package com.example.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

internal class BlogViewModel(container: AppContainer) : ViewModel() {
    private val manager = container.blogManager
    var uiState by mutableStateOf(manager.state.value)
        private set

    init {
        viewModelScope.launch { manager.state.collect { uiState = it } }
    }

    fun refresh() = manager.refresh()
    fun loadOlder() = manager.loadOlder()
    fun publish(body: String, media: List<BlogPendingMedia>, onPublished: (Long) -> Unit) =
        manager.publish(body, media, onPublished)
    fun selectPost(postId: Long) = manager.selectPost(postId)
    fun toggleLike(postId: Long) = manager.toggleLike(postId)
    fun addComment(postId: Long, body: String) = manager.addComment(postId, body)
    fun deletePost(postId: Long, onDeleted: () -> Unit = {}) = manager.deletePost(postId, onDeleted)
    fun deleteComment(commentId: Long) = manager.deleteComment(commentId)
    suspend fun cachedMedia(media: BlogMedia, forceRefresh: Boolean = false) =
        manager.cachedMedia(media, forceRefresh)
    fun clearError() = manager.clearError()
}
