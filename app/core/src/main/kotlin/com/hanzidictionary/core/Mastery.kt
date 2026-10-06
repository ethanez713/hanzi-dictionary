package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * "Has this card actually been learned?" — answered only from what the app can measure.
 *
 * The question matters because the failure mode is well documented: learners judge a word
 * learned after recalling it once and stop reviewing it, and that costs them dearly a week
 * later (Karpicke & Roediger 2008, *Science*; Kornell & Bjork 2008, *Memory*), while their own
 * confidence is nearly uncorrelated with what they will recall. So nothing here treats one
 * correct answer, or a "feels easy" impression, as evidence. What the literature does support:
 *
 *  - **Spaced successes, not repeated ones.** About three correct recalls spread over separate
 *    sessions captures most of the durability gain, with sharply diminishing returns after
 *    (Rawson & Dunlosky 2011, 2018 — "successive relearning"). Same-day repeats don't count
 *    as extra successes.
 *  - **A success after a long gap is the strongest single event.** Bahrick's vocabulary work
 *    found 30-day gaps retained best, and 13 sessions at 56-day gaps matched 26 at 14-day
 *    gaps (Bahrick 1979; Bahrick et al. 1993).
 *  - **Any miss resets the streak.** Not because one lapse erases everything, but because the
 *    claim being made is "I recall this reliably", and a miss falsifies it for now.
 *
 * What the literature does *not* supply is a validated cut-off such as "retire at stability ≥
 * N days". The constants below are judgement calls anchored to those papers — and to Anki's
 * 21-day "mature" and 8-lapse "leech" conventions, which are conventions, not findings — and
 * are named so they're easy to move. The app only ever *suggests* retiring a card (a tag and a
 * filter); archiving stays the user's swipe.
 *
 * Self-reported "I know it" from new-word triage ([StudyRepository.ORIGIN_TRIAGE]) is excluded
 * from all of this: a claim is not a retrieval. A final-check grade and an archive spot-check
 * are retrievals, so they count — they are the evidence the retirement test exists to record.
 */
enum class MasteryLevel {
    /** Still being learned — the default, and where every lapse sends a card back to. */
    LEARNING,

    /** [MASTERY_STREAK] spaced successes, the latest after a [LEARNED_MIN_GAP_DAYS]+ gap. */
    LEARNED,

    /** Durable by every measure here; a reasonable candidate to archive. */
    RETIRE_READY,
}

/** Successes (distinct days) in a row before a card can count as learned. */
const val MASTERY_STREAK = 3

/** The latest success must have come after at least this long away: ~Anki's "mature" (21d),
 * and short of Bahrick's 30-day optimum so a once-a-week studier can still get here. */
const val LEARNED_MIN_GAP_DAYS = 21.0

/** FSRS stability (days for recall to fall to 90%) before a card is retire-ready. Half a
 * year of stability is a judgement call; no study validates a specific number. */
const val RETIRE_MIN_STABILITY_DAYS = 180.0

/** Within the streak, at least one success must have survived this long a gap — about
 * Bahrick's 56-day spacing, the one that matched twice as many short-gap sessions. */
const val RETIRE_MIN_GAP_DAYS = 60.0

/** Lapses before a card is flagged as struggling. A convention, tuned to be earlier than
 * Anki's leech so it surfaces cards you "consistently get wrong" before they're hopeless. */
const val STRUGGLING_LAPSES = 4

/** Anki's leech threshold (a convention, not a finding). */
const val LEECH_LAPSES = 8

/** One past grading, as mastery needs it. [rating] is [Rating.value]. */
data class ReviewEntry(
    val ts: Long,
    val rating: Int,
    /** Whole days since the card's previous grading when this one happened; null for the
     * first ever. */
    val elapsedDays: Double?,
    val origin: String,
)

data class Mastery(
    val level: MasteryLevel,
    /** Consecutive successes on distinct local days, unbroken by a miss. */
    val spacedStreak: Int,
    /** Days away before the streak's latest success. */
    val lastGapDays: Double,
    /** The longest gap any success in the streak survived. */
    val longestGapDays: Double,
    val stabilityDays: Double?,
    val lapses: Int,
    /** [STRUGGLING_LAPSES]+ lapses and not yet back on a streak. */
    val struggling: Boolean,
    /** [LEECH_LAPSES]+ lapses and not yet back on a streak. */
    val leech: Boolean,
) {
    companion object {
        val NONE = Mastery(MasteryLevel.LEARNING, 0, 0.0, 0.0, null, 0, struggling = false, leech = false)
    }
}

/**
 * Derives a card's [Mastery] from its schedule ([card], for stability and lapses) and its
 * grading [history]. History order doesn't matter; it's sorted here. A card with no schedule
 * (never graded, or archived-then-restored) has no mastery yet.
 */
fun masteryOf(
    card: CardState?,
    history: List<ReviewEntry>,
    tz: TimeZone = TimeZone.getDefault(),
): Mastery {
    if (card == null) return Mastery.NONE

    // Triage is a claim, not a retrieval. Final-check and archive spot-checks are
    // retrievals and stay in: dropping them would throw away the proof just recorded.
    val retrievals = history
        .filter { it.origin != StudyRepository.ORIGIN_TRIAGE }
        .sortedBy { it.ts }

    // Gap of the first success on each day, for the current unbroken run of successes. A
    // second success the same day (an "ahead" top-up, a requeue) is practice, not another
    // spaced retrieval, so it neither counts nor breaks the run.
    val runGaps = mutableListOf<Double>()
    var lastDay: Long? = null
    for (entry in retrievals) {
        if (entry.rating < Rating.GOOD.value) {
            runGaps.clear()
            lastDay = null
            continue
        }
        val day = (entry.ts + tz.getOffset(entry.ts)) / DAY_MS
        if (day == lastDay) continue
        lastDay = day
        runGaps += entry.elapsedDays ?: 0.0
    }

    val streak = runGaps.size
    val lastGap = runGaps.lastOrNull() ?: 0.0
    val longestGap = runGaps.maxOrNull() ?: 0.0
    val stability = card.stability
    val onStreak = streak >= MASTERY_STREAK

    val level = when {
        onStreak && stability != null && stability >= RETIRE_MIN_STABILITY_DAYS &&
            longestGap >= RETIRE_MIN_GAP_DAYS -> MasteryLevel.RETIRE_READY
        onStreak && lastGap >= LEARNED_MIN_GAP_DAYS -> MasteryLevel.LEARNED
        else -> MasteryLevel.LEARNING
    }
    return Mastery(
        level = level,
        spacedStreak = streak,
        lastGapDays = lastGap,
        longestGapDays = longestGap,
        stabilityDays = stability,
        lapses = card.lapses,
        struggling = card.lapses >= STRUGGLING_LAPSES && !onStreak,
        leech = card.lapses >= LEECH_LAPSES && !onStreak,
    )
}

/**
 * The one quiet line shown beside the Archive button — the measurable evidence for the
 * decision, in plain numbers rather than a verdict: "4 in a row · last after 63 d · stable
 * ~210 d". Null when there is nothing to say (never graded), so the sheet stays clean for
 * words you haven't started.
 */
fun masterySummary(m: Mastery): String? {
    val parts = buildList {
        if (m.spacedStreak > 0) {
            add("${m.spacedStreak} in a row")
            if (m.lastGapDays >= 1.0) add("last after ${m.lastGapDays.roundToInt()} d")
        }
        m.stabilityDays?.let { add("stable ~${it.roundToInt()} d") }
        if (m.lapses > 0) add(if (m.lapses == 1) "1 lapse" else "${m.lapses} lapses")
        when (m.level) {
            MasteryLevel.RETIRE_READY -> add("ready to archive")
            MasteryLevel.LEARNED -> add("learned")
            MasteryLevel.LEARNING -> if (m.struggling) add("struggling")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
