package com.hanzidictionary.core

import java.util.TimeZone

/** Local calendar day containing [ts], as a count of [DAY_MS] since the epoch.
 * Core stays off java.time; the offset at [ts] is the whole calculation. */
fun localDay(ts: Long, tz: TimeZone = TimeZone.getDefault()): Long =
    (ts + tz.getOffset(ts)) / DAY_MS

/** UTC millis of the local midnight that opens the day containing [ts].
 *
 * The offset that matters is the one in force *at that midnight*, not at [ts]: on the day
 * the clocks change the two differ by an hour, and using [ts]'s put the previous evening's
 * last hour into today (or left today's first hour out), so the new-card budget and the
 * daily goal disagreed with the streak about what had been done today. */
fun startOfLocalDay(ts: Long, tz: TimeZone = TimeZone.getDefault()): Long {
    val day = localDay(ts, tz) * DAY_MS
    val guess = day - tz.getOffset(ts)
    return day - tz.getOffset(guess)
}
