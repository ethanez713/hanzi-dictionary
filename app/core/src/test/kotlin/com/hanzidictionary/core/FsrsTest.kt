package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins our FSRS-6 port to the reference implementation. The golden sequence below is the
 * expected output of py-fsrs's own `test_review_card` (MIT, open-spaced-repetition) with
 * fuzzing off and default parameters — if a formula is transcribed wrong, these intervals
 * diverge immediately, which is the whole point of keeping the case verbatim.
 */
class FsrsTest {
    private val fsrs = Fsrs(enableFuzz = false)

    private fun newCard(now: Long) = CardState(itemId = "U+597D", kind = "character", due = now)

    @Test
    fun matchesReferenceIntervalSequence() {
        val ratings = listOf(
            Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
            Rating.AGAIN, Rating.AGAIN,
            Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
        )
        var now = 1_669_724_400_000L // 2022-11-29T12:30Z, the reference test's start time
        var card = newCard(now)
        val intervalDays = mutableListOf<Long>()

        for (rating in ratings) {
            card = fsrs.review(card, rating, now)
            intervalDays += (card.due - card.lastReview!!) / DAY_MS
            now = card.due // the reference reviews each card exactly when it comes due
        }

        assertEquals(
            listOf(0L, 2L, 11L, 46L, 163L, 498L, 0L, 0L, 2L, 4L, 7L, 12L, 21L),
            intervalDays,
        )
    }

    @Test
    fun againInReviewPhaseCountsALapseAndDropsToRelearning() {
        var now = 1_669_724_400_000L
        var card = newCard(now)
        // Two Goods graduate a card out of the 1min/10min learning steps.
        repeat(2) {
            card = fsrs.review(card, Rating.GOOD, now)
            now = card.due
        }
        assertEquals(CardPhase.REVIEW, card.phase)
        assertEquals(0, card.lapses)

        card = fsrs.review(card, Rating.AGAIN, now)
        assertEquals(CardPhase.RELEARNING, card.phase)
        assertEquals(1, card.lapses)
        assertEquals(3, card.reviews)
        assertEquals(2, card.recalled)
        // Relearning steps are minutes, not days: a forgotten card comes straight back.
        assertTrue(card.due - now < DAY_MS, "relearning card should be due within the day")
    }

    @Test
    fun retrievabilityDecaysToNinetyPercentAtOneStability() {
        var card = newCard(0L)
        card = fsrs.review(card, Rating.GOOD, 0L)
        card = fsrs.review(card, Rating.GOOD, 10 * 60_000L)
        val stabilityDays = card.stability!!

        // Stability is *defined* as the point where recall probability hits 90%.
        val atStability = fsrs.retrievability(card, card.lastReview!! + (stabilityDays * DAY_MS).toLong())
        assertTrue(atStability in 0.88..0.92, "R at t=S was $atStability, expected ~0.90")
        assertEquals(1.0, fsrs.retrievability(card, card.lastReview!!), 0.001)

        // Monotone decay. Note FSRS-6's curve is a power law with a very fat tail (decay
        // 0.1542): even ten years out R is only ~0.33, not ~0 — so assert the shape, not
        // an arbitrary floor.
        val curve = listOf(1L, 7L, 30L, 365L, 3650L)
            .map { fsrs.retrievability(card, card.lastReview!! + it * DAY_MS) }
        assertEquals(curve.sortedDescending(), curve, "retrievability must never increase")
        assertTrue(curve.last() < atStability)
    }

    @Test
    fun difficultyStaysInRangeUnderRepeatedExtremes() {
        var now = 0L
        var card = newCard(now)
        repeat(20) {
            card = fsrs.review(card, Rating.AGAIN, now)
            now = card.due
        }
        assertTrue(card.difficulty!! <= 10.0, "difficulty escaped its range: ${card.difficulty}")

        repeat(20) {
            card = fsrs.review(card, Rating.EASY, now)
            now = card.due
        }
        assertTrue(card.difficulty!! >= 1.0, "difficulty escaped its range: ${card.difficulty}")
        assertTrue(card.stability!! > 0.0)
    }

    @Test
    fun fuzzKeepsIntervalsCloseButNotIdentical() {
        val fuzzed = Fsrs(enableFuzz = true)
        val intervals = (1..40).map {
            var now = 0L
            var card = newCard(now)
            repeat(5) {
                card = fuzzed.review(card, Rating.GOOD, now)
                now = card.due
            }
            (card.due - card.lastReview!!) / DAY_MS
        }
        val unfuzzed = run {
            var now = 0L
            var card = newCard(now)
            repeat(5) {
                card = fsrs.review(card, Rating.GOOD, now)
                now = card.due
            }
            (card.due - card.lastReview!!) / DAY_MS
        }
        assertTrue(intervals.distinct().size > 1, "fuzz produced no variation at all")
        // Fuzz is a nudge, not a reschedule: everything stays within ±30% of the ideal.
        assertTrue(
            intervals.all { it > unfuzzed * 0.7 && it < unfuzzed * 1.3 },
            "fuzzed intervals $intervals strayed too far from $unfuzzed",
        )
    }
}
