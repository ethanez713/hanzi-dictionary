package com.hanzidictionary.core

/**
 * A component's family: the common characters built on it, sorted into the different jobs
 * it does. As a sound part the groups are the syllables it stands for (青: qing, jing, cai);
 * as a meaning part they are the things it means (月: moon, flesh).
 *
 * This is a reading surface and deliberately knows nothing about study state — the archive,
 * the deck and what is "known" play no part in what is listed or in what order. Order is
 * frequency alone: a character is as common as the commonest word it appears in.
 */

/** Which of a component's two jobs a family page leads with. */
enum class ComponentRole { SOUND, MEANING }

/**
 * The family a tap on a breakdown card opens, or null when the card should open something
 * else. A card is tapped for the job the part is doing in *this* character: the sound part
 * of 请 leads to everything else 青 lends its sound to, the meaning part to everything else
 * 讠 lends its meaning to. A part that is both leads with sound — the rarer and more
 * surprising of its two jobs, and the meaning family is one tap below it on the same page.
 *
 * Null for a part with no role to follow (好's 女 and 子 are neither sound nor meaning in the
 * pack's terms) and for an unencoded "ids:" fragment, which has no page at all.
 */
fun familyRoleOf(part: ComponentBreakdown): ComponentRole? = when {
    !part.navigable -> null
    isSoundBearing(part.function) -> ComponentRole.SOUND
    isMeaningBearing(part.function) -> ComponentRole.MEANING
    else -> null
}

/** The word a character is best known from, shown beside it (请 → 请问). */
data class FamilyWord(val id: String, val simp: String, val pinyin: String?, val gloss: String?)

data class FamilyMember(
    val glyphId: String,
    val char: String,
    val pinyin: String?,       // primary reading, toned
    val gloss: String?,
    val word: FamilyWord?,     // its commonest multi-character word, when it has one
    val wordRank: Long?,       // corpus rank of its commonest word of any length (1 = most common)
    val hint: String? = null,  // meaning family only: what the part lends *this* character
)

/** How a group's syllable sits against the component's own reading, nearest first. The
 * order mirrors the pipeline's match tiers (pinyin.py: initial outranks rime). */
enum class SoundRelation { SAME, SAME_INITIAL, RHYME, DIFFERENT }

data class FamilyGroup(
    val label: String?,            // "qing" / "flesh"; null on the leftovers group, and on a
                                   // meaning family that turned out to have no named use
    val relation: SoundRelation?,  // sound series only, and only when the component has a reading
    val members: List<FamilyMember>,  // every member, most common first — the page shows the top few
    val leftovers: Boolean = false,   // one-off sounds / unsorted uses gathered into one group,
                                      // where each row says for itself what it is
)

data class ComponentFamily(
    val forms: List<String>,       // the component in every shape it takes (氵 水 氺), asked-for first
    val roleGloss: String?,        // what it contributes as a meaning part ("water")
    val sound: List<FamilyGroup>,
    val meaning: List<FamilyGroup>,
)

/** How many rows of a group show before "show all". The request was 5–10 per use. */
const val FAMILY_GROUP_SHOWN = 8

/** A meaning needs this many characters behind it to stand as a use of its own. */
const val MIN_USE_GROUP = 2

/** Every shape of [componentId] that counts as the same meaning part: 氵 ↔ 水 ↔ 氺. */
fun componentForms(componentId: String): List<String> {
    val parent = radicalParentOf(componentId) ?: componentId
    return (listOf(componentId, parent) + radicalFormsOf(parent)).distinct()
}

/** Most common first; a character with no ranked word sorts last. */
val byFrequency: Comparator<FamilyMember> =
    compareBy({ it.wordRank == null }, { it.wordRank ?: Long.MAX_VALUE }, { it.glyphId })

private val TONE_MARKS: Map<Char, Char> = buildMap {
    for ((base, marked) in listOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
    )) for (m in marked) put(m, base)
}

/** "qǐng" → "qing", "lǜ" → "lü". Unlike [stripTones] this keeps the ü: lu and lü are
 * different sounds, and a sound series is exactly where that difference is the point. */
fun tonelessSyllable(pinyin: String): String =
    pinyin.trim().lowercase().map { TONE_MARKS[it] ?: it }.joinToString("")

// Longest first, so zh/ch/sh are tried before z/c/s. Same inventory as pipeline/pinyin.py.
private val INITIALS = listOf(
    "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
    "j", "q", "x", "r", "z", "c", "s", "y", "w",
)

private fun splitSyllable(syllable: String): Pair<String, String> {
    val initial = INITIALS.firstOrNull { syllable.startsWith(it) && syllable.length > it.length } ?: ""
    return initial to syllable.removePrefix(initial)
}

/** The closest [syllable] comes to any of the component's [own] toneless readings, or null
 * when the component has no reading to compare against. */
fun soundRelation(syllable: String, own: List<String>): SoundRelation? {
    if (own.isEmpty()) return null
    val (initial, final) = splitSyllable(syllable)
    return own.minOf { o ->
        val (oInitial, oFinal) = splitSyllable(o)
        when {
            o == syllable -> SoundRelation.SAME
            initial.isNotEmpty() && initial == oInitial -> SoundRelation.SAME_INITIAL
            final == oFinal -> SoundRelation.RHYME
            else -> SoundRelation.DIFFERENT
        }
    }
}

/**
 * [members] sorted into the syllables the component stands for, nearest to its own reading
 * first. Tone is ignored for grouping — each row still shows its own — because "does 青
 * still say qing here" is the question, and qīng/qíng/qǐng all answer yes.
 *
 * A syllable only one character has is noise as a heading, so those are gathered into one
 * leftovers group — unless it is the component's own sound, which is worth a heading even
 * for one character, or there is only one such stray, which reads better under its own name
 * than under "other".
 */
fun soundSeries(componentReadings: List<String>, members: List<FamilyMember>): List<FamilyGroup> {
    val own = componentReadings.map(::tonelessSyllable).distinct()
    val named = mutableListOf<FamilyGroup>()
    val strays = mutableListOf<FamilyGroup>()
    val silent = mutableListOf<FamilyMember>()
    for ((syllable, group) in members.sortedWith(byFrequency).groupBy { it.pinyin?.let(::tonelessSyllable) }) {
        if (syllable == null) { silent += group; continue }
        val relation = soundRelation(syllable, own)
        val g = FamilyGroup(syllable, relation, group)
        if (group.size == 1 && relation != SoundRelation.SAME) strays += g else named += g
    }
    if (strays.size == 1 && silent.isEmpty()) { named += strays; strays.clear() }

    val ordered = named.sortedWith(
        compareBy(
            { it.relation?.ordinal ?: Int.MAX_VALUE },
            { -it.members.size },
            { it.members.first().wordRank ?: Long.MAX_VALUE },
            { it.label },
        ),
    )
    val rest = (strays.flatMap { it.members } + silent).sortedWith(byFrequency)
    return if (rest.isEmpty()) ordered else ordered + FamilyGroup(null, null, rest, leftovers = true)
}

/** The series in one line, for the page header: "qing 5 · jing 3 · other 2". */
fun soundRange(groups: List<FamilyGroup>): String =
    groups.joinToString(" · ") { "${it.label ?: "other"} ${it.members.size}" }

/**
 * Hints that are one meaning under two spellings. makemeahanzi writes each character's hint
 * by hand, so 亻 is "person" 141 times and "people" 16 — a split that says nothing about how
 * the part is used. Only outright synonyms belong here: "heart"/"mind" (忄), "tree"/"wood"
 * (木) and "dog"/"animal" (犭) are real differences in what the part contributes and must
 * stay apart. A judgement table — extend it when a family shows a split that isn't one.
 */
private val HINT_SYNONYMS = mapOf(
    "people" to "person",
    "words" to "speech",
    "meat" to "flesh",
    "rock" to "stone",
    "ship" to "boat",
    "door" to "gate",
)

fun canonicalHint(hint: String): String = HINT_SYNONYMS[hint] ?: hint

/**
 * [members] sorted into the distinct things the component means, biggest use first.
 *
 * Most components mean one thing, and for those the honest page is a single plain list
 * headed by that meaning — inventing sub-uses would be worse than none. Groups appear only
 * when at least two meanings each have [MIN_USE_GROUP] characters behind them (月: moon,
 * flesh). Whatever is left — a meaning only one character has, or a character no source
 * gives a hint for — goes in a leftovers group, where a row shows its own hint if it has one.
 */
fun meaningFamily(members: List<FamilyMember>): List<FamilyGroup> {
    val sorted = members.sortedWith(byFrequency)
    if (sorted.isEmpty()) return emptyList()
    val uses = sorted.filter { it.hint != null }
        .groupBy { canonicalHint(it.hint!!) }
        .filterValues { it.size >= MIN_USE_GROUP }
    if (uses.size < 2) return listOf(FamilyGroup(uses.keys.singleOrNull(), null, sorted))

    val groups = uses.map { (use, group) -> FamilyGroup(use, null, group) }
        .sortedWith(compareBy({ -it.members.size }, { it.members.first().wordRank ?: Long.MAX_VALUE }, { it.label }))
    val rest = sorted.filter { m -> m.hint?.let(::canonicalHint) !in uses.keys }
    return if (rest.isEmpty()) groups else groups + FamilyGroup(null, null, rest, leftovers = true)
}

/** The rows of [group] to show before "show all": the [limit] most common, plus the
 * character the reader came from when it would otherwise be folded away — the page should
 * never hide the one member they already know is in it. */
fun visibleMembers(group: FamilyGroup, limit: Int, originGlyphId: String?): List<FamilyMember> {
    val top = group.members.take(limit)
    val origin = group.members.firstOrNull { it.glyphId == originGlyphId }
    return if (origin == null || origin in top) top else top + origin
}
