package com.hanzidictionary.core

import java.text.Normalizer

/** "qīng" → "qing": NFD-decompose, drop combining marks, lowercase. Matches what the
 * FTS tokenizer (unicode61, remove_diacritics) does to the indexed pinyin. */
fun stripTones(pinyin: String): String =
    Normalizer.normalize(pinyin, Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .lowercase()

/**
 * Split run-together pinyin ("qingchu") into syllables, using the bare syllable
 * inventory derived from the pack's own readings. Learners type multi-syllable pinyin
 * without spaces constantly; FTS needs the space-separated form.
 *
 * Longest-match first with backtracking. The FINAL segment may be a bare prefix of a
 * valid syllable so a half-typed query ("qingch") still segments while typing.
 * Returns null when the token isn't fully segmentable or yields fewer than two
 * segments — callers then treat the token as a single syllable/English word.
 */
fun segmentPinyin(token: String, syllables: Set<String>): List<String>? {
    if (syllables.isEmpty()) return null
    // An apostrophe is an explicit syllable boundary (xi'an = xi + an, not xian).
    val pieces = token.lowercase().split("'").filter { it.isNotEmpty() }
    if (pieces.isEmpty()) return null

    val out = mutableListOf<String>()
    for ((i, piece) in pieces.withIndex()) {
        if (piece.any { it !in 'a'..'z' }) return null
        val segs = segmentPiece(piece, syllables, allowTrailingPartial = i == pieces.lastIndex) ?: return null
        out += segs
    }
    return out.takeIf { it.size >= 2 }
}

/**
 * Fuzzy fallback variants for a pinyin token that returned nothing (spec §9 ranking:
 * "… > English FTS > fuzzy"). Two error families, bounded to a small ordered list:
 * adjacent-letter transpositions ("qign" → "qing") first — plain typos are the
 * likeliest miss — then the classic fuzzy-pinyin confusions (zh/z, ch/c, sh/s, l/n
 * initials; ang/an, eng/en, ing/in finals). Callers try variants in order and stop
 * at the first that matches.
 */
fun fuzzyPinyinVariants(token: String): List<String> {
    val t = token.lowercase()
    if (t.length < 2 || t.any { it !in 'a'..'z' }) return emptyList()
    val out = LinkedHashSet<String>()

    for (i in 0 until t.length - 1) {
        if (t[i] != t[i + 1]) {
            out.add(t.substring(0, i) + t[i + 1] + t[i] + t.substring(i + 2))
        }
    }

    // Retroflex/dental initials, l/n: both directions.
    when {
        t.startsWith("zh") -> out.add("z" + t.drop(2))
        t.startsWith("ch") -> out.add("c" + t.drop(2))
        t.startsWith("sh") -> out.add("s" + t.drop(2))
        t.startsWith("z") -> out.add("zh" + t.drop(1))
        t.startsWith("c") -> out.add("ch" + t.drop(1))
        t.startsWith("s") -> out.add("sh" + t.drop(1))
    }
    when {
        t.startsWith("l") -> out.add("n" + t.drop(1))
        t.startsWith("n") -> out.add("l" + t.drop(1))
    }

    // Nasal finals: both directions.
    when {
        t.endsWith("ang") -> out.add(t.dropLast(3) + "an")
        t.endsWith("eng") -> out.add(t.dropLast(3) + "en")
        t.endsWith("ing") -> out.add(t.dropLast(3) + "in")
        t.endsWith("an") -> out.add(t.dropLast(2) + "ang")
        t.endsWith("en") -> out.add(t.dropLast(2) + "eng")
        t.endsWith("in") -> out.add(t.dropLast(2) + "ing")
    }

    out.remove(t)
    return out.toList()
}

private fun segmentPiece(piece: String, syllables: Set<String>, allowTrailingPartial: Boolean): List<String>? {
    val maxLen = syllables.maxOf { it.length }

    fun solve(pos: Int): List<String>? {
        if (pos == piece.length) return emptyList()
        for (len in minOf(piece.length - pos, maxLen) downTo 1) {
            val part = piece.substring(pos, pos + len)
            if (part in syllables) {
                solve(pos + len)?.let { return listOf(part) + it }
            }
        }
        val rest = piece.substring(pos)
        return if (allowTrailingPartial && syllables.any { it.length > rest.length && it.startsWith(rest) }) {
            listOf(rest)
        } else {
            null
        }
    }

    return solve(0)
}
