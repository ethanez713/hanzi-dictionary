package com.hanzidictionary.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.userdb.UserDatabase
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Eligibility clocks, the archive sample, and what each swipe writes (and undoes). */
class FinalCheckTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val t0 = 20_000L * DAY_MS

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var study: StudyRepository
    private lateinit var user: UserRepository
    private val fsrs = Fsrs(enableFuzz = false)

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(driver)
        study = StudyRepository(driver, fsrs)
        user = UserRepository(driver)
    }

    @AfterTest
    fun teardown() = driver.close()

    // ---- the two clocks ----

    @Test
    fun aGapAlreadyInTheStreakUnlocksOnTheNextLocalDayAndNotBefore() {
        val last = t0 + 10 * hour
        val held = candidateGate(longestGapDays = RETIRE_MIN_GAP_DAYS, lastReview = last, now = t0 + 20 * hour, tz = utc)
        assertFalse(held.eligible)
        assertEquals(t0 + DAY_MS, held.unlockAt, "same local day is not proof, however many hours apart")

        val nextDay = candidateGate(RETIRE_MIN_GAP_DAYS, last, t0 + DAY_MS, utc)
        assertTrue(nextDay.eligible)
        assertNull(nextDay.unlockAt)

        // One elapsed day is enough once the streak already survived the long gap.
        val barely = candidateGate(RETIRE_MIN_GAP_DAYS, t0, t0 + DAY_MS, utc)
        assertTrue(barely.eligible)
    }

    @Test
    fun theSameInstantsSplitOnTheLocalDayNotUtc() {
        // 21:00 and 23:00 UTC are one UTC day. In GMT+2 they are 23:00 and 01:00, two local days.
        val last = t0 + 21 * hour
        val now = t0 + 23 * hour
        assertFalse(candidateGate(RETIRE_MIN_GAP_DAYS, last, now, utc).eligible)
        assertTrue(candidateGate(RETIRE_MIN_GAP_DAYS, last, now, TimeZone.getTimeZone("GMT+2")).eligible)
    }

    @Test
    fun withoutALongGapTheCheckItselfHasToSpanSixtyDays() {
        val last = t0
        val early = candidateGate(10.0, last, last + gapMs(RETIRE_MIN_GAP_DAYS) - 1, utc)
        assertFalse(early.eligible)
        assertEquals(last + gapMs(RETIRE_MIN_GAP_DAYS), early.unlockAt)

        val due = candidateGate(10.0, last, last + gapMs(RETIRE_MIN_GAP_DAYS), utc)
        assertTrue(due.eligible)

        // Just under the gap does not count, even one millisecond under.
        assertFalse(candidateGate(RETIRE_MIN_GAP_DAYS - 0.1, last, last + gapMs(RETIRE_MIN_GAP_DAYS) - 1, utc).eligible)
    }

    @Test
    fun candidacyIsTheStreakAndTheStability() {
        assertTrue(isFinalCheckCandidate(mastery(streak = MASTERY_STREAK, stability = RETIRE_MIN_STABILITY_DAYS)))
        assertFalse(isFinalCheckCandidate(mastery(streak = MASTERY_STREAK - 1, stability = 1_000.0)))
        assertFalse(isFinalCheckCandidate(mastery(streak = MASTERY_STREAK, stability = RETIRE_MIN_STABILITY_DAYS - 1)))
        assertFalse(isFinalCheckCandidate(mastery(streak = MASTERY_STREAK, stability = null)))
    }

    @Test
    fun daysUntilUnlockRoundUpSoTodayStillCountsAsADay() {
        assertEquals(1, daysUntilUnlock(t0 + DAY_MS, t0))
        assertEquals(1, daysUntilUnlock(t0 + 1, t0))
        assertEquals(2, daysUntilUnlock(t0 + 2 * DAY_MS, t0))
        assertEquals(3, daysUntilUnlock(t0 + 2 * DAY_MS + 1, t0))
        assertEquals(0, daysUntilUnlock(t0, t0))
    }

    // ---- the archive sample ----

    @Test
    fun auditWeightIsOneMinusRecallAndAMissIsThePrior() {
        assertEquals(0.75, auditWeight(AUDIT_NO_HISTORY_RETRIEVABILITY))
        assertEquals(1.0, auditWeight(0.0))
        assertEquals(0.0, auditWeight(1.0))
        assertEquals(0.0, auditWeight(1.5), "a recall above 1 must not produce a negative weight")
    }

    @Test
    fun archiveItemsAreEligibleAfterThirtyDaysOrIfNeverTested() {
        assertTrue(auditEligible(null, t0))
        assertTrue(auditEligible(t0, t0 + gapMs(AUDIT_MIN_GAP_DAYS)))
        assertFalse(auditEligible(t0, t0 + gapMs(AUDIT_MIN_GAP_DAYS) - 1))
    }

    @Test
    fun weightedSampleFollowsTheTicketAndDoesNotReplace() {
        val pool = listOf("A" to 1.0, "B" to 0.5)
        assertEquals("A", draw(pool, 0.0))
        assertEquals("B", draw(pool, 0.9), "0.9 × 1.5 lands past A's weight of 1")

        val three = listOf("A" to 1.0, "B" to 1.0, "C" to 1.0)
        val two = sampleWeighted(three, limit = 2, ScriptedRandom(doubleArrayOf(0.0, 0.0))) { it.second }
        assertEquals(listOf("A", "B"), two.map { it.first })

        val all = sampleWeighted(three, limit = 9, ScriptedRandom(doubleArrayOf(0.0, 0.0, 0.0))) { it.second }
        assertEquals(listOf("A", "B", "C"), all.map { it.first })
    }

    @Test
    fun aSureRecallTakesNoSampleSlotAndAnEmptyPoolDrawsNothing() {
        val kept = buildFinalCheckSession(
            eligible = emptyList(),
            auditable = listOf(face("sure", 1.0, FinalCheckKind.AUDIT), face("fading", 0.0, FinalCheckKind.AUDIT)),
            random = ScriptedRandom(doubleArrayOf(0.0)),
        )
        assertEquals(listOf("fading"), kept.map { it.item.itemId })

        val none = buildFinalCheckSession(
            eligible = emptyList(),
            auditable = listOf(face("sure", 1.0, FinalCheckKind.AUDIT)),
            random = object : Random() {
                override fun nextBits(bitCount: Int) = error("nothing to draw")
                override fun nextDouble() = error("nothing to draw")
            },
        )
        assertTrue(none.isEmpty())
    }

    @Test
    fun theSessionTestsTheLeastRecalledCandidatesThenACappedSample() {
        val candidates = (0..15).map { face("C%02d".format(it), it.toDouble()) }
        val archived = (0..5).map { face("A$it", 0.5, FinalCheckKind.AUDIT) }
        val session = buildFinalCheckSession(
            candidates,
            archived,
            ScriptedRandom(DoubleArray(5) { 0.0 }),
        )
        assertEquals((0..14).map { "C%02d".format(it) }, session.take(15).map { it.item.itemId })
        assertTrue(session.take(15).all { it.kind == FinalCheckKind.CANDIDATE })
        assertEquals((0..4).map { "A$it" }, session.drop(15).map { it.item.itemId })
        assertTrue(session.drop(15).all { it.kind == FinalCheckKind.AUDIT })
    }

    @Test
    fun equalRecallBreaksTiesByItemId() {
        val session = buildFinalCheckSession(
            eligible = listOf(face("U+0002", 0.4), face("U+0001", 0.4)),
            auditable = emptyList(),
            random = ScriptedRandom(doubleArrayOf()),
        )
        assertEquals(listOf("U+0001", "U+0002"), session.map { it.item.itemId })
    }

    // ---- how the row and the end screen read ----

    @Test
    fun theStudyLineAppearsOnlyWhenSomeoneIsEligible() {
        assertNull(finalCheckStudyLine(0))
        assertEquals("1 ready for a final check ›", finalCheckStudyLine(1))
        assertEquals("4 ready for a final check ›", finalCheckStudyLine(4))
    }

    @Test
    fun theArchiveRowSaysReadyOrTheWaitOrWhatCanBeSpotChecked() {
        assertEquals("Nothing to check yet", finalCheckStatusLabel(FinalCheckStatus(0, null, 0)))
        assertEquals("1 ready", finalCheckStatusLabel(FinalCheckStatus(1, 12, 0)))
        assertEquals("3 ready · 2 archived to spot-check", finalCheckStatusLabel(FinalCheckStatus(3, 4, 2)))
        assertEquals("Next in 1 day", finalCheckStatusLabel(FinalCheckStatus(0, 1, 0)))
        assertEquals("Next in 4 days · 12 archived to spot-check", finalCheckStatusLabel(FinalCheckStatus(0, 4, 12)))
        assertEquals("1 archived to spot-check", finalCheckStatusLabel(FinalCheckStatus(0, null, 1)))
    }

    @Test
    fun theReportNamesEachOutcome() {
        assertEquals(
            "Archived 2. Sent 1 back to study. 3 spot-checks passed.",
            finalCheckReport(archived = 2, backToStudy = 1, spotChecksPassed = 3),
        )
        assertEquals(
            "Archived 1. Sent 0 back to study. 1 spot-check passed.",
            finalCheckReport(1, 0, 1),
        )
    }

    // ---- wired through the user database ----

    @Test
    fun theProofClockAndTheHeldGapAreReadFromRealHistory() {
        val list = study.createList("A", t0)
        val proof = StudyItem.character("U+1000")
        val held = StudyItem.character("U+1001")
        plantReady(proof, list, lastReview = t0, longGap = false)
        plantReady(held, list, lastReview = t0 + 10 * hour, longGap = true)

        val tooSoon = t0 + gapMs(RETIRE_MIN_GAP_DAYS) - 1
        // The held gap unlocked on the next local day, so it is already in the sitting.
        // The other card is one millisecond short of the 60 a grade would record.
        assertEquals(listOf(held), idsAt(tooSoon))
        val waiting = study.finalCheckStatus(tooSoon, utc)
        assertEquals(1, waiting.eligible)
        assertEquals(1, waiting.daysToFirstUnlock)

        val due = t0 + gapMs(RETIRE_MIN_GAP_DAYS)
        assertEquals(listOf(proof, held), session(due).map { it.item })

        // The held gap is already in the streak, so the next local day is enough — and today is not.
        assertTrue(session(t0 + 20 * hour).isEmpty())
        assertEquals(listOf(held), session(t0 + DAY_MS).map { it.item })
    }

    @Test
    fun localMidnightIsWhatMakesAHeldGapDue() {
        val list = study.createList("A", t0)
        val item = StudyItem.character("U+2000")
        val last = t0 + 80 * DAY_MS + 21 * hour
        val now = t0 + 80 * DAY_MS + 23 * hour
        plantReady(item, list, lastReview = last, longGap = true)

        assertTrue(session(now).isEmpty())
        assertEquals(1, study.finalCheckStatus(now, utc).daysToFirstUnlock)
        val gmt2 = TimeZone.getTimeZone("GMT+2")
        assertEquals(listOf(item), study.finalCheckSession(now, gmt2).map { it.item })
    }

    @Test
    fun aShortStreakOrAThinStabilityIsNotWaiting() {
        val list = study.createList("A", t0)
        val thin = StudyItem.character("U+3000")
        val short = StudyItem.character("U+3001")
        plantReady(thin, list, lastReview = t0, stability = RETIRE_MIN_STABILITY_DAYS - 1)
        plantReviews(short, list, listOf(t0 - 40 * DAY_MS, t0), stability = 500.0)

        val later = t0 + 1_000 * DAY_MS
        assertTrue(session(later).isEmpty())
        val status = study.finalCheckStatus(later, utc)
        assertEquals(0, status.eligible)
        assertNull(status.daysToFirstUnlock)
    }

    @Test
    fun anItemOutsideEveryListOrAlreadyArchivedIsNotACandidate() {
        val list = study.createList("A", t0)
        val loose = StudyItem.character("U+4000")
        val archived = StudyItem.character("U+4001")
        plantReviews(loose, null, listOf(t0 - 40 * DAY_MS, t0 - 20 * DAY_MS, t0), stability = 200.0)
        plantReady(archived, list, lastReview = t0, longGap = true)
        markKnown(archived)

        val now = t0 + gapMs(RETIRE_MIN_GAP_DAYS)
        val session = session(now)
        assertTrue(session.none { it.item == loose })
        assertEquals(FinalCheckKind.AUDIT, session.single { it.item == archived }.kind)
    }

    @Test
    fun candidatesComeLeastRecalledFirstAndTheSampleFollowsThem() {
        val list = study.createList("A", t0)
        val items = (0..15).map { StudyItem.character("U+%04X".format(0x5000 + it)) }
        items.forEachIndexed { i, item ->
            plantReady(item, list, lastReview = t0, stability = RETIRE_MIN_STABILITY_DAYS + i)
        }
        val archived = StudyItem.character("U+5FFF")
        plantArchived(archived, reviewedAt = null)

        val now = t0 + gapMs(RETIRE_MIN_GAP_DAYS)
        val session = session(now)
        val candidates = session.filter { it.kind == FinalCheckKind.CANDIDATE }
        assertEquals(FINAL_CHECK_LIMIT, candidates.size)
        assertEquals(items.take(FINAL_CHECK_LIMIT), candidates.map { it.item })
        assertEquals(listOf(archived), session.filter { it.kind == FinalCheckKind.AUDIT }.map { it.item })
        assertTrue(session.indexOfFirst { it.kind == FinalCheckKind.AUDIT } > candidates.lastIndex)
    }

    @Test
    fun theArchiveSampleSkipsAnythingTestedInsideThirtyDays() {
        val now = t0 + 100 * DAY_MS
        val due = StudyItem.character("U+6000")
        val recent = StudyItem.character("U+6001")
        val never = StudyItem.character("U+6002")
        plantArchived(due, reviewedAt = now - gapMs(AUDIT_MIN_GAP_DAYS))
        plantArchived(recent, reviewedAt = now - gapMs(AUDIT_MIN_GAP_DAYS) + 1)
        plantArchived(never, reviewedAt = null)

        val ids = study.finalCheckSession(now, utc).map { it.item }.toSet()
        assertEquals(setOf(due, never), ids)
        assertEquals(2, study.finalCheckStatus(now, utc).auditable)
    }

    @Test
    fun sixArchivedItemsSampleDownToFive() {
        val now = t0
        val items = (0..5).map { StudyItem.character("U+61%02d".format(it)) }
        items.forEach { plantArchived(it, reviewedAt = null) }
        assertEquals(AUDIT_SAMPLE_LIMIT, study.finalCheckSession(now, utc).size)
    }

    @Test
    fun passingACandidateArchivesItAndUndoPutsTheScheduleBack() {
        val list = study.createList("A", t0)
        val item = StudyItem.character("U+7000")
        plantReady(item, list, lastReview = t0, stability = 200.0)
        val now = t0 + gapMs(RETIRE_MIN_GAP_DAYS)
        val before = study.cardState(item.itemId)!!

        val undo = study.answerFinalCheck(item, FinalCheckKind.CANDIDATE, FinalCheckSwipe.PASS, now)
        assertTrue(undo != null)
        assertTrue(user.isKnown(item.itemId))
        assertNull(study.cardState(item.itemId))
        assertEquals(listOf(item), study.itemsInList(list))

        val grade = logs(item.itemId).last()
        assertEquals(StudyRepository.ORIGIN_FINAL, grade.origin)
        assertEquals(Rating.GOOD.value.toLong(), grade.rating)
        assertEquals(RETIRE_MIN_GAP_DAYS, grade.elapsed)
        val expected = fsrs.review(before, Rating.GOOD, now)
        assertEquals(expected.stability, grade.stability)
        assertEquals(expected.difficulty, grade.difficulty)
        assertTrue(grade.stability!! > before.stability!!, "the log stores the post-review stability")

        study.undoFinalCheck(undo)
        assertFalse(user.isKnown(item.itemId))
        val restored = study.cardState(item.itemId)!!
        assertEquals(before, restored)
        assertEquals(3, logs(item.itemId).size)
    }

    @Test
    fun missingACandidateLeavesItInStudy() {
        val list = study.createList("A", t0)
        val item = StudyItem.character("U+7001")
        plantReady(item, list, lastReview = t0, stability = 200.0)
        val now = t0 + gapMs(RETIRE_MIN_GAP_DAYS)

        assertNull(study.answerFinalCheck(item, FinalCheckKind.CANDIDATE, FinalCheckSwipe.MISS, now))
        assertFalse(user.isKnown(item.itemId))
        val card = study.cardState(item.itemId)!!
        assertEquals(CardPhase.RELEARNING, card.phase)
        assertEquals(1, card.lapses)
        val grade = logs(item.itemId).last()
        assertEquals(StudyRepository.ORIGIN_FINAL, grade.origin)
        assertEquals(Rating.AGAIN.value.toLong(), grade.rating)
        assertEquals(listOf(item), study.itemsInList(list))
    }

    @Test
    fun skippingWritesNothing() {
        val list = study.createList("A", t0)
        val item = StudyItem.character("U+7002")
        plantReady(item, list, lastReview = t0)
        val before = study.cardState(item.itemId)
        val rows = logs(item.itemId).size

        assertNull(study.answerFinalCheck(item, FinalCheckKind.CANDIDATE, FinalCheckSwipe.SKIP, t0 + DAY_MS))
        assertEquals(before, study.cardState(item.itemId))
        assertEquals(rows, logs(item.itemId).size)
        assertFalse(user.isKnown(item.itemId))
    }

    @Test
    fun passingAnArchivedItemLogsTheRebuiltMemoryAndStaysArchived() {
        val item = StudyItem.character("U+8000")
        val now = t0 + 40 * DAY_MS
        val reviewedAt = t0
        plantArchived(item, reviewedAt = reviewedAt, stability = 50.0)

        assertNull(study.answerFinalCheck(item, FinalCheckKind.AUDIT, FinalCheckSwipe.PASS, now))
        assertTrue(user.isKnown(item.itemId))
        assertNull(study.cardState(item.itemId))

        val grade = logs(item.itemId).last()
        assertEquals(StudyRepository.ORIGIN_AUDIT, grade.origin)
        assertEquals(Rating.GOOD.value.toLong(), grade.rating)
        assertEquals(40.0, grade.elapsed)
        val base = rebuiltCard(item, RebuiltMemory(50.0, 5.0, reviewedAt, reviews = 1, lapses = 0))
        val expected = fsrs.review(base, Rating.GOOD, now)
        assertEquals(expected.stability, grade.stability)
        assertEquals(expected.difficulty, grade.difficulty)
        assertTrue(grade.stability!! > 50.0)
    }

    @Test
    fun anArchivedItemWithNoHistoryCanStillBePassed() {
        val item = StudyItem.character("U+8001")
        plantArchived(item, reviewedAt = null)

        assertNull(study.answerFinalCheck(item, FinalCheckKind.AUDIT, FinalCheckSwipe.PASS, t0))
        assertTrue(user.isKnown(item.itemId))
        assertNull(study.cardState(item.itemId))
        val grade = logs(item.itemId).single()
        assertEquals(StudyRepository.ORIGIN_AUDIT, grade.origin)
        assertEquals(Rating.GOOD.value.toLong(), grade.rating)
        assertNull(grade.elapsed)
        assertTrue(grade.stability != null)
    }

    @Test
    fun missingAnArchivedItemSendsItBackAndUndoRestoresTheArchive() {
        val item = StudyItem.character("U+8002")
        val now = t0 + 40 * DAY_MS
        plantArchived(item, reviewedAt = t0, stability = 50.0)
        val addedAt = knownAddedAt(item.itemId)

        val undo = study.answerFinalCheck(item, FinalCheckKind.AUDIT, FinalCheckSwipe.MISS, now)
        assertTrue(undo != null)
        assertFalse(user.isKnown(item.itemId))
        val card = study.cardState(item.itemId)!!
        assertEquals(CardPhase.RELEARNING, card.phase)
        val base = rebuiltCard(item, RebuiltMemory(50.0, 5.0, t0, reviews = 1, lapses = 0))
        val expected = fsrs.review(base, Rating.AGAIN, now)
        assertEquals(expected.stability, card.stability)
        assertEquals(expected.due, card.due)
        assertEquals(1, card.lapses)
        val list = study.lists().single()
        assertEquals(StudyRepository.DEFAULT_LIST_NAME, list.name)
        assertEquals(listOf(item), study.itemsInList(list.id))
        assertEquals(StudyRepository.ORIGIN_AUDIT, logs(item.itemId).last().origin)
        assertEquals(Rating.AGAIN.value.toLong(), logs(item.itemId).last().rating)

        study.undoFinalCheck(undo)
        assertTrue(user.isKnown(item.itemId))
        assertEquals(addedAt, knownAddedAt(item.itemId))
        assertNull(study.cardState(item.itemId))
        assertTrue(study.listIdsContaining(item.itemId).isEmpty())
        assertEquals(listOf("app"), logs(item.itemId).map { it.origin })
    }

    @Test
    fun aMissThatWasAlreadyListedDoesNotFileItAgain() {
        val list = study.createList("Signs", t0)
        val item = StudyItem.character("U+8003")
        study.addToList(list, item, t0)
        plantArchived(item, reviewedAt = t0, stability = 50.0)

        val undo = study.answerFinalCheck(item, FinalCheckKind.AUDIT, FinalCheckSwipe.MISS, t0 + 40 * DAY_MS)!!
        assertEquals(setOf(list), study.listIdsContaining(item.itemId))
        assertEquals(1, study.lists().size)

        study.undoFinalCheck(undo)
        assertEquals(setOf(list), study.listIdsContaining(item.itemId))
        assertTrue(user.isKnown(item.itemId))
        assertNull(study.cardState(item.itemId))
    }

    @Test
    fun aMissWithNoHistoryStartsANewCard() {
        val item = StudyItem.character("U+8004")
        plantArchived(item, reviewedAt = null)
        val undo = study.answerFinalCheck(item, FinalCheckKind.AUDIT, FinalCheckSwipe.MISS, t0)!!

        assertFalse(user.isKnown(item.itemId))
        val card = study.cardState(item.itemId)!!
        assertEquals(CardPhase.LEARNING, card.phase)
        assertTrue(card.stability != null)
        assertEquals(listOf(item), study.itemsInList(study.lists().single().id))

        study.undoFinalCheck(undo)
        assertTrue(user.isKnown(item.itemId))
        assertNull(study.cardState(item.itemId))
        assertTrue(study.listIdsContaining(item.itemId).isEmpty())
    }

    @Test
    fun finalAndAuditGradesCountTowardRecentAccuracy() {
        val item = StudyItem.character("U+9000")
        repeat(3) { study.grade(item, Rating.AGAIN, StudyRepository.ORIGIN_FINAL, t0 + it) }
        repeat(2) { study.grade(item, Rating.GOOD, StudyRepository.ORIGIN_AUDIT, t0 + 10 + it) }
        study.grade(item, Rating.AGAIN, StudyRepository.ORIGIN_TRIAGE, t0 + 20)

        assertEquals(2.0 / 5.0, study.deckLoad(t0 + 30).recentSuccess)
    }

    // ---- fixtures ----

    private fun session(now: Long) = study.finalCheckSession(now, utc, Random(0))

    private fun idsAt(now: Long) = session(now).map { it.item }

    private fun mastery(streak: Int, stability: Double?) = Mastery(
        level = MasteryLevel.LEARNING,
        spacedStreak = streak,
        lastGapDays = 1.0,
        longestGapDays = 1.0,
        stabilityDays = stability,
        lapses = 0,
        struggling = false,
        leech = false,
    )

    private fun face(id: String, recall: Double, kind: FinalCheckKind = FinalCheckKind.CANDIDATE) =
        FinalCheckCard(kind, StudyItem.character(id), Mastery.NONE, recall)

    private fun draw(pool: List<Pair<String, Double>>, ticket: Double): String =
        sampleWeighted(pool, limit = 1, ScriptedRandom(doubleArrayOf(ticket))) { it.second }.single().first

    /** Three spaced successes ending at [lastReview]. [longGap] stores a 70-day elapsed on
     * the last of them; otherwise the longest stored gap is 20. */
    private fun plantReady(
        item: StudyItem,
        listId: Long,
        lastReview: Long,
        stability: Double = RETIRE_MIN_STABILITY_DAYS,
        longGap: Boolean = false,
    ) {
        val reviews = listOf(lastReview - 40 * DAY_MS, lastReview - 20 * DAY_MS, lastReview)
        plantReviews(item, listId, reviews, stability, longGapElapsed = if (longGap) 70.0 else 10.0)
    }

    private fun plantReviews(
        item: StudyItem,
        listId: Long?,
        at: List<Long>,
        stability: Double,
        longGapElapsed: Double = 10.0,
    ) {
        if (listId != null) study.addToList(listId, item, at.first())
        at.forEachIndexed { i, ts ->
            val elapsed = when (i) {
                0 -> null
                at.lastIndex -> longGapElapsed
                else -> 20.0
            }
            val loggedStability = if (i == at.lastIndex) stability else 20.0
            insertReview(item, ts, elapsed, loggedStability)
        }
        insertCard(item, stability, at.last(), reviews = at.size, lapses = 0)
    }

    private fun plantArchived(item: StudyItem, reviewedAt: Long?, stability: Double = 50.0) {
        if (reviewedAt != null) insertReview(item, reviewedAt, elapsed = 10.0, stability = stability)
        user.setKnown(item.itemId, true, item.kind)
    }

    private fun markKnown(item: StudyItem) {
        driver.execute(
            null,
            "INSERT INTO known_item(item_id, kind, status, added_at) VALUES ('${item.itemId}', '${item.kind}', 'known', 7)",
            0,
        )
    }

    private fun insertCard(item: StudyItem, stability: Double, lastReview: Long, reviews: Int, lapses: Int) {
        driver.execute(
            null,
            """INSERT INTO card_state(item_id, kind, state, step, stability, difficulty, due, last_review, reviews, lapses)
               VALUES ('${item.itemId}', '${item.kind}', 'review', NULL, $stability, 5.0, $lastReview, $lastReview, $reviews, $lapses)""",
            0,
        )
    }

    private fun insertReview(item: StudyItem, ts: Long, elapsed: Double?, stability: Double) {
        val elapsedSql = elapsed?.toString() ?: "NULL"
        driver.execute(
            null,
            """INSERT INTO review_log(item_id, rating, ts, elapsed_days, stability, difficulty, origin, reveal_ms)
               VALUES ('${item.itemId}', 3, $ts, $elapsedSql, $stability, 5.0, 'app', NULL)""",
            0,
        )
    }

    private fun logs(itemId: String): List<LogRow> = driver.executeQuery(
        null,
        """SELECT rating, ts, elapsed_days, stability, difficulty, origin
           FROM review_log WHERE item_id = '$itemId' ORDER BY ts, rowid""",
        { cursor ->
            val out = mutableListOf<LogRow>()
            while (cursor.next().value) {
                out += LogRow(
                    rating = cursor.getLong(0)!!,
                    ts = cursor.getLong(1)!!,
                    elapsed = cursor.getDouble(2),
                    stability = cursor.getDouble(3),
                    difficulty = cursor.getDouble(4),
                    origin = cursor.getString(5)!!,
                )
            }
            QueryResult.Value(out)
        },
        0,
    ).value

    private fun knownAddedAt(itemId: String): Long =
        driver.longs("SELECT added_at FROM known_item WHERE item_id = '$itemId'").single()!!

    private data class LogRow(
        val rating: Long,
        val ts: Long,
        val elapsed: Double?,
        val stability: Double?,
        val difficulty: Double?,
        val origin: String,
    )
}

private class ScriptedRandom(private val draws: DoubleArray) : Random() {
    private var index = 0
    override fun nextBits(bitCount: Int): Int = error("not used")
    override fun nextDouble(): Double = draws[index++]
}

private fun gapMs(days: Double): Long = (days * DAY_MS.toDouble()).toLong()

private fun SqlDriver.longs(sql: String): List<Long?> = executeQuery(
    null,
    sql,
    { cursor ->
        val out = mutableListOf<Long?>()
        while (cursor.next().value) out += cursor.getLong(0)
        QueryResult.Value(out)
    },
    0,
).value
