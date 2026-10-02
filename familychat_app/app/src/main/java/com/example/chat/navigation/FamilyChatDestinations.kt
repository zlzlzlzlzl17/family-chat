package com.example.chat

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.navigation.NavBackStackEntry

internal object FamilyChatDestinations {
    const val HOME = "home"
    const val CHAT_PATTERN = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_SECURITY = "settings/security"
    const val ACCOUNT = "account"
    const val CONTACT_PATTERN = "contact/{userCode}"
    const val GROUP_PATTERN = "group/{conversationId}"
    const val PRERELEASE = "settings/about/prerelease"
    const val CALL_PATTERN = "call/{conversationId}"
    const val IMAGE_PATTERN = "image/{conversationId}/{messageId}"
    const val BLOG = "blog"
    const val BLOG_COMPOSE = "blog/compose"
    const val BLOG_POST_PATTERN = "blog/post/{postId}"
    const val BLOG_MEDIA_PATTERN = "blog/media/{postId}/{index}"

    fun chat(conversationId: Long): String = "chat/$conversationId"

    fun contact(userCode: String): String = "contact/${Uri.encode(userCode)}"

    fun group(conversationId: Long): String = "group/$conversationId"

    fun call(conversationId: Long): String = "call/$conversationId"

    fun image(conversationId: Long, messageId: Long): String = "image/$conversationId/$messageId"

    fun blogPost(postId: Long): String = "blog/post/$postId"

    fun blogMedia(postId: Long, index: Int): String = "blog/media/$postId/$index"
}

/**
 * How deep a destination sits in the app.
 *
 * This drives navigation motion: equal depth means the two screens are peers
 * and get a small lateral shared-axis shift, increasing depth means a
 * drill-down and gets the larger slide. Ordering inside the `when` matters -
 * the more specific prefixes have to be tested before the broader ones.
 */
internal fun String.familyChatNavigationDepth(): Int = when {
    this == FamilyChatDestinations.HOME || this == FamilyChatDestinations.BLOG -> 0
    this.startsWith("chat/") || this == FamilyChatDestinations.SETTINGS -> 1
    this == FamilyChatDestinations.BLOG_COMPOSE || this.startsWith("blog/post/") -> 1
    // Sits with the chat image viewer: both are a full-screen photo opened from
    // a piece of content, and both use the scale-and-fade rather than a slide.
    this.startsWith("blog/media/") -> 3
    // Prerelease is reached from About, so it has to read as deeper than the
    // "settings/" tier it would otherwise fall into - otherwise opening it
    // animates as a sideways tab switch.
    this == FamilyChatDestinations.PRERELEASE -> 3
    this.startsWith("settings/") || this == FamilyChatDestinations.ACCOUNT ||
        this.startsWith("contact/") || this.startsWith("group/") -> 2
    this.startsWith("call/") || this.startsWith("image/") -> 3
    else -> 0
}

/**
 * Left-to-right order of the top-level sections, matching the section bar.
 * Used to point the shared-axis slide the same way the tabs are laid out, so
 * moving to Blog always travels right and coming back always travels left.
 */
internal fun String.familyChatSectionIndex(): Int =
    if (this == FamilyChatDestinations.BLOG) 1 else 0

/**
 * Full-screen media opens by scaling up out of the thing that was tapped, not by
 * sliding in from the edge. Both viewers use it, so the gesture means the same
 * thing whether the photo came from a chat or from a post.
 */
internal fun String.isFullScreenMedia(): Boolean =
    this == FamilyChatDestinations.IMAGE_PATTERN || this.startsWith("blog/media/")

/** True when both ends of a transition sit at the same depth. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isPeerMove(): Boolean {
    val from = initialState.destination.route ?: return false
    val to = targetState.destination.route ?: return false
    return from.familyChatNavigationDepth() == to.familyChatNavigationDepth()
}

/** +1 when the move travels right along the section bar, -1 when it travels left. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.lateralSign(): Int {
    val from = initialState.destination.route?.familyChatSectionIndex() ?: 0
    val to = targetState.destination.route?.familyChatSectionIndex() ?: 0
    return if (to < from) -1 else 1
}
