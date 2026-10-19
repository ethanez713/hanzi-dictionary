package com.hanzidictionary.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hanzidictionary.core.NudgeMode
import com.hanzidictionary.core.NudgeSettings
import com.hanzidictionary.core.PhotoSort

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
    var photoSort by mutableStateOf(readPhotoSort())
        private set

    // Study reminders (the notification). Defaults match NudgeSettings, except that it ships
    // off: a notification is something you opt into, and enabling it is also what asks for
    // the POST_NOTIFICATIONS permission.
    var nudgeEnabled by mutableStateOf(prefs.getBoolean(KEY_NUDGE_ON, false))
        private set
    var nudgeMode by mutableStateOf(readNudgeMode())
        private set
    var nudgeIntervalHours by mutableStateOf(prefs.getInt(KEY_NUDGE_INTERVAL, NudgeSettings().intervalHours))
        private set
    var nudgeWindowStartMin by mutableStateOf(prefs.getInt(KEY_NUDGE_FROM, NudgeSettings().windowStartMin))
        private set
    var nudgeWindowEndMin by mutableStateOf(prefs.getInt(KEY_NUDGE_TO, NudgeSettings().windowEndMin))
        private set

    /** A plain snapshot for the background job, which runs outside Compose. */
    fun nudgeSettings() = NudgeSettings(
        enabled = nudgeEnabled,
        mode = nudgeMode,
        intervalHours = nudgeIntervalHours,
        windowStartMin = nudgeWindowStartMin,
        windowEndMin = nudgeWindowEndMin,
    )

    fun updateNudgeEnabled(value: Boolean) {
        nudgeEnabled = value
        prefs.edit().putBoolean(KEY_NUDGE_ON, value).apply()
    }

    fun updateNudgeMode(mode: NudgeMode) {
        nudgeMode = mode
        prefs.edit().putString(KEY_NUDGE_MODE, mode.name).apply()
    }

    fun updateNudgeIntervalHours(hours: Int) {
        nudgeIntervalHours = hours
        prefs.edit().putInt(KEY_NUDGE_INTERVAL, hours).apply()
    }

    fun updateNudgeWindowStart(minutes: Int) {
        nudgeWindowStartMin = minutes
        prefs.edit().putInt(KEY_NUDGE_FROM, minutes).apply()
    }

    fun updateNudgeWindowEnd(minutes: Int) {
        nudgeWindowEndMin = minutes
        prefs.edit().putInt(KEY_NUDGE_TO, minutes).apply()
    }

    fun updateShowSwipeHints(value: Boolean) {
        showSwipeHints = value
        prefs.edit().putBoolean(KEY_HINTS, value).apply()
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun updatePhotoSort(sort: PhotoSort) {
        photoSort = sort
        prefs.edit().putString(KEY_PHOTO_SORT, sort.name).apply()
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.AUTO.name) }
            .getOrDefault(ThemeMode.AUTO)

    private fun readPhotoSort(): PhotoSort =
        runCatching { PhotoSort.valueOf(prefs.getString(KEY_PHOTO_SORT, null) ?: PhotoSort.RECENT.name) }
            .getOrDefault(PhotoSort.RECENT)

    private fun readNudgeMode(): NudgeMode =
        runCatching { NudgeMode.valueOf(prefs.getString(KEY_NUDGE_MODE, null) ?: NudgeMode.ADAPTIVE.name) }
            .getOrDefault(NudgeMode.ADAPTIVE)

    companion object {
        const val PREFS = "app_settings"
        private const val KEY_HINTS = "show_swipe_hints"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PHOTO_SORT = "photo_sort"
        private const val KEY_NUDGE_ON = "nudge_enabled"
        private const val KEY_NUDGE_MODE = "nudge_mode"
        private const val KEY_NUDGE_INTERVAL = "nudge_interval_hours"
        private const val KEY_NUDGE_FROM = "nudge_window_start_min"
        private const val KEY_NUDGE_TO = "nudge_window_end_min"
    }
}
