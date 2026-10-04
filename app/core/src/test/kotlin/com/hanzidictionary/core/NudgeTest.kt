package com.hanzidictionary.core

import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        hasDue: Boolean = true,
    ) = shouldNudge(now, settings, lastReview, lastNudge, hasDue, utc)

    private fun reshow(now: Long, dismissedAt: Long? = null, settings: NudgeSettings = adaptive) =
        shouldReshow(now, settings, dismissedAt, utc)

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
    fun aPartialDayReopensOneIntervalAfterTheLastReview() {
        val reviewed = at(1, 9)
        assertFalse(should(at(1, 12), lastReview = reviewed), "3h later is too soon")
        assertTrue(should(at(1, 13), lastReview = reviewed), "the default 4h interval has passed")
        assertFalse(should(at(1, 14), lastReview = reviewed, lastNudge = at(1, 12)), "a nudge 2h ago still counts")
    }

    @Test
    fun adaptiveSendsAtMostOnePerDay() {
        assertFalse(should(at(0, 15), lastNudge = at(0, 10)))
        assertTrue(should(at(1, 10), lastNudge = at(0, 10)))
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
    fun anOpenReminderReturnsOnceTheIntervalSinceASwipePasses() {
        assertTrue(reshow(at(0, 12)), "only hidden for the open app, never dismissed")
        assertFalse(reshow(at(0, 12), dismissedAt = at(0, 10)), "2h after a swipe, 4h interval")
        assertTrue(reshow(at(0, 14), dismissedAt = at(0, 10)))
        assertTrue(reshow(at(0, 12), dismissedAt = at(0, 8), settings = every2h))
    }

    @Test
    fun anOpenReminderStaysQuietOutsideTheWindowOrWhenDisabled() {
        assertFalse(reshow(at(0, 22)))
        assertFalse(reshow(at(0, 12), settings = adaptive.copy(enabled = false)))
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
    fun theSessionRunsUntilNothingIsOwed() {
        var s = NudgeSession(WidgetCardInfo(StudyItem.character("c0"), "F:c0", "A:c0"))
        val deck = (1..20).map { dueEntry("c$it") }
        repeat(10) { s = nextNudgeSession(s, Rating.GOOD, deck, summarize, Random(it)) }
        assertEquals(10, s.graded)
        assertNotNull(s.card)
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
