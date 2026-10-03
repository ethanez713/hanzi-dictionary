package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The notification's whole decision logic — when to post, and which card comes next —
 * driven without a device. */
class NudgeTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour

    // 2024-01-10 00:00 UTC; tests pick an hour of the day on top of it.
    private val midnight = 1_704_844_800_000L
    private fun at(day: Int, hourOfDay: Int, minute: Int = 0) =
        midnight + day * this.day + hourOfDay * hour + minute * 60_000L

    private val adaptive = NudgeSettings(enabled = true, mode = NudgeMode.ADAPTIVE)
    private val every2h = NudgeSettings(enabled = true, mode = NudgeMode.INTERVAL, intervalHours = 2)

    private fun should(
        now: Long,
        settings: NudgeSettings = adaptive,
        lastReview: Long? = null,
        lastNudge: Long? = null,
        ignored: Int = 0,
        hasDue: Boolean = true,
    ) = shouldNudge(now, settings, lastReview, lastNudge, ignored, hasDue, utc)

    @Test
    fun disabledOrNothingDueNeverNudges() {
        assertFalse(should(at(0, 12), settings = adaptive.copy(enabled = false)))
        assertFalse(should(at(0, 12), hasDue = false))
    }

    @Test
    fun staysSilentOutsideTheWakingWindow() {
        assertFalse(should(at(0, 7)))
        assertFalse(should(at(0, 21))) // the end of the window is exclusive
        assertTrue(should(at(0, 9)))
        assertTrue(should(at(0, 20, 59)))
    }

    @Test
    fun aWindowThatWrapsMidnightIsHonoured() {
        val night = adaptive.copy(windowStartMin = 22 * 60, windowEndMin = 6 * 60)
        assertTrue(should(at(0, 23), settings = night))
        assertTrue(should(at(0, 5), settings = night))
        assertFalse(should(at(0, 12), settings = night))
    }

    @Test
    fun adaptiveNudgesAboutADayAfterTheLastReview() {
        val reviewed = at(0, 18)
        assertFalse(should(at(1, 10), lastReview = reviewed), "16h later is too soon")
        assertTrue(should(at(1, 18), lastReview = reviewed), "a day later, at the usual hour")
    }

    @Test
    fun adaptiveSkipsADayYouAlreadyStudied() {
        // Studied this morning; the 23h gap is long past from yesterday's session, but the
        // habit already happened today so a nudge would be noise.
        assertFalse(should(at(1, 14), lastReview = at(1, 9)))
    }

    @Test
    fun adaptiveSendsAtMostOnePerDay() {
        assertFalse(should(at(0, 15), lastNudge = at(0, 10)))
        assertTrue(should(at(1, 10), lastNudge = at(0, 10)))
    }

    @Test
    fun adaptiveBacksOffAfterFourIgnoredNudges() {
        val last = at(0, 10)
        assertTrue(should(at(1, 10), lastNudge = last, ignored = 3))
        assertFalse(should(at(1, 10), lastNudge = last, ignored = 4), "backed off to every 2 days")
        assertTrue(should(at(2, 10), lastNudge = last, ignored = 4))
    }

    @Test
    fun aFirstEverNudgeHasNothingToWaitFor() {
        assertTrue(should(at(0, 12)))
    }

    @Test
    fun intervalModeWaitsTheConfiguredGapSinceNudgeAndReview() {
        assertFalse(should(at(0, 12), settings = every2h, lastNudge = at(0, 11)))
        assertTrue(should(at(0, 13), settings = every2h, lastNudge = at(0, 11)))
        // A review counts too: you just studied, so no nudge a minute later.
        assertFalse(should(at(0, 13), settings = every2h, lastNudge = at(0, 9), lastReview = at(0, 12, 30)))
    }

    @Test
    fun intervalModeIgnoresTheDailyCapButNotTheWindow() {
        assertTrue(should(at(0, 17), settings = every2h, lastNudge = at(0, 15), lastReview = at(0, 8)))
        assertFalse(should(at(0, 22), settings = every2h, lastNudge = at(0, 15)))
    }

    @Test
    fun ignoredStreakGrowsUntilAReviewResetsIt() {
        assertEquals(0, nextIgnoredStreak(5, lastNudgeAt = null, lastReviewAt = at(0, 9)))
        assertEquals(3, nextIgnoredStreak(2, lastNudgeAt = at(0, 9), lastReviewAt = at(0, 8)))
        assertEquals(1, nextIgnoredStreak(0, lastNudgeAt = at(0, 9), lastReviewAt = null))
        assertEquals(0, nextIgnoredStreak(7, lastNudgeAt = at(0, 9), lastReviewAt = at(0, 10)))
    }

    // ---- the in-notification session ----

    private fun newEntry(id: String) = DeckEntry(StudyItem.character(id), null, DeckBucket.NEW)
    private fun dueEntry(id: String) =
        DeckEntry(StudyItem.character(id), CardState(itemId = id, kind = "character", due = 0L), DeckBucket.DUE)
    private fun aheadEntry(id: String) =
        DeckEntry(StudyItem.character(id), CardState(itemId = id, kind = "character", due = 1L), DeckBucket.AHEAD)

    private val summarize: (StudyItem) -> WidgetCardInfo? = { item ->
        WidgetCardInfo(item, front = "F:${item.itemId}", answer = "A:${item.itemId}")
    }

    @Test
    fun aSessionOpensOnANeverSeenCardFirst() {
        val s = startNudgeSession(listOf(dueEntry("a"), newEntry("b")), summarize, Random(1))
        assertEquals("b", s.card?.item?.itemId)
        assertEquals(0, s.graded)
    }

    @Test
    fun anEmptyDeckStartsAnEndedSession() {
        assertNull(startNudgeSession(emptyList(), summarize).card)
    }

    @Test
    fun gradingMovesOnAndRemembersTheAnswer() {
        val start = NudgeSession(WidgetCardInfo(StudyItem.character("a"), "F:a", "A:a"))
        val next = nextNudgeSession(start, Rating.AGAIN, listOf(dueEntry("a"), dueEntry("b")), summarize, Random(1))
        assertEquals("b", next.card?.item?.itemId)
        assertEquals(1, next.graded)
        assertEquals(NudgeGraded("F:a", "A:a", knew = false), next.previous)
    }

    @Test
    fun aGoodGradeIsRecordedAsKnew() {
        val start = NudgeSession(WidgetCardInfo(StudyItem.character("a"), "F:a", "A:a"))
        val next = nextNudgeSession(start, Rating.GOOD, listOf(dueEntry("b")), summarize)
        assertTrue(next.previous!!.knew)
    }

    @Test
    fun theSessionEndsWhenNothingIsOwedAnyMore() {
        val start = NudgeSession(WidgetCardInfo(StudyItem.character("a"), "F:a", "A:a"))
        // Only the card just graded, and an "ahead" top-up nobody is owed.
        val next = nextNudgeSession(start, Rating.GOOD, listOf(dueEntry("a"), aheadEntry("z")), summarize)
        assertNull(next.card)
        assertEquals(1, next.graded)
        assertEquals("F:a", next.previous?.front, "the closing card still shows the last answer")
    }

    @Test
    fun theSessionStopsAfterTheCap() {
        var s = NudgeSession(WidgetCardInfo(StudyItem.character("c0"), "F:c0", "A:c0"))
        val deck = (1..20).map { dueEntry("c$it") }
        repeat(NUDGE_SESSION_MAX) { s = nextNudgeSession(s, Rating.GOOD, deck, summarize, Random(it)) }
        assertNull(s.card)
        assertEquals(NUDGE_SESSION_MAX, s.graded)
    }

    @Test
    fun anEndedSessionStaysEnded() {
        val ended = NudgeSession(null, graded = 3)
        assertEquals(ended, nextNudgeSession(ended, Rating.GOOD, listOf(dueEntry("a")), summarize))
    }

    @Test
    fun titleRotatesDailyAndPluralisesTheCount() {
        assertNotEquals(nudgeTitle(10, 3), nudgeTitle(11, 3), "same wording two days running")
        assertEquals(nudgeTitle(10, 3), nudgeTitle(10 + 4, 3), "cycles through the four variants")
        assertTrue(nudgeTitle(0, 1).contains("1 card") && !nudgeTitle(0, 1).contains("cards"))
        assertTrue(nudgeTitle(0, 7).contains("7 cards"))
    }
}
