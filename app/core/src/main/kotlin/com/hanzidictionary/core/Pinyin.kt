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
