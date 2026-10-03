package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the app may suggest new words — each gate on its own, and the way they combine. */
class DeckHealthTest {
    private fun gap(upcoming: Int, backlog: Int = 0, success: Double? = 0.9, session: Int = NEW_WORD_SESSION) =
        newWordGap(DeckLoad(upcoming, backlog, success), session)

    @Test
    fun aDeckWithAFullSittingComingUpNeverAsksForMore() {
        assertEquals(0, gap(upcoming = NEW_WORD_SESSION))
        assertEquals(0, gap(upcoming = 40))
    }

    @Test
    fun aThinDeckAsksForJustEnoughToFillOneSitting() {
        assertEquals(NEW_WORD_SESSION, gap(upcoming = 0))
        assertEquals(2, gap(upcoming = NEW_WORD_SESSION - 2))
        assertEquals(1, gap(upcoming = NEW_WORD_SESSION - 1))
    }

    @Test
    fun anOverdueBacklogHoldsNewWordsBack() {
        // Thin upcoming work but a pile already overdue: adding more only deepens the pile.
        assertEquals(0, gap(upcoming = 1, backlog = 2 * NEW_WORD_SESSION))
        assertEquals(NEW_WORD_SESSION - 1, gap(upcoming = 1, backlog = 2 * NEW_WORD_SESSION - 1))
    }

    @Test
    fun recentMissesHoldNewWordsBack() {
        assertEquals(0, gap(upcoming = 0, success = RECENT_SUCCESS_MIN - 0.01))
        assertEquals(NEW_WORD_SESSION, gap(upcoming = 0, success = RECENT_SUCCESS_MIN), "the bound is inclusive")
    }

    @Test
    fun noReviewHistoryYetDoesNotBlockTheOffer() {
        assertEquals(NEW_WORD_SESSION, gap(upcoming = 0, success = null))
    }

    @Test
    fun theSittingSizeIsAdjustable() {
        assertEquals(8, gap(upcoming = 2, session = 10))
    }

    @Test
    fun successRateCountsOnlyGoodOrBetter() {
        // newest first; 3 of 5 are Good+.
        assertEquals(0.6, recentSuccessRate(listOf(3, 1, 3, 4, 2))!!, 1e-9)
    }

    @Test
    fun tooFewReviewsToCallARate() {
        assertNull(recentSuccessRate(listOf(1, 1, 1, 1)))
        assertNull(recentSuccessRate(emptyList()))
    }
}
