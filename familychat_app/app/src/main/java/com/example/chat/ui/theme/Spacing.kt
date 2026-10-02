package com.example.chat.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The app's spacing scale.
 *
 * Before this existed the UI used 342 hardcoded `dp` literals across 55 distinct
 * values. The four most common were 12, 8, 4 and 6dp - effectively two grids
 * competing, with off-grid values (6, 10, 14, 18) producing layouts that never
 * look wrong on any single screen but never quite settle either.
 *
 * Every previous value rounds onto one of these six steps. Prefer these over new
 * literals; if a value here genuinely does not fit, that is worth a conversation
 * rather than a 7th step.
 */
object Spacing {
    /** 4dp - hairline gaps, icon-to-label padding inside a chip. */
    val xs = 4.dp

    /** 8dp - tight grouping, gap between related controls. */
    val sm = 8.dp

    /** 12dp - default gap between elements in a row or column. */
    val md = 12.dp

    /** 16dp - screen edge inset, Material 3 list-item horizontal padding. */
    val lg = 16.dp

    /** 24dp - separation between distinct groups on a screen. */
    val xl = 24.dp

    /** 32dp - major section breaks, empty-state breathing room. */
    val xxl = 32.dp
}
