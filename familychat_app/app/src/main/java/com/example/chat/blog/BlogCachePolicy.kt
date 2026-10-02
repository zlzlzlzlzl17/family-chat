package com.example.chat

/**
 * What a refresh of the newest page means for the cached feed.
 *
 * Split out from [LocalBlogStore] so the rule can be tested without Room. The
 * store applies exactly this decision and nothing else, so the SQL and the test
 * cannot drift apart.
 */
internal sealed interface BlogCacheDecision {
    /** The server returned nothing, so it holds nothing. */
    data object ClearAll : BlogCacheDecision

    /**
     * Anything cached at or above [minPostId] and absent from [keepIds] no
     * longer exists on the server. A [minPostId] of 0 means the page was the
     * entire feed, so nothing outside it survives.
     */
    data class Prune(val minPostId: Long, val keepIds: List<Long>) : BlogCacheDecision
}

/**
 * @param pageIds ids in the newest page, as returned by the server.
 * @param hasMore whether the server said older posts exist - it sends a cursor
 *   only when the page filled the limit, so a blank cursor means this page *is*
 *   the whole feed. Without that distinction a short page would only be trusted
 *   for its own range, and posts deleted below it would survive forever.
 */
internal fun newestPageDecision(pageIds: List<Long>, hasMore: Boolean): BlogCacheDecision = when {
    pageIds.isEmpty() -> BlogCacheDecision.ClearAll
    hasMore -> BlogCacheDecision.Prune(minPostId = pageIds.min(), keepIds = pageIds)
    else -> BlogCacheDecision.Prune(minPostId = 0L, keepIds = pageIds)
}

/**
 * Which cached posts survive the refresh.
 *
 * Mirrors the delete in [BlogDao.pruneMissingPosts] - remove where
 * `postId >= minPostId AND postId NOT IN keepIds` - so a survivor is a post
 * below the window or one the server just confirmed.
 */
internal fun survivorsAfterNewestPage(
    cachedIds: List<Long>,
    pageIds: List<Long>,
    hasMore: Boolean,
): List<Long> = when (val decision = newestPageDecision(pageIds, hasMore)) {
    BlogCacheDecision.ClearAll -> emptyList()
    is BlogCacheDecision.Prune ->
        cachedIds.filter { it < decision.minPostId || decision.keepIds.contains(it) }
}
