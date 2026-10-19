package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Streak rules and the local-day activity count. Day numbers are local-day indexes,
 * so the tests don't depend on a machine timezone. */
class StreakTest {
    @Test
    fun todayUnstudiedIsAtRiskNotBroken() {
        val state = streak(setOf(0L, 1L, 2L), today = 3L)
        assertEquals(StreakState(3, 3, studiedToday = false, frozenDays = emptySet()), state)
    }

    @Test
    fun oneMissedDayIsBridgedAndCountsTowardLength() {
        // Day 2 is the only miss, between studied days, so it is bridged.
        val state = streak(setOf(0L, 1L, 3L, 4L), today = 4L)
        assertEquals(StreakState(5, 5, studiedToday = true, frozenDays = setOf(2L)), state)
    }

    @Test
    fun missedYesterdayIsBridgedWhileTodayIsAtRisk() {
        // Day 3 is a finished miss; day 4 has not been studied yet, so it is not a second miss.
        val state = streak(setOf(0L, 1L, 2L), today = 4L)
        assertEquals(StreakState(4, 4, studiedToday = false, frozenDays = setOf(3L)), state)
    }

    @Test
    fun aSecondGapWithinSevenDaysBreaks() {
        // Day 1 is bridged. Day 7 falls inside the preceding 6 days of that bridge, so the
        // second single miss is not — the run ends and today starts another.
        val state = streak(setOf(0L, 2L, 3L, 4L, 5L, 6L, 8L), today = 8L)
        assertEquals(StreakState(1, 7, studiedToday = true, frozenDays = setOf(1L)), state)
    }

    @Test
    fun aGapSevenDaysAfterAFreezeStillBridges() {
        // Day 8's preceding 6 days are 2..7, which does not include the bridge on day 1.
        val state = streak(setOf(0L, 2L, 3L, 4L, 5L, 6L, 7L, 9L), today = 9L)
        assertEquals(StreakState(10, 10, studiedToday = true, frozenDays = setOf(1L, 8L)), state)
    }

    @Test
    fun twoConsecutiveMissesBreak() {
        val state = streak(setOf(0L, 1L, 2L, 3L, 6L, 7L), today = 7L)
        assertEquals(StreakState(2, 4, studiedToday = true, frozenDays = emptySet()), state)
    }

    @Test
    fun longestKeepsTheBestRunIncludingBridgedDays() {
        // Days 0..5 with day 2 bridged are a run of 6; two misses, then today alone.
        val state = streak(setOf(0L, 1L, 3L, 4L, 5L, 8L), today = 8L)
        assertEquals(StreakState(1, 6, studiedToday = true, frozenDays = setOf(2L)), state)
    }

    @Test
    fun noActivityIsAnEmptyStreak() {
        assertEquals(StreakState(0, 0, studiedToday = false, frozenDays = emptySet()), streak(emptySet(), today = 5L))
    }

    @Test
    fun dailyActivitySplitsOnAFixedNonUtcDayBoundary() {
        val tz = TimeZone.getTimeZone("GMT+8")
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(8 * 3_600_000, tz.rawOffset, "GMT+8 must be a fixed offset, not a DST zone")

        // 16:00 UTC is midnight in GMT+8. One millisecond earlier is still the previous
        // local day, but both instants are the same UTC day.
        val utcMidnight = (1_700_000_000_000L / DAY_MS) * DAY_MS
        val localMidnight = utcMidnight + (DAY_MS - tz.rawOffset)
        val before = localMidnight - 1

        assertEquals(localDay(before, utc), localDay(localMidnight, utc))
        assertEquals(localDay(before, tz) + 1, localDay(localMidnight, tz))
        assertEquals(localMidnight - DAY_MS, startOfLocalDay(before, tz))
        assertEquals(localMidnight, startOfLocalDay(localMidnight, tz))

        val counts = dailyActivity(
            listOf(
                ActivityRow(before, "U+597D"),
                ActivityRow(localMidnight, "U+597D"),
                ActivityRow(localMidnight + 60_000, "U+597D"),
                ActivityRow(localMidnight + 120_000, "U+4F60"),
            ),
            tz,
        )
        assertEquals(
            mapOf(localDay(before, tz) to 1, localDay(localMidnight, tz) to 2),
            counts,
        )
    }
}
