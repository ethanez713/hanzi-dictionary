package com.hanzidictionary.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.hanzidictionary.app.R

/** Bundled Noto Sans CJK SC: the device's system font leaves CJK Extension characters and
 * the ⿰-style ideographic-description operators as tofu boxes; Noto renders both, plus a
 * clean Latin face for the UI chrome. Applied to every text style so no CJK is ever tofu.
 * (Ultra-rare glyphs outside Noto are filtered out of search rather than shown as boxes.) */
internal val NotoCjk = FontFamily(Font(R.font.noto_sans_cjk_sc))

private fun Typography.withFontFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

// Generated from seed #A65E2E (warm ochre) as a full M3 tonal palette. Fixed rather than
// dynamic: a per-device wallpaper-derived scheme would drift away from the semantic-color
// contrast ratios this app also depends on (see SemanticColors.kt), which are only proven
// against these exact surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF914C1D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC7),
    onPrimaryContainer = Color(0xFF2F1500),
    secondary = Color(0xFF735949),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFDDCCA),
    onSecondaryContainer = Color(0xFF2B1705),
    tertiary = Color(0xFF59623C),
    onTertiary = Color(0xFFFFFFFE),
    tertiaryContainer = Color(0xFFDEE7BA),
    onTertiaryContainer = Color(0xFF191E00),
    error = Color(0xFFBF0031),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF430002),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF0DFD6),
    onSurfaceVariant = Color(0xFF51443C),
    outline = Color(0xFF83746C),
    outlineVariant = Color(0xFFD4C3BA),
    inverseSurface = Color(0xFF362F2B),
    inverseOnSurface = Color(0xFFF8EFEA),
    inversePrimary = Color(0xFFFFB68A),
    // The surfaceContainer roles are spelled out because their defaults are the M3
    // BASELINE neutrals, not anything derived from the scheme around them -- left
    // unset they drop a purple-grey slab into an otherwise ochre app.
    surfaceDim = Color(0xFFE1D8D4),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF2ED),
    surfaceContainer = Color(0xFFF5ECE7),
    surfaceContainerHigh = Color(0xFFEFE6E2),
    surfaceContainerHighest = Color(0xFFEAE1DC),
    scrim = Color(0xFF040000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68A),
    onPrimary = Color(0xFF522300),
    primaryContainer = Color(0xFF743504),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFFE0C0AE),
    onSecondary = Color(0xFF422B1E),
    secondaryContainer = Color(0xFF5A4133),
    onSecondaryContainer = Color(0xFFFDDCCA),
    tertiary = Color(0xFFC2CB9F),
    onTertiary = Color(0xFF2A3410),
    tertiaryContainer = Color(0xFF414B25),
    onTertiaryContainer = Color(0xFFDEE7BA),
    error = Color(0xFFFFB3B0),
    onError = Color(0xFF690016),
    errorContainer = Color(0xFF930023),
    onErrorContainer = Color(0xFFFFDAD8),
    background = Color(0xFF19120D),
    onBackground = Color(0xFFEAE1DC),
    surface = Color(0xFF19120D),
    onSurface = Color(0xFFEAE1DC),
    surfaceVariant = Color(0xFF51443C),
    onSurfaceVariant = Color(0xFFD4C3BA),
    outline = Color(0xFF9D8E85),
    outlineVariant = Color(0xFF51443C),
    inverseSurface = Color(0xFFEAE1DC),
    inverseOnSurface = Color(0xFF362F2B),
    inversePrimary = Color(0xFF914C1D),
    // The surfaceContainer roles are spelled out because their defaults are the M3
    // BASELINE neutrals, not anything derived from the scheme around them -- left
    // unset they drop a purple-grey slab into an otherwise ochre app.
    surfaceDim = Color(0xFF19120D),
    surfaceBright = Color(0xFF3E3834),
    surfaceContainerLowest = Color(0xFF150C06),
    surfaceContainerLow = Color(0xFF201A17),
    surfaceContainer = Color(0xFF241E1B),
    surfaceContainerHigh = Color(0xFF2F2925),
    surfaceContainerHighest = Color(0xFF3A3330),
    scrim = Color(0xFF040000),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val typography = remember { Typography().withFontFamily(NotoCjk) }
    val glyphType = remember { glyphType(NotoCjk) }
    val semanticColors = if (dark) DarkSemanticColors else LightSemanticColors
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(
            LocalGlyphType provides glyphType,
            LocalSemanticColors provides semanticColors,
        ) {
            content()
        }
    }
}
