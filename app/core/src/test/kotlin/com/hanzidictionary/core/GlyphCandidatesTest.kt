package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.db.HanziDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which photo-find characters get offered for triage, over a tiny hand-built pack (the
 * real one is a build artifact; this checks the ordering and exclusion rules, not the data).
 */
class GlyphCandidatesTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: HanziRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HanziDatabase.Schema.create(driver)
        // id, hsk, freq_rank. Deliberately inserted out of order.
        character("g_l2_common", hsk = 2, freq = 10)
        character("g_l1_rare", hsk = 1, freq = 900)
        character("g_l1_tie_b", hsk = 1, freq = 5)
        character("g_l1_common", hsk = 1, freq = 5)
        character("g_l1_tie_a", hsk = 1, freq = 5)
        character("g_l1_unranked", hsk = 1, freq = null)
        character("g_l3", hsk = 3, freq = 1)
        character("g_ungraded", hsk = null, freq = 1)
        character("g_not_asked", hsk = 1, freq = 1)
        repo = HanziRepository(driver)
    }

    @AfterTest
    fun teardown() = driver.close()

    private fun character(id: String, hsk: Int?, freq: Int?, char: String = id) {
        driver.execute(
            null,
            "INSERT INTO glyph(id, char, scripts, hsk, freq_rank) VALUES (?, ?, 'simp', ?, ?)",
            4,
        ) {
            bindString(0, id)
            bindString(1, char)
            if (hsk == null) bindLong(2, null) else bindLong(2, hsk.toLong())
            if (freq == null) bindLong(3, null) else bindLong(3, freq.toLong())
        }
    }

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

    // Includes an id the pack does not have. g_not_asked is in the pack but not asked for.
    private val asked = listOf(
        "g_l2_common",
        "g_missing",
        "g_l1_rare",
        "g_l1_tie_b",
        "g_l1_common",
        "g_l1_tie_a",
        "g_l1_unranked",
        "g_l3",
        "g_ungraded",
    )

    private fun ids(ids: List<String> = asked, exclude: Set<String> = emptySet(), limit: Int = 10) =
        repo.glyphCandidates(ids, exclude, limit).map { it.itemId }

    @Test
    fun easiestLevelFirstThenMostCommonWithUngradedLast() {
        // Same level and frequency break the tie by id. No HSK level still qualifies, last.
        assertEquals(
            listOf(
                "g_l1_common",
                "g_l1_tie_a",
                "g_l1_tie_b",
                "g_l1_rare",
                "g_l1_unranked",
                "g_l2_common",
                "g_l3",
                "g_ungraded",
            ),
            ids(),
        )
    }

    @Test
    fun alreadySeenCharactersAreSkippedAndTheNextOnesTakeTheirPlace() {
        assertEquals(
            listOf("g_l1_rare", "g_l2_common"),
            ids(
                exclude = setOf("g_l1_common", "g_l1_tie_a", "g_l1_tie_b", "g_l1_unranked"),
                limit = 2,
            ),
        )
    }

    @Test
    fun theLimitCapsTheOffer() {
        assertEquals(listOf("g_l1_common", "g_l1_tie_a"), ids(limit = 2))
    }

    @Test
    fun nothingAskedMeansNothingToOffer() {
        assertEquals(emptyList(), ids(ids = emptyList()))
    }

    @Test
    fun everythingSeenMeansNothingToOffer() {
        assertEquals(emptyList(), ids(exclude = ids().toSet()))
    }

    @Test
    fun candidatesAreCharacterItems() {
        assertEquals(StudyItem.character("g_l1_common"), repo.glyphCandidates(asked, emptySet(), 1).single())
    }

    @Test
    fun aGlyphRepeatedAcrossChunksIsOfferedOnce() {
        // 500 ids per query. The same glyph sits in the first chunk and the second.
        val repeated = listOf("g_l1_common") + List(499) { "missing$it" } + "g_l1_common"
        assertEquals(listOf("g_l1_common"), ids(repeated))
    }

    @Test
    fun itemSummariesKeepsAMixOfCharactersAndWordsInOrder() {
        character("U+4E00", hsk = 1, freq = 1, char = "一")
        word("w_yi", hsk = 1, freq = 1)
        val summaries = repo.itemSummaries(
            listOf(
                StudyItem.word("w_yi"),
                StudyItem.character("U+4E00"),
                StudyItem.character("U+NOPE"),
                StudyItem.word("w_missing"),
            ),
        )
        assertEquals(listOf("w_yi", "U+4E00"), summaries.map { it.item.itemId })
        assertEquals(listOf(true, false), summaries.map { it.item.isWord })
        assertEquals("一", summaries[1].text)
    }
}
