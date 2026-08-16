package com.hanzidictionary.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Roles this app colors outside the M3 scheme: swipe grading, and the semantic/phonetic
 * component coding (spec §9). Each role needs its own light and dark value — a color tuned
 * for 4.5:1 contrast against the light card surface can fall as low as 1.8:1 against the
 * dark one (measured on the old Material-Design-2 800-series swatches this replaces), so a
 * single hardcoded value can never satisfy both themes at once.
 */
@Immutable
data class SemanticColors(
    val affirm: Color,
    val negative: Color,
    val skip: Color,
    val meaning: Color,
    val sound: Color,
    val soundAndMeaning: Color,
)

val LocalSemanticColors = staticCompositionLocalOf<SemanticColors> { error("SemanticColors not provided") }

// Verified >= 4.96:1 against the card surface in their own theme.
internal val LightSemanticColors = SemanticColors(
    affirm = Color(0xFF2E6B37),
    negative = Color(0xFFB3261E),
    skip = Color(0xFF5A5F63),
    meaning = Color(0xFFA62A2A),
    sound = Color(0xFF1D5FA8),
    soundAndMeaning = Color(0xFF6B3FA0),
)

internal val DarkSemanticColors = SemanticColors(
    affirm = Color(0xFF86D492),
    negative = Color(0xFFFFB4AB),
    skip = Color(0xFFC2C7CB),
    meaning = Color(0xFFFFB4A8),
    sound = Color(0xFFA6C8FF),
    soundAndMeaning = Color(0xFFD3BBFF),
)
