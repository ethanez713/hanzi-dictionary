package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the repository against the real pipeline-built packs (not a synthetic
 * fixture) so it doubles as an integration check that the shipped schema, the FTS5
 * index, and the two-file core/ids split all line up with what the code expects.
 * Requires `python -m pipeline.build && python -m pipeline.package` to have run.
 */
class HanziRepositoryTest {
    private lateinit var coreDriver: JdbcSqliteDriver
    private lateinit var idsDriver: JdbcSqliteDriver
    private lateinit var repo: HanziRepository

    @BeforeTest
    fun setup() {
        val buildDir = File("../../pipeline/data/build")
        val core = File(buildDir, "core.db")
        val ids = File(buildDir, "ids.db")
        assertTrue(core.exists(), "core.db missing at ${core.absolutePath}; run pipeline.build + pipeline.package")
        assertTrue(ids.exists(), "ids.db missing at ${ids.absolutePath}; run pipeline.package")
        coreDriver = JdbcSqliteDriver("jdbc:sqlite:${core.absolutePath}")
        idsDriver = JdbcSqliteDriver("jdbc:sqlite:${ids.absolutePath}")
        repo = HanziRepository(coreDriver, idsDriver)
    }

    @AfterTest
    fun teardown() {
        coreDriver.close()
        idsDriver.close()
    }

    @Test
    fun searchByPinyinPrefixFoldsTones() {
        val results = repo.search("qing")
        assertTrue(results.isNotEmpty(), "qing should return results")
        assertTrue(results.any { it.char == "请" || it.char == "青" }, "expected 请/青 among qing results")
    }

    @Test
    fun searchBySingleCharRanksExactFirst() {
        val results = repo.search("请")
        assertEquals("请", results.first().char)
    }

    @Test
    fun characterPageComputesPhoneticComponent() {
        val page = repo.characterPageByChar("请")
        assertNotNull(page)
        assertTrue(page.readings.any { it.system == "pinyin" && it.value.startsWith("q") })
        val phon = page.breakdown.firstOrNull { it.componentId == "青" }
        assertNotNull(phon, "青 should appear as a component of 请")
        assertEquals("phonetic", phon.function)
    }

    @Test
    fun characterPageResolvesIdsFromIdsDb() {
        val page = repo.characterPageByChar("请")
        assertNotNull(page)
        assertNotNull(page.ids, "请 should have a resolved decomposition")
        assertTrue(page.ids!!.contains("青"), "请 decomposition should contain 青, got ${page.ids}")
    }

    @Test
    fun componentPageListsPhoneticMembers() {
        val page = repo.componentPage("青")
        assertTrue(page.memberCount >= 2, "青 should be a phonetic series with multiple members")
        assertTrue(page.phoneticMembers.any { it.char == "请" }, "请 should be a phonetic member of 青")
    }

    @Test
    fun sourcesArePresentForProvenance() {
        val sources = repo.sources()
        assertTrue(sources.any { it.id == "makemeahanzi" && it.license.contains("LGPL") })
        assertTrue(sources.any { it.id == "cjkvi-ids" })
        assertTrue(sources.any { it.id == "hsk" && it.license == "MIT" })
    }

    @Test
    fun searchResultsCarryDefinitions() {
        val results = repo.search("qing")
        val qing = results.first { it.char == "情" }
        val def = qing.definition
        assertNotNull(def, "search rows should carry the gloss they display")
        assertTrue("feeling" in def, "unexpected 情 gloss: $def")
    }

    @Test
    fun characterPageCarriesDefinition() {
        val page = repo.characterPageByChar("请")
        assertNotNull(page)
        val def = page.definition
        assertNotNull(def, "请 should carry a Unihan kDefinition gloss")
        assertTrue("ask" in def || "request" in def, "unexpected 请 gloss: $def")
    }

    @Test
    fun englishSearchFindsCharacterByGloss() {
        val results = repo.search("feeling")
        assertTrue(results.any { it.char == "情" }, "searching an English gloss should find 情")
    }

    @Test
    fun radicalFormCarriesUsefulGloss() {
        // 讠's raw kDefinition is pure bookkeeping ("simplified Kangxi radical 149");
        // the pack derives a learner gloss from its parent 言 instead.
        val page = repo.characterPageByChar("说")
        assertNotNull(page)
        val yan = page.breakdown.first { it.componentId == "讠" }
        assertNotNull(yan.definition)
        assertTrue("speech" in yan.definition!!, "讠 should gloss via 言, got: ${yan.definition}")
    }

    @Test
    fun claimedPhoneticSurvivesTotalSoundDrift() {
        // 说's 兑 (duì vs shuō) has no computable sound link at any stratum, but
        // makemeahanzi's etymology asserts the phonetic role — the detector must keep
        // it as phonetic/opaque instead of dropping to "unknown".
        val page = repo.characterPageByChar("说")
        assertNotNull(page)
        val dui = page.breakdown.firstOrNull { it.componentId == "兑" }
        assertNotNull(dui, "兑 should appear as a component of 说")
        assertEquals("phonetic", dui.function)
        assertEquals("opaque", dui.matchClass)
    }

    @Test
    fun breakdownCarriesComponentGlossAndPinyin() {
        val page = repo.characterPageByChar("情")
        assertNotNull(page)
        val qing = page.breakdown.first { it.componentId == "青" }
        assertEquals("qīng", qing.pinyin, "sound component should carry its own reading")
        assertNotNull(qing.definition, "component should carry its own gloss")
    }

    @Test
    fun characterPageCarriesContainingWords() {
        val page = repo.characterPageByChar("情")
        assertNotNull(page)
        assertTrue(page.words.isNotEmpty(), "情 should list containing words")
        val shiqing = page.words.firstOrNull { it.simp == "事情" }
        assertNotNull(shiqing, "事情 (HSK 2) should rank among 情's top words, got ${page.words.map { it.simp }}")
        assertTrue(shiqing.gloss!!.contains("matter") || shiqing.gloss!!.contains("affair"))
        assertEquals("shì qing", shiqing.pinyin, "numbered CC-CEDICT pinyin should be tone-marked")
    }

    @Test
    fun componentPageLinksToOwnCharacterPage() {
        val page = repo.componentPage("青")
        assertEquals("U+9752", page.glyphId, "青 is itself a pack glyph and should link to it")
        assertNotNull(page.definition, "青 should carry its own gloss on the component page")
    }

    @Test
    fun characterPageCarriesHskLevel() {
        val page = repo.characterPageByChar("请")
        assertNotNull(page)
        assertEquals(1L, page.hsk, "请 should be HSK level 1")
    }

    @Test
    fun characterPageListsConfusables() {
        // 妹 / 妺 are a kSpoofingVariant pair; both are in the T1/T2 build.
        val page = repo.characterPageByChar("妹")
        assertNotNull(page)
        assertTrue(page.confusables.any { it.char == "妺" }, "妹 should list 妺 as a confusable")
    }

    @Test
    fun practiceQueueHoldsEncounteredUnknownsUntilArchived() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserRepository.Schema.create(d)
        val user = UserRepository(d)

        user.logLookup("U+8BF7")                        // looked up 请
        user.recordPhotoFind("U+60C5", "p1", 1000)      // found 情 in a photo
        user.setKnown("U+4E00", true)                   // 一 already known: never queued
        user.logLookup("U+4E00")

        assertEquals(setOf("U+8BF7", "U+60C5"), user.practiceQueue().toSet())

        user.setKnown("U+60C5", true)                   // "I knew it" archives 情
        assertEquals(listOf("U+8BF7"), user.practiceQueue())
        d.close()
    }

    @Test
    fun userKnownStateRoundTrips() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserRepository.Schema.create(d)
        val user = UserRepository(d)
        assertFalse(user.isKnown("U+8BF7"))
        user.setKnown("U+8BF7", true)
        assertTrue(user.isKnown("U+8BF7"))
        assertEquals(1L, user.knownCount())
        user.importKnown(listOf("U+4E00", "U+4E8C"))
        assertEquals(3L, user.knownCount())
        user.setKnown("U+8BF7", false)
        assertFalse(user.isKnown("U+8BF7"))
        assertEquals(2L, user.knownCount())
        d.close()
    }

    @Test
    fun knownCharactersFloatToFrontOfSeries() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserRepository.Schema.create(d)
        val user = UserRepository(d)

        val before = repo.componentPage("青")
        assertTrue(before.phoneticMembers.size >= 2)
        val lastMember = before.phoneticMembers.last()

        user.setKnown(lastMember.glyphId, true)
        val after = repo.componentPage("青", user.knownIds())
        assertEquals(
            lastMember.glyphId,
            after.phoneticMembers.first().glyphId,
            "a known member should be reordered to the front of the series",
        )
        d.close()
    }
}
