package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What counts as "learned" and "ready to retire" — every threshold and every way the streak
 * can be reset or must not be padded. */
class MasteryTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val hour = 3_600_000L

    private fun card(stability: Double? = 30.0, lapses: Int = 0) =
        CardState(itemId = "U+597D", kind = "character", due = 0L, stability = stability, lapses = lapses)

    /** A grading [dayOffset] days in, [hours] into the day. Elapsed defaults to "a week". */
    private fun entry(
        dayOffset: Int,
        rating: Rating = Rating.GOOD,
        elapsed: Double? = 7.0,
        hours: Int = 9,
        origin: String = StudyRepository.ORIGIN_APP,
    ) = ReviewEntry(dayOffset * day + hours * hour, rating.value, elapsed, origin)

    private fun mastery(card: CardState?, vararg history: ReviewEntry) = masteryOf(card, history.toList(), utc)

    @Test
    fun aCardWithNoScheduleHasNoMasteryYet() {
        assertEquals(Mastery.NONE, mastery(null, entry(0)))
    }

    @Test
    fun oneCorrectAnswerIsNeverEnoughToCallACardLearned() {
        val m = mastery(card(stability = 400.0), entry(0, elapsed = 120.0))
        assertEquals(1, m.spacedStreak)
        assertEquals(MasteryLevel.LEARNING, m.level)
    }

    @Test
    fun threeSpacedSuccessesAfterALongGapIsLearned() {
        val m = mastery(card(), entry(0, elapsed = null), entry(7), entry(14, elapsed = 25.0))
        assertEquals(3, m.spacedStreak)
        assertEquals(25.0, m.lastGapDays)
        assertEquals(MasteryLevel.LEARNED, m.level)
    }

    @Test
    fun threeSuccessesWithNoLongGapAreStillLearning() {
        val m = mastery(card(), entry(0, elapsed = null), entry(3, elapsed = 3.0), entry(10, elapsed = 7.0))
        assertEquals(3, m.spacedStreak)
        assertEquals(MasteryLevel.LEARNING, m.level, "needs a success after ${LEARNED_MIN_GAP_DAYS.toInt()}+ days away")
    }

    @Test
    fun theLongGapBoundaryIsInclusive() {
        val at = mastery(card(), entry(0), entry(1), entry(30, elapsed = LEARNED_MIN_GAP_DAYS))
        val below = mastery(card(), entry(0), entry(1), entry(30, elapsed = LEARNED_MIN_GAP_DAYS - 1))
        assertEquals(MasteryLevel.LEARNED, at.level)
        assertEquals(MasteryLevel.LEARNING, below.level)
    }

    @Test
    fun sameDayRepeatsDontPadTheStreakOrBreakIt() {
        // Two Good answers the same day (a requeue, an "ahead" top-up) are one spaced success.
        val m = mastery(card(), entry(0), entry(7, hours = 9), entry(7, hours = 17, elapsed = 0.0))
        assertEquals(2, m.spacedStreak)
    }

    @Test
    fun theGapCountedIsTheFirstSuccessOfTheDayNotALaterRepeat() {
        val m = mastery(
            card(),
            entry(0), entry(7),
            entry(60, hours = 9, elapsed = 53.0),
            entry(60, hours = 18, elapsed = 0.0),
        )
        assertEquals(3, m.spacedStreak)
        assertEquals(53.0, m.lastGapDays, "the repeat's elapsed=0 must not hide the 53-day gap")
    }

    @Test
    fun aMissResetsTheStreak() {
        val m = mastery(card(), entry(0), entry(7), entry(14, Rating.AGAIN), entry(21))
        assertEquals(1, m.spacedStreak)
        assertEquals(MasteryLevel.LEARNING, m.level)
    }

    @Test
    fun aHardAnswerIsNotACleanSuccess() {
        val m = mastery(card(), entry(0), entry(7), entry(14, Rating.HARD))
        assertEquals(0, m.spacedStreak)
    }

    @Test
    fun aMissAndItsRelearningTheSameDayLeavesAStreakOfOne() {
        val m = mastery(card(), entry(0), entry(7), entry(14, Rating.AGAIN, hours = 9), entry(14, hours = 9 + 1, elapsed = 0.0))
        assertEquals(1, m.spacedStreak)
    }

    @Test
    fun aTriageClaimIsNotARetrieval() {
        val triage = entry(0, Rating.EASY, elapsed = null, origin = StudyRepository.ORIGIN_TRIAGE)
        val m = mastery(card(), triage, entry(8, elapsed = 8.0), entry(16, elapsed = 8.0))
        assertEquals(2, m.spacedStreak, "the self-reported 'know it' must not count as a success")
    }

    @Test
    fun aTriageClaimDoesNotBreakOrPadAnExistingStreak() {
        val claim = entry(5, Rating.EASY, origin = StudyRepository.ORIGIN_TRIAGE)
        val m = mastery(card(), entry(0), entry(7), claim, entry(14))
        assertEquals(3, m.spacedStreak)
    }

    @Test
    fun retireReadyNeedsStabilityAndALongSurvivedGapOnTopOfTheStreak() {
        val history = arrayOf(entry(0, elapsed = null), entry(30, elapsed = 30.0), entry(100, elapsed = 70.0))

        assertEquals(MasteryLevel.RETIRE_READY, mastery(card(stability = 200.0), *history).level)
        assertEquals(
            MasteryLevel.LEARNED,
            mastery(card(stability = RETIRE_MIN_STABILITY_DAYS - 1), *history).level,
            "long gap survived, but the memory isn't durable enough yet",
        )
        // Stable, but no single success has survived a 60-day gap.
        val shortGaps = arrayOf(entry(0, elapsed = null), entry(30, elapsed = 30.0), entry(75, elapsed = 45.0))
        assertEquals(MasteryLevel.LEARNED, mastery(card(stability = 400.0), *shortGaps).level)
    }

    @Test
    fun noStabilityMeansNoRetirement() {
        val history = arrayOf(entry(0), entry(30, elapsed = 65.0), entry(100, elapsed = 70.0))
        assertNull(mastery(card(stability = null), *history).stabilityDays)
        assertFalse(mastery(card(stability = null), *history).level == MasteryLevel.RETIRE_READY)
    }

    @Test
    fun cardsYouKeepMissingAreFlaggedUntilTheyRecover() {
        val missed = mastery(card(lapses = STRUGGLING_LAPSES), entry(0, Rating.AGAIN))
        assertTrue(missed.struggling)
        assertFalse(missed.leech)

        val leech = mastery(card(lapses = LEECH_LAPSES), entry(0, Rating.AGAIN))
        assertTrue(leech.struggling && leech.leech)

        // The same lapse count, but three spaced successes since: no longer a problem card.
        val recovered = mastery(card(lapses = LEECH_LAPSES), entry(0), entry(7), entry(14))
        assertFalse(recovered.struggling || recovered.leech)
    }

    @Test
    fun fewLapsesAreNotFlagged() {
        assertFalse(mastery(card(lapses = STRUGGLING_LAPSES - 1), entry(0, Rating.AGAIN)).struggling)
    }

    @Test
    fun historyOrderDoesNotMatter() {
        val shuffled = mastery(card(), entry(14, elapsed = 25.0), entry(0, elapsed = null), entry(7))
        assertEquals(3, shuffled.spacedStreak)
        assertEquals(MasteryLevel.LEARNED, shuffled.level)
    }

    @Test
    fun localMidnightDecidesWhatCountsAsTheSameDay() {
        // 21:30 and 22:30 UTC are one day in UTC, but straddle local midnight at GMT+2
        // (23:30, then 00:30 the next day) — so the zone, not UTC, decides what is "a new day".
        val first = ReviewEntry(21 * hour + 30 * 60_000L, Rating.GOOD.value, null, "app")
        val second = ReviewEntry(22 * hour + 30 * 60_000L, Rating.GOOD.value, 0.0, "app")
        assertEquals(1, masteryOf(card(), listOf(first, second), utc).spacedStreak)
        assertEquals(2, masteryOf(card(), listOf(first, second), TimeZone.getTimeZone("GMT+2")).spacedStreak)
    }

    @Test
    fun summaryStatesTheEvidenceInPlainNumbers() {
        val m = mastery(card(stability = 210.4, lapses = 1), entry(0, elapsed = null), entry(30, elapsed = 30.0), entry(100, elapsed = 62.6))
        assertEquals(
            "3 in a row · last after 63 d · stable ~210 d · 1 lapse · ready to archive",
            masterySummary(m),
        )
    }

    @Test
    fun summaryIsQuietForACardNobodyHasGraded() {
        assertNull(masterySummary(Mastery.NONE))
    }

    @Test
    fun summaryFlagsAStrugglingCard() {
        val m = mastery(card(stability = 2.0, lapses = 5), entry(0, Rating.AGAIN))
        assertEquals("stable ~2 d · 5 lapses · struggling", masterySummary(m))
    }
}
