package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.db.HanziDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which HSK words get offered for triage, over a tiny hand-built pack (the real one is a
 * build artifact; this checks the ordering and exclusion rules, not the data).
 */
class HskWordCandidatesTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: HanziRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HanziDatabase.Schema.create(driver)
        // id, hsk, freq_rank. Deliberately inserted out of order.
        word("w_l2_common", hsk = 2, freq = 10)
        word("w_l1_rare", hsk = 1, freq = 900)
        word("w_l1_common", hsk = 1, freq = 5)
        word("w_l1_unranked", hsk = 1, freq = null)
        word("w_l3", hsk = 3, freq = 1)
        word("w_ungraded", hsk = null, freq = 1)
        repo = HanziRepository(driver)
    }

    @AfterTest
    fun teardown() = driver.close()

    private fun word(id: String, hsk: Int?, freq: Int?) {
        driver.execute(
            null,
            "INSERT INTO word(id, simp, trad, hsk, freq_rank) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, id)
            bindString(1, id)
            bindString(2, id)
            if (hsk == null) bindLong(3, null) else bindLong(3, hsk.toLong())
            if (freq == null) bindLong(4, null) else bindLong(4, freq.toLong())
        }
    }

    private fun ids(exclude: Set<String> = emptySet(), limit: Int = 10) =
        repo.hskWordCandidates(exclude, limit).map { it.itemId }

    @Test
    fun easiestLevelFirstThenMostCommonWithUnrankedLast() {
        assertEquals(
            listOf("w_l1_common", "w_l1_rare", "w_l1_unranked", "w_l2_common", "w_l3"),
            ids(),
        )
    }

    @Test
    fun wordsOutsideTheHskCatalogueAreNeverOffered() {
        assertEquals(false, "w_ungraded" in ids())
    }

    @Test
    fun alreadySeenWordsAreSkippedAndTheNextOnesTakeTheirPlace() {
        assertEquals(
            listOf("w_l1_rare", "w_l2_common"),
            ids(exclude = setOf("w_l1_common", "w_l1_unranked"), limit = 2),
        )
    }

    @Test
    fun theLimitCapsTheOffer() {
        assertEquals(listOf("w_l1_common", "w_l1_rare"), ids(limit = 2))
    }

    @Test
    fun everythingSeenMeansNothingToOffer() {
        assertEquals(emptyList(), ids(exclude = ids().toSet()))
    }

    @Test
    fun candidatesAreWordItems() {
        assertEquals(true, repo.hskWordCandidates(emptySet(), 1).single().isWord)
    }
}
