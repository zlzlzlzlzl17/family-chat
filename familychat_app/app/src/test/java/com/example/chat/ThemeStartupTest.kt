package com.example.chat

import androidx.compose.ui.unit.isSpecified
import com.example.chat.ui.theme.ChatShapes
import com.example.chat.ui.theme.FullShape
import com.example.chat.ui.theme.Spacing
import com.example.chat.ui.theme.Typography
import com.example.chat.ui.theme.chatTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pieces of the theme that run before anything is drawn.
 *
 * A failure in any of these is an immediate crash on launch with no UI to show
 * an error in, so they are worth covering even though they look trivial: a
 * TextUnit arithmetic error or a static-initialiser cycle here takes the whole
 * app down before the first frame.
 */
class ThemeStartupTest {

    @Test
    fun typographyRampIsFullyDefined() {
        // Every role must carry a specified size and line height. An unspecified
        // one is invisible until something multiplies it, at which point
        // TextUnit arithmetic throws.
        val roles = listOf(
            "displayLarge" to Typography.displayLarge,
            "displayMedium" to Typography.displayMedium,
            "displaySmall" to Typography.displaySmall,
            "headlineLarge" to Typography.headlineLarge,
            "headlineMedium" to Typography.headlineMedium,
            "headlineSmall" to Typography.headlineSmall,
            "titleLarge" to Typography.titleLarge,
            "titleMedium" to Typography.titleMedium,
            "titleSmall" to Typography.titleSmall,
            "bodyLarge" to Typography.bodyLarge,
            "bodyMedium" to Typography.bodyMedium,
            "bodySmall" to Typography.bodySmall,
            "labelLarge" to Typography.labelLarge,
            "labelMedium" to Typography.labelMedium,
            "labelSmall" to Typography.labelSmall,
        )
        for ((name, style) in roles) {
            assertTrue("$name has an unspecified fontSize", style.fontSize.isSpecified)
            assertTrue("$name has an unspecified lineHeight", style.lineHeight.isSpecified)
        }
    }

    @Test
    fun everyTextSizeProducesAUsableRamp() {
        for (size in AppTextSize.entries) {
            val ramp = chatTypography(size)
            assertNotNull("chatTypography($size) returned null", ramp)
            assertTrue(
                "bodyLarge fontSize unspecified at $size",
                ramp.bodyLarge.fontSize.isSpecified,
            )
            assertTrue(
                "labelSmall lineHeight unspecified at $size",
                ramp.labelSmall.lineHeight.isSpecified,
            )
        }
    }

    @Test
    fun textSizeScalesTheRampInProportion() {
        val medium = chatTypography(AppTextSize.MEDIUM)
        val large = chatTypography(AppTextSize.LARGE)
        val small = chatTypography(AppTextSize.SMALL)

        assertTrue(large.bodyLarge.fontSize.value > medium.bodyLarge.fontSize.value)
        assertTrue(small.bodyLarge.fontSize.value < medium.bodyLarge.fontSize.value)
    }

    @Test
    fun storedTextSizeAlwaysResolves() {
        assertEquals(AppTextSize.MEDIUM, AppTextSize.fromStored(null))
        assertEquals(AppTextSize.MEDIUM, AppTextSize.fromStored(""))
        assertEquals(AppTextSize.MEDIUM, AppTextSize.fromStored("nonsense"))
        assertEquals(AppTextSize.SMALL, AppTextSize.fromStored("SMALL"))
        assertEquals(AppTextSize.LARGE, AppTextSize.fromStored("large"))
        for (size in AppTextSize.entries) {
            assertEquals(size, AppTextSize.fromStored(size.name))
        }
    }

    @Test
    fun shapeAndSpacingTokensInitialise() {
        // Static-initialiser failures in these surface as a launch crash.
        assertNotNull(ChatShapes.medium)
        assertNotNull(FullShape)
        assertTrue(Spacing.xs.value < Spacing.xxl.value)
    }
}
