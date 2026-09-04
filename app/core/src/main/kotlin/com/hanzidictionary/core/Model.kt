package com.hanzidictionary.core

/** Domain models exposed to the UI layer. Deliberately free of SQLDelight/Android types
 * so this whole module stays KMP-portable (spec §9). */

data class SearchResult(
    val glyphId: String,
    val char: String,
    val primaryPinyin: String?,
    val definition: String?,   // short English gloss, shown in the result row
    val freqRank: Long?,
    val tradChar: String? = null, // traditional form folded into this row (輕 on 轻's row)
)

data class ReadingView(
    val system: String,   // pinyin|jyutping|mc_baxter|oc_zz|oc_bs|jp_on|jp_kun
    val value: String,
    val freq: Long?,
)

data class ComponentBreakdown(
    val componentId: String,   // literal component char, or "ids:<subtree>" pseudo-id
    val displayChar: String?,  // the char to render (null only for degenerate ids: forms)
    val position: Int,
    val function: String,      // semantic|phonetic|both|empty|unknown
    val matchClass: String?,   // exact|tone|initial|rime|oc_only|opaque
    val confidence: Double?,
    val navigable: Boolean,    // true if it has its own component page (not an ids: pseudo)
    val glyphId: String?,      // set when the component is itself a pack character, so a tap
                               // can open its character page rather than its component page
    val resolution: String,    // computed|agreement|single_source|conflict (spec §7 honesty)
    val definition: String?,   // the component's own standalone gloss
    val roleGloss: String?,    // what this part contributes here ("head" for 页 in 项), set
                               // only on meaning-bearing parts — a sound part contributes no
                               // meaning, so a role gloss on one would be a fiction
    val pinyin: String?,       // the component's primary reading — what a sound-component contributes
)

/** True when this component lends its character meaning — the parts a role gloss describes. */
fun isMeaningBearing(function: String): Boolean = function == "semantic" || function == "both"

/** True when this component is the one lending its character the sound.
 *
 * A component's reading is worth showing whatever its role — a part you can *say* is a part
 * you can remember — but only here is the reading the point, so this is what decides whether
 * it's set at full size or as a footnote under the gloss. */
fun isSoundBearing(function: String): Boolean = function == "phonetic" || function == "both"

data class ClaimView(
    val predicate: String,     // component_function|etymology_text|...
    val value: String,         // JSON string as stored
    val source: String,
    val confidence: Double?,
)

data class VariantView(
    val fromGlyph: String,     // traditional
    val toGlyph: String,       // simplified
    val fromChar: String,
    val toChar: String,
    val mappingType: String?,  // systematic|merger|irregular (unclassified)|...
)

data class ConfusableView(
    val glyphId: String,
    val char: String,
    val kind: String,          // visual
)

/** A word containing the page's character (CC-CEDICT, spec §9 IA item 6). */
data class WordView(
    val id: String,            // word/{id} route target
    val simp: String,
    val trad: String,          // may equal simp
    val pinyin: String?,
    val gloss: String?,        // first definition(s), pre-joined for display
    val hsk: Long?,
)

data class CharacterPage(
    val glyphId: String,
    val char: String,
    val scripts: String,       // simp|trad|both
    val tier: Int,             // 1 = has Middle+Old Chinese data (full drift analysis),
                               // 2 = modern-computed only (spec §6 honesty)
    val definition: String?,   // Unihan kDefinition — a classical gloss, so it can miss the
                               // sense a learner meets (项 is "neck, nape" here, not "item")
    val modernSenses: List<String>,  // CC-CEDICT senses; the gloss to lead with when present
    val strokeCount: Long?,
    val freqRank: Long?,
    val hsk: Long?,
    val readings: List<ReadingView>,
    val ids: String?,          // resolved decomposition IDS string
    val breakdown: List<ComponentBreakdown>,
    val words: List<WordView>, // words containing this character, learner-relevant first
    val claims: List<ClaimView>,
    val variants: List<VariantView>,
    val confusables: List<ConfusableView>,
)

data class SeriesMember(
    val glyphId: String,
    val char: String,
    val function: String,
    val matchClass: String?,
    val freqRank: Long?,
)

data class ComponentPage(
    val componentId: String,
    val char: String?,          // null for "ids:" pseudo-components
    val glyphId: String?,       // set when the component is itself a pack glyph, so the
                                // UI can link to its own character page
    val definition: String?,    // the component's own gloss, when it is a pack glyph
    val ownReadings: List<ReadingView>,
    val memberCount: Int,
    val reliability: Double?,
    val bestStratum: String?,   // pinyin|mc|oc
    val phoneticMembers: List<SeriesMember>,
    val semanticMembers: List<SeriesMember>,
)

/** A word matched by search (spec §9 ranking "word"). */
data class WordHit(
    val id: String,
    val simp: String,
    val trad: String,
    val pinyin: String?,
    val gloss: String?,
    val hsk: Long?,
)

/** One constituent character on a word page (spec §15 word page IA). */
data class WordCharView(
    val glyphId: String,
    val char: String,
    val definition: String?,
)

data class WordPage(
    val id: String,
    val simp: String,
    val trad: String,
    val pinyin: String?,
    val senses: List<String>,
    val hsk: Long?,
    val freqRank: Long?,            // corpus frequency rank (1 = most common), null if unranked
    val chars: List<WordCharView>,  // distinct constituent characters, in order
    val sentences: List<SentenceExample>,  // Tatoeba examples using this word
    val lookAlikes: List<LookAlike>,  // characters easily confused with this word's characters
)

/** A character a learner might confuse with another — a shape near-twin, a character sharing
 * a big component, or a phonetic-series sibling — carrying enough to teach the difference at
 * a glance. [sharedComponent] is the part the two have in common when that is *why* they were
 * paired (己/已 have no shared part to name, so it is null for shape pairs). */
data class LookAlike(
    val glyphId: String,
    val char: String,
    val pinyin: String?,
    val gloss: String?,
    val sharedComponent: String? = null,
)

/** A Tatoeba example sentence (spec §9 word page): the Mandarin, and its English gloss
 * where one is linked. */
data class SentenceExample(val zh: String, val en: String?)

/** A visually-confusable pair (Unihan kSpoofingVariant), for the confusable drill. */
data class ConfusablePair(
    val glyphA: String,
    val glyphB: String,
    val charA: String,
    val charB: String,
)

/** One option in the look-alike drill: a common member of a shared phonetic series
 * (清/情/晴 all carry 青), each with the pinyin+gloss that lets a wrong answer teach. */
data class DrillChoice(
    val glyphId: String,
    val char: String,
    val pinyin: String,
    val gloss: String,
)

/** A study item resolved to display text, whichever kind it is (spec §17). */
data class ItemSummary(
    val item: StudyItem,
    val text: String,           // the character, or the word in simplified form
    val secondaryText: String?, // traditional form, when it differs
    val pinyin: String?,
    val gloss: String?,
)

/** All source rows, for the provenance/attributions screen (spec §9 IA item 9, §15). */
data class SourceInfo(
    val id: String,
    val name: String,
    val license: String,
    val url: String,
    val version: String,
)
