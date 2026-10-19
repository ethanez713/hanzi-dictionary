package com.hanzidictionary.core

/**
 * Cut points that split one user's non-zero daily counts into four shades.
 *
 * Zero is not a shade. An empty day stays neutral, and it is left out of the
 * sample so a run of days off does not drag every active day into the bottom
 * band. The cuts are sample quartiles — position (n−1)·p on the sorted
 * non-zero counts — which stay defined for a history shorter than four days.
 * Fewer than four distinct values simply never reach the upper shades: a flat
 * history has no "busiest" day to paint darker. Null when nothing was studied.
 */
data class ActivityQuartiles(val q1: Double, val q2: Double, val q3: Double)

fun activityQuartiles(counts: Collection<Int>): ActivityQuartiles? {
    val positive = counts.filter { it > 0 }.sorted()
    if (positive.isEmpty()) return null
    return ActivityQuartiles(
        q1 = sampleQuartile(positive, 0.25),
        q2 = sampleQuartile(positive, 0.50),
        q3 = sampleQuartile(positive, 0.75),
    )
}

/** 0 is a day with no reviews. 1..4 are the bands of [quartiles]; a count that
 * lands on a cut stays in the lower band. */
fun activityLevel(count: Int, quartiles: ActivityQuartiles?): Int {
    if (count <= 0 || quartiles == null) return 0
    val value = count.toDouble()
    return when {
        value <= quartiles.q1 -> 1
        value <= quartiles.q2 -> 2
        value <= quartiles.q3 -> 3
        else -> 4
    }
}

private fun sampleQuartile(sorted: List<Int>, p: Double): Double {
    val last = sorted.lastIndex
    val position = last * p
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(last)
    val fraction = position - lower
    return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
}
