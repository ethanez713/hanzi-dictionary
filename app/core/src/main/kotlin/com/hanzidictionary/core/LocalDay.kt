package com.hanzidictionary.core

import java.util.TimeZone

/** Local calendar day containing [ts], as a count of [DAY_MS] since the epoch.
 * Core stays off java.time; the offset at [ts] is the whole calculation. */
fun localDay(ts: Long, tz: TimeZone = TimeZone.getDefault()): Long =
    (ts + tz.getOffset(ts)) / DAY_MS

/** UTC millis of the local midnight that opens the day containing [ts]. The offset
 * is the one in effect at [ts], so "today" here is the same instant the new-card
 * budget and the review count already bucket on. */
fun startOfLocalDay(ts: Long, tz: TimeZone = TimeZone.getDefault()): Long {
    val offset = tz.getOffset(ts)
    return localDay(ts, tz) * DAY_MS - offset
}
