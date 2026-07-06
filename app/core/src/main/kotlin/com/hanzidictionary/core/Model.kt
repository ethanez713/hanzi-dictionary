package com.hanzidictionary.core

/** Domain models exposed to the UI layer. Deliberately free of SQLDelight/Android types
 * so this whole module stays KMP-portable (spec §9). */

data class SearchResult(
    val glyphId: String,
    val char: String,
    val primaryPinyin: String?,
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
    val matchClass: String?,   // exact|tone|initial|rime|oc_only
    val confidence: Double?,
    val navigable: Boolean,    // true if it has its own component page (not an ids: pseudo)
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

data class CharacterPage(
    val glyphId: String,
    val char: String,
    val scripts: String,       // simp|trad|both
    val strokeCount: Long?,
    val freqRank: Long?,
    val hsk: Long?,
    val readings: List<ReadingView>,
    val ids: String?,          // resolved decomposition IDS string
    val breakdown: List<ComponentBreakdown>,
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
    val ownReadings: List<ReadingView>,
    val memberCount: Int,
    val reliability: Double?,
    val bestStratum: String?,   // pinyin|mc|oc
    val phoneticMembers: List<SeriesMember>,
    val semanticMembers: List<SeriesMember>,
)

/** All source rows, for the provenance/attributions screen (spec §9 IA item 9, §15). */
data class SourceInfo(
    val id: String,
    val name: String,
    val license: String,
    val url: String,
    val version: String,
)
