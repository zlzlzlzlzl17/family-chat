package com.example.chat.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette.
//
// The previous scheme drew page, card and bubble at #F7FBF6, #F1F7F0 and
// #FFFFFF - 1.04:1 and 1.09:1 apart. That is not a subtle separation, it is no
// separation, which is why cards never read as cards however correctly they
// were tokenised. The page is now tinted so white can do the work of being
// raised: #E7EFE9 against #FFFFFF is 1.17:1.
//
// The primary is lightened from #1B6A50 to #2E8168. It has to work in two
// places at once - white text on it in a filled header, and itself as text on
// white - and #2E8168 is the lightest value that clears 4.5:1 both ways
// (4.71:1 each). #4FA285 was tried and fails as body text at 3.07:1.
val MdLightPrimary = Color(0xFF2E8168)
val MdLightOnPrimary = Color(0xFFFFFFFF)
val MdLightPrimaryContainer = Color(0xFFB6EFD3)
val MdLightOnPrimaryContainer = Color(0xFF00291C)
val MdLightSecondary = Color(0xFF4D6358)
val MdLightOnSecondary = Color(0xFFFFFFFF)
val MdLightSecondaryContainer = Color(0xFFD0E8D9)
val MdLightOnSecondaryContainer = Color(0xFF082017)
val MdLightTertiary = Color(0xFF3B6471)
val MdLightOnTertiary = Color(0xFFFFFFFF)
val MdLightTertiaryContainer = Color(0xFFBFEAF9)
val MdLightOnTertiaryContainer = Color(0xFF001F27)
val MdLightError = Color(0xFFBA1A1A)
val MdLightOnError = Color(0xFFFFFFFF)
val MdLightErrorContainer = Color(0xFFFFDAD6)
val MdLightOnErrorContainer = Color(0xFF410002)
// Page is tinted; surface is white. Everything raised - cards, bubbles, the
// chat top bar - sits on surface and now separates from the page it rests on.
val MdLightBackground = Color(0xFFE7EFE9)
val MdLightOnBackground = Color(0xFF10201A)
val MdLightSurface = Color(0xFFFFFFFF)
val MdLightOnSurface = Color(0xFF10201A)
val MdLightSurfaceVariant = Color(0xFFDBE5DD)
val MdLightOnSurfaceVariant = Color(0xFF4A5A52)
val MdLightOutline = Color(0xFF7B8A82)
val MdLightOutlineVariant = Color(0xFFC7D3CB)

val MdDarkPrimary = Color(0xFF89D5B4)
val MdDarkOnPrimary = Color(0xFF003827)
val MdDarkPrimaryContainer = Color(0xFF00513B)
val MdDarkOnPrimaryContainer = Color(0xFFA5F2CF)
val MdDarkSecondary = Color(0xFFB5CCBE)
val MdDarkOnSecondary = Color(0xFF20352B)
val MdDarkSecondaryContainer = Color(0xFF364B41)
val MdDarkOnSecondaryContainer = Color(0xFFD0E8D9)
val MdDarkTertiary = Color(0xFFA3CEDD)
val MdDarkOnTertiary = Color(0xFF033543)
val MdDarkTertiaryContainer = Color(0xFF224C59)
val MdDarkOnTertiaryContainer = Color(0xFFBFEAF9)
val MdDarkError = Color(0xFFFFB4AB)
val MdDarkOnError = Color(0xFF690005)
val MdDarkErrorContainer = Color(0xFF93000A)
val MdDarkOnErrorContainer = Color(0xFFFFDAD6)
val MdDarkBackground = Color(0xFF0F1512)
val MdDarkOnBackground = Color(0xFFDEE4DE)
val MdDarkSurface = Color(0xFF0F1512)
val MdDarkOnSurface = Color(0xFFDEE4DE)
val MdDarkSurfaceVariant = Color(0xFF404942)
val MdDarkOnSurfaceVariant = Color(0xFFBFC9C2)
val MdDarkOutline = Color(0xFF8A938C)
val MdDarkOutlineVariant = Color(0xFF404942)

// Read-receipt tick ("actually read"). Kept as an explicit light/dark pair
// because the tick is drawn on top of the outgoing bubble, so it must contrast
// against primaryContainer rather than against the page surface.
//
// The previous single value (#34B7F1, declared in message/RichComposerBridge.kt)
// was applied in both themes and measured 1.76:1 against the light bubble
// #A5F2CF, below the 3:1 WCAG minimum for non-text UI components.
// These values measure 4.93:1 on the light bubble and 5.08:1 on the dark one.
val MdLightReadReceipt = Color(0xFF0B57D0)
val MdDarkReadReceipt = Color(0xFF7CC6FF)

// Filled app bar.
//
// This cannot use `primary`. In a Material 3 dark scheme primary is deliberately
// a light tint (#89D5B4 here) because its job is to be legible *on* dark
// surfaces - so a bar filled with it lands 10.77:1 above the page and glares.
// The bar needs its own pair: mid-green in light, deep green in dark, each with
// content chosen for that ground.
//
// Dark bar #164A37 gives 8.47:1 for its content and sits 1.82:1 above the page,
// so it still reads as a bar without dominating the screen.
val MdLightTopBar = Color(0xFF2E8168)
val MdLightOnTopBar = Color(0xFFFFFFFF)
val MdDarkTopBar = Color(0xFF164A37)
val MdDarkOnTopBar = Color(0xFFDCEFE5)

// Removed: the parallel Wa* palette (WaPrimary/WaAccent/WaSurface/WaBubbleMine/
// WaBubbleOther/WaText/WaMuted/WaSeen). Seven of its eight entries were unused,
// and several aliased light-theme values that would have broken in dark mode had
// anything read them. WaSeen in particular was a second, dead read-receipt blue
// sitting beside the live one - see MdLightReadReceipt above.
//
// The single real consumer was a fallback in safeUserColor(), which now takes
// the fallback from the active colour scheme instead.
