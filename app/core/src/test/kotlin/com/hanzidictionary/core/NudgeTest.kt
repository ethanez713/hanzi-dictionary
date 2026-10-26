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

    // ---- the evening streak reminder ----

    private fun atRisk(current: Int = 4, studiedToday: Boolean = false) =
        StreakState(current, longest = current, studiedToday = studiedToday, frozenDays = emptySet())

    private fun streakNudge(
        now: Long,
        settings: NudgeSettings = adaptive,
        streak: StreakState = atRisk(),
        lastNudge: Long? = null,
    ) = shouldStreakNudge(now, settings, streak, lastNudge, utc)

    @Test
    fun streakNudgeFiresOnlyInTheLastThreeHoursOfTheWindow() {
        assertFalse(streakNudge(at(0, 12)))
        assertFalse(streakNudge(at(0, 17, 59)))
        assertTrue(streakNudge(at(0, 18)))
        assertTrue(streakNudge(at(0, 20, 59)))
        assertFalse(streakNudge(at(0, 21)))
        // A window shorter than three hours is all tail.
        val brief = adaptive.copy(windowStartMin = 20 * 60, windowEndMin = 21 * 60)
        assertTrue(streakNudge(at(0, 20), settings = brief))
        assertFalse(streakNudge(at(0, 19, 59), settings = brief))
    }

    @Test
    fun streakNudgeStaysQuietOnceTodayIsStudied() {
        assertFalse(streakNudge(at(0, 19), streak = atRisk(studiedToday = true)))
    }

    @Test
    fun streakNudgeDoesNotPostTwiceInADay() {
        assertFalse(streakNudge(at(0, 20), lastNudge = at(0, 18)))
        assertTrue(streakNudge(at(1, 19), lastNudge = at(0, 18)))
        assertTrue(streakNudge(at(0, 19), lastNudge = null))
    }

    @Test
    fun aBrokenStreakIsNotNudged() {
        assertFalse(streakNudge(at(0, 19), streak = atRisk(current = 0)))
        assertTrue(streakNudge(at(0, 19), streak = atRisk(current = 1)))
    }

    @Test
    fun streakNudgeStaysQuietWhenRemindersOrTheStreakSwitchAreOff() {
        assertFalse(streakNudge(at(0, 19), settings = adaptive.copy(enabled = false)))
        assertFalse(streakNudge(at(0, 19), settings = adaptive.copy(streakReminder = false)))
    }

    @Test
    fun streakNudgeHonoursAWindowThatWrapsMidnight() {
        // 22:00–06:00: the last three hours sit after midnight, not at 22:00.
        val night = adaptive.copy(windowStartMin = 22 * 60, windowEndMin = 6 * 60)
        assertFalse(streakNudge(at(0, 23), settings = night))
        assertFalse(streakNudge(at(0, 2, 59), settings = night))
        assertTrue(streakNudge(at(0, 3), settings = night))
        assertTrue(streakNudge(at(0, 5, 59), settings = night))
        assertFalse(streakNudge(at(0, 6), settings = night))
        // 22:00–01:00 is itself three hours, so the tail crosses midnight.
        val late = adaptive.copy(windowStartMin = 22 * 60, windowEndMin = 60)
        assertTrue(streakNudge(at(0, 22), settings = late))
        assertTrue(streakNudge(at(0, 0, 30), settings = late))
        assertFalse(streakNudge(at(0, 1), settings = late))
        assertFalse(streakNudge(at(0, 15), settings = late))
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
    fun aStreakSessionUsesTheTopAheadCard() {
        val deck = listOf(dueEntry("due"), aheadEntry("first"), aheadEntry("second"))
        assertEquals("first", startStreakNudgeSession(deck, summarize).card?.item?.itemId)
        assertNull(startStreakNudgeSession(emptyList(), summarize).card)
        assertNull(startStreakNudgeSession(listOf(dueEntry("due")), summarize).card)
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

    @Test
    fun streakTitleRotatesByDayAndEmbedsTheLength() {
        assertNotEquals(streakNudgeTitle(10, 6), streakNudgeTitle(11, 6))
        assertEquals(streakNudgeTitle(10, 6), streakNudgeTitle(14, 6))
        assertEquals(
            listOf(
                "Keep your 6-day streak",
                "Day 7 is one card away",
                "6 days running — don't stop now",
                "One card keeps your 6-day streak",
            ),
            (0 until 4).map { streakNudgeTitle(it, 6) },
        )
        assertEquals("Day 2 is one card away", streakNudgeTitle(1, 1))
        assertEquals("1 day running — don't stop now", streakNudgeTitle(2, 1))
        assertTrue((0 until 4).all { title ->
            val text = streakNudgeTitle(title, 6)
            text.contains("6") || text.contains("7")
        })
    }

    @Test
    fun theDoneLineNamesTheStreakOnlyWhenThereIsOne() {
        assertEquals("All caught up · 1 reviewed", nudgeDoneTitle(1, 0))
        assertEquals("All caught up · 3 reviewed", nudgeDoneTitle(3, 0))
        assertEquals("All caught up · 1 reviewed · 1-day streak", nudgeDoneTitle(1, 1))
        assertEquals("All caught up · 3 reviewed · 5-day streak", nudgeDoneTitle(3, 5))
    }

    @Test
    fun theStatusLineNamesTheStreakAndTodaysGoal() {
        assertNull(nudgeStatusLine(0, 0L, 20, streakInTitle = false))
        assertEquals("12/20", nudgeStatusLine(0, 12L, 20, streakInTitle = false))
        assertEquals("1-day streak · 1/20", nudgeStatusLine(1, 1L, 20, streakInTitle = false))
        assertEquals("5-day streak · 12/20", nudgeStatusLine(5, 12L, 20, streakInTitle = false))
        assertEquals("5-day streak · 0/20", nudgeStatusLine(5, 0L, 20, streakInTitle = false))
    }

    @Test
    fun theStatusLineDropsTheStreakWhenTheTitleAlreadySaysIt() {
        // "Keep your 5-day streak" over "5-day streak · 0/20" would say the run twice.
        assertEquals("0/20", nudgeStatusLine(5, 0L, 20, streakInTitle = true))
        assertEquals("12/20", nudgeStatusLine(5, 12L, 20, streakInTitle = true))
        assertEquals("3/20", nudgeStatusLine(0, 3L, 20, streakInTitle = true))
        assertNull(nudgeStatusLine(0, 0L, 20, streakInTitle = true))
    }
}
