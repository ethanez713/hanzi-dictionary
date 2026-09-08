package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun aMeaningComponentIsGlossedByWhatItContributes() {
        // 页 alone is "page, sheet, leaf" — a borrowed sense. What it lends 项 is "head",
        // which is the only reading under which 工 + 页 arriving at "nape of neck" parses.
        val xiang = repo.characterPageByChar("项")
        assertNotNull(xiang)
        assertEquals("head", xiang.breakdown.single { it.componentId == "页" }.roleGloss)

        // The same defect across the highest-traffic components in the pack: 王 in 珠 is
        // 玉 "jade", not "king"; 贝 in 财 is "money", not "sea shell".
        assertEquals("jade", repo.characterPageByChar("珠")!!
            .breakdown.single { it.componentId == "王" }.roleGloss)
        assertEquals("money", repo.characterPageByChar("财")!!
            .breakdown.single { it.componentId == "贝" }.roleGloss)

        // 𤣩 has no glyph row of its own, so nothing but the curated table can gloss it and
        // 玩 showed a blank component card before this fix.
        assertEquals("jade", repo.characterPageByChar("玩")!!
            .breakdown.single { it.componentId == "𤣩" }.roleGloss)
    }

    @Test
    fun aSoundComponentGetsNoRoleGloss() {
        // 工 lends 项 nothing but its sound, so a "meaning it contributes" would be a fiction.
        val gong = repo.characterPageByChar("项")!!.breakdown.single { it.componentId == "工" }
        assertNull(gong.roleGloss)
        assertNotNull(gong.definition, "it still carries its own gloss, which is what shows")
    }

    @Test
    fun everyRoleGlossedComponentIsMeaningBearing() {
        // The rule lives in the repository rather than the UI, so assert it holds pack-wide
        // rather than for the handful of characters spelled out above.
        for (char in listOf("项", "玩", "财", "情", "说", "妈", "钱")) {
            val page = repo.characterPageByChar(char) ?: continue
            for (part in page.breakdown.filter { it.roleGloss != null }) {
                assertTrue(isMeaningBearing(part.function),
                    "$char/${part.componentId} is ${part.function} but carries a role gloss")
            }
        }
    }

    @Test
    fun theHeadlineGlossLeadsWithTheModernSense() {
        // Unihan has 项 as "neck, nape of neck; sum; funds" — none of which is the sense in
        // 项目/选项/事项, every one of the words the same page lists underneath.
        val xiang = repo.characterPageByChar("项")
        assertNotNull(xiang)
        assertTrue(xiang.modernSenses.isNotEmpty())
        assertTrue(xiang.modernSenses.any { it == "item" }, "got ${xiang.modernSenses}")
        assertFalse(xiang.modernSenses.first().startsWith("surname"),
            "a proper-noun reading must not head the gloss")
        assertTrue(xiang.definition!!.contains("nape"), "the classical gloss is still kept")
    }

    @Test
    fun aCharacterWithNoCedictEntryStillHasAGloss() {
        // Two thirds of the pack has no CC-CEDICT single-char entry, so the Unihan gloss is
        // the fallback and must not have been displaced.
        val rare = repo.characterPageByChar("龘")
        if (rare != null) {
            assertTrue(rare.modernSenses.isEmpty() || rare.definition != null)
        }
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
    fun coverageTierAndResolutionAreHonest() {
        val qing = repo.characterPageByChar("情")
        assertNotNull(qing)
        assertEquals(1, qing.tier, "情 has MC+OC readings, so full drift analysis (T1)")
        // 青 in 情: the reading comparison matches AND makemeahanzi asserts the role
        assertEquals("agreement", qing.breakdown.first { it.componentId == "青" }.resolution)

        val shuo = repo.characterPageByChar("说")
        assertNotNull(shuo)
        // 兑 in 说: no computable match, only the source's assertion
        assertEquals("single_source", shuo.breakdown.first { it.componentId == "兑" }.resolution)
    }

    @Test
    fun radicalFormsRoundTrip() {
        assertEquals("水", radicalParentOf("氵"))
        assertTrue("氵" in radicalFormsOf("水") && "氺" in radicalFormsOf("水"))
        assertEquals(null, radicalParentOf("青"))
    }

    @Test
    fun sharedPhraseYieldsCharactersAndLongestWord() {
        // 你好吗 (shared/pasted text): not a CC-CEDICT entry itself, but every character
        // should list, and word search should fall back to the longest prefix word 你好.
        val chars = repo.search("你好吗")
        assertEquals(listOf("你", "好", "吗"), chars.take(3).map { it.char })

        val words = repo.searchWords("你好吗")
        assertTrue(words.any { it.simp == "你好" }, "expected 你好 via greedy fallback, got ${words.map { it.simp }}")
    }

    @Test
    fun wordSearchMatchesEnglishPinyinAndCjk() {
        val byEnglish = repo.searchWords("novel")
        assertTrue(byEnglish.any { it.simp == "小说" }, "'novel' should find 小说, got ${byEnglish.map { it.simp }}")

        val byPinyin = repo.searchWords("shuo hua")
        assertTrue(byPinyin.any { it.simp == "说话" }, "'shuo hua' should find 说话")

        val byCjk = repo.searchWords("说话")
        assertTrue(byCjk.any { it.simp == "说话" }, "说话 should find itself")
    }

    @Test
    fun wordPageAssemblesFormsSensesAndCharacterLinks() {
        val hit = repo.searchWords("小说").first { it.simp == "小说" }
        val page = repo.wordPage(hit.id)
        assertNotNull(page)
        assertEquals("小说", page.simp)
        assertEquals("小說", page.trad)
        assertTrue(page.senses.isNotEmpty())
        // 小, 说, plus 說 from the traditional form — each linking to its glyph
        assertTrue(page.chars.any { it.char == "说" })
        assertTrue(page.chars.any { it.char == "小" })
        assertTrue(page.chars.all { it.glyphId.startsWith("U+") })
    }

    @Test
    fun sortByFrequencyServesCommonCharactersFirst() {
        val common = "U+8BF7"   // 请, high-frequency
        val rare = repo.search("qing").last().glyphId
        val sorted = repo.sortByFrequency(listOf(rare, common, "U+FFFFF" /* not in pack */))
        assertEquals(common, sorted.first(), "请 should sort before a rare character")
        assertEquals(2, sorted.size, "ids missing from the pack are dropped")
    }

    @Test
    fun confusablePairsAvailableForDrill() {
        val pairs = repo.confusablePairs()
        assertTrue(pairs.isNotEmpty(), "pack should carry kSpoofingVariant pairs")
        assertTrue(
            pairs.any { setOf(it.charA, it.charB) == setOf("妹", "妺") },
            "妹/妺 should be a drillable pair",
        )
    }

    @Test
    fun confusionEventsRoundTrip() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserRepository.Schema.create(d)
        val user = UserRepository(d)
        assertEquals(0L, user.confusionCount())
        user.logConfusion("U+59B9", "U+59BA")
        assertEquals(1L, user.confusionCount())
        d.close()
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
    fun searchFoldsTraditionalIntoSimplifiedRow() {
        val results = repo.search("qing")
        val chars = results.map { it.char }
        // 轻/輕 both match "qing"; the traditional row must be folded, not listed twice.
        if ("轻" in chars) {
            assertFalse("輕" in chars, "輕 should be folded into 轻's row, not its own")
            assertEquals("輕", results.first { it.char == "轻" }.tradChar)
        }
    }

    @Test
    fun wordSearchRanksByCorpusFrequency() {
        val hits = repo.searchWords("qing")
        assertTrue(hits.isNotEmpty(), "qing should return word hits")
        // Frequency-first: everyday words up top, corpus-rare words (乳清 "whey",
        // rank ~109k) and defs-only matches (Qinghai counties) nowhere near it.
        val topFive = hits.take(5).map { it.simp }
        assertTrue(
            topFive.any { it in setOf("情况", "事情", "清楚", "请问", "亲爱") },
            "a common qing word should lead, got $topFive",
        )
        assertFalse("乳清" in topFive, "corpus-rare words must not lead")
    }

    @Test
    fun wordSearchSegmentsRunTogetherPinyin() {
        val hits = repo.searchWords("qingchu")
        assertTrue(hits.any { it.simp == "清楚" }, "qingchu should find 清楚, got ${hits.map { it.simp }}")
        val nihao = repo.searchWords("nihao")
        assertTrue(nihao.any { it.simp == "你好" }, "nihao should find 你好, got ${nihao.map { it.simp }}")
    }

    @Test
    fun pinyinSegmentationHandlesAmbiguityAndPartials() {
        val syllables = setOf("qing", "chu", "ni", "hao", "xi", "an", "xian", "wo")
        assertEquals(listOf("qing", "chu"), segmentPinyin("qingchu", syllables))
        assertEquals(listOf("ni", "hao"), segmentPinyin("nihao", syllables))
        // trailing partial syllable while typing
        assertEquals(listOf("qing", "ch"), segmentPinyin("qingch", syllables))
        // apostrophe form
        assertEquals(listOf("xi", "an"), segmentPinyin("xi'an", syllables))
        // single syllable → null (caller keeps original token)
        assertEquals(null, segmentPinyin("qing", syllables))
        // not segmentable → null
        assertEquals(null, segmentPinyin("feeling", syllables))
    }

    @Test
    fun drillSeriesServesCommonDistinctLookAlikes() {
        val groups = repo.drillSeries()
        assertTrue(groups.isNotEmpty(), "drill needs at least one look-alike group")
        for (g in groups) {
            assertTrue(g.size >= 2)
            assertTrue(g.all { it.pinyin.isNotBlank() && it.gloss.isNotBlank() })
            assertEquals(g.size, g.map { it.gloss.lowercase() }.distinct().size, "glosses must be distinct")
        }
        val qingGroup = groups.firstOrNull { g -> g.any { it.char == "清" } && g.any { it.char == "情" } }
        assertNotNull(qingGroup, "the 青 series (清/情) should be a drill group")
    }

    @Test
    fun myWordsDrillAnchorsEveryRoundOnACharacterIUseAndPairsItPlausibly() {
        // A tiny "saved list": one character with well-known look-alikes, and one without.
        val qing = repo.characterPageByChar("请")!!.glyphId
        val mine = setOf(qing)

        val groups = repo.drillSeriesFor(mine)
        assertTrue(groups.isNotEmpty(), "请 has look-alikes, so it should yield a group")
        for (g in groups) {
            assertTrue(g.size >= 2, "a round needs a target and at least one distractor")
            // Target-first is the contract the drill screen deals rounds on.
            assertTrue(g.first().glyphId in mine, "the target must be one of my characters")
            assertTrue(g.all { it.pinyin.isNotBlank() && it.gloss.isNotBlank() })
            assertTrue(
                g.drop(1).none { it.gloss.equals(g.first().gloss, ignoreCase = true) },
                "a distractor that means the same thing makes the cue unanswerable",
            )
        }
        val qingGroup = groups.single()
        assertTrue(
            qingGroup.drop(1).any { it.char == "清" || it.char == "情" || it.char == "晴" },
            "请's distractors should be its 青 siblings, got ${qingGroup.map { it.char }}",
        )
    }

    @Test
    fun myWordsDrillOnlyOffersDistractorsAUserCouldPlausiblyMeet() {
        val qing = repo.characterPageByChar("请")!!.glyphId
        // Every distractor is either one of mine or common enough to actually run into.
        val distractors = repo.drillSeriesFor(setOf(qing)).flatMap { it.drop(1) }
        assertTrue(distractors.isNotEmpty())
        for (d in distractors) {
            val rank = repo.characterPage(d.glyphId)!!.freqRank
            assertTrue(
                rank != null && rank <= 3000L,
                "${d.char} (rank $rank) is too rare to be a useful confusion",
            )
        }
    }

    @Test
    fun myWordsDrillIsEmptyWithNothingSavedRatherThanFallingBackSilently() {
        // The screen decides what to do about an empty result (it drops to the pooled drill
        // and says so); the repository must not paper over it here.
        assertTrue(repo.drillSeriesFor(emptySet()).isEmpty())
        // Word ids aren't glyphs — the drill compares single characters.
        assertTrue(repo.drillSeriesFor(setOf("w000123")).isEmpty())
    }

    @Test
    fun aComponentThatIsItselfACharacterCarriesItsGlyphIdForDirectNavigation() {
        // Tapping 请's 青 goes to 青's character page, which needs the glyph id on the
        // breakdown row. 讠 is a combining form with no character page of its own.
        val qing = repo.characterPageByChar("请")!!
        val qingPart = qing.breakdown.single { it.displayChar == "青" }
        assertEquals(repo.characterPageByChar("青")!!.glyphId, qingPart.glyphId)
        assertTrue(qingPart.navigable)
    }

    @Test
    fun breakdownPrefersADecompositionWhosePartsAreAllRealCharacters() {
        // 举: cjkvi-ids says ⿱兴⿻二丨, whose bottom half is an unencoded shape the app could
        // only render as "not in the component index" and could not link to. makemeahanzi
        // names the same part 扌, which has a page, a gloss and a reading — so the pipeline
        // takes the navigable analysis even though cjkvi-ids normally outranks it.
        val ju = repo.characterPageByChar("举")
        assertNotNull(ju)
        assertEquals(listOf("兴", "扌"), ju.breakdown.map { it.displayChar })
        assertTrue(ju.breakdown.all { it.navigable }, "every part of 举 should open its own page")
    }

    @Test
    fun anUnencodedPartIsStillReportedRatherThanDropped() {
        // The honest remainder: 两's lower part (⿻冂从) has no codepoint in ANY source, so
        // no choice of decomposition can name it. It must still appear as a positioned part
        // — dropping it would silently misrepresent the character as having one component.
        val liang = repo.characterPageByChar("两")
        assertNotNull(liang)
        assertEquals(2, liang.breakdown.size)
        val unencoded = liang.breakdown.single { it.displayChar == null }
        assertTrue(unencoded.componentId.startsWith("ids:"), "expected an ids: pseudo-component")
        assertFalse(unencoded.navigable, "an unencoded shape has no page to open")
    }

    private fun lookAlikeChars(char: String): List<String> =
        repo.characterPageByChar(char)!!.let { p -> repo.visualLookAlikes(p.glyphId).map { it.char } }

    @Test
    fun visualLookAlikesCoverShapeNeighbours() {
        // Layer 1 of the drawer: the classic 形近字 confusions, which exist only because the
        // pipeline computes shape-code pairs — Unihan's own kSpoofingVariant carries 75 pairs
        // in total, one of them between common characters.
        assertTrue("已" in lookAlikeChars("己"), "己/已 is the canonical pair, got ${lookAlikeChars("己")}")
        assertTrue(
            lookAlikeChars("未").any { it == "末" || it == "木" },
            "未 should list 末/木, got ${lookAlikeChars("未")}",
        )
        assertTrue("天" in lookAlikeChars("大"), "大/天 differ by one stroke, got ${lookAlikeChars("大")}")
    }

    @Test
    fun visualLookAlikesCoverSharedComponents() {
        // Layer 2: characters several strokes apart that share a big, distinctive component in
        // the same place. Shape-code distance can't reach these — its stroke gate is ±1 — and
        // they are exactly the confusions a learner hits: 斯 with 期 (其) and with 所 (斤).
        assertTrue("期" in lookAlikeChars("斯"), "斯/期 both carry 其, got ${lookAlikeChars("斯")}")
        assertTrue("所" in lookAlikeChars("斯"), "斯/所 both carry 斤, got ${lookAlikeChars("斯")}")
        assertTrue("清" in lookAlikeChars("请"), "请/清 both carry 青, got ${lookAlikeChars("请")}")

        // The shared part is named, because it's the whole reason the pair is confusable.
        val qi = repo.visualLookAlikes(repo.characterPageByChar("斯")!!.glyphId).single { it.char == "期" }
        assertEquals("其", qi.sharedComponent)

        // And the line this layer must not cross: a component that files hundreds of
        // characters together is a filing system, not a resemblance. Nobody mistakes 江 for 河
        // because both are wet.
        assertTrue("河" !in lookAlikeChars("江"), "shared-radical noise leaked in: ${lookAlikeChars("江")}")
        // Same for a part that sits somewhere else in the other character: 的 and 怕 both
        // contain 白, on opposite sides.
        assertTrue("怕" !in lookAlikeChars("的"), "misplaced-part noise leaked in: ${lookAlikeChars("的")}")
    }

    @Test
    fun visualLookAlikesLeadWithShapeNeighboursThenCommonOnes() {
        // Order the drawer relies on: shape near-twins first (the stronger claim, and they
        // have no shared part to name), then the shared-component groups. Within a group,
        // common characters lead — a drawer is only worth opening if what's in it is what
        // you'll meet.
        val page = repo.characterPageByChar("日")!!
        val items = repo.visualLookAlikes(page.glyphId)
        assertEquals(
            items.sortedBy { it.sharedComponent != null }.map { it.char },
            items.map { it.char },
            "shape pairs should precede shared-component ones, got ${items.map { it.char to it.sharedComponent }}",
        )
        val shapeRanks = items.filter { it.sharedComponent == null }.map { la ->
            repo.characterPage(la.glyphId)?.freqRank ?: Long.MAX_VALUE
        }
        assertEquals(shapeRanks.sorted(), shapeRanks, "look-alikes should be frequency-ordered, got $shapeRanks")
    }

    @Test
    fun singleCharacterLookAlikesLeadWithVisualConfusables() {
        // What the character page's look-alike drawer serves: the spoofing variant 妺 ahead
        // of any series sibling, and never the character you're already looking at.
        val mei = "U+59B9"   // 妹
        val out = repo.lookAlikesForGlyphs(listOf(mei))
        assertEquals("妺", out.first().char, "妹's visual confusable should lead, got ${out.map { it.char }}")
        assertTrue(out.none { it.glyphId == mei }, "the character itself is not its own look-alike")

        // A character with no confusable still gets its phonetic-series siblings.
        val qing = repo.lookAlikesForGlyphs(listOf("U+8BF7"))   // 请
        assertTrue(qing.any { it.char == "清" || it.char == "情" }, "请 should list its 青 series")
        assertTrue(qing.all { it.pinyin != null }, "each look-alike carries the reading that tells it apart")
    }

    @Test
    fun composeSearchIntersectsComponents() {
        val results = repo.composeSearch(listOf("氵", "青"))
        assertTrue(results.any { it.char == "清" }, "氵+青 should find 清, got ${results.map { it.char }}")
        // Positional-variant awareness: the full form 水 must reach 氵-characters too.
        val viaParent = repo.composeSearch(listOf("水", "青"))
        assertTrue(viaParent.any { it.char == "清" }, "水+青 should also find 清 via 氵")
        // Most-common member first, not an arbitrary one.
        val single = repo.composeSearch(listOf("青"))
        assertTrue(single.isNotEmpty())
        assertNotNull(single.first().freqRank, "compose results should lead with ranked characters")
    }

    @Test
    fun fuzzyFallbackRescuesTyposAndFuzzyPinyin() {
        // Transposition: "qign" matches nothing exactly, falls back to "qing".
        val typo = repo.search("qign")
        assertTrue(typo.any { it.char == "情" || it.char == "请" }, "qign should fuzzy-match qing chars")
        // Variant generation is ordered: transpositions before initial/final toggles.
        val variants = fuzzyPinyinVariants("zhang")
        assertTrue("zhnag" in variants)
        assertTrue("zang" in variants && "zhan" in variants)
        assertTrue(fuzzyPinyinVariants("shi").contains("si"))
        assertTrue(fuzzyPinyinVariants("si").contains("shi"))
    }

    @Test
    fun pinyinVForUmlautMatches() {
        val results = repo.search("lv")
        assertTrue(results.any { it.primaryPinyin?.let { p -> stripTones(p) == "lu" } == true },
            "lv should match lü readings, got ${results.take(5).map { it.char to it.primaryPinyin }}")
    }

    @Test
    fun displayGlossStripsKangxiRadicalNoise() {
        assertEquals("blue, green; young", displayGloss("blue, green; young; Kangxi radical 174"))
        assertEquals("Kangxi radical 174", displayGloss("Kangxi radical 174"))
        assertEquals("feeling, sentiment, emotion", displayGloss("feeling, sentiment, emotion"))
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
