package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.random.Random

/**
 * The last check before a card leaves rotation, and a spot-check that the archive still holds.
 *
 * [Mastery] says when a card is worth asking. The gate here says when the answer would
 * actually be evidence: either the streak already survived a [RETIRE_MIN_GAP_DAYS] gap and
 * the last review was on an earlier local day, or that many days have now elapsed and a
 * pass on this card *is* the gap. A same-day retest is practice, the same way mastery
 * refuses to count one, so it is never that proof.
 *
 * Nothing in this file writes. [StudyRepository] applies a swipe and can undo the two
 * swipes that move a card into or out of the archive.
 */

/** Retirement candidates tested in one sitting. Capped so the check stays a sitting
 * and doesn't become a second review queue. */
const val FINAL_CHECK_LIMIT = 15

/** Archived items spot-checked after the candidates. Small on purpose: the archive is
 * not a deck to re-review in full. */
const val AUDIT_SAMPLE_LIMIT = 5

/** How long an archived item is left alone after its last test — a final check, a
 * spot-check, or the review it was archived from. A same-week retest mostly re-asks
 * what was just proved. Thirty days is Bahrick's gap that retained best, and still a
 * judgement call, like the other retirement numbers. */
const val AUDIT_MIN_GAP_DAYS = 30.0

/** Assumed recall when the archive has no grading to rebuild a memory from (a bulk import,
 * a word archived on sight). An untested "I know it" is wrong about 4-25% of the time on
 * Yes/No vocabulary tests; this takes the pessimistic end. Lower would let a large import
 * crowd every tested word out of the sample. The sample weight is one minus recall. */
const val AUDIT_NO_HISTORY_RETRIEVABILITY = 0.75

enum class FinalCheckKind {
    /** Active card the sitting may archive. */
    CANDIDATE,

    /** Already archived; a pass leaves it there. */
    AUDIT,
}

/** Right affirms, left is the negative, up moves on — the same three swipes as practice. */
enum class FinalCheckSwipe { PASS, MISS, SKIP }

data class FinalCheckCard(
    val kind: FinalCheckKind,
    val item: StudyItem,
    val mastery: Mastery,
    val retrievability: Double,
)

/**
 * What undoing an archive or a return-to-study puts back. A miss that stays in study,
 * a skip, and a passed spot-check don't produce one: nothing of theirs is worth undoing.
 */
data class FinalCheckUndo(
    val item: StudyItem,
    /** The review_log row the swipe wrote. Captured before any later insert, so undo
     * deletes this grade and not a list row written after it. */
    val reviewRowId: Long,
    /** card_state to restore. Null means there was none — the item was archived — and
     * undo deletes the schedule the swipe created. */
    val card: CardState?,
    val wasKnown: Boolean,
    val knownAddedAt: Long,
    /** Set only when the swipe filed the item into a list it was not already in. */
    val addedToListId: Long?,
)

data class FinalCheckStatus(
    val eligible: Int,
    /** Whole days until the soonest waiting candidate unlocks; null when nobody is waiting. */
    val daysToFirstUnlock: Int?,
    val auditable: Int,
)

/** Whether a card that already has the streak and the stability may be tested now. */
data class CandidateGate(
    val eligible: Boolean,
    /** First instant a waiting candidate becomes eligible; null when [eligible]. */
    val unlockAt: Long?,
)

/** Memory rebuilt for an archived item. [stability] is the post-review value stored on
 * its newest review_log row — archiving deletes card_state and keeps no other copy. */
data class RebuiltMemory(
    val stability: Double,
    val difficulty: Double,
    val reviewedAt: Long,
    val reviews: Int,
    val lapses: Int,
)

/**
 * Streak and stability are what make a card worth a final check. The 60-day gap is not
 * required yet: the check itself is allowed to supply it.
 */
fun isFinalCheckCandidate(mastery: Mastery): Boolean =
    mastery.spacedStreak >= MASTERY_STREAK &&
        mastery.stabilityDays != null &&
        mastery.stabilityDays >= RETIRE_MIN_STABILITY_DAYS

/**
 * Two clocks, and the earlier one wins.
 *
 *  - The streak already holds a [RETIRE_MIN_GAP_DAYS] gap: the next local day is enough.
 *    The same local day is not, however large the clock gap inside it looks.
 *  - It doesn't: wait until [RETIRE_MIN_GAP_DAYS] whole days have elapsed since the last
 *    review, the same count [StudyRepository.grade] will store as `elapsed_days`. Opening
 *    the gate on a fractional day would archive a card whose logged proof was still 59.
 */
fun candidateGate(
    longestGapDays: Double,
    lastReview: Long,
    now: Long,
    tz: TimeZone = TimeZone.getDefault(),
): CandidateGate {
    if (longestGapDays >= RETIRE_MIN_GAP_DAYS) {
        if (localDay(lastReview, tz) < localDay(now, tz)) {
            return CandidateGate(eligible = true, unlockAt = null)
        }
        return CandidateGate(eligible = false, unlockAt = startOfNextLocalDay(lastReview, tz))
    }
    val unlockAt = lastReview + gapMillis(RETIRE_MIN_GAP_DAYS)
    return if (elapsedSinceReview(lastReview, now) >= RETIRE_MIN_GAP_DAYS) {
        CandidateGate(eligible = true, unlockAt = null)
    } else {
        CandidateGate(eligible = false, unlockAt = unlockAt)
    }
}

/** Whole days until [unlockAt], rounded up. A candidate who unlocks in an hour is still
 * a day away — "0 days" would read as ready. */
fun daysUntilUnlock(unlockAt: Long, now: Long): Int {
    val remaining = unlockAt - now
    if (remaining <= 0L) return 0
    return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
}

/** Never tested, or the last test (whatever its origin) was at least [AUDIT_MIN_GAP_DAYS]
 * whole days ago. The day count matches the one a grade stores. */
fun auditEligible(lastTestedAt: Long?, now: Long): Boolean {
    if (lastTestedAt == null) return true
    return elapsedSinceReview(lastTestedAt, now) >= AUDIT_MIN_GAP_DAYS
}

/** Sample weight. A card the model is sure you'll recall (weight 0) takes no slot. */
fun auditWeight(retrievability: Double): Double = (1.0 - retrievability).coerceAtLeast(0.0)

/**
 * Null when the newest row can't be a memory: no row, or it stored no stability or
 * difficulty. The caller then uses [AUDIT_NO_HISTORY_RETRIEVABILITY] instead of a
 * retrievability it can't compute.
 */
fun rebuiltMemory(
    stability: Double?,
    difficulty: Double?,
    reviewedAt: Long?,
    reviews: Int,
    lapses: Int,
): RebuiltMemory? {
    if (stability == null || difficulty == null || reviewedAt == null) return null
    return RebuiltMemory(stability, difficulty, reviewedAt, reviews, lapses)
}

/** Review phase, not learning: the logged stability is already a long-term memory,
 * and an Again from here is a lapse rather than a first encounter. */
fun rebuiltCard(item: StudyItem, memory: RebuiltMemory): CardState = CardState(
    itemId = item.itemId,
    kind = item.kind,
    phase = CardPhase.REVIEW,
    step = null,
    stability = memory.stability,
    difficulty = memory.difficulty,
    due = memory.reviewedAt,
    lastReview = memory.reviewedAt,
    reviews = memory.reviews,
    lapses = memory.lapses,
)

/**
 * Weighted random without replacement. One [Random.nextDouble] per pick, walked across
 * the weights, so a seeded random replays the same sample. Weight 0 is dropped before
 * the draw — nothing to land in.
 */
fun <T> sampleWeighted(
    items: List<T>,
    limit: Int,
    random: Random,
    weightOf: (T) -> Double,
): List<T> {
    val pool = items.filter { weightOf(it) > 0.0 }.toMutableList()
    if (pool.isEmpty() || limit <= 0) return emptyList()
    val out = ArrayList<T>(minOf(limit, pool.size))
    repeat(minOf(limit, pool.size)) {
        val total = pool.sumOf(weightOf)
        var ticket = random.nextDouble() * total
        val pickedAt = pool.indexOfFirst { item ->
            ticket -= weightOf(item)
            ticket <= 0.0
        }.let { index -> if (index == -1) pool.lastIndex else index }
        out += pool.removeAt(pickedAt)
    }
    return out
}

/**
 * Candidates first, lowest retrievability first, then the archive sample. The cards
 * closest to forgotten are the ones a retirement test most needs to catch, and they
 * come before the spot-check so a sitting you don't finish still made the archive
 * decisions.
 */
fun buildFinalCheckSession(
    eligible: List<FinalCheckCard>,
    auditable: List<FinalCheckCard>,
    random: Random,
    candidateLimit: Int = FINAL_CHECK_LIMIT,
    auditLimit: Int = AUDIT_SAMPLE_LIMIT,
): List<FinalCheckCard> {
    val candidates = eligible
        .sortedWith(compareBy({ it.retrievability }, { it.item.itemId }))
        .take(candidateLimit)
    val sample = sampleWeighted(auditable, auditLimit, random) { auditWeight(it.retrievability) }
    return candidates + sample
}

/** Null when there is nothing to offer. The study strip is the only place this appears,
 * and only once a candidate is actually testable. */
fun finalCheckStudyLine(eligible: Int): String? = when {
    eligible <= 0 -> null
    eligible == 1 -> "1 ready for a final check ›"
    else -> "$eligible ready for a final check ›"
}

/**
 * The archive-section row. Ready outranks waiting: if you can start, the waiters are
 * not the headline. Spot-check count is always appended when there is one.
 */
fun finalCheckStatusLabel(status: FinalCheckStatus): String {
    val parts = mutableListOf<String>()
    when {
        status.eligible == 1 -> parts += "1 ready"
        status.eligible > 1 -> parts += "${status.eligible} ready"
        status.daysToFirstUnlock == 1 -> parts += "Next in 1 day"
        status.daysToFirstUnlock != null && status.daysToFirstUnlock > 1 ->
            parts += "Next in ${status.daysToFirstUnlock} days"
    }
    when (status.auditable) {
        0 -> {}
        1 -> parts += "1 archived to spot-check"
        else -> parts += "${status.auditable} archived to spot-check"
    }
    return if (parts.isEmpty()) "Nothing to check yet" else parts.joinToString(" · ")
}

fun finalCheckReport(archived: Int, backToStudy: Int, spotChecksPassed: Int): String {
    val passed = if (spotChecksPassed == 1) "1 spot-check passed" else "$spotChecksPassed spot-checks passed"
    return "Archived $archived. Sent $backToStudy back to study. $passed."
}

private fun elapsedSinceReview(lastReview: Long, now: Long): Double =
    ((now - lastReview) / DAY_MS).toDouble().coerceAtLeast(0.0)

private fun gapMillis(days: Double): Long = (days * DAY_MS.toDouble()).toLong()

private fun localDay(ts: Long, tz: TimeZone): Long =
    (ts + tz.getOffset(ts)) / DAY_MS

private fun startOfNextLocalDay(ts: Long, tz: TimeZone): Long {
    val offset = tz.getOffset(ts)
    val day = (ts + offset) / DAY_MS
    // The offset at the next midnight can differ across a DST change. Recompute once
    // so the unlock is that local midnight rather than an hour either side of it.
    val firstGuess = (day + 1) * DAY_MS - offset
    val offsetThen = tz.getOffset(firstGuess)
    return (day + 1) * DAY_MS - offsetThen
}
