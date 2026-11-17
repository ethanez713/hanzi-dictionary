package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.db.HanziDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Search ranking on a miniature pack, so it runs without the real one. The FTS tables are
 * created the way pipeline/package.py creates them — keep the two definitions in step, the
 * tokenizer option in particular: it is what these tests are about.
 */
class SearchTest {
    private var wordSeq = 0

    private fun pack(block: JdbcSqliteDriver.() -> Unit): HanziRepository {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HanziDatabase.Schema.create(d)
        d.execute(null, "CREATE VIRTUAL TABLE glyph_fts USING fts5(glyph_id UNINDEXED, char, pinyin, definition, tokenize=\"unicode61 remove_diacritics 2\")", 0)
        d.execute(null, "CREATE VIRTUAL TABLE word_fts USING fts5(word_id UNINDEXED, simp, trad, pinyin, defs, tokenize=\"unicode61 remove_diacritics 2\")", 0)
        d.block()
        return HanziRepository(d)
    }

    private fun JdbcSqliteDriver.glyph(char: String, pinyin: String?, rank: Int?, definition: String = "") {
        val id = glyphIdOfChar(char)
        execute(null, "INSERT INTO glyph VALUES ('$id', '$char', 'both', NULL, ${rank ?: "NULL"}, NULL, '$definition', NULL)", 0)
        if (pinyin != null) execute(null, "INSERT INTO reading VALUES ('$id', 'pinyin', '$pinyin', NULL, NULL, 'unihan')", 0)
        execute(null, "INSERT INTO glyph_fts VALUES ('$id', '$char', '${pinyin.orEmpty()}', '$definition')", 0)
    }

    private fun JdbcSqliteDriver.word(simp: String, pinyin: String, rank: Int) {
        val id = "w${++wordSeq}"
        execute(null, "INSERT INTO word VALUES ('$id', '$simp', '$simp', '$pinyin', '[\"x\"]', $rank, NULL)", 0)
        execute(null, "INSERT INTO word_fts VALUES ('$id', '$simp', '$simp', '$pinyin', 'x')", 0)
    }

    @Test
    fun vFindsTonedUmlautSyllables() {
        // ǚ carries two marks; SQLite's default folding leaves it alone, so "nu*" missed it.
        val repo = pack {
            glyph("女", "nǚ", 100); glyph("努", "nǔ", 200); glyph("绿", "lǜ", 300); glyph("路", "lù", 50)
            glyph("旅", "lǚ", 400); glyph("游", "yóu", 500)
            word("旅游", "lǚ yóu", 10)
        }
        assertTrue(repo.search("nv").any { it.char == "女" })
        assertTrue(repo.search("lv").any { it.char == "绿" })
        assertEquals("旅游", repo.searchWords("lvyou").single().simp)
        assertEquals("旅游", repo.searchWords("lv you").single().simp)
    }

    @Test
    fun commonCharactersSurviveABigMatch() {
        // 150 rare "yi" characters sit ahead of 意 in rowid order; ranking has to happen
        // before the cut, not after it.
        val repo = pack {
            for (i in 0 until 150) glyph(String(Character.toChars(0x3400 + i)), "yì", null)
            glyph("意", "yì", 3)
        }
        assertEquals("意", repo.search("yi", limit = 20).first().char)
    }

    @Test
    fun aPinyinMatchOutranksAnEnglishOne() {
        val repo = pack {
            glyph("心", "xīn", 1, "heart"); glyph("头", "tóu", 2, "head")
            glyph("和", "hé", 30, "and"); glyph("喝", "hē", 40, "drink")
        }
        assertEquals(listOf("和", "喝", "心", "头"), repo.search("he").map { it.char })
    }

    @Test
    fun runTogetherPinyinLeadsWithTheSyllablesInOrder() {
        val repo = pack {
            glyph("医", "yī", 1); glyph("生", "shēng", 2); glyph("意", "yì", 3); glyph("应", "yìng", 4); glyph("声", "shēng", 5)
            word("生意", "shēng yi", 10); word("应声", "yìng shēng", 20); word("医生", "yī shēng", 30)
        }
        assertEquals("医生", repo.searchWords("yisheng").first().simp)
        assertEquals("医生", repo.searchWords("yi sheng").first().simp)
        assertEquals(3, repo.searchWords("yisheng").size, "the looser matches still follow")
    }

    @Test
    fun aLookAlikeWithNoReadingDoesNotCrash() {
        val repo = pack {
            glyph("己", "jǐ", 1); glyph("已", null, 2)
            execute(null, "INSERT INTO confusable VALUES ('${glyphIdOfChar("己")}', '${glyphIdOfChar("已")}', 'shape', NULL)", 0)
        }
        val twin = repo.visualLookAlikes(glyphIdOfChar("己")).single()
        assertEquals("已", twin.char)
        assertNull(twin.pinyin)
    }
}
