package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.random.Random

/**
 * The study-reminder notification's brains, pulled out of the WorkManager job and the
 * receiver so every rule is a pure function a unit test can drive without a device — the
 * same split as [WidgetState]. The Android side owns the NotificationManager, the prefs and
 * the FSRS write; this file owns *whether to nudge* and *which card comes next*.
 *
 * A reminder is **open** from its first post until nothing is owed. [shouldNudge] decides when
 * one opens; [shouldReshow] decides when an open one that is no longer on screen (swiped away)
 * comes back. The opening cadence follows Yancey & Settles (KDD 2020, Duolingo): send around the
 * hour the learner last studied. Persisting until the deck is clear is a deliberate choice of
 * this app's user over that study's caution about repeated nudges.
 */

enum class NudgeMode {
    /** Opens ~a day after the last review; after studying today, only if cards are still owed
     * an interval later. */
    ADAPTIVE,

    /** Opens once [NudgeSettings.intervalHours] hours have passed since the last nudge and review. */
    INTERVAL,
}

data class NudgeSettings(
    val enabled: Boolean = false,
    val mode: NudgeMode = NudgeMode.ADAPTIVE,
    /** Gap between opening nudges (interval mode) and how soon a swiped-away reminder returns
     * (both modes). */
    val intervalHours: Int = 4,
    /** Waking window, minutes after local midnight. Nothing is posted outside it. */
    val windowStartMin: Int = 9 * 60,
    val windowEndMin: Int = 21 * 60,
)

private const val HOUR_MS = 3_600_000L

/** Adaptive mode waits this long after the last review, so the nudge lands near the hour
 * you habitually study rather than at a fixed clock time (Duolingo uses ~23.5h). */
internal const val ADAPTIVE_GAP_HOURS = 23L

/**
 * Should the hourly job open a reminder right now?
 *
 * [lastReviewAt] is the newest `review_log` row from *any* origin (app, widget,
 * notification); [lastNudgeAt] is when the previous reminder opened.
 */
fun shouldNudge(
    now: Long,
    settings: NudgeSettings,
    lastReviewAt: Long?,
    lastNudgeAt: Long?,
    hasDue: Boolean,
    tz: TimeZone = TimeZone.getDefault(),
): Boolean {
    if (!settings.enabled || !hasDue) return false
    if (!inWindow(now, settings, tz)) return false

    val sinceReview = lastReviewAt?.let { now - it }
    val sinceNudge = lastNudgeAt?.let { now - it }
    val interval = settings.intervalHours.coerceAtLeast(1) * HOUR_MS

    return when (settings.mode) {
        NudgeMode.ADAPTIVE -> {
            // Studied today with cards still owed: the habit happened, but the work didn't finish.
            val gap = if (lastReviewAt != null && localDay(lastReviewAt, tz) == localDay(now, tz)) interval else ADAPTIVE_GAP_HOURS * HOUR_MS
            (sinceReview == null || sinceReview >= gap) && (sinceNudge == null || sinceNudge >= gap)
        }
        NudgeMode.INTERVAL ->
            (sinceReview == null || sinceReview >= interval) && (sinceNudge == null || sinceNudge >= interval)
    }
}

/**
 * Should an already-open reminder that isn't on screen be posted again? [dismissedAt] is when
 * the user swiped it away, or null if it was only hidden (the app came to the foreground).
 */
fun shouldReshow(
    now: Long,
    settings: NudgeSettings,
    dismissedAt: Long?,
    tz: TimeZone = TimeZone.getDefault(),
): Boolean {
    if (!settings.enabled || !inWindow(now, settings, tz)) return false
    return dismissedAt == null || now - dismissedAt >= settings.intervalHours.coerceAtLeast(1) * HOUR_MS
}

internal fun inWindow(now: Long, settings: NudgeSettings, tz: TimeZone): Boolean {
    val minuteOfDay = (((now + tz.getOffset(now)) % DAY_MS) / 60_000L).toInt()
    val start = settings.windowStartMin
    val end = settings.windowEndMin
    return if (start <= end) minuteOfDay in start until end
    // A window that wraps midnight (e.g. 22:00–06:00).
    else minuteOfDay >= start || minuteOfDay < end
}

/** The card just graded in a notification session, kept so the next render can show what the
 * answer was — a ✗ should still teach you something. */
data class NudgeGraded(val front: String, val answer: String, val knew: Boolean)

/**
 * A notification session: the card on screen, the one just graded, and how many were done.
 * `card == null` means the session is over: nothing is owed any more.
 */
data class NudgeSession(
    val card: WidgetCardInfo?,
    val previous: NudgeGraded? = null,
    val graded: Int = 0,
)

/** Picks the opening card of a session. Reuses the widget's picker so the notification and
 * the widget draw from the same pool by the same rule, minus "ahead" top-ups nobody is owed. */
fun startNudgeSession(
    deck: List<DeckEntry>,
    summarize: (StudyItem) -> WidgetCardInfo?,
    random: Random = Random.Default,
): NudgeSession =
    NudgeSession(selectWidgetCard(deck.filter { it.bucket != DeckBucket.AHEAD }, random = random)?.let { summarize(it.item) })

/**
 * Advances [prev] after the card on screen was graded with [rating]. The graded card is kept
 * off the next draw, and the session ends when the deck has nothing owed left. The caller has already written the FSRS grade and rebuilt [deck] afterwards,
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
