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
