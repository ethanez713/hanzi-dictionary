package com.hanzidictionary.core

import java.util.TimeZone

/**
 * A study streak over local days. Display only — a lapse costs a number, never a card.
 *
 * One missed day is bridged when no other bridged day falls in the preceding
 * [STREAK_FREEZE_WINDOW_DAYS], so a single off-day doesn't wipe a long run. Two misses
 * in a row end it, and a bridged day counts toward the length. Today is still open:
 * an empty today leaves the streak at risk instead of breaking it. The window is a
 * judgement call anchored in docs/research/engagement.md.
 */

/** Days before a bridged day that must themselves contain no other bridge. */
const val STREAK_FREEZE_WINDOW_DAYS = 6

data class ActivityRow(val ts: Long, val itemId: String)

data class StreakState(
    val current: Int,
    val longest: Int,
    /** False when [today] has no review yet: the run is intact through yesterday, and
     * still to be kept. */
    val studiedToday: Boolean,
    /** Every bridged day in the span, including runs that have already ended. */
    val frozenDays: Set<Long>,
)

/** Distinct items per local day. Grading the same item twice in a day is practice,
 * not more activity — the same distinct-day rule mastery uses. */
fun dailyActivity(rows: List<ActivityRow>, tz: TimeZone = TimeZone.getDefault()): Map<Long, Int> {
    val itemsByDay = HashMap<Long, MutableSet<String>>()
    for (row in rows) {
        itemsByDay.getOrPut(localDay(row.ts, tz)) { HashSet() }.add(row.itemId)
    }
    return itemsByDay.mapValues { it.value.size }
}

fun streak(activeDays: Set<Long>, today: Long): StreakState {
    val studiedToday = today in activeDays
    if (activeDays.isEmpty()) return StreakState(0, 0, studiedToday, emptySet())

    // Forward, not back from today: a miss may be bridged only when no earlier bridge
    // sits in the preceding window, so those decisions have to be made in order.
    // An empty today is still open, so the run that counts ends yesterday.
    val frozen = HashSet<Long>()
    val runLength = HashMap<Long, Int>()
    var longest = 0
    var day = activeDays.min()
    val last = maxOf(activeDays.max(), today)
    while (day <= last) {
        val prev = runLength[day - 1] ?: 0
        val length = when {
            day in activeDays -> prev + 1
            day == today -> 0
            prev == 0 -> 0
            else -> {
                val bridged = bridgedLength(day, today, activeDays, frozen, prev)
                if (bridged > 0) frozen += day
                bridged
            }
        }
        if (length > 0) {
            runLength[day] = length
            if (length > longest) longest = length
        }
        day++
    }
    val end = if (studiedToday) today else today - 1
    return StreakState(runLength[end] ?: 0, longest, studiedToday, frozen.toSet())
}

/** Length a missed [day] would add if bridged, or 0 when the miss breaks the run.
 * A bridge has to connect a live run to a following study day — or to an empty
 * today, which is not a miss yet. */
private fun bridgedLength(
    day: Long,
    today: Long,
    activeDays: Set<Long>,
    frozen: Set<Long>,
    prev: Int,
): Int {
    val next = day + 1
    val continues = next in activeDays || (next == today && today !in activeDays)
    if (!continues) return 0
    if ((1..STREAK_FREEZE_WINDOW_DAYS).any { day - it in frozen }) return 0
    return prev + 1
}

/** Streak lengths that earn a one-time note. */
val STREAK_MILESTONES = listOf(7, 30, 100, 365)

/** Archive sizes that earn a one-time note. */
val ARCHIVED_MILESTONES = listOf(100, 500, 1000)

/**
 * A one-time study note. [alsoShown] is every lower threshold of the same kind
 * crossed at the same moment: jumping from no streak to 30 days celebrates 30
 * only, and those skipped ids have to be recorded too or the next call would
 * celebrate them.
 */
data class Milestone(
    val id: String,
    val message: String,
    val alsoShown: Set<String> = emptySet(),
)

/**
 * The single highest crossed threshold not in [shown]. When a streak threshold
 * and an archive threshold are both new, the streak one is returned and the
 * archive one stays unshown for the next call.
 */
fun newMilestone(streakDays: Int, archivedCount: Int, shown: Set<String>): Milestone? =
    highestUnshown("streak", STREAK_MILESTONES, streakDays, shown) { "$it-day streak" }
        ?: highestUnshown("archived", ARCHIVED_MILESTONES, archivedCount, shown) {
            "$it characters archived"
        }

private fun highestUnshown(
    kind: String,
    thresholds: List<Int>,
    value: Int,
    shown: Set<String>,
    message: (Int) -> String,
): Milestone? {
    val pending = thresholds.filter { value >= it && "$kind:$it" !in shown }
    val top = pending.maxOrNull() ?: return null
    return Milestone(
        id = "$kind:$top",
        message = message(top),
        alsoShown = pending.filter { it != top }.mapTo(HashSet()) { "$kind:$it" },
    )
}
