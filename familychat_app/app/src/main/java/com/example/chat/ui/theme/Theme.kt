package com.example.chat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.example.chat.AppDisplayMode
import com.example.chat.AppTextSize

// Material 3 layers surfaces through the surfaceContainer roles rather than
// through opacity. Previously these were left at their defaults while surface
// and background were overridden, so the two disagreed - which is why
// chatBubbleTokens() composites its own alpha values to fake elevation.
// Defining them here gives that code somewhere correct to move to.
// The ladder now runs the other way in light: raised things are white, and the
// steps descend toward the page tint. The old ladder crowded five values into a
// 4% band, so every step was invisible.
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFFFFFFF)
private val LightSurfaceContainer = Color(0xFFF2F7F3)
private val LightSurfaceContainerHigh = Color(0xFFDFE9E2)
private val LightSurfaceContainerHighest = Color(0xFFD5E1D8)

private val DarkSurfaceContainerLowest = Color(0xFF0A100D)
private val DarkSurfaceContainerLow = Color(0xFF1B221E)
private val DarkSurfaceContainer = Color(0xFF1F2723)
private val DarkSurfaceContainerHigh = Color(0xFF283029)
private val DarkSurfaceContainerHighest = Color(0xFF333B35)

private val LightScheme = lightColorScheme(
    primary = MdLightPrimary,
    onPrimary = MdLightOnPrimary,
    primaryContainer = MdLightPrimaryContainer,
    onPrimaryContainer = MdLightOnPrimaryContainer,
    secondary = MdLightSecondary,
    onSecondary = MdLightOnSecondary,
    secondaryContainer = MdLightSecondaryContainer,
    onSecondaryContainer = MdLightOnSecondaryContainer,
    tertiary = MdLightTertiary,
    onTertiary = MdLightOnTertiary,
    tertiaryContainer = MdLightTertiaryContainer,
    onTertiaryContainer = MdLightOnTertiaryContainer,
    error = MdLightError,
    onError = MdLightOnError,
    errorContainer = MdLightErrorContainer,
    onErrorContainer = MdLightOnErrorContainer,
    background = MdLightBackground,
    onBackground = MdLightOnBackground,
    surface = MdLightSurface,
    onSurface = MdLightOnSurface,
    surfaceVariant = MdLightSurfaceVariant,
    onSurfaceVariant = MdLightOnSurfaceVariant,
    outline = MdLightOutline,
    outlineVariant = MdLightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

private val DarkScheme = darkColorScheme(
    primary = MdDarkPrimary,
    onPrimary = MdDarkOnPrimary,
    primaryContainer = MdDarkPrimaryContainer,
    onPrimaryContainer = MdDarkOnPrimaryContainer,
    secondary = MdDarkSecondary,
    onSecondary = MdDarkOnSecondary,
    secondaryContainer = MdDarkSecondaryContainer,
    onSecondaryContainer = MdDarkOnSecondaryContainer,
    tertiary = MdDarkTertiary,
    onTertiary = MdDarkOnTertiary,
    tertiaryContainer = MdDarkTertiaryContainer,
    onTertiaryContainer = MdDarkOnTertiaryContainer,
    error = MdDarkError,
    onError = MdDarkOnError,
    errorContainer = MdDarkErrorContainer,
    onErrorContainer = MdDarkOnErrorContainer,
    background = MdDarkBackground,
    onBackground = MdDarkOnBackground,
    surface = MdDarkSurface,
    onSurface = MdDarkOnSurface,
    surfaceVariant = MdDarkSurfaceVariant,
    onSurfaceVariant = MdDarkOnSurfaceVariant,
    outline = MdDarkOutline,
    outlineVariant = MdDarkOutlineVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

@Composable
fun ChatTheme(
    displayMode: AppDisplayMode = AppDisplayMode.SYSTEM,
    dynamicColor: Boolean = false,
    textSize: AppTextSize = AppTextSize.MEDIUM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (displayMode) {
        AppDisplayMode.SYSTEM -> isSystemInDarkTheme()
        AppDisplayMode.LIGHT -> false
        AppDisplayMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    // Remembered on textSize: at SMALL or LARGE this builds a new Typography,
    // and handing MaterialTheme a fresh instance on every recomposition would
    // invalidate every consumer of the typography CompositionLocal each time.
    val typography = remember(textSize) { chatTypography(textSize) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = ChatShapes,
        content = content
    )
}

/** Container and content colours for the accent-filled app bar. */
data class AppBarColors(val container: Color, val content: Color)

/**
 * Colours for the filled app bar.
 *
 * Deliberately not `primary`/`onPrimary`: in a dark Material 3 scheme primary is
 * a light tint meant for text on dark surfaces, so filling a bar with it
 * produces a glaring band. Dark mode gets its own deep green instead.
 *
 * Dark is detected from the scheme's own background rather than from
 * isSystemInDarkTheme(), so this stays correct when the user has forced light or
 * dark in settings, and degrades sensibly under dynamic colour.
 */
@Composable
fun appBarColors(): AppBarColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        AppBarColors(container = MdDarkTopBar, content = MdDarkOnTopBar)
    } else {
        AppBarColors(container = MdLightTopBar, content = MdLightOnTopBar)
    }
}
