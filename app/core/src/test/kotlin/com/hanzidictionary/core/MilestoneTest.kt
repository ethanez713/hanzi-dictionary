package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One-time study notes: each threshold once, the highest unshown one, streak before archive. */
class MilestoneTest {
    @Test
    fun nothingBelowTheFirstThreshold() {
        assertNull(newMilestone(0, 0, emptySet()))
        assertNull(newMilestone(6, 99, emptySet()))
        assertEquals(listOf(7, 30, 100, 365), STREAK_MILESTONES)
        assertEquals(listOf(100, 500, 1000), ARCHIVED_MILESTONES)
    }

    @Test
    fun eachThresholdFiresOnce() {
        val shown = HashSet<String>()
        for (days in STREAK_MILESTONES) {
            val hit = newMilestone(days, 0, shown)!!
            assertEquals(Milestone("streak:$days", "$days-day streak"), hit)
            shown += hit.id
            shown += hit.alsoShown
            assertNull(newMilestone(days, 0, shown))
        }
        for (count in ARCHIVED_MILESTONES) {
            val hit = newMilestone(0, count, shown)!!
            assertEquals(Milestone("archived:$count", "$count characters archived"), hit)
            shown += hit.id
            shown += hit.alsoShown
            assertNull(newMilestone(0, count, shown))
        }
    }

    @Test
    fun theHighestUnshownThresholdWinsAndLowerOnesAreSkipped() {
        assertEquals(
            Milestone("streak:30", "30-day streak", setOf("streak:7")),
            newMilestone(30, 0, emptySet()),
        )
        assertEquals(
            Milestone("streak:100", "100-day streak", setOf("streak:7", "streak:30")),
            newMilestone(100, 99, emptySet()),
        )
        assertEquals(
            Milestone(
                "streak:365",
                "365-day streak",
                setOf("streak:7", "streak:30", "streak:100"),
            ),
            newMilestone(400, 0, emptySet()),
        )
        assertEquals(
            Milestone("archived:1000", "1000 characters archived", setOf("archived:100", "archived:500")),
            newMilestone(0, 1000, emptySet()),
        )
        // 7 is already recorded, so 30 stands alone.
        assertEquals(
            Milestone("streak:30", "30-day streak"),
            newMilestone(36, 0, setOf("streak:7")),
        )
    }

    @Test
    fun aNewStreakIsNotedBeforeANewArchive() {
        val streak = newMilestone(365, 1000, emptySet())!!
        assertEquals("streak:365", streak.id)
        assertEquals(setOf("streak:7", "streak:30", "streak:100"), streak.alsoShown)

        val shown = setOf(streak.id) + streak.alsoShown
        val archived = newMilestone(365, 1000, shown)!!
        assertEquals("archived:1000", archived.id)
        assertEquals(setOf("archived:100", "archived:500"), archived.alsoShown)

        assertNull(newMilestone(365, 1000, shown + archived.id + archived.alsoShown))
    }

    @Test
    fun aShownSetHidesOnlyTheThresholdsItNames() {
        assertNull(newMilestone(7, 99, setOf("streak:7")))
        assertEquals(
            Milestone("archived:100", "100 characters archived"),
            newMilestone(7, 100, setOf("streak:7")),
        )
        assertEquals(
            Milestone("streak:7", "7-day streak"),
            newMilestone(29, 499, setOf("archived:100")),
        )
    }
}
