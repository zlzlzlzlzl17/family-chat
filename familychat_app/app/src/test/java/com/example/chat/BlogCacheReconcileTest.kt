package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached feed has to converge on the server without depending on the device
 * having been awake at the right moment.
 *
 * The original refresh only ever upserted, so nothing a refresh did could
 * remove a post. Deletions were carried purely by realtime events, which meant a
 * phone that was backgrounded, killed or offline when a post was deleted - or
 * when the admin cleared every post - kept showing it forever, and no amount of
 * pulling to refresh would fix it.
 *
 * `hasMore` comes from the server's cursor, which it sends only when the page
 * filled the limit. A page with no cursor is therefore the entire feed, not
 * merely its newest slice - the distinction these tests exist to hold.
 */
class BlogCacheReconcileTest {

    @Test
    fun anEmptyNewestPageMeansTheServerHasNothing() {
        // Ordered newest-first with no cursor, so "no rows" is not "no rows in
        // this window", it is "no rows at all". This is the cleared-blog case.
        assertEquals(BlogCacheDecision.ClearAll, newestPageDecision(emptyList(), hasMore = false))
        assertEquals(
            emptyList<Long>(),
            survivorsAfterNewestPage(cachedIds = listOf(9L, 8L, 7L), pageIds = emptyList(), hasMore = false),
        )
    }

    @Test
    fun aShortPageIsTheWholeFeedSoStragglersBelowItGo() {
        // The server returned two posts and no cursor: it has exactly two. A
        // cached 7 is a post deleted while this device was not listening, and
        // must not survive just because it sorts below the page.
        val survivors = survivorsAfterNewestPage(
            cachedIds = listOf(10L, 9L, 8L, 7L),
            pageIds = listOf(10L, 8L),
            hasMore = false,
        )
        assertEquals(listOf(10L, 8L), survivors)
    }

    @Test
    fun aFullPageOnlySpeaksForItsOwnWindow() {
        // A cursor means older posts exist that this page never described, so
        // pruning below its floor would erase history the user paged in.
        val survivors = survivorsAfterNewestPage(
            cachedIds = listOf(50L, 40L, 30L, 20L, 10L),
            pageIds = listOf(50L, 40L, 30L),
            hasMore = true,
        )
        assertTrue("older pages were wrongly pruned: $survivors", survivors.containsAll(listOf(20L, 10L)))
        assertEquals(listOf(50L, 40L, 30L, 20L, 10L), survivors)
    }

    @Test
    fun aGapInsideAFullPageIsStillPruned() {
        val survivors = survivorsAfterNewestPage(
            cachedIds = listOf(100L, 99L, 98L, 50L),
            pageIds = listOf(100L, 98L),
            hasMore = true,
        )
        assertEquals(listOf(100L, 98L, 50L), survivors)
    }

    @Test
    fun anUnchangedFeedSurvivesIntact() {
        val cached = listOf(5L, 4L, 3L)
        assertEquals(cached, survivorsAfterNewestPage(cached, cached, hasMore = false))
    }

    @Test
    fun newPostsArriveWithoutDisturbingCachedOnes() {
        val survivors = survivorsAfterNewestPage(
            cachedIds = listOf(10L, 9L),
            pageIds = listOf(11L, 10L, 9L),
            hasMore = false,
        )
        assertEquals(listOf(10L, 9L), survivors)
    }

    @Test
    fun theWindowFloorComesFromTheOldestReturnedPost() {
        val decision = newestPageDecision(listOf(30L, 20L, 25L), hasMore = true)
        assertTrue(decision is BlogCacheDecision.Prune)
        assertEquals(20L, (decision as BlogCacheDecision.Prune).minPostId)
    }

    @Test
    fun awholeFeedPageHasNoFloor() {
        val decision = newestPageDecision(listOf(30L, 20L), hasMore = false)
        assertTrue(decision is BlogCacheDecision.Prune)
        assertEquals(0L, (decision as BlogCacheDecision.Prune).minPostId)
    }
}
