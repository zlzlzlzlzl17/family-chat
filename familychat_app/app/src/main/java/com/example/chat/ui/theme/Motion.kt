package com.example.chat.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * The app's motion scale.
 *
 * These constants previously lived in `message/RichComposerBridge.kt` - a text
 * composer helper - alongside a colour and a component token type. That location
 * is why the app had exactly two speeds: nothing about a composer file invites
 * you to add a third.
 *
 * [MotionShort] and [MotionMedium] keep their original values deliberately.
 * Renumbering them would silently retime 39 existing call sites, and there is no
 * UI regression net for those yet. The two longer steps are new.
 */

/** 140ms - state flips the user should barely notice: ticks, small toggles. */
internal const val MotionShort = 140

/** 240ms - the default. Navigation transitions, appearing and disappearing UI. */
internal const val MotionMedium = 240

/** 400ms - larger surfaces travelling further: sheets, full-screen overlays. */
internal const val MotionLong = 400

/** 600ms - reserved for a single deliberate, attention-carrying moment. */
internal const val MotionExtraLong = 600

/**
 * Spring used when something arrives under its own steam - a message landing in
 * the thread, a list item settling after a reorder.
 *
 * Springs are preferred over fixed-duration tweens for entrances because the
 * slight overshoot reads as physical response rather than as a scripted
 * playback. Tweens remain correct for transitions that must finish in lockstep
 * with something else, such as a navigation crossfade.
 */
internal fun <T> arrivalSpring(): FiniteAnimationSpec<T> = spring(
    dampingRatio = 0.75f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * Placement spring for list items that move because their neighbours changed.
 * Stiffer and better damped than [arrivalSpring] - items sliding past each other
 * should not wobble.
 */
internal fun placementSpring(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset(1, 1),
)
