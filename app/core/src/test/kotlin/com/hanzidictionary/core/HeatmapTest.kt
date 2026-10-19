package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Quartile shades for the activity heatmap. 0 is an empty day; 1..4 are the
 * non-zero sample, and a short history is allowed to leave a shade unused. */
class HeatmapTest {
    @Test
    fun noCountsAndAllZerosHaveNoScale() {
        assertNull(activityQuartiles(emptyList()))
        assertNull(activityQuartiles(listOf(0, 0, 0)))
        assertEquals(0, activityLevel(0, null))
        assertEquals(0, activityLevel(8, null))
    }

    @Test
    fun zerosStayNeutralAndDoNotMoveTheOtherDays() {
        val withZeros = activityQuartiles(listOf(0, 0, 10, 11, 12, 13))
        val without = activityQuartiles(listOf(10, 11, 12, 13))
        assertEquals(without, withZeros)
        assertEquals(0, activityLevel(0, withZeros))
    }

    @Test
    fun oneRepeatedCountIsOnlyTheLightestShade() {
        // Every active day is the same, so none of them is "busier than usual".
        val quartiles = assertNotNull(activityQuartiles(listOf(5, 5, 5)))
        assertEquals(1, activityLevel(5, quartiles))
    }

    @Test
    fun shortHistoriesLeaveAShadeUnused() {
        val two = assertNotNull(activityQuartiles(listOf(1, 2)))
        assertEquals(1, activityLevel(1, two))
        assertEquals(4, activityLevel(2, two))

        val three = assertNotNull(activityQuartiles(listOf(1, 2, 3)))
        assertEquals(1, activityLevel(1, three))
        assertEquals(2, activityLevel(2, three))
        assertEquals(4, activityLevel(3, three))
    }

    @Test
    fun aTightClusterUsesAllFourShades() {
        // Close counts still span the scale. A fraction of the max would paint
        // every one of these the same dark shade.
        val quartiles = assertNotNull(activityQuartiles(listOf(13, 10, 12, 11)))
        assertEquals(1, activityLevel(10, quartiles))
        assertEquals(2, activityLevel(11, quartiles))
        assertEquals(3, activityLevel(12, quartiles))
        assertEquals(4, activityLevel(13, quartiles))
    }

    @Test
    fun aCountSittingOnACutStaysInTheLowerShade() {
        // Cuts land on 2, 3 and 4. The day that equals a cut is not pushed up.
        val quartiles = assertNotNull(activityQuartiles(listOf(1, 2, 3, 4, 5)))
        assertEquals(1, activityLevel(1, quartiles))
        assertEquals(1, activityLevel(2, quartiles))
        assertEquals(2, activityLevel(3, quartiles))
        assertEquals(3, activityLevel(4, quartiles))
        assertEquals(4, activityLevel(5, quartiles))
    }

    @Test
    fun repeatedLowDaysOutweighAFewHighOnes() {
        // Five days at 1 pull the lower cuts down. Quartiles weigh each day,
        // not each distinct count — 2 is already in the upper half.
        val quartiles = assertNotNull(activityQuartiles(listOf(1, 1, 1, 1, 1, 2, 3, 4)))
        assertEquals(1, activityLevel(1, quartiles))
        assertEquals(3, activityLevel(2, quartiles))
        assertEquals(4, activityLevel(3, quartiles))
        assertEquals(4, activityLevel(4, quartiles))
    }
}
