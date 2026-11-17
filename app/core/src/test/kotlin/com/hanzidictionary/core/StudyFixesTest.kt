package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.userdb.UserDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions from the 2026-11 review, over an in-memory user DB: undo that really undoes,
 * the new-card budget, due ordering, and what the Encountered deck holds. */
class StudyFixesTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var study: StudyRepository
    private lateinit var user: UserRepository

    private val now = 1_700_000_000_000L
    private val hao = StudyItem.character("U+597D")

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
    fun undoingAnArchivePutsTheScheduleBack() {
        val list = study.createList("Saved", now)
        study.addToList(list, hao, now)
        study.grade(hao, Rating.GOOD, now = now)
        val graded = study.grade(hao, Rating.GOOD, now = now + 3 * DAY_MS)

        val undo = study.archive(hao, now + 4 * DAY_MS)
        assertTrue(user.isKnown(hao.itemId))
        assertNull(study.cardState(hao.itemId), "archiving still discards the schedule")

        study.undoArchive(undo)
        assertFalse(user.isKnown(hao.itemId))
        assertEquals(graded, study.cardState(hao.itemId), "an Undo that returned a never-studied card undid nothing")
    }

    @Test
    fun undoingAnArchiveOfAnAlreadyArchivedItemLeavesItArchived() {
        user.setKnown(hao.itemId, true)
        study.undoArchive(study.archive(hao, now))
        assertTrue(user.isKnown(hao.itemId), "the tap changed nothing, so its Undo must change nothing")
    }

    @Test
    fun addIfAbsentSaysWhetherItAdded() {
        val list = study.createList("Saved", now)
        assertTrue(study.addToListIfAbsent(list, hao, now))
        assertFalse(study.addToListIfAbsent(list, hao, now + 1), "already there: an Undo must not remove it")
        assertEquals(listOf(hao), study.itemsInList(list))
    }

    @Test
    fun spotChecksDoNotSpendTheNewCardBudget() {
        val full = study.newAllowance(now)
        // Fifteen archived, never-graded items answered in an archive spot-check.
        for (i in 0 until 15) {
            val item = StudyItem.character("U+%04X".format(0x4E00 + i))
            user.setKnown(item.itemId, true)
            study.answerFinalCheck(item, FinalCheckKind.AUDIT, FinalCheckSwipe.PASS, now)
        }
        assertEquals(full, study.newAllowance(now), "a spot-check tests a claim; it introduces nothing")
        study.grade(hao, Rating.GOOD, now = now)
        assertEquals(full - 1, study.newAllowance(now))
    }

    @Test
    fun aCardOnItsLearningStepsLeadsTheDueBucket() {
        val list = study.createList("Saved", now)
        val old = StudyItem.character("U+4E00")
        study.addToList(list, old, now)
        study.addToList(list, hao, now)
        // 一 is a long-overdue review; 好 was missed ten minutes ago and its step has come up.
        study.grade(old, Rating.GOOD, now = now - 100 * DAY_MS)
        study.grade(old, Rating.GOOD, now = now - 100 * DAY_MS + 20 * 60_000L)
        study.grade(hao, Rating.GOOD, now = now - 40 * DAY_MS)
        study.grade(hao, Rating.GOOD, now = now - 40 * DAY_MS + 20 * 60_000L)
        study.grade(hao, Rating.AGAIN, now = now - 11 * 60_000L)
        assertTrue(study.cardState(old.itemId)!!.due < now, "一 is overdue")
        assertEquals(CardPhase.RELEARNING, study.cardState(hao.itemId)?.phase)

        val deck = study.deckFor(DeckSource.UserList(list), now = now)
        assertEquals(listOf(DeckBucket.DUE, DeckBucket.DUE), deck.map { it.bucket })
        assertEquals(hao, deck.first().item, "whole-day recall reads 1.0 for it; it must not sort last")
        assertEquals(hao, study.deckFor(DeckSource.UserList(list), limit = 1, now = now).single().item)
    }

    @Test
    fun cardStatesSurviveMoreIdsThanSqliteBinds() {
        val ids = (0 until 1500).map { "U+%04X".format(0x4E00 + it) }
        study.grade(StudyItem.character(ids[1200]), Rating.GOOD, now = now)
        assertEquals(setOf(ids[1200]), study.cardStates(ids).keys)
    }

    @Test
    fun theEncounteredDeckLeavesOutWhatWasAlreadyDealtWith() {
        val seen = "U+4E00"
        val dismissed = "U+4E8C"
        user.logLookup(seen)
        user.logLookup(hao.itemId)
        user.recordPhotoFind(dismissed, "p1", now)
        user.dismissPhotoWord(dismissed, now + 1)
        study.addToList(study.createList("Saved", now), hao, now)
        assertEquals(listOf(seen), user.practiceQueue())
    }

    @Test
    fun aBulkArchiveUndoesOnlyWhatItChanged() {
        val ni = StudyItem.character("U+4F60")
        val word = StudyItem.word("w000123")
        user.setKnown(ni.itemId, true)
        val graded = study.grade(hao, Rating.GOOD, now = now)

        val undos = study.archiveAll(listOf(hao, ni, word), now)
        assertEquals(listOf(hao, word), undos.map { it.item }, "ni was archived already")
        assertEquals(setOf(hao, ni, word), user.knownItems().toSet(), "words come back as words")

        study.undoArchives(undos)
        assertEquals(listOf(ni), user.knownItems())
        assertEquals(graded, study.cardState(hao.itemId))
    }
}
