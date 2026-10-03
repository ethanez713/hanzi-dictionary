package com.hanzidictionary.app.nudge

import android.content.Context
import android.content.SharedPreferences
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.NudgeGraded
import com.hanzidictionary.core.NudgeSession
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.WidgetCardInfo

/**
 * What the reminder remembers between its hourly job and the taps on the notification: when it
 * last fired, how many in a row went unanswered, and the in-notification session (the card on
 * screen and the one just graded).
 *
 * SharedPreferences rather than user.db, same as the widget: this is throwaway UI state that
 * must survive the process being killed between two taps, not study history. The history
 * itself is `review_log`, which every notification grade writes with `origin = 'notification'`.
 */
internal class NudgeStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var lastNudgeAt: Long?
        get() = prefs.getLong(KEY_LAST_NUDGE, -1L).takeIf { it >= 0 }
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_LAST_NUDGE) else putLong(KEY_LAST_NUDGE, value) }.apply()
        }

    var ignoredStreak: Int
        get() = prefs.getInt(KEY_IGNORED, 0)
        set(value) = prefs.edit().putInt(KEY_IGNORED, value).apply()

    /** The headline chosen when the session opened, kept so re-posts after a grade don't
     * re-roll the wording under the user's thumb. */
    var title: String
        get() = prefs.getString(KEY_TITLE, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_TITLE, value).apply()

    fun loadSession(): NudgeSession? {
        val itemId = prefs.getString(KEY_ITEM, null)
        val front = prefs.getString(KEY_FRONT, null)
        val graded = prefs.getInt(KEY_GRADED, 0)
        val card = if (itemId != null && front != null) {
            WidgetCardInfo(
                StudyItem(itemId, prefs.getString(KEY_KIND, StudyItem.KIND_CHARACTER)!!),
                front,
                prefs.getString(KEY_ANSWER, "").orEmpty(),
            )
        } else {
            null
        }
        val prevFront = prefs.getString(KEY_PREV_FRONT, null)
        val previous = prevFront?.let {
            NudgeGraded(it, prefs.getString(KEY_PREV_ANSWER, "").orEmpty(), prefs.getBoolean(KEY_PREV_KNEW, false))
        }
        // No card and nothing graded means no session was ever opened (or it was cleared).
        if (card == null && previous == null) return null
        return NudgeSession(card, previous, graded)
    }

    /** Committed synchronously: the receiver may be torn down the moment `goAsync()` finishes. */
    fun saveSession(session: NudgeSession) {
        val editor = prefs.edit()
        val card = session.card
        if (card == null) {
            for (key in CARD_KEYS) editor.remove(key)
        } else {
            editor.putString(KEY_ITEM, card.item.itemId)
            editor.putString(KEY_KIND, card.item.kind)
            editor.putString(KEY_FRONT, card.front)
            editor.putString(KEY_ANSWER, card.answer)
        }
        val previous = session.previous
        if (previous == null) {
            for (key in PREV_KEYS) editor.remove(key)
        } else {
            editor.putString(KEY_PREV_FRONT, previous.front)
            editor.putString(KEY_PREV_ANSWER, previous.answer)
            editor.putBoolean(KEY_PREV_KNEW, previous.knew)
        }
        editor.putInt(KEY_GRADED, session.graded)
        editor.commit()
    }

    fun clearSession() {
        val editor = prefs.edit()
        for (key in CARD_KEYS + PREV_KEYS + KEY_GRADED) editor.remove(key)
        editor.commit()
    }

    private companion object {
        const val PREFS = "nudge"
        const val KEY_LAST_NUDGE = "last_nudge_at"
        const val KEY_IGNORED = "ignored_streak"
        const val KEY_TITLE = "title"
        const val KEY_ITEM = "item"
        const val KEY_KIND = "kind"
        const val KEY_FRONT = "front"
        const val KEY_ANSWER = "answer"
        const val KEY_GRADED = "graded"
        const val KEY_PREV_FRONT = "prev_front"
        const val KEY_PREV_ANSWER = "prev_answer"
        const val KEY_PREV_KNEW = "prev_knew"
        val CARD_KEYS = listOf(KEY_ITEM, KEY_KIND, KEY_FRONT, KEY_ANSWER)
        val PREV_KEYS = listOf(KEY_PREV_FRONT, KEY_PREV_ANSWER, KEY_PREV_KNEW)
    }
}

/** The deck the reminder draws from — the same pool the home-screen widget follows: the
 * active list, or every list pooled. */
internal fun nudgeDeck(app: HanziApp) = app.studyRepository.let { study ->
    val activeId = study.activeListId()
    val source = if (activeId != null) DeckSource.UserList(activeId) else DeckSource.Everything
    study.deckFor(source, limit = DECK_LIMIT)
}

private const val DECK_LIMIT = 60
