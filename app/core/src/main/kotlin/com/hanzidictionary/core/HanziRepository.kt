package com.hanzidictionary.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.hanzidictionary.core.db.HanziDatabase

/**
 * Read-only access over a prebuilt pack. The platform supplies a [SqlDriver] pointed at
 * core.db and, optionally, a second [idsDriver] pointed at ids.db (cjkvi-ids
 * decompositions, kept in a separate file so the GPL layer stays separate per spec §15).
 *
 * ids.db is opened as its own driver rather than ATTACHed, deliberately: both Android's
 * SQLiteDatabase and SQLDelight's JDBC driver pool connections, and ATTACH does not
 * reliably propagate across a connection pool — a query can land on a connection that
 * never saw the ATTACH. Two drivers merged in Kotlin sidesteps that entirely, and keeps
 * the two license layers physically separate (they are never combined into one file).
 *
 * FTS5 search and the ids.decomposition lookup go through raw driver queries; everything
 * else uses generated SQLDelight queries.
 */
class HanziRepository(
    private val driver: SqlDriver,
    private val idsDriver: SqlDriver? = null,
) {
    private val db = HanziDatabase(driver)

    // ---- search ----

    fun search(query: String, limit: Int = 50): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val ordered = LinkedHashMap<String, SearchResult>()

        // 1. exact single-character hit ranks first (spec §9 "exact char > word > pinyin")
        if (q.length <= 2 && isSingleCjk(q)) {
            db.hanziQueries.glyphByChar(q).executeAsList().forEach { g ->
                ordered.putIfAbsent(g.id, g.toSearchResult())
            }
        }

        // 2. FTS: pinyin prefix and/or char, then rank by frequency (nulls last).
        // The FTS index is a search enhancement, not load-bearing: if the query engine
        // rejects it (e.g. a SQLite build without the fts5 module, or a malformed match)
        // degrade to the exact matches from step 1 rather than crashing the caller.
        val ftsMatch = buildFtsMatch(q) ?: return ordered.values.toList()
        try {
            val ftsGlyphIds = ftsQuery(ftsMatch, limit * 4)
            if (ftsGlyphIds.isNotEmpty()) {
                val glyphs = db.hanziQueries.glyphsByIds(ftsGlyphIds).executeAsList()
                    .sortedWith(compareBy({ it.freq_rank == null }, { it.freq_rank }))
                for (g in glyphs) ordered.putIfAbsent(g.id, g.toSearchResult())
            }
        } catch (e: Exception) {
            // Swallow: no FTS results, exact hits (if any) still returned below.
        }

        return ordered.values.take(limit).toList()
    }

    private fun buildFtsMatch(q: String): String? {
        val hasCjk = q.any { isCjkChar(it) }
        val latinTokens = q.lowercase().split(" ")
            .map { sanitizeFts(it) }
            .filter { t -> t.isNotEmpty() && t.any { it in 'a'..'z' } }

        val clauses = mutableListOf<String>()
        // A character has one pinyin syllable, so the pinyin clause only makes sense
        // for a single token; English glosses are multi-word, so the definition clause
        // ANDs all tokens (spec §9 ranking: pinyin and English FTS both feed search).
        if (latinTokens.size == 1) clauses += "pinyin:${latinTokens[0]}*"
        if (latinTokens.isNotEmpty()) {
            val defClause = latinTokens.joinToString(" AND ") { "definition:$it*" }
            clauses += if (latinTokens.size > 1) "($defClause)" else defClause
        }
        if (hasCjk) clauses += "char:${sanitizeFts(q)}"
        return clauses.joinToString(" OR ").ifEmpty { null }
    }

    private fun ftsQuery(match: String, limit: Int): Collection<String> {
        val ids = LinkedHashSet<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT glyph_id FROM glyph_fts WHERE glyph_fts MATCH ? LIMIT ?",
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { ids.add(it) }
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, match)
            bindLong(1, limit.toLong())
        }.value
        return ids
    }

    // ---- character page ----

    fun characterPage(glyphId: String): CharacterPage? {
        val g = db.hanziQueries.glyphById(glyphId).executeAsOneOrNull() ?: return null

        val readings = db.hanziQueries.readingsForGlyph(glyphId).executeAsList()
            .map { ReadingView(it.system, it.value_, it.freq) }
            .sortedWith(compareBy({ systemOrder(it.system) }, { it.freq?.let { f -> -f } ?: 0L }))

        val breakdown = db.hanziQueries.componentUseForGlyph(glyphId).executeAsList().map { cu ->
            val isPseudo = cu.component_id.startsWith("ids:")
            // The component's own gloss and reading are what it contributes to the
            // character (meaning for a semantic part, sound for a phonetic one) — worth
            // two point lookups for the 2-3 components a breakdown has.
            val compGlyph = if (isPseudo) null else
                db.hanziQueries.glyphByChar(cu.component_id).executeAsList().firstOrNull()
            val compPinyin = compGlyph?.let { g ->
                db.hanziQueries.readingsForGlyph(g.id).executeAsList()
                    .filter { it.system == "pinyin" }
                    .maxByOrNull { it.freq ?: 0L }?.value_
            }
            ComponentBreakdown(
                componentId = cu.component_id,
                displayChar = if (isPseudo) null else cu.component_id,
                position = cu.position.toInt(),
                function = cu.function,
                matchClass = cu.match_class,
                confidence = cu.confidence,
                navigable = !isPseudo,
                definition = compGlyph?.definition,
                pinyin = compPinyin,
            )
        }

        val claims = db.hanziQueries.claimsForSubject(glyphId).executeAsList()
            .map { ClaimView(it.predicate, it.value_, it.source_id, it.confidence) }

        val variants = buildVariants(glyphId)
        val confusables = buildConfusables(glyphId)

        return CharacterPage(
            glyphId = g.id,
            char = g.char,
            scripts = g.scripts,
            definition = g.definition,
            strokeCount = g.stroke_count,
            freqRank = g.freq_rank,
            hsk = g.hsk,
            readings = readings,
            ids = resolvedIds(glyphId),
            breakdown = breakdown,
            words = wordsForGlyph(glyphId),
            claims = claims,
            variants = variants,
            confusables = confusables,
        )
    }

    /** Words containing the character, learner-relevant first (HSK level, then length).
     * defs is a JSON array of CC-CEDICT senses; the first two make a compact gloss. */
    fun wordsForGlyph(glyphId: String, limit: Long = 6): List<WordView> =
        db.hanziQueries.wordsForGlyph(glyphId, limit).executeAsList().map { w ->
            WordView(w.simp, w.trad, w.pinyin, firstSenses(w.defs), w.hsk)
        }

    private fun firstSenses(defsJson: String?, count: Int = 2): String? {
        if (defsJson == null) return null
        // Minimal JSON-array-of-strings reader; kept dependency-free so core stays
        // KMP-portable (no org.json on the JVM-only test path either).
        val senses = mutableListOf<String>()
        var i = defsJson.indexOf('"')
        while (i >= 0 && senses.size < count) {
            val sb = StringBuilder()
            var j = i + 1
            while (j < defsJson.length && defsJson[j] != '"') {
                if (defsJson[j] == '\\' && j + 1 < defsJson.length) {
                    j++
                    sb.append(
                        when (defsJson[j]) {
                            'n' -> '\n'; 't' -> '\t'; 'u' -> {
                                val code = defsJson.substring(j + 1, j + 5).toInt(16)
                                j += 4
                                code.toChar()
                            }
                            else -> defsJson[j]
                        },
                    )
                } else {
                    sb.append(defsJson[j])
                }
                j++
            }
            senses.add(sb.toString())
            i = defsJson.indexOf('"', j + 1)
        }
        return senses.joinToString("; ").ifEmpty { null }
    }

    private fun buildConfusables(glyphId: String): List<ConfusableView> =
        db.hanziQueries.confusablesFor(glyphId).executeAsList().map { row ->
            val otherId = if (row.glyph_a == glyphId) row.glyph_b else row.glyph_a
            ConfusableView(otherId, charOfGlyphId(otherId), row.kind)
        }

    fun characterPageByChar(char: String): CharacterPage? {
        val g = db.hanziQueries.glyphByChar(char).executeAsList().firstOrNull() ?: return null
        return characterPage(g.id)
    }

    private fun buildVariants(glyphId: String): List<VariantView> {
        val edges = (db.hanziQueries.variantsFrom(glyphId).executeAsList() +
            db.hanziQueries.variantsTo(glyphId).executeAsList()).distinct()
        return edges.map { e ->
            VariantView(
                fromGlyph = e.from_glyph,
                toGlyph = e.to_glyph,
                fromChar = charOfGlyphId(e.from_glyph),
                toChar = charOfGlyphId(e.to_glyph),
                mappingType = e.mapping_type,
            )
        }
    }

    /** Prefer the cjkvi-ids decomposition (ids.db) matching pipeline merge priority;
     * fall back to the makemeahanzi rows carried in core.db. */
    private fun resolvedIds(glyphId: String): String? {
        idsDriver?.let { d ->
            rawSingleString(d, "SELECT ids FROM decomposition WHERE glyph_id = ? LIMIT 1", glyphId)
                ?.let { return it }
        }
        return db.hanziQueries.decompositionsForGlyph(glyphId).executeAsList().firstOrNull()?.ids
    }

    // ---- component page ----

    /** [knownIds] reorders each series known-first (spec §1, §9): characters the user
     * already knows float to the front, then by frequency. */
    fun componentPage(componentId: String, knownIds: Set<String> = emptySet()): ComponentPage {
        val members = db.hanziQueries.charactersUsingComponent(componentId).executeAsList()

        // known first (false sorts before true), then known freq rank, nulls last
        val order = compareBy<SeriesMember>(
            { it.glyphId !in knownIds },
            { it.freqRank == null },
            { it.freqRank ?: Long.MAX_VALUE },
        )

        val phonetic = members.filter { it.function == "phonetic" || it.function == "both" }
            .map { SeriesMember(it.glyphId, it.char, it.function, it.matchClass, it.freqRank) }
            .sortedWith(order)
        val semantic = members.filter { it.function == "semantic" || it.function == "both" }
            .map { SeriesMember(it.glyphId, it.char, it.function, it.matchClass, it.freqRank) }
            .sortedWith(order)

        val series = db.hanziQueries.phoneticSeriesFor(componentId).executeAsOneOrNull()

        val isPseudo = componentId.startsWith("ids:")
        // A component that is itself a pack glyph (青, 尾, …) gets a link to its own
        // character page plus its gloss; radicals-only forms (氵) usually aren't glyphs.
        val ownGlyph = if (isPseudo) null else db.hanziQueries.glyphByChar(componentId).executeAsList().firstOrNull()
        val ownReadings = ownGlyph?.let { g ->
            db.hanziQueries.readingsForGlyph(g.id).executeAsList()
                .map { ReadingView(it.system, it.value_, it.freq) }
                .sortedWith(compareBy({ systemOrder(it.system) }, { it.freq?.let { f -> -f } ?: 0L }))
        } ?: emptyList()

        return ComponentPage(
            componentId = componentId,
            char = if (isPseudo) null else componentId,
            glyphId = ownGlyph?.id,
            definition = ownGlyph?.definition,
            ownReadings = ownReadings,
            memberCount = series?.member_count?.toInt() ?: members.size,
            reliability = series?.reliability,
            bestStratum = series?.best_stratum,
            phoneticMembers = phonetic,
            semanticMembers = semantic,
        )
    }

    /** Of [ids], the subset that exist as glyphs in the pack — used to drop dead links
     * (e.g. an OCR'd character outside the T1/T2 coverage) before showing them. */
    fun existingGlyphIds(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return db.hanziQueries.glyphsByIds(ids.toList()).executeAsList().map { it.id }.toSet()
    }

    /** [ids] reordered most-common-first (freq_rank asc, unranked last) — study decks
     * should serve frequent characters before rare ones. Chunked to stay under SQLite's
     * bound-parameter limit (an HSK band can exceed it). */
    fun sortByFrequency(ids: Collection<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val rank = mutableMapOf<String, Long>()
        for (chunk in ids.chunked(500)) {
            db.hanziQueries.glyphsByIds(chunk).executeAsList().forEach { g ->
                rank[g.id] = g.freq_rank ?: Long.MAX_VALUE
            }
        }
        return ids.filter { it in rank }.sortedBy { rank[it] }
    }

    /** Every visually-confusable pair in the pack (drill source). */
    fun confusablePairs(): List<ConfusablePair> =
        db.hanziQueries.allConfusables().executeAsList().map {
            ConfusablePair(it.glyph_a, it.glyph_b, charOfGlyphId(it.glyph_a), charOfGlyphId(it.glyph_b))
        }

    // ---- sources / provenance ----

    fun sources(): List<SourceInfo> =
        db.hanziQueries.allSources().executeAsList()
            .map { SourceInfo(it.id, it.name, it.license, it.url, it.version) }

    // ---- helpers ----

    private fun rawSingleString(target: SqlDriver, sql: String, arg: String): String? {
        var result: String? = null
        target.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor: SqlCursor ->
                if (cursor.next().value) result = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, arg) }.value
        return result
    }

    private fun com.hanzidictionary.core.db.Glyph.toSearchResult(): SearchResult {
        val py = db.hanziQueries.readingsForGlyph(id).executeAsList()
            .filter { it.system == "pinyin" }
            .maxByOrNull { it.freq ?: 0L }?.value_
        return SearchResult(id, char, py, definition, freq_rank)
    }
}

// ---- pure helpers (no driver / no Android) ----

fun charOfGlyphId(glyphId: String): String {
    val cp = glyphId.removePrefix("U+").toInt(16)
    return String(Character.toChars(cp))
}

fun glyphIdOfChar(char: String): String {
    val cp = char.codePointAt(0)
    return "U+%04X".format(cp)
}

private fun isCjkChar(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}

private fun isSingleCjk(s: String): Boolean =
    s.codePointCount(0, s.length) == 1 && s.codePointAt(0) >= 0x3400

/** FTS5 is fed a bareword; strip anything that could be read as query syntax. */
private fun sanitizeFts(q: String): String = q.filter { it.isLetterOrDigit() || isCjkChar(it) }

private fun systemOrder(system: String): Int = when (system) {
    "pinyin" -> 0
    "jyutping" -> 1
    "mc_baxter" -> 2
    "oc_zz" -> 3
    "oc_bs" -> 4
    else -> 5
}
