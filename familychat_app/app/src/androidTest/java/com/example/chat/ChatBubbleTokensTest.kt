package com.example.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chat.ui.theme.ChatBubbleTokens
import com.example.chat.ui.theme.ChatTheme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the read-receipt tick against the defect fixed in v3.0.17.
 *
 * The tick was a single fixed blue applied in both themes and measured 1.76:1
 * against the light outgoing bubble - well under the 3:1 WCAG 1.4.11 minimum for
 * non-text UI components - while dark mode happened to pass. These tests assert
 * the requirement (sufficient contrast in both themes) rather than a specific
 * hex value, so a future palette change cannot reintroduce the bug while still
 * looking like it satisfies the test.
 *
 * Instrumented: needs a connected device or emulator.
 * Run with: gradlew :app:connectedBetaDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ChatBubbleTokensTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** WCAG 2.1 contrast ratio between two opaque colours. */
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun tokensFor(mode: AppDisplayMode, mine: Boolean): ChatBubbleTokens {
        var captured: ChatBubbleTokens? = null
        composeRule.setContent {
            ChatTheme(displayMode = mode) {
                captured = chatBubbleTokens(mine = mine)
            }
        }
        composeRule.waitForIdle()
        return requireNonNull(captured)
    }

    private fun requireNonNull(tokens: ChatBubbleTokens?): ChatBubbleTokens =
        tokens ?: throw AssertionError("chatBubbleTokens() produced no value")

    @Test
    fun readTickMeetsContrastOnOutgoingBubbleInLightMode() {
        val tokens = tokensFor(AppDisplayMode.LIGHT, mine = true)
        val ratio = contrastRatio(tokens.readReceipt, tokens.container)
        assertTrue(
            "Read tick contrast on the light outgoing bubble is $ratio:1, below the 3:1 minimum",
            ratio >= 3.0f,
        )
    }

    @Test
    fun readTickMeetsContrastOnOutgoingBubbleInDarkMode() {
        val tokens = tokensFor(AppDisplayMode.DARK, mine = true)
        val ratio = contrastRatio(tokens.readReceipt, tokens.container)
        assertTrue(
            "Read tick contrast on the dark outgoing bubble is $ratio:1, below the 3:1 minimum",
            ratio >= 3.0f,
        )
    }

    @Test
    fun readTickIsDistinguishableFromTheDeliveredTick() {
        // Two grey ticks and two blue ticks are the same glyph; colour is the
        // only thing separating "delivered" from "actually read".
        val light = tokensFor(AppDisplayMode.LIGHT, mine = true)
        assertNotEquals(
            "Read and delivered ticks render identically, so the states are indistinguishable",
            light.readReceipt,
            light.muted,
        )
    }

    @Test
    fun readTickAdaptsToTheme() {
        // The original defect was one fixed colour reused across both themes.
        val light = tokensFor(AppDisplayMode.LIGHT, mine = true)
        val dark = tokensFor(AppDisplayMode.DARK, mine = true)
        assertNotEquals(
            "Read tick is theme-independent, which is how it previously failed light mode",
            light.readReceipt,
            dark.readReceipt,
        )
    }

    @Test
    fun bubbleTextMeetsContrastInBothThemesAndDirections() {
        for (mode in listOf(AppDisplayMode.LIGHT, AppDisplayMode.DARK)) {
            for (mine in listOf(true, false)) {
                val tokens = tokensFor(mode, mine)
                val ratio = contrastRatio(tokens.content, tokens.container)
                assertTrue(
                    "Body text contrast is $ratio:1 for mode=$mode mine=$mine, below the 4.5:1 minimum",
                    ratio >= 4.5f,
                )
            }
        }
    }
}
