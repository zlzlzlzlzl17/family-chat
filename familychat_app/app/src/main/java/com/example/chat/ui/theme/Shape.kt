package com.example.chat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's corner radius scale.
 *
 * The UI previously used 13 distinct hardcoded radii - 8, 10, 12, 13, 14, 16,
 * 18, 20, 22, 24, 28, 32 and 999 - while this scale existed but was referenced
 * only 14 times against 21 hardcoded uses. A 13dp corner beside a 14dp one is
 * invisible on its own; collectively it is why nothing in the app looked
 * aligned with anything else.
 *
 * Four rectangular steps plus [FullShape] cover every case:
 *
 *   extraSmall  8dp   inline chips, small clipped thumbnails
 *   small      12dp   avatars on rounded-square groups, compact cards
 *   medium     16dp   the default: cards, sheets, chat bubbles, images
 *   large      28dp   dialogs and large containing surfaces
 *
 * [Shapes.extraLarge] intentionally matches large: the design uses four
 * rectangular steps, and Material 3 provides five slots. Anything that wants to
 * be fully rounded should use [FullShape] rather than a large dp value, so it
 * stays a pill at any height.
 */
val ChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * A pill. Use instead of half-the-height dp values such as
 * `RoundedCornerShape(24.dp)` on a 48dp control, which silently stops being a
 * pill the moment the control's height changes.
 */
val FullShape = RoundedCornerShape(percent = 50)
