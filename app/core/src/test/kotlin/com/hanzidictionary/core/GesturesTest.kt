package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The photo grid's swipe commit distance. This shipped unreachable twice, so the rule that
 * makes it reachable is pinned here rather than left to be re-derived from the gesture code. */
class GesturesTest {
    private val screen = 400f    // dp-ish units; the function is unit-agnostic
    private val base = screen * 0.42f

    @Test
    fun aGestureWithRoomKeepsItsFullThreshold() {
        // Grabbed mid-screen, dragging right: 200 of room, 160 of it is still more than the
        // 168 the gesture asks for... so the ask wins only where the room genuinely exceeds it.
        assertEquals(base, commitDistance(base, screen))
        assertTrue(commitDistance(base, 300f) == base, "300 of room comfortably covers a 168 ask")
    }

    @Test
    fun aGestureStartingNearTheEdgeCommitsAtTheEdge() {
        // The bug: last-column tile, ~50 of room to the right, against a 168 ask. The old
        // flat threshold could never be reached because the finger left the screen first.
        val room = 50f
        val threshold = commitDistance(base, room)
        assertTrue(threshold < room, "threshold $threshold must be inside the $room available")
        assertTrue(threshold > 0f, "an edge tile must still be swipeable")
        assertEquals(room * EDGE_REACH, threshold)
    }

    @Test
    fun thresholdIsNeverNegativeOrBeyondTheScreen() {
        // Defensive: a drag can start outside the measured bounds (a stale onGloballyPositioned
        // during a scroll fling), and a negative threshold would fire the action instantly.
        assertEquals(0f, commitDistance(base, -20f))
        assertEquals(0f, commitDistance(base, 0f))
        assertTrue(commitDistance(base, 10_000f) == base, "absurd room still caps at the ask")
    }
}
