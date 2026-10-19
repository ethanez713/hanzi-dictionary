package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Order of the Dictionary "From your photos" feed. Recency is the query's own order;
 * the other two are scales with an unranked end, and neither may reshuffle a tie. */
class PhotoSortTest {
    @Test
    fun recentKeepsTheIncomingOrder() {
        val finds = listOf(
            find("late", hsk = 1, freq = 1),
            find("early", hsk = 6, freq = 900),
            find("unranked"),
        )
        assertSame(finds, sortFinds(finds, PhotoSort.RECENT))
    }

    @Test
    fun hskOrdersByLevelThenFrequencyWithUnrankedLast() {
        val finds = listOf(
            find("h2-freq10-a", hsk = 2, freq = 10),
            find("unranked-freq1", freq = 1),
            find("h1-freq50", hsk = 1, freq = 50),
            find("h1-freq5-a", hsk = 1, freq = 5),
            find("h1-freq5-b", hsk = 1, freq = 5),
            find("h1-unranked-freq", hsk = 1),
            find("unranked-b"),
            find("unranked-freq3", freq = 3),
            find("h2-unranked-freq", hsk = 2),
            find("h2-freq10-b", hsk = 2, freq = 10),
            find("unranked-c"),
        )
        assertEquals(
            listOf(
                "h1-freq5-a",
                "h1-freq5-b",
                "h1-freq50",
                "h1-unranked-freq",
                "h2-freq10-a",
                "h2-freq10-b",
                "h2-unranked-freq",
                "unranked-freq1",
                "unranked-freq3",
                "unranked-b",
                "unranked-c",
            ),
            sortFinds(finds, PhotoSort.HSK).map { it.glyphId },
        )
    }

    @Test
    fun frequencyOrdersByRankWithUnrankedLastAndIgnoresHsk() {
        val finds = listOf(
            find("null-a", hsk = 1),
            find("rank2-a", hsk = 9, freq = 2),
            find("null-b", hsk = 1),
            find("rank2-b", hsk = 1, freq = 2),
            find("rank50", hsk = 1, freq = 50),
            find("null-c"),
            find("rank1", hsk = 6, freq = 1),
        )
        assertEquals(
            listOf("rank1", "rank2-a", "rank2-b", "rank50", "null-a", "null-b", "null-c"),
            sortFinds(finds, PhotoSort.FREQUENCY).map { it.glyphId },
        )
    }

    @Test
    fun anEmptyFeedStaysEmpty() {
        assertEquals(emptyList(), sortFinds(emptyList(), PhotoSort.HSK))
        assertEquals(emptyList(), sortFinds(emptyList(), PhotoSort.FREQUENCY))
        assertEquals(emptyList(), sortFinds(emptyList(), PhotoSort.RECENT))
    }

    private fun find(id: String, hsk: Long? = null, freq: Long? = null) = SearchResult(
        glyphId = id,
        char = "字",
        primaryPinyin = null,
        definition = null,
        freqRank = freq,
        hsk = hsk,
    )
}
