package com.example.chat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Resolved colours for one chat bubble.
 *
 * This type used to live in `message/RichComposerBridge.kt`. That mattered more
 * than it looked: because the read-receipt colour was declared beside it in a
 * feature file rather than in this package, it never picked up light/dark
 * handling and ended up failing contrast in light mode. Component tokens belong
 * next to the theme that resolves them.
 *
 * Values are produced by `chatBubbleTokens()`, which reads the active
 * MaterialTheme colour scheme.
 */
internal data class ChatBubbleTokens(
    val container: Color,
    val content: Color,
    val muted: Color,
    val outline: Color,
    val replyContainer: Color,
    /**
     * Colour of the "read" tick on this bubble. The tick is drawn on the bubble,
     * so it must contrast with the bubble rather than with the page surface -
     * which is why this is per-bubble rather than a single global constant.
     */
    val readReceipt: Color,
)
