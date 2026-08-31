package com.hanzidictionary.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.R
import com.hanzidictionary.app.ThemeMode

/** Noto Sans CJK: bundled for offline use and to avoid tofu on CJK Extension characters
 * (the system font drops them). Used only where text may contain CJK; latin UI text uses
 * the Ethan system fonts below. */
val NotoCjk = FontFamily(Font(R.font.noto_sans_cjk_sc))

/** Piazzolla: high-contrast transitional serif, used for screen titles and display text. */
val Piazzolla = FontFamily(
    Font(R.font.piazzolla_regular, FontWeight.Normal),
    Font(R.font.piazzolla_semibold, FontWeight.SemiBold),
)

/** Instrument Sans: clean geometric sans, used for dense UI text and controls. */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
)

/** IBM Plex Mono: used for anything numeric or key-like — pinyin, counts, intervals,
 * HSK bands, eyebrow labels. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
)

// ── Ethan design-system color tokens ─────────────────────────────────────────

/** Full Ethan design-system token set, provided via [LocalEthanColors].
 * M3's ColorScheme handles M3 components; this carries the tokens that custom
 * composables reference directly. Ramp numbers are *roles*, not lightness. */
@Immutable
data class EthanColors(
    val paper0: Color, val paper1: Color, val paper2: Color,
    val ink900: Color, val ink800: Color, val ink700: Color,
    val ink600: Color, val ink500: Color, val ink400: Color,
    val ink300: Color, val ink200: Color, val ink100: Color, val ink050: Color,
    val orange700: Color, val orange600: Color, val orange500: Color,
    val orange400: Color, val orange200: Color, val orange100: Color, val orange050: Color,
    val jade600: Color, val jade500: Color, val jade100: Color,
    val amber600: Color, val amber500: Color, val amber100: Color,
    val textHeading: Color, val textBody: Color, val textMuted: Color, val textFaint: Color,
    val textAccent: Color, val textLink: Color,
    val borderSubtle: Color, val borderDefault: Color, val borderStrong: Color,
    val surfaceAccentSoft: Color, val onAccent: Color, val onAccentSoft: Color,
    val onAccentSoftMuted: Color, val knob: Color,
    val inkRule: Color, val inkRuleStrong: Color, val scrim: Color,
    val underlineInk: Color, val accent: Color,
    val isDark: Boolean,
)

private val LightColors = EthanColors(
    paper0 = Color(0xFFFDFCF9),
    paper1 = Color(0xFFF8F6F1),
    paper2 = Color(0xFFF1EDE5),
    ink900 = Color(0xFF211D18), ink800 = Color(0xFF2E2924), ink700 = Color(0xFF3E3830),
    ink600 = Color(0xFF574F45), ink500 = Color(0xFF70685B), ink400 = Color(0xFF93897A),
    ink300 = Color(0xFFB5AC9C), ink200 = Color(0xFFD8D1C5), ink100 = Color(0xFFE8E2D8),
    ink050 = Color(0xFFF2EFE8),
    orange700 = Color(0xFF944A17), orange600 = Color(0xFFBF6321), orange500 = Color(0xFFDD7A32),
    orange400 = Color(0xFFE9A05E), orange200 = Color(0xFFF4D2AC), orange100 = Color(0xFFFBE8D6),
    orange050 = Color(0xFFFDF5EE),
    jade600 = Color(0xFF0B6E5D), jade500 = Color(0xFF0E8A73), jade100 = Color(0xFFDCF0EB),
    amber600 = Color(0xFF8A6403), amber500 = Color(0xFFF0C41A), amber100 = Color(0xFFFDF4CB),
    textHeading = Color(0xFF211D18), textBody = Color(0xFF3E3830),
    textMuted = Color(0xFF574F45), textFaint = Color(0xFF70685B),
    textAccent = Color(0xFF944A17), textLink = Color(0xFF944A17),
    borderSubtle = Color(0xFFE8E2D8), borderDefault = Color(0xFFD8D1C5),
    borderStrong = Color(0xFFB5AC9C),
    surfaceAccentSoft = Color(0xFFFDF5EE),
    onAccent = Color(0xFFFFF8F1), onAccentSoft = Color(0xFF944A17),
    onAccentSoftMuted = Color(0xFF8A6440), knob = Color(0xFFFDFCF9),
    inkRule = Color(0x24211D18), inkRuleStrong = Color(0x48211D18),
    scrim = Color(0x52211D18), underlineInk = Color(0xFFDD7A32),
    accent = Color(0xFFDD7A32),
    isDark = false,
)

private val DarkColors = EthanColors(
    paper0 = Color(0xFF232019),
    paper1 = Color(0xFF1A1712),
    paper2 = Color(0xFF12100C),
    ink900 = Color(0xFFF7F4ED), ink800 = Color(0xFFE8E3DA), ink700 = Color(0xFFD6D0C5),
    ink600 = Color(0xFFB4AEA2), ink500 = Color(0xFF9A9385), ink400 = Color(0xFF6F6A5E),
    ink300 = Color(0xFF565046), ink200 = Color(0xFF423D34), ink100 = Color(0xFF322E27),
    ink050 = Color(0xFF2A2620),
    orange700 = Color(0xFFF6C08B), orange600 = Color(0xFFEE9E4E), orange500 = Color(0xFFDD7A32),
    orange400 = Color(0xFFC4682A), orange200 = Color(0xFF493B31), orange100 = Color(0xFF372F28),
    orange050 = Color(0xFF2E2925),
    jade600 = Color(0xFF8FE9C4), jade500 = Color(0xFF33B98A), jade100 = Color(0xFF123A2C),
    amber600 = Color(0xFFFFD98A), amber500 = Color(0xFFE5AF2B), amber100 = Color(0xFF3E2D07),
    textHeading = Color(0xFFF7F4ED), textBody = Color(0xFFD6D0C5),
    textMuted = Color(0xFFB4AEA2), textFaint = Color(0xFF9A9385),
    textAccent = Color(0xFFF6C08B), textLink = Color(0xFFF6C08B),
    borderSubtle = Color(0xFF322E27), borderDefault = Color(0xFF423D34),
    borderStrong = Color(0xFF565046),
    surfaceAccentSoft = Color(0xFF2E2925),
    onAccent = Color(0xFFFFF8F1), onAccentSoft = Color(0xFFEAB988),
    onAccentSoftMuted = Color(0xFFC3A184), knob = Color(0xFFFFF8F1),
    inkRule = Color(0x24F7F4ED), inkRuleStrong = Color(0x42F7F4ED),
    scrim = Color(0xA8080705), underlineInk = Color(0xFFDD7A32),
    accent = Color(0xFFDD7A32),
    isDark = true,
)

val LocalEthanColors = staticCompositionLocalOf { LightColors }

/** Shortcut inside composables: `LocalEthanColors.current` → `eth`. */
object EthanTheme {
    val colors: EthanColors
        @Composable @ReadOnlyComposable get() = LocalEthanColors.current
}

// ── Material 3 color mappings ─────────────────────────────────────────────────

private fun ethanToM3Light() = lightColorScheme(
    primary           = LightColors.accent,
    onPrimary         = LightColors.onAccent,
    primaryContainer  = LightColors.orange100,
    onPrimaryContainer = LightColors.orange700,
    secondary         = LightColors.ink600,
    onSecondary       = LightColors.paper0,
    secondaryContainer = LightColors.ink100,
    onSecondaryContainer = LightColors.ink900,
    background        = LightColors.paper1,
    onBackground      = LightColors.ink900,
    surface           = LightColors.paper0,
    onSurface         = LightColors.ink900,
    surfaceVariant    = LightColors.paper2,
    onSurfaceVariant  = LightColors.ink600,
    outline           = LightColors.borderDefault,
    outlineVariant    = LightColors.borderSubtle,
    error             = Color(0xFF5326FF),
    onError           = Color(0xFFFFFFFF),
)

private fun ethanToM3Dark() = darkColorScheme(
    primary           = DarkColors.accent,
    onPrimary         = DarkColors.onAccent,
    primaryContainer  = DarkColors.orange100,
    onPrimaryContainer = DarkColors.orange700,
    secondary         = DarkColors.ink600,
    onSecondary       = DarkColors.paper0,
    secondaryContainer = DarkColors.ink100,
    onSecondaryContainer = DarkColors.ink900,
    background        = DarkColors.paper1,
    onBackground      = DarkColors.ink900,
    surface           = DarkColors.paper0,
    onSurface         = DarkColors.ink900,
    surfaceVariant    = DarkColors.paper2,
    onSurfaceVariant  = DarkColors.ink600,
    outline           = DarkColors.borderDefault,
    outlineVariant    = DarkColors.borderSubtle,
    error             = Color(0xFF6A3DFF),
    onError           = Color(0xFFFFFFFF),
)

// ── Typography ────────────────────────────────────────────────────────────────

private val EthanTypography = Typography(
    // Piazzolla handles titles; Material3 maps: displayLarge → 46sp, headlineLarge → 32sp
    displayLarge  = TextStyle(fontFamily = Piazzolla, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-0.018).sp),
    displayMedium = TextStyle(fontFamily = Piazzolla, fontWeight = FontWeight.SemiBold, fontSize = 35.sp, lineHeight = 38.sp, letterSpacing = (-0.015).sp),
    displaySmall  = TextStyle(fontFamily = Piazzolla, fontWeight = FontWeight.Normal,   fontSize = 25.sp, lineHeight = 29.sp, letterSpacing = (-0.012).sp),
    headlineLarge = TextStyle(fontFamily = Piazzolla, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.009).sp),
    headlineMedium = TextStyle(fontFamily = Piazzolla, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    headlineSmall  = TextStyle(fontFamily = Piazzolla, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    // Instrument Sans handles dense UI rows and labels
    titleLarge  = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall  = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge   = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium  = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall   = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge  = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.sp),
    labelMedium = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.sp),
    labelSmall  = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.075.sp),
)

/** Resolve the effective dark/light choice for a [ThemeMode] against the system setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.AUTO -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ethan = if (darkTheme) DarkColors else LightColors
    val m3Colors = if (darkTheme) ethanToM3Dark() else ethanToM3Light()
    CompositionLocalProvider(LocalEthanColors provides ethan) {
        MaterialTheme(colorScheme = m3Colors, typography = EthanTypography, content = content)
    }
}
