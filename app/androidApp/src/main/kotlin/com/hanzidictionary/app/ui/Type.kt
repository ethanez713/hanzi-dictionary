package com.hanzidictionary.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Text styles for standalone Chinese glyphs, distinct from M3's [Typography][androidx.compose.material3.Typography]
 * roles. Material's default text style (`bodyLarge`, which `MaterialTheme` provides via
 * `ProvideTextStyle`) carries a 24sp line height and 0.5sp letter-spacing tuned for reading
 * paragraphs of Latin prose — set a lone 64-80sp character glyph in it and the glyph
 * overflows its own line box while sitting inside body letter-spacing meant for much smaller
 * text. [GlyphType] is the set of sizes this app actually draws a character or component in,
 * each with a line height sized to the glyph (1.15x, not 1.5x) and zero letter-spacing.
 */
@Immutable
data class GlyphType(
    val hero: TextStyle,
    val large: TextStyle,
    /** A word page's headword. Smaller than a character's [large] header because a word is
     * several glyphs wide — 中华人民共和国 at 64sp needs more width than any phone has. */
    val word: TextStyle,
    val medium: TextStyle,
    val small: TextStyle,
    val tile: TextStyle,
    /** The swipe-direction mark thrown over a dragged card. Not a Chinese glyph, but it has
     * the same problem — a lone 44sp symbol in bodyLarge's 24sp line box — and the same fix. */
    val swipeHint: TextStyle,
)

val LocalGlyphType = staticCompositionLocalOf<GlyphType> { error("GlyphType not provided") }

private fun glyphStyle(family: FontFamily, size: Int, lineHeight: Int) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

internal fun glyphType(family: FontFamily) = GlyphType(
    hero = glyphStyle(family, 80, 92),
    large = glyphStyle(family, 64, 74),
    word = glyphStyle(family, 44, 51),
    medium = glyphStyle(family, 32, 37),
    small = glyphStyle(family, 27, 31),
    tile = glyphStyle(family, 30, 35),
    swipeHint = glyphStyle(family, 44, 51),
)
