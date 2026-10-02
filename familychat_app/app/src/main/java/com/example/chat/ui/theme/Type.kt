package com.example.chat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.chat.AppTextSize

private val ChatFontFamily = FontFamily.SansSerif

// The scale runs slightly larger than stock Material 3 (17sp titles, 15sp body
// against M3's 16 and 14). That is deliberate for this audience and is kept.
//
// Previously only 10 of Material 3's 15 roles were defined, so the display roles
// and the outer headline roles silently fell back to defaults built for the
// stock scale - a component reaching for headlineLarge got a size that did not
// belong to this ramp. The five missing roles are now defined explicitly and in
// proportion with the rest.
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 35.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 33.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    ),
    // 11sp Medium rather than 10sp Normal. This role carries the small metadata
    // that appears on every message, and 10sp Normal sits below comfortable
    // reading size for an app whose users span several generations.
    labelSmall = TextStyle(
        fontFamily = ChatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.1.sp
    )
)

/**
 * Returns the type ramp scaled for the user's chosen [AppTextSize].
 *
 * Every role is scaled by the same factor, so the proportions between display,
 * title, body and label are identical at all three settings - only the absolute
 * sizes move. MEDIUM returns the ramp above unchanged.
 */
fun chatTypography(textSize: AppTextSize): Typography {
    if (textSize == AppTextSize.MEDIUM) return Typography
    val s = textSize.scale
    fun TextStyle.scaled(): TextStyle = copy(
        fontSize = fontSize * s,
        lineHeight = lineHeight * s,
    )
    return Typography(
        displayLarge = Typography.displayLarge.scaled(),
        displayMedium = Typography.displayMedium.scaled(),
        displaySmall = Typography.displaySmall.scaled(),
        headlineLarge = Typography.headlineLarge.scaled(),
        headlineMedium = Typography.headlineMedium.scaled(),
        headlineSmall = Typography.headlineSmall.scaled(),
        titleLarge = Typography.titleLarge.scaled(),
        titleMedium = Typography.titleMedium.scaled(),
        titleSmall = Typography.titleSmall.scaled(),
        bodyLarge = Typography.bodyLarge.scaled(),
        bodyMedium = Typography.bodyMedium.scaled(),
        bodySmall = Typography.bodySmall.scaled(),
        labelLarge = Typography.labelLarge.scaled(),
        labelMedium = Typography.labelMedium.scaled(),
        labelSmall = Typography.labelSmall.scaled(),
    )
}
