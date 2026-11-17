package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest {
    private val now = 1_700_000_000_000L

    @Test
    fun aColdStartHasNothingToForget() {
        assertFalse(isStaleSession(lastActiveAt = null, now = now))
    }

    @Test
    fun stepAwayAndComeStraightBackKeepsTheTrail() {
        assertFalse(isStaleSession(now - 60_000, now))
        assertFalse(isStaleSession(now - SESSION_IDLE_TIMEOUT_MS + 1, now))
    }

    @Test
    fun aLongAbsenceIsANewErrand() {
        assertTrue(isStaleSession(now - SESSION_IDLE_TIMEOUT_MS, now))
        assertTrue(isStaleSession(now - 4 * 60 * 60 * 1000L, now))
    }

    @Test
    fun aClockThatMovedBackwardsNeverWipesTheStack() {
        // Timezone change or an NTP correction: better to keep a trail than to drop one
        // out from under the user.
        assertFalse(isStaleSession(now + 60 * 60 * 1000L, now))
    }
}

class LocalDayTest {
    private val ny = java.util.TimeZone.getTimeZone("America/New_York")
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, m - 1, d, h, min)
        }.timeInMillis

    @kotlin.test.Test
    fun midnightIsTheSameInstantAllDayWhenTheClocksChange() {
        // Spring forward, 2024-03-10: local midnight is 05:00 UTC (EST), though by noon the
        // offset is EDT. Using noon's offset put Saturday 23:30 into Sunday.
        val springMidnight = utc(2024, 3, 10, 5)
        kotlin.test.assertEquals(springMidnight, startOfLocalDay(utc(2024, 3, 10, 16), ny))
        kotlin.test.assertEquals(springMidnight, startOfLocalDay(utc(2024, 3, 10, 5, 30), ny))
        // Fall back, 2024-11-03: local midnight is 04:00 UTC (EDT); a 00:30 review counts.
        val fallMidnight = utc(2024, 11, 3, 4)
        kotlin.test.assertEquals(fallMidnight, startOfLocalDay(utc(2024, 11, 3, 18), ny))
        kotlin.test.assertTrue(utc(2024, 11, 3, 4, 30) >= startOfLocalDay(utc(2024, 11, 3, 18), ny))
        // An ordinary day is unchanged.
        kotlin.test.assertEquals(utc(2024, 6, 1, 4), startOfLocalDay(utc(2024, 6, 1, 20), ny))
    }
}
