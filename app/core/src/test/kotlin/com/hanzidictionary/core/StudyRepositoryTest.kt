package com.hanzidictionary.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.userdb.UserDatabase
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lists + scheduling over an in-memory user DB (no content pack needed — this layer
 * never touches it). */
class StudyRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var study: StudyRepository
    private lateinit var user: UserRepository

    private val now = 1_700_000_000_000L
    private val hao = StudyItem.character("U+597D")
    private val ni = StudyItem.character("U+4F60")
    private val nihao = StudyItem.word("w000123")

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(driver)
        study = StudyRepository(driver, Fsrs(enableFuzz = false))
        user = UserRepository(driver)
    }

    @AfterTest
    fun teardown() = driver.close()

    @Test
    fun listsHoldCharactersAndWordsIndependently() {
        val signs = study.createList("Signs I saw", now)
        val hsk = study.createList("HSK 4", now + 1)

        study.addToList(signs, hao, now)
        study.addToList(signs, nihao, now)
        study.addToList(hsk, hao, now)

        assertEquals(listOf(hao, nihao), study.itemsInList(signs))
        assertEquals(setOf(signs, hsk), study.listIdsContaining(hao.itemId))
        assertEquals(setOf(signs), study.listIdsContaining(nihao.itemId))

        study.removeFromList(signs, hao.itemId)
        assertEquals(setOf(hsk), study.listIdsContaining(hao.itemId))
        // Removing from one list must not disturb the other lists' membership.
        assertEquals(listOf(hao), study.itemsInList(hsk))
    }

    @Test
    fun addingTheSameItemTwiceIsIdempotent() {
        val list = study.createList("Saved", now)
        study.addToList(list, hao, now)
        study.addToList(list, hao, now + 5_000)
        assertEquals(1, study.itemsInList(list).size)
    }

    @Test
    fun archivingInOneListRemovesTheItemFromEveryDeck() {
        val a = study.createList("A", now)
        val b = study.createList("B", now)
        study.addToList(a, hao, now)
        study.addToList(b, hao, now)
        study.addToList(b, ni, now)

        user.setKnown(hao.itemId, true)

        assertTrue(study.deckFor(DeckSource.UserList(a), now = now).isEmpty())
        assertEquals(listOf(ni), study.deckFor(DeckSource.UserList(b), now = now).map { it.item })
        // The item stays *in* both lists — the list is a record of what you studied.
        assertEquals(listOf(hao), study.itemsInList(a))
        assertEquals(1, study.statsByList(now)[a]!!.archived)
        assertEquals(0, study.statsByList(now)[a]!!.learning)
    }

    @Test
    fun archivingDiscardsTheSchedule() {
        val list = study.createList("A", now)
        study.addToList(list, hao, now)
        study.grade(hao, Rating.GOOD, now = now)
        assertNotNull(study.cardState(hao.itemId))

        user.setKnown(hao.itemId, true)
        assertNull(study.cardState(hao.itemId))
    }

    @Test
    fun deckServesDueCardsLeastRecalledFirst() {
        val list = study.createList("A", now)
        val items = (0..2).map { StudyItem.character("U+000$it") }
        items.forEach { study.addToList(list, it, now) }

        // Grade all three now, then look at the deck far enough in the future that every
        // card is overdue. The one repeatedly forgotten has the lowest stability, so it
        // must come back first.
        study.grade(items[0], Rating.EASY, now = now)
        study.grade(items[1], Rating.GOOD, now = now)
        study.grade(items[2], Rating.AGAIN, now = now)

        val later = now + 400L * DAY_MS
        val deck = study.deckFor(DeckSource.UserList(list), now = later)
        assertEquals(items, deck.map { it.item }.reversed())
        assertTrue(deck.all { it.bucket == DeckBucket.DUE })
    }

    @Test
    fun newCardsAreCappedPerDayButDueCardsNeverAre() {
        val list = study.createList("Big import", now)
        val items = (1..40).map { StudyItem.character("U+1%03d".format(it)) }
        items.forEach { study.addToList(list, it, now) }

        val firstSession = study.deckFor(DeckSource.UserList(list), now = now)
        assertEquals(StudyRepository.NEW_PER_DAY.toInt(), firstSession.size)
        assertTrue(firstSession.all { it.bucket == DeckBucket.NEW })

        // Introduce the whole allowance, then confirm the budget is spent for the day.
        firstSession.forEach { study.grade(it.item, Rating.GOOD, now = now) }
        assertEquals(0, study.newAllowance(now))
        val secondSession = study.deckFor(DeckSource.UserList(list), now = now + 60_000)
        assertTrue(
            secondSession.none { it.bucket == DeckBucket.NEW },
            "new-card budget leaked: ${secondSession.count { it.bucket == DeckBucket.NEW }} extra",
        )
        // ...but the cards introduced a minute ago are in their 10-minute learning step,
        // so they come back as DUE later in the session. That is the point of the steps.
        val afterSteps = study.deckFor(DeckSource.UserList(list), now = now + 20 * 60_000)
        assertEquals(StudyRepository.NEW_PER_DAY.toInt(), afterSteps.count { it.bucket == DeckBucket.DUE })
    }

    @Test
    fun deckTopsUpWithAheadCardsWhenNothingIsDue() {
        val list = study.createList("A", now)
        val items = listOf(hao, ni)
        items.forEach { study.addToList(list, it, now) }
        // Graduate both out of learning so they sit days in the future.
        repeat(2) { items.forEach { study.grade(it, Rating.EASY, now = now) } }

        val deck = study.deckFor(DeckSource.UserList(list), now = now + 60_000)
        assertEquals(2, deck.size, "practice-ahead should still offer the whole list")
        assertTrue(deck.all { it.bucket == DeckBucket.AHEAD })
        // Ahead cards are ordered by how close to forgetting they are, same as due ones.
        val r = deck.map { study.retrievability(it.card!!, now + 60_000) }
        assertEquals(r.sorted(), r)
    }

    @Test
    fun neverSeenCardsLeadDueReviewsSoTheyAreNeverStarved() {
        val list = study.createList("mix", now)
        val seen = StudyItem.character("U+8000")
        val fresh = StudyItem.character("U+8001")
        study.addToList(list, seen, now)
        study.addToList(list, fresh, now)
        // `seen` gets a memory state and comes back due within minutes; `fresh` is never
        // graded, so it has no signal at all — the card we want surfaced first to get some.
        study.grade(seen, Rating.AGAIN, now = now)

        val deck = study.deckFor(DeckSource.UserList(list), now = now + 5 * 60_000)
        assertEquals(fresh, deck.first().item, "a never-seen card must lead a due review")
        assertEquals(DeckBucket.NEW, deck.first().bucket)
        assertTrue(
            deck.any { it.item == seen && it.bucket == DeckBucket.DUE },
            "the due review is still in the deck, just behind the new card",
        )
    }

    @Test
    fun everythingPoolsAcrossListsWithoutDuplicates() {
        val a = study.createList("A", now)
        val b = study.createList("B", now)
        study.addToList(a, hao, now)
        study.addToList(b, hao, now)
        study.addToList(b, ni, now)

        val deck = study.deckFor(DeckSource.Everything, now = now)
        // 好 is in both lists but must appear once. (Order between two items added in the
        // same millisecond isn't meaningful, so compare as a set.)
        assertEquals(2, deck.size)
        assertEquals(setOf(hao, ni), deck.map { it.item }.toSet())
    }

    @Test
    fun gradingWritesCountersAndAnAuditableLog() {
        val list = study.createList("A", now)
        study.addToList(list, hao, now)

        study.grade(hao, Rating.GOOD, now = now)
        study.grade(hao, Rating.AGAIN, StudyRepository.ORIGIN_WIDGET, now + 11 * 60_000)
        val card = study.cardState(hao.itemId)!!

        assertEquals(2, card.reviews)
        assertEquals(1, card.lapses)
        assertEquals(1, card.recalled)
        assertEquals(2L, study.reviewsToday(now))
    }

    @Test
    fun lastReviewAtIsTheNewestGradingFromAnyOrigin() {
        assertNull(study.lastReviewAt(), "nothing graded yet")
        study.grade(hao, Rating.GOOD, now = now)
        study.grade(ni, Rating.GOOD, StudyRepository.ORIGIN_NOTIFICATION, now + 5_000)
        assertEquals(now + 5_000, study.lastReviewAt())
    }

    @Test
    fun revealTimeIsRecordedOnAGradingAndMayBeAbsent() {
        study.grade(hao, Rating.GOOD, now = now, revealMs = 1_234L)
        study.grade(hao, Rating.GOOD, StudyRepository.ORIGIN_WIDGET, now + 5_000)
        assertEquals(listOf<Long?>(1_234L, null), driver.longs("SELECT reveal_ms FROM review_log ORDER BY ts"))
    }

    @Test
    fun masteryIsReadFromTheGradingHistoryAndSkipsUnscheduledItems() {
        val day = 86_400_000L
        study.grade(hao, Rating.GOOD, now = now)
        study.grade(hao, Rating.GOOD, now = now + 7 * day)
        study.grade(hao, Rating.GOOD, now = now + 30 * day)
        // A self-reported "I know it" seeds a schedule but is no retrieval.
        study.grade(ni, Rating.EASY, StudyRepository.ORIGIN_TRIAGE, now)

        val mastery = study.masteryFor(listOf(hao.itemId, ni.itemId, nihao.itemId))

        assertEquals(setOf(hao.itemId, ni.itemId), mastery.keys, "an item with no schedule has no mastery")
        assertEquals(3, mastery.getValue(hao.itemId).spacedStreak)
        assertEquals(MasteryLevel.LEARNED, mastery.getValue(hao.itemId).level, "30 days away before the last success")
        assertEquals(0, mastery.getValue(ni.itemId).spacedStreak)
    }

    @Test
    fun migrationFromV3KeepsReviewHistoryAndAddsRevealTime() {
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // v3's review_log verbatim (no reveal_ms, no index) with one past grading in it.
        old.execute(
            null,
            """CREATE TABLE review_log (item_id TEXT NOT NULL, rating INTEGER NOT NULL, ts INTEGER NOT NULL,
               elapsed_days REAL, stability REAL, difficulty REAL, origin TEXT NOT NULL)""",
            0,
        )
        old.execute(null, "INSERT INTO review_log VALUES ('U+597D', 3, 10, 2.0, 5.0, 4.0, 'app')", 0)

        UserDatabase.Schema.migrate(old, 3, UserDatabase.Schema.version)

        assertEquals(listOf<Long?>(null), old.longs("SELECT reveal_ms FROM review_log"), "old rows get NULL")
        old.execute(null, "INSERT INTO review_log VALUES ('U+597D', 3, 20, 2.0, 5.0, 4.0, 'app', 900)", 0)
        assertEquals(listOf<Long?>(null, 900L), old.longs("SELECT reveal_ms FROM review_log ORDER BY ts"))
        val history = StudyRepository(old).reviewHistory(listOf("U+597D")).getValue("U+597D")
        assertEquals(listOf(10L, 20L), history.map { it.ts }, "history survives and reads back in time order")
        old.close()
    }

    // ---- new-word triage ----

    private val day = 86_400_000L

    @Test
    fun knowFilesTheWordAndSchedulesARealCheckAboutAWeekOut() {
        study.triage(nihao, TriageVerdict.KNOW, now)

        val list = study.ensureActiveList(now)
        assertEquals(listOf(nihao), study.itemsInList(list.id))
        val card = study.cardState(nihao.itemId)!!
        assertEquals(CardPhase.REVIEW, card.phase, "an Easy first rating skips the learning steps")
        val days = (card.due - now) / day
        assertTrue(days in 6..10, "expected a check in about a week, got $days days")
    }

    @Test
    fun knowIsLoggedAsATriageClaimNotAReview() {
        study.triage(nihao, TriageVerdict.KNOW, now)
        assertEquals(1L, study.reviewsToday(now))
        assertEquals(null, study.deckLoad(now).recentSuccess, "a claim must not feed the accuracy gate")
        assertEquals(0, study.masteryFor(listOf(nihao.itemId)).getValue(nihao.itemId).spacedStreak)
    }

    @Test
    fun activityCountsDistinctStudiedItemsPerLocalDayAndSkipsTriage() {
        val tz = TimeZone.getTimeZone("UTC")
        study.grade(hao, Rating.GOOD, now = now)
        study.grade(hao, Rating.AGAIN, now = now + 60_000)
        study.grade(ni, Rating.GOOD, StudyRepository.ORIGIN_FINAL, now = now + 120_000)
        study.triage(nihao, TriageVerdict.KNOW, now + 180_000)
        study.grade(StudyItem.character("U+4E00"), Rating.GOOD, now = now - 30 * day)

        assertEquals(mapOf(localDay(now, tz) to 2), study.activity(now + 180_000, days = 7, tz = tz))
    }

    @Test
    fun streakStateTreatsAnEmptyTodayAsAtRiskAndIgnoresTriage() {
        val tz = TimeZone.getTimeZone("UTC")
        val midnight = startOfLocalDay(now, tz)
        study.grade(hao, Rating.GOOD, now = midnight - 2 * day + 3_600_000)
        study.grade(ni, Rating.GOOD, now = midnight - day + 3_600_000)
        study.triage(nihao, TriageVerdict.KNOW, midnight + 3_600_000)

        assertEquals(
            StreakState(current = 2, longest = 2, studiedToday = false, frozenDays = emptySet()),
            study.streakState(midnight + 3_600_000, tz),
        )
    }

    @Test
    fun dontKnowFilesAnOrdinaryNewCard() {
        study.triage(nihao, TriageVerdict.DONT_KNOW, now)

        val list = study.ensureActiveList(now)
        assertEquals(listOf(nihao), study.itemsInList(list.id))
        assertNull(study.cardState(nihao.itemId), "no schedule until it is first practised")
        assertEquals(DeckBucket.NEW, study.deckFor(DeckSource.UserList(list.id), now = now).single().bucket)
    }

    @Test
    fun skipFilesNothingAndHidesTheWordOnlyForAWhile() {
        study.triage(nihao, TriageVerdict.SKIP, now)

        assertTrue(study.allListItems().isEmpty())
        assertEquals(setOf(nihao.itemId), study.recentlySkippedIds(now + day))
        val after = now + (StudyRepository.TRIAGE_SKIP_TTL_DAYS + 1) * day
        assertTrue(study.recentlySkippedIds(after).isEmpty(), "a skipped word comes back after the TTL")
    }

    @Test
    fun everSeenCoversListedArchivedAndScheduledItems() {
        val list = study.createList("A", now)
        study.addToList(list, hao, now)
        user.setKnown(ni.itemId, true, ni.kind)
        study.grade(nihao, Rating.GOOD, now = now)

        assertEquals(setOf(hao.itemId, ni.itemId, nihao.itemId), study.everSeenIds())
    }

    @Test
    fun anEmptyDeckIsThinSoNewWordsAreWanted() {
        assertEquals(NEW_WORD_SESSION, study.newWordsWanted(now))
    }

    @Test
    fun unseenAndSoonDueCardsCountAsUpcomingWork() {
        val list = study.createList("A", now)
        val unseen = (1..3).map { StudyItem.character("U+4E0$it") }
        unseen.forEach { study.addToList(list, it, now) }          // 3 never-seen cards
        study.addToList(list, hao, now)
        study.grade(hao, Rating.GOOD, now = now - 3 * day)         // due within the window
        study.addToList(list, ni, now)
        study.grade(ni, Rating.GOOD, now = now - 3 * day)
        study.grade(ni, Rating.GOOD, now = now)                    // pushed well beyond it

        val load = study.deckLoad(now)
        assertEquals(4, load.upcoming, "3 unseen + 1 due soon; the other card is far off")
        assertEquals(1, study.newWordsWanted(now), "5 - 4 upcoming")
    }

    @Test
    fun archivedItemsAreNotUpcomingWork() {
        val list = study.createList("A", now)
        study.addToList(list, hao, now)
        assertEquals(1, study.deckLoad(now).upcoming)
        user.setKnown(hao.itemId, true, hao.kind)
        assertEquals(0, study.deckLoad(now).upcoming)
    }

    @Test
    fun reviewStatsWrapsRecalledAndLapsesFromCardState() {
        study.grade(hao, Rating.GOOD, now = now)
        study.grade(hao, Rating.AGAIN, now = now + 60_000)
        study.grade(hao, Rating.GOOD, now = now + 120_000)

        val card = study.cardState(hao.itemId)!!
        val stats = ReviewStats(knew = card.recalled, missed = card.lapses)

        assertEquals(ReviewStats(knew = 2, missed = 1), stats)
        assertEquals(2.0 / 3.0, stats.accuracy)
    }

    @Test
    fun reviewStatsAreEmptyRatherThanZeroPercentWhenNothingWasAnswered() {
        val stats = ReviewStats(knew = 0, missed = 0)
        assertEquals(0, stats.total)
        assertNull(stats.accuracy, "no answers is a gap, not a 0% score")
    }

    @Test
    fun activeListSurvivesAndFallsBackWhenItsListDisappears() {
        // First swipe with no lists at all creates the default one.
        val created = study.ensureActiveList(now)
        assertEquals(StudyRepository.DEFAULT_LIST_NAME, created.name)
        assertEquals(created.id, study.activeListId())

        val other = study.createList("Signs", now)
        study.setActiveListId(other)
        assertEquals(other, study.activeListId())

        study.deleteList(other)
        assertNull(study.activeListId(), "a deleted list must not stay active")
        assertEquals(created.id, study.ensureActiveList(now).id)

        study.setActiveListId(null) // "everything"
        assertNull(study.activeListId())
    }

    @Test
    fun migrationFromV1PreservesExistingUserData() {
        // A v1 database as shipped before this feature: the archive, lookup history and
        // photo finds a user has accumulated. Losing these to a schema upgrade would be
        // the worst possible outcome, so build one by hand and upgrade it for real.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        for (sql in V1_SCHEMA) old.execute(null, sql, 0)
        old.execute(null, "INSERT INTO known_item VALUES ('U+597D', 'character', 'known', 1)", 0)
        old.execute(null, "INSERT INTO lookup_event VALUES ('U+4F60', 2)", 0)
        old.execute(null, "INSERT INTO photo_find VALUES ('U+8BF7', 'photo1', 3)", 0)

        UserDatabase.Schema.migrate(old, 1, UserDatabase.Schema.version)

        val migrated = UserRepository(old)
        assertTrue(migrated.isKnown("U+597D"), "archive lost in migration")
        assertEquals(1, migrated.knownCount().toInt())
        assertEquals(listOf("U+4F60", "U+8BF7"), migrated.practiceQueue().sorted())
        assertEquals(1, migrated.recentPhotoFinds().size)

        // ...and the new feature works on the upgraded database.
        val upgraded = StudyRepository(old, Fsrs(enableFuzz = false))
        val list = upgraded.createList("After migration", now)
        upgraded.addToList(list, ni, now)
        assertEquals(listOf(ni), upgraded.deckFor(DeckSource.UserList(list), now = now).map { it.item })
        assertFalse(upgraded.lists().isEmpty())
        old.close()
    }

    private companion object {
        /** The v1 user schema verbatim, so the migration test upgrades a real old DB. */
        val V1_SCHEMA = listOf(
            """CREATE TABLE known_item (item_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL,
               status TEXT NOT NULL, added_at INTEGER NOT NULL)""",
            "CREATE TABLE lookup_event (item_id TEXT NOT NULL, ts INTEGER NOT NULL)",
            """CREATE TABLE confusion_event (glyph_a TEXT NOT NULL, glyph_b TEXT NOT NULL,
               ts INTEGER NOT NULL)""",
            """CREATE TABLE scan_state (id INTEGER NOT NULL PRIMARY KEY,
               last_photo_timestamp INTEGER NOT NULL)""",
            """CREATE TABLE photo_find (glyph_id TEXT NOT NULL, photo_id TEXT NOT NULL,
               found_at INTEGER NOT NULL)""",
            "PRAGMA user_version = 1",
        )
    }
}

/** First column of every row of [sql], as nullable longs — for asserting on columns the
 * repository API doesn't expose. */
private fun app.cash.sqldelight.db.SqlDriver.longs(sql: String): List<Long?> =
    executeQuery(
        null,
        sql,
        { cursor ->
            val out = mutableListOf<Long?>()
            while (cursor.next().value) out += cursor.getLong(0)
            QueryResult.Value(out)
        },
        0,
    ).value

private fun app.cash.sqldelight.db.SqlDriver.execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
): QueryResult.Value<Long> = execute(identifier, sql, parameters, null) as QueryResult.Value<Long>
