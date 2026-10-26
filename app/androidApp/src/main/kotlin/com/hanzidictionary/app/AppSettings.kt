package com.hanzidictionary.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hanzidictionary.core.NudgeMode
import com.hanzidictionary.core.NudgeSettings
import com.hanzidictionary.core.PhotoSort
import kotlin.math.abs

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

    /** Reviews that fill the Study ring. Display only — the streak counts days. */
    var dailyGoal by mutableStateOf(readDailyGoal())
        private set

    /** Size of a manual new-word batch. The thin-deck prompt sizes itself. */
    var newWordsPerBatch by mutableStateOf(readNewWordsPerBatch())
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
    var nudgeStreakReminder by mutableStateOf(prefs.getBoolean(KEY_NUDGE_STREAK, NudgeSettings().streakReminder))
        private set

    /** A plain snapshot for the background job, which runs outside Compose. */
    fun nudgeSettings() = NudgeSettings(
        enabled = nudgeEnabled,
        mode = nudgeMode,
        intervalHours = nudgeIntervalHours,
        windowStartMin = nudgeWindowStartMin,
        windowEndMin = nudgeWindowEndMin,
        streakReminder = nudgeStreakReminder,
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

    fun updateNudgeStreakReminder(value: Boolean) {
        nudgeStreakReminder = value
        prefs.edit().putBoolean(KEY_NUDGE_STREAK, value).apply()
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

    fun updateDailyGoal(goal: Int) {
        val coerced = coerceDailyGoal(goal)
        dailyGoal = coerced
        prefs.edit().putInt(KEY_DAILY_GOAL, coerced).apply()
    }

    fun updateNewWordsPerBatch(count: Int) {
        val coerced = coerceNewWordsPerBatch(count)
        newWordsPerBatch = coerced
        prefs.edit().putInt(KEY_NEW_WORDS_BATCH, coerced).apply()
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

    /** A missing or unreadable value falls back to the default; a number that isn't one
     * of the choices snaps to the nearest one so a hand-edited pref can't strand the ring. */
    private fun readDailyGoal(): Int {
        if (!prefs.contains(KEY_DAILY_GOAL)) return DEFAULT_DAILY_GOAL
        val raw = runCatching { prefs.getInt(KEY_DAILY_GOAL, DEFAULT_DAILY_GOAL) }.getOrElse {
            runCatching { prefs.getString(KEY_DAILY_GOAL, null)?.trim()?.toIntOrNull() }.getOrNull()
                ?: return DEFAULT_DAILY_GOAL
        }
        return coerceDailyGoal(raw)
    }

    /** Same fallback as [readDailyGoal]: a hand-edited pref must still light one segment. */
    private fun readNewWordsPerBatch(): Int {
        if (!prefs.contains(KEY_NEW_WORDS_BATCH)) return DEFAULT_NEW_WORDS_BATCH
        val raw = runCatching { prefs.getInt(KEY_NEW_WORDS_BATCH, DEFAULT_NEW_WORDS_BATCH) }.getOrElse {
            runCatching { prefs.getString(KEY_NEW_WORDS_BATCH, null)?.trim()?.toIntOrNull() }.getOrNull()
                ?: return DEFAULT_NEW_WORDS_BATCH
        }
        return coerceNewWordsPerBatch(raw)
    }

    companion object {
        const val PREFS = "app_settings"
        val DAILY_GOAL_CHOICES = listOf(10, 20, 30, 50)
        const val DEFAULT_DAILY_GOAL = 20
        val NEW_WORDS_BATCH_CHOICES = listOf(5, 10, 15)
        const val DEFAULT_NEW_WORDS_BATCH = 5
        private const val KEY_HINTS = "show_swipe_hints"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PHOTO_SORT = "photo_sort"
        private const val KEY_DAILY_GOAL = "daily_goal"
        private const val KEY_NEW_WORDS_BATCH = "new_words_per_batch"
        private const val KEY_NUDGE_ON = "nudge_enabled"
        private const val KEY_NUDGE_MODE = "nudge_mode"
        private const val KEY_NUDGE_INTERVAL = "nudge_interval_hours"
        private const val KEY_NUDGE_FROM = "nudge_window_start_min"
        private const val KEY_NUDGE_TO = "nudge_window_end_min"
        private const val KEY_NUDGE_STREAK = "nudge_streak_reminder"

        fun coerceDailyGoal(value: Int): Int = DAILY_GOAL_CHOICES.minBy { abs(it - value) }

        fun coerceNewWordsPerBatch(value: Int): Int = NEW_WORDS_BATCH_CHOICES.minBy { abs(it - value) }
    }
}
