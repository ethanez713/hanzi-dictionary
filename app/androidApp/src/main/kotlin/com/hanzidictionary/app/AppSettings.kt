package com.hanzidictionary.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How the app picks light vs dark: follow the system, or force one. */
enum class ThemeMode { AUTO, LIGHT, DARK }

/**
 * User-facing app preferences, backed by SharedPreferences and exposed as Compose state so a
 * change from the Settings screen recomposes the whole tree live (theme flips, hint gutters
 * collapse) without an activity restart. One instance lives on [HanziApp].
 */
class AppSettings(private val prefs: SharedPreferences) {
    var showSwipeHints by mutableStateOf(prefs.getBoolean(KEY_HINTS, true))
        private set
    var themeMode by mutableStateOf(readThemeMode())
        private set

    fun updateShowSwipeHints(value: Boolean) {
        showSwipeHints = value
        prefs.edit().putBoolean(KEY_HINTS, value).apply()
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.AUTO.name) }
            .getOrDefault(ThemeMode.AUTO)

    companion object {
        const val PREFS = "app_settings"
        private const val KEY_HINTS = "show_swipe_hints"
        private const val KEY_THEME = "theme_mode"
    }
}
