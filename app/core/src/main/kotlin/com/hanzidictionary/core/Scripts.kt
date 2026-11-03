package com.hanzidictionary.core

/** One character in the other script, with its pack id so a page can link to it. */
data class ScriptForm(val glyphId: String, val char: String)

/**
 * A character's counterpart(s) in the other script: 轻 → traditional 輕, 輕 → simplified 轻.
 * [traditional] says which way the [forms] point. A simplified character can stand for
 * several traditional ones (发 → 發 髮), so [forms] is a list.
 */
data class OtherScript(val traditional: Boolean, val forms: List<ScriptForm>)

/**
 * Reads [glyphId]'s counterpart off its simplification edges ([CharacterPage.variants]).
 * The traditional direction wins when a character has both: the app teaches simplified, so
 * a simplified character's traditional forms are the thing worth showing. Null when the
 * character is written the same in both scripts.
 */
fun otherScript(glyphId: String, variants: List<VariantView>): OtherScript? {
    val trad = variants
        .filter { it.toGlyph == glyphId && it.fromGlyph != glyphId }
        .map { ScriptForm(it.fromGlyph, it.fromChar) }
        .distinct()
    if (trad.isNotEmpty()) return OtherScript(traditional = true, forms = trad)
    val simp = variants
        .filter { it.fromGlyph == glyphId && it.toGlyph != glyphId }
        .map { ScriptForm(it.toGlyph, it.toChar) }
        .distinct()
    if (simp.isNotEmpty()) return OtherScript(traditional = false, forms = simp)
    return null
}

/** True for a character used only in traditional writing (輕, 後) — what the photo feed's
 * "Traditional" toggle hides. A character shared by both scripts (人) is never hidden. */
val SearchResult.isTraditionalOnly: Boolean get() = scripts == "trad"

/** The photo feed with traditional-only characters dropped unless [showTraditional]. */
fun filterScripts(finds: List<SearchResult>, showTraditional: Boolean): List<SearchResult> =
    if (showTraditional) finds else finds.filterNot { it.isTraditionalOnly }
