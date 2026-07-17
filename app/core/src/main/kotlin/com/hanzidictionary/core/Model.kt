package com.hanzidictionary.core

/** Domain models exposed to the UI layer. Deliberately free of SQLDelight/Android types
 * so this whole module stays KMP-portable (spec §9). */

data class SearchResult(
    val glyphId: String,
    val char: String,
    val primaryPinyin: String?,
    val definition: String?,   // short English gloss, shown in the result row
    val freqRank: Long?,
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
    val resolution: String,    // computed|agreement|single_source|conflict (spec §7 honesty)
    val definition: String?,   // the component's own gloss — what a meaning-component contributes
    val pinyin: String?,       // the component's primary reading — what a sound-component contributes
)

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
    val definition: String?,   // short English gloss (Unihan kDefinition)
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
    val chars: List<WordCharView>,  // distinct constituent characters, in order
)

/** A visually-confusable pair (Unihan kSpoofingVariant), for the confusable drill. */
data class ConfusablePair(
    val glyphA: String,
    val glyphB: String,
    val charA: String,
    val charB: String,
)

/** All source rows, for the provenance/attributions screen (spec §9 IA item 9, §15). */
data class SourceInfo(
    val id: String,
    val name: String,
    val license: String,
    val url: String,
    val version: String,
)
