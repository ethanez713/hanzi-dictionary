package com.hanzidictionary.core

/**
 * "Is my deck too thin?" — the only gate on the app suggesting new words.
 *
 * Honest framing first: the research has no validated rule for *when* to add vocabulary. What
 * it does establish is the shape of the trade-off — spacing needs enough items in rotation to
 * make the gaps long (Kornell 2009; Nakata & Webb 2016: it is the spacing, not the set size,
 * that pays), review workload grows with every item added (the FSRS retention/workload
 * simulations), and a learner who is already missing items is a poor candidate for more.
 * The rule below is an engineering heuristic built on those three facts, not a finding, and is
 * deliberately conservative: for someone studying a few cards every couple of days it will
 * usually say "not yet", which is the right answer.
 *
 *  - **Thin** = fewer than a session's worth ([NEW_WORD_SESSION]) of cards due or unseen
 *    within the next [DECK_LOOKAHEAD_DAYS] days — the next sitting would run out of cards.
 *  - **Not swamped** = the overdue backlog is under two sessions; adding to a pile you can't
 *    clear only deepens it.
 *  - **Not struggling** = recent accuracy is at least [RECENT_SUCCESS_MIN], so the current
 *    deck is demonstrably manageable.
 */

/** Cards that make a sitting; the deck is "thin" when less than this is coming up. */
const val NEW_WORD_SESSION = 5

/** How far ahead to look for upcoming work. Two days: this user studies every couple of days. */
const val DECK_LOOKAHEAD_DAYS = 2

/** Accuracy over the recent reviews below which new words are held back. */
const val RECENT_SUCCESS_MIN = 0.8

/** How many of the latest reviews "recent" means. */
const val RECENT_SUCCESS_WINDOW = 20

/** Fewer reviews than this and the rate is noise — no gate rather than a misleading one. */
const val RECENT_SUCCESS_MIN_SAMPLE = 5

/** The inputs to the thin-deck rule, so a test (or a log line) can see why it said what it did. */
data class DeckLoad(
    /** Cards due, or never seen, within [DECK_LOOKAHEAD_DAYS]. */
    val upcoming: Int,
    /** Cards already overdue right now. */
    val backlog: Int,
    /** Share of recent real reviews answered correctly; null before there is enough to say. */
    val recentSuccess: Double?,
)

/**
 * How many new words to offer: 0 unless the deck is thin, not swamped, and recently going
 * well; otherwise just enough to fill one session.
 */
fun newWordGap(load: DeckLoad, session: Int = NEW_WORD_SESSION): Int {
    if (load.backlog >= 2 * session) return 0
    if (load.recentSuccess != null && load.recentSuccess < RECENT_SUCCESS_MIN) return 0
    return (session - load.upcoming).coerceAtLeast(0)
}

/**
 * Share of [ratings] (newest first, [Rating.value]s) that were correct, or null when there
 * are too few to be meaningful. Only a plain Good-or-better counts as correct, matching the
 * practice grammar where the only two grades are Again and Good.
 */
fun recentSuccessRate(ratings: List<Int>): Double? {
    if (ratings.size < RECENT_SUCCESS_MIN_SAMPLE) return null
    return ratings.count { it >= Rating.GOOD.value }.toDouble() / ratings.size
}
