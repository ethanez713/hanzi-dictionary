package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hanzidictionary.core.db.HanziDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The family page's grouping rules, on hand-built members, and its queries, on a small
 * in-memory pack built from the app's own schema — so both run without the real pack.
 * Readings and hints mirror the real data they were designed against (青's series; 月's
 * moon/flesh split in makemeahanzi).
 */
class ComponentFamilyTest {
    private fun m(char: String, pinyin: String?, rank: Long?, hint: String? = null) =
        FamilyMember(glyphIdOfChar(char), char, pinyin, null, null, rank, hint)

    private fun List<FamilyGroup>.chars() = map { g -> g.members.joinToString("") { it.char } }

    // ---- sound series ----

    private val qing = listOf(
        m("请", "qǐng", 40), m("情", "qíng", 30), m("清", "qīng", 90), m("晴", "qíng", 900),
        m("精", "jīng", 200), m("睛", "jīng", 300), m("静", "jìng", 400),
        m("猜", "cāi", 500), m("倩", "qiàn", 4000),
    )

    @Test
    fun aSeriesIsGroupedBySyllableWhateverTheTone() {
        val groups = soundSeries(listOf("qīng"), qing)
        assertEquals(listOf("qing", "jing", null), groups.map { it.label })
        assertEquals("情请清晴", groups.chars()[0], "qíng, qǐng and qīng are one sound; commonest first")
    }

    @Test
    fun groupsRunNearestToTheComponentsOwnSoundFirst() {
        val groups = soundSeries(listOf("qīng"), qing)
        assertEquals(
            listOf(SoundRelation.SAME, SoundRelation.RHYME, null),
            groups.map { it.relation },
        )
    }

    @Test
    fun oneOffSoundsAreGatheredRatherThanHeadedOneByOne() {
        val rest = soundSeries(listOf("qīng"), qing).last()
        assertTrue(rest.leftovers)
        assertEquals("猜倩", rest.members.joinToString("") { it.char })
    }

    @Test
    fun aSingleStrayKeepsItsOwnName() {
        // One leftover under "other sounds" hides the only thing worth knowing about it.
        val groups = soundSeries(listOf("qīng"), qing.filterNot { it.char == "倩" })
        assertEquals(listOf("qing", "jing", "cai"), groups.map { it.label })
        assertFalse(groups.last().leftovers)
    }

    @Test
    fun theComponentsOwnSoundIsHeadedEvenForOneCharacter() {
        val groups = soundSeries(listOf("gōng"), listOf(
            m("功", "gōng", 10), m("江", "jiāng", 20), m("红", "hóng", 30), m("空", "kōng", 40),
        ))
        assertEquals("gong", groups.first().label)
        assertEquals(SoundRelation.SAME, groups.first().relation)
        assertTrue(groups.last().leftovers)
    }

    @Test
    fun aPolyphonicComponentIsComparedOnItsClosestReading() {
        // 乐 is lè and yuè; 烁 shuò is near neither, but a yue-member matches the second.
        assertEquals(SoundRelation.SAME, soundRelation("yue", listOf("le", "yue")))
        assertEquals(SoundRelation.SAME_INITIAL, soundRelation("qian", listOf("qing")))
        assertEquals(SoundRelation.RHYME, soundRelation("jing", listOf("qing")))
        assertEquals(SoundRelation.DIFFERENT, soundRelation("cai", listOf("qing")))
        assertNull(soundRelation("cai", emptyList()), "no reading to compare against")
    }

    @Test
    fun tonelessKeepsTheUmlaut() {
        assertEquals("lü", tonelessSyllable("lǜ"))
        assertEquals("lu", tonelessSyllable("lù"))
        assertEquals("qing", tonelessSyllable("Qǐng"))
    }

    @Test
    fun theRangeLineCountsEachSound() {
        assertEquals("qing 4 · jing 3 · other 2", soundRange(soundSeries(listOf("qīng"), qing)))
    }

    // ---- meaning family ----

    @Test
    fun aComponentWithTwoMeaningsIsSplitByThem() {
        val groups = meaningFamily(listOf(
            m("脑", "nǎo", 50, "flesh"), m("胖", "pàng", 60, "flesh"), m("脸", "liǎn", 20, "flesh"),
            m("期", "qī", 10, "moon"), m("朗", "lǎng", 700, "moon"),
            m("朋", "péng", 5, null), m("服", "fú", 15, "boat"),
        ))
        assertEquals(listOf("flesh", "moon", null), groups.map { it.label })
        assertEquals(listOf("脸脑胖", "期朗", "朋服"), groups.chars())
        assertTrue(groups.last().leftovers, "a one-off hint and an unhinted character are not uses")
    }

    @Test
    fun aComponentWithOneMeaningIsOnePlainList() {
        val groups = meaningFamily(listOf(
            m("河", "hé", 30, "water"), m("洗", "xǐ", 20, "water"), m("法", "fǎ", 10, null),
            m("酒", "jiǔ", 40, "wine"),
        ))
        assertEquals(1, groups.size)
        assertEquals("water", groups.single().label)
        assertFalse(groups.single().leftovers)
        assertEquals("法洗河酒", groups.chars().single(), "everyone is in it, hinted or not")
    }

    @Test
    fun noHintsAtAllStillListsTheFamily() {
        val groups = meaningFamily(listOf(m("好", "hǎo", 1), m("妈", "mā", 2)))
        assertNull(groups.single().label)
        assertEquals(2, groups.single().members.size)
    }

    @Test
    fun synonymHintsAreOneUse() {
        // 亻 is "person" 141 times and "people" 16 in makemeahanzi — one meaning, two spellings.
        val groups = meaningFamily(listOf(
            m("你", "nǐ", 1, "person"), m("他", "tā", 2, "person"),
            m("们", "men", 3, "people"), m("众", "zhòng", 4, "people"),
        ))
        assertEquals(listOf("person"), groups.map { it.label })
    }

    @Test
    fun unrankedCharactersSortLast() {
        val sorted = listOf(m("甲", "jiǎ", null), m("乙", "yǐ", 9), m("丙", "bǐng", 3)).sortedWith(byFrequency)
        assertEquals("丙乙甲", sorted.joinToString("") { it.char })
    }

    @Test
    fun theOriginCharacterIsNeverFoldedAway() {
        val group = FamilyGroup("qing", SoundRelation.SAME, (1..12).map { m(('一' + it).toString(), "qīng", it.toLong()) })
        val origin = group.members[10].glyphId
        assertEquals(8, visibleMembers(group, 8, null).size)
        assertEquals(group.members.take(8) + group.members[10], visibleMembers(group, 8, origin))
        assertEquals(8, visibleMembers(group, 8, group.members[2].glyphId).size, "already visible: no duplicate")
    }

    @Test
    fun aRadicalIsOneFamilyInAllItsShapes() {
        assertEquals(setOf("氵", "水", "氺"), componentForms("氵").toSet())
        assertEquals(componentForms("氵").toSet(), componentForms("水").toSet())
        assertEquals("氵", componentForms("氵").first(), "the shape asked for leads")
        assertEquals(listOf("青"), componentForms("青"))
    }

    @Test
    fun aCardOpensTheFamilyOfTheJobItsPartIsDoing() {
        fun part(function: String, navigable: Boolean = true) = ComponentBreakdown(
            componentId = "青", displayChar = "青", position = 0, function = function, matchClass = null,
            confidence = null, navigable = navigable, glyphId = "U+9752", resolution = "computed",
            definition = null, roleGloss = null, pinyin = null,
        )
        assertEquals(ComponentRole.SOUND, familyRoleOf(part("phonetic")))
        assertEquals(ComponentRole.MEANING, familyRoleOf(part("semantic")))
        assertEquals(ComponentRole.SOUND, familyRoleOf(part("both")), "a both-part leads with sound")
        assertNull(familyRoleOf(part("unknown")), "no role to follow: the card opens the character")
        assertNull(familyRoleOf(part("empty")))
        assertNull(familyRoleOf(part("semantic", navigable = false)), "an ids: fragment has no page")
    }

    // ---- the queries, against a miniature pack ----

    private fun miniPack(block: (JdbcSqliteDriver) -> Unit): HanziRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HanziDatabase.Schema.create(driver)
        block(driver)
        return HanziRepository(driver)
    }

    private fun JdbcSqliteDriver.glyph(char: String, pinyin: String?, hsk: Int? = 1, gloss: String? = null) {
        val id = glyphIdOfChar(char)
        val defs = gloss?.let { "'[\"$it\"]'" } ?: "NULL"
        execute(null, "INSERT INTO glyph VALUES ('$id', '$char', 'both', NULL, NULL, ${hsk ?: "NULL"}, NULL, $defs)", 0)
        if (pinyin != null) execute(null, "INSERT INTO reading VALUES ('$id', 'pinyin', '$pinyin', NULL, NULL, 'unihan')", 0)
    }

    private fun JdbcSqliteDriver.uses(char: String, component: String, function: String) =
        execute(null, "INSERT INTO component_use VALUES ('${glyphIdOfChar(char)}', '$component', 0, '$function', NULL, NULL, 'computed')", 0)

    private var wordSeq = 0
    private fun JdbcSqliteDriver.word(simp: String, rank: Int?) {
        val id = "w${++wordSeq}"
        execute(null, "INSERT INTO word VALUES ('$id', '$simp', '$simp', NULL, '[\"gloss of $simp\"]', ${rank ?: "NULL"}, NULL)", 0)
        simp.forEachIndexed { i, c ->
            execute(null, "INSERT INTO word_char VALUES ('$id', '${glyphIdOfChar(c.toString())}', $i)", 0)
        }
    }

    private fun JdbcSqliteDriver.hint(char: String, component: String, hint: String) =
        execute(null, "INSERT INTO claim VALUES ('${glyphIdOfChar(char)}', 'role_hint', " +
            "'{\"component\": \"$component\", \"hint\": \"$hint\"}', 'makemeahanzi', NULL, 0.6, '2026-01-01')", 0)

    @Test
    fun aSoundSeriesComesOutOfThePackRankedByItsWords() {
        val repo = miniPack { d ->
            d.glyph("青", "qīng"); d.glyph("请", "qǐng", gloss = "to ask"); d.glyph("清", "qīng")
            d.glyph("精", "jīng"); d.glyph("睛", "jīng"); d.glyph("鲭", "qīng", hsk = null)
            for (c in listOf("请", "清", "精", "睛", "鲭")) d.uses(c, "青", "phonetic")
            d.uses("请", "讠", "semantic")
            d.word("请", 500); d.word("请问", 800); d.word("申请", 2000)
            d.word("清楚", 300); d.word("精神", 900); d.word("眼睛", 700)
        }
        val sound = repo.componentFamily("青").sound

        assertEquals(listOf("qing", "jing"), sound.map { it.label })
        assertEquals(listOf("清请", "睛精"), sound.chars(), "清楚 (300) outranks 请 (500); 鲭 has no HSK level")
        val qing3 = sound[0].members.single { it.char == "请" }
        assertEquals("qǐng", qing3.pinyin)
        assertEquals("to ask", qing3.gloss)
        assertEquals("请问", qing3.word?.simp, "the anchor is the commonest *multi-character* word")
        assertEquals(500L, qing3.wordRank, "but the rank counts the character standing alone too")
    }

    @Test
    fun theOriginCharacterJoinsItsFamilyWithoutAnHskLevel() {
        val repo = miniPack { d ->
            d.glyph("青", "qīng"); d.glyph("清", "qīng"); d.glyph("鲭", "qīng", hsk = null)
            d.uses("清", "青", "phonetic"); d.uses("鲭", "青", "phonetic")
        }
        assertEquals("清", repo.componentFamily("青").sound.chars().single())
        assertEquals("清鲭", repo.componentFamily("青", glyphIdOfChar("鲭")).sound.chars().single())
    }

    @Test
    fun aMeaningFamilyPoolsEveryShapeAndSplitsOnTheHints() {
        val repo = miniPack { d ->
            for ((c, hint) in listOf("脑" to "flesh", "脸" to "flesh", "期" to "moon", "朗" to "moon")) {
                d.glyph(c, "x"); d.uses(c, "月", "semantic"); d.hint(c, "月", hint)
            }
            d.glyph("朋", "péng"); d.uses("朋", "月", "semantic"); d.uses("朋", "月", "semantic")
            // A hint about a different part of the character must not leak onto 月.
            d.glyph("胡", "hú"); d.uses("胡", "月", "semantic"); d.hint("胡", "古", "old")
            d.execute(null, "INSERT INTO component_gloss VALUES ('月', 'moon', 'makemeahanzi', 8, 0.8)", 0)

            d.glyph("河", "hé"); d.uses("河", "氵", "semantic"); d.hint("河", "氵", "water")
            d.glyph("泉", "quán"); d.uses("泉", "水", "semantic"); d.hint("泉", "水", "water")
        }
        val yue = repo.componentFamily("月")
        assertEquals(listOf("flesh", "moon", null), yue.meaning.map { it.label })
        assertEquals(setOf("朋", "胡"), yue.meaning.last().members.map { it.char }.toSet(),
            "朋 carries 月 twice and is still one row")
        assertTrue(yue.meaning.last().members.all { it.hint == null })
        assertEquals("moon", yue.roleGloss)
        assertTrue(yue.sound.isEmpty())

        val water = repo.componentFamily("氵")
        assertEquals("water", water.meaning.single().label)
        assertEquals(setOf("河", "泉"), water.meaning.single().members.map { it.char }.toSet())
    }

    @Test
    fun aPackWithoutRoleHintsStillListsTheFamily() {
        // The hints arrive with a pack rebuild; an older pack must degrade to one plain list.
        val repo = miniPack { d ->
            d.glyph("脑", "nǎo"); d.uses("脑", "月", "semantic")
            d.glyph("期", "qī"); d.uses("期", "月", "both")
        }
        val family = repo.componentFamily("月")
        assertNull(family.meaning.single().label)
        assertEquals(2, family.meaning.single().members.size)
        assertEquals("期", family.sound.single().members.single().char, "a both-part is in both families")
    }
}
