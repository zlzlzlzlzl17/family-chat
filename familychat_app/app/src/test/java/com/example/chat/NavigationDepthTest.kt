package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The depth ladder decides which motion a navigation gets: equal depth is a
 * lateral shared-axis move between peers, increasing depth is a drill-down.
 *
 * It is worth a test because the failure is silent. `familyChatNavigationDepth`
 * existed for a while but was never called, so Chats and Blog - peers by every
 * other definition in the app - were animated as parent and child, and nothing
 * flagged it.
 */
class NavigationDepthTest {

    @Test
    fun chatsAndBlogArePeers() {
        assertEquals(
            FamilyChatDestinations.HOME.familyChatNavigationDepth(),
            FamilyChatDestinations.BLOG.familyChatNavigationDepth(),
        )
    }

    @Test
    fun blogSubScreensSitBelowTheFeed() {
        val feed = FamilyChatDestinations.BLOG.familyChatNavigationDepth()
        assertTrue(FamilyChatDestinations.BLOG_COMPOSE.familyChatNavigationDepth() > feed)
        assertTrue(FamilyChatDestinations.BLOG_POST_PATTERN.familyChatNavigationDepth() > feed)
    }

    @Test
    fun prereleaseIsDeeperThanTheAboutScreenItOpensFrom() {
        // Prerelease matches the broad "settings/" prefix, so it used to land on
        // the same rung as About and open with a sideways tab slide.
        assertTrue(
            FamilyChatDestinations.PRERELEASE.familyChatNavigationDepth() >
                FamilyChatDestinations.SETTINGS_ABOUT.familyChatNavigationDepth()
        )
    }

    @Test
    fun settingsScreensSitBelowTheSettingsMenu() {
        val menu = FamilyChatDestinations.SETTINGS.familyChatNavigationDepth()
        for (route in listOf(
            FamilyChatDestinations.SETTINGS_GENERAL,
            FamilyChatDestinations.SETTINGS_ABOUT,
            FamilyChatDestinations.SETTINGS_SECURITY,
        )) {
            assertTrue("$route should be deeper than the settings menu", route.familyChatNavigationDepth() > menu)
        }
    }

    @Test
    fun chatIsDeeperThanTheConversationList() {
        assertTrue(
            FamilyChatDestinations.CHAT_PATTERN.familyChatNavigationDepth() >
                FamilyChatDestinations.HOME.familyChatNavigationDepth()
        )
    }

    @Test
    fun bothMediaViewersOpenTheSameWay() {
        // The two full-screen viewers must scale up out of the tapped thing
        // rather than slide in, or opening a photo means something different
        // depending on whether it arrived in a chat or in a post.
        assertTrue(FamilyChatDestinations.IMAGE_PATTERN.isFullScreenMedia())
        assertTrue(FamilyChatDestinations.BLOG_MEDIA_PATTERN.isFullScreenMedia())
        assertEquals(
            FamilyChatDestinations.IMAGE_PATTERN.familyChatNavigationDepth(),
            FamilyChatDestinations.BLOG_MEDIA_PATTERN.familyChatNavigationDepth(),
        )
        // The blog post screen is not a viewer; it must keep the normal slide.
        assertFalse(FamilyChatDestinations.BLOG_POST_PATTERN.isFullScreenMedia())
        assertTrue(
            FamilyChatDestinations.BLOG_MEDIA_PATTERN.familyChatNavigationDepth() >
                FamilyChatDestinations.BLOG_POST_PATTERN.familyChatNavigationDepth()
        )
    }

    @Test
    fun sectionOrderMatchesTheSectionBar() {
        // Chats sits left of Blog in the bar, so the slide has to travel right
        // when opening Blog and left when returning.
        assertTrue(
            FamilyChatDestinations.HOME.familyChatSectionIndex() <
                FamilyChatDestinations.BLOG.familyChatSectionIndex()
        )
        // Everything that is not a top-level section is treated as the first
        // one, so a drill-down never picks up a lateral direction by accident.
        assertEquals(0, FamilyChatDestinations.CHAT_PATTERN.familyChatSectionIndex())
        assertEquals(0, FamilyChatDestinations.BLOG_COMPOSE.familyChatSectionIndex())
    }
}
