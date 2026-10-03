package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.random.Random

/**
 * The study-reminder notification's brains, pulled out of the WorkManager job and the
 * receiver so every rule is a pure function a unit test can drive without a device — the
 * same split as [WidgetState]. The Android side owns the NotificationManager, the prefs and
 * the FSRS write; this file owns *whether to nudge* and *which card comes next*.
 *
 * The cadence rules follow the one large-scale study of recurring practice reminders
 * (Yancey & Settles, KDD 2020, Duolingo): send around the hour the learner last studied,
 * at most once a day, and stop repeating yourself — frequent ignored nudges drive people to
 * mute the app, which is worse than no nudge. The exact back-off numbers are a heuristic,
 * not a measured optimum.
 */

enum class NudgeMode {
    /** One a day, ~a day after the last review, skipped if you already studied today. */
    ADAPTIVE,

    /** Every [NudgeSettings.intervalHours] hours inside the waking window. */
    INTERVAL,
}

data class NudgeSettings(
    val enabled: Boolean = false,
    val mode: NudgeMode = NudgeMode.ADAPTIVE,
    val intervalHours: Int = 4,
    /** Waking window, minutes after local midnight. Nothing is posted outside it. */
    val windowStartMin: Int = 9 * 60,
    val windowEndMin: Int = 21 * 60,
)

private const val HOUR_MS = 3_600_000L

/** Adaptive mode waits this long after the last review, so the nudge lands near the hour
 * you habitually study rather than at a fixed clock time (Duolingo uses ~23.5h). */
internal const val ADAPTIVE_GAP_HOURS = 23L

/** After this many nudges in a row with no review between them, back off. */
internal const val IGNORED_BACKOFF_AFTER = 4

/** Backed-off adaptive cadence: once every two days instead of daily. */
internal const val BACKOFF_GAP_HOURS = 48L

/** Cards a single notification session walks before it stops by itself. A notification is a
 * micro-session (Dingler et al., MobileHCI 2017), not a place to spend twenty minutes. */
const val NUDGE_SESSION_MAX = 5

/**
 * Should the hourly job post a notification right now?
 *
 * [lastReviewAt] is the newest `review_log` row from *any* origin (app, widget,
 * notification); [lastNudgeAt] is when the previous notification was posted. [ignoredStreak]
 * is how many consecutive nudges went unanswered — see [nextIgnoredStreak].
 */
fun shouldNudge(
    now: Long,
    settings: NudgeSettings,
    lastReviewAt: Long?,
    lastNudgeAt: Long?,
    ignoredStreak: Int,
    hasDue: Boolean,
    tz: TimeZone = TimeZone.getDefault(),
): Boolean {
    if (!settings.enabled || !hasDue) return false
    if (!inWindow(now, settings, tz)) return false

    val sinceReview = lastReviewAt?.let { now - it }
    val sinceNudge = lastNudgeAt?.let { now - it }

    return when (settings.mode) {
        NudgeMode.ADAPTIVE -> {
            // Already studied today → the habit worked, don't spend a nudge on it.
            if (lastReviewAt != null && sameLocalDay(lastReviewAt, now, tz)) return false
            val gapHours = if (ignoredStreak >= IGNORED_BACKOFF_AFTER) BACKOFF_GAP_HOURS else ADAPTIVE_GAP_HOURS
            (sinceReview == null || sinceReview >= gapHours * HOUR_MS) &&
                (sinceNudge == null || sinceNudge >= gapHours * HOUR_MS)
        }
        NudgeMode.INTERVAL -> {
            val gap = settings.intervalHours.coerceAtLeast(1) * HOUR_MS
            (sinceReview == null || sinceReview >= gap) && (sinceNudge == null || sinceNudge >= gap)
        }
    }
}

/**
 * Whether the nudge posted at [lastNudgeAt] went unanswered, folded into the running streak.
 * Computed when the *next* nudge is about to post, so no dismiss intent is needed: if there
 * is no review newer than the previous nudge, it was ignored. Any review resets the streak.
 */
fun nextIgnoredStreak(streak: Int, lastNudgeAt: Long?, lastReviewAt: Long?): Int = when {
    lastNudgeAt == null -> 0
    lastReviewAt != null && lastReviewAt >= lastNudgeAt -> 0
    else -> streak + 1
}

internal fun inWindow(now: Long, settings: NudgeSettings, tz: TimeZone): Boolean {
    val minuteOfDay = (((now + tz.getOffset(now)) % DAY_MS) / 60_000L).toInt()
    val start = settings.windowStartMin
    val end = settings.windowEndMin
    return if (start <= end) minuteOfDay in start until end
    // A window that wraps midnight (e.g. 22:00–06:00).
    else minuteOfDay >= start || minuteOfDay < end
}

private fun sameLocalDay(a: Long, b: Long, tz: TimeZone): Boolean =
    (a + tz.getOffset(a)) / DAY_MS == (b + tz.getOffset(b)) / DAY_MS

/** The card just graded in a notification session, kept so the next render can show what the
 * answer was — a ✗ should still teach you something. */
data class NudgeGraded(val front: String, val answer: String, val knew: Boolean)

/**
 * A notification session: the card on screen, the one just graded, and how many were done.
 * `card == null` means the session is over (nothing left due, or [NUDGE_SESSION_MAX] reached).
 */
data class NudgeSession(
    val card: WidgetCardInfo?,
    val previous: NudgeGraded? = null,
    val graded: Int = 0,
)

/** Picks the opening card of a session. Reuses the widget's picker so the notification and
 * the widget draw from the same pool by the same rule. */
fun startNudgeSession(
    deck: List<DeckEntry>,
    summarize: (StudyItem) -> WidgetCardInfo?,
    random: Random = Random.Default,
): NudgeSession =
    NudgeSession(selectWidgetCard(deck, random = random)?.let { summarize(it.item) })

/**
 * Advances [prev] after the card on screen was graded with [rating]. The graded card is kept
 * off the next draw, and the session ends at [NUDGE_SESSION_MAX] or when the deck has
 * nothing left. The caller has already written the FSRS grade and rebuilt [deck] afterwards,
 * so a card that just became due again in ten minutes won't be redealt.
 */
fun nextNudgeSession(
    prev: NudgeSession,
    rating: Rating,
    deck: List<DeckEntry>,
    summarize: (StudyItem) -> WidgetCardInfo?,
    random: Random = Random.Default,
): NudgeSession {
    val done = prev.card ?: return prev
    val graded = prev.graded + 1
    val previous = NudgeGraded(done.front, done.answer, knew = rating != Rating.AGAIN)
    if (graded >= NUDGE_SESSION_MAX) return NudgeSession(null, previous, graded)
    // Only cards that actually need a look: a notification that dealt "ahead" cards would
    // pad the session with reviews nobody was owed.
    val live = deck.filter { it.bucket != DeckBucket.AHEAD }
    val next = selectWidgetCard(live, avoid = done.item.itemId, random = random)
        ?.takeIf { it.item.itemId != done.item.itemId }
        ?.let { summarize(it.item) }
    return NudgeSession(next, previous, graded)
}

/** Title wording rotated by day so the same line never shows two days running — a template
 * you've just seen stops working (the Duolingo "novelty effect"). */
fun nudgeTitle(dayIndex: Long, dueCount: Int): String {
    val n = dueCount.coerceAtLeast(1)
    val cards = if (n == 1) "1 card" else "$n cards"
    val options = listOf(
        "$cards ready for you",
        "Two minutes for $cards?",
        "$cards would like a look",
        "A quick pass: $cards",
    )
    return options[Math.floorMod(dayIndex, options.size.toLong()).toInt()]
}
