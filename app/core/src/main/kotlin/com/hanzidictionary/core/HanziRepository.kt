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

        // 1. exact character hits rank first (spec §9 "exact char > word > pinyin").
        // A multi-character query (pasted or shared text like 你好吗) lists each
        // distinct character in text order.
        for (ch in extractHanzi(q)) {
            db.hanziQueries.glyphByChar(ch).executeAsList().forEach { g ->
                ordered.putIfAbsent(g.id, g.toSearchResult())
            }
        }

        // 2. FTS: pinyin prefix and/or char, then rank by frequency (nulls last).
        // The FTS index is a search enhancement, not load-bearing: if the query engine
        // rejects it (e.g. a SQLite build without the fts5 module, or a malformed match)
        // degrade to the exact matches from step 1 rather than crashing the caller.
        fun addFtsGlyphs(match: String): Boolean = try {
            val ftsGlyphIds = ftsQuery(match, limit * 4)
            val glyphs = db.hanziQueries.glyphsByIds(ftsGlyphIds).executeAsList()
                .sortedWith(compareBy({ it.freq_rank == null }, { it.freq_rank }))
            for (g in glyphs) ordered.putIfAbsent(g.id, g.toSearchResult())
            ftsGlyphIds.isNotEmpty()
        } catch (e: Exception) {
            // Swallow: no FTS results, exact hits (if any) still returned below.
            false
        }
        buildFtsMatch(q)?.let { addFtsGlyphs(it) }

        // 3. Fuzzy fallback (spec §9 "… > English FTS > fuzzy"): a single pinyin-ish
        // token with zero hits retries typo/fuzzy-pinyin variants, first match wins.
        if (ordered.isEmpty()) {
            singlePinyinToken(q)?.let { token ->
                for (variant in fuzzyPinyinVariants(token)) {
                    if (addFtsGlyphs("pinyin:$variant*")) break
                }
            }
        }

        // Fold traditional forms into their simplified counterpart's row (輕 into 轻):
        // when both scripts match, two rows with identical glosses just pad the list.
        if (ordered.size > 1) {
            val edges = db.hanziQueries.simplificationEdgesAmong(ordered.keys.toList()).executeAsList()
            for (e in edges) {
                val trad = ordered[e.from_glyph] ?: continue
                val simp = ordered[e.to_glyph] ?: continue
                ordered[e.to_glyph] = simp.copy(tradChar = trad.char)
                ordered.remove(e.from_glyph)
            }
        }

        return ordered.values.take(limit).toList()
    }

    /** The query's single latin token when it plausibly IS pinyin, else null. */
    private fun singlePinyinToken(q: String): String? {
        if (q.any { isCjkChar(it) }) return null
        val tokens = q.lowercase().split(" ").map { sanitizeFts(it) }.filter { it.isNotEmpty() }
        return tokens.singleOrNull()?.takeIf { t -> t.all { it in 'a'..'z' } }
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
        // 'v' is not valid pinyin — learners type it for ü ("lv" → 律), and the FTS
        // tokenizer strips ü's diaeresis to plain u.
        if (latinTokens.size == 1) clauses += "pinyin:${latinTokens[0].replace('v', 'u')}*"
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
                resolution = cu.resolution,
                definition = compGlyph?.definition,
                pinyin = compPinyin,
            )
        }

        val claims = db.hanziQueries.claimsForSubject(glyphId).executeAsList()
            .map { ClaimView(it.predicate, it.value_, it.source_id, it.confidence) }

        val variants = buildVariants(glyphId)
        val confusables = buildConfusables(glyphId)

        // Coverage tier (spec §6, shown honestly): T1 characters carry Middle + Old
        // Chinese readings, so their drift analysis spans all strata; T2 is
        // modern-computed only.
        val tier = if (readings.any { it.system == "mc_baxter" } &&
            readings.any { it.system == "oc_zz" || it.system == "oc_bs" }
        ) 1 else 2

        return CharacterPage(
            glyphId = g.id,
            char = g.char,
            scripts = g.scripts,
            tier = tier,
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
            WordView(w.id, w.simp, w.trad, w.pinyin, firstSenses(w.defs), w.hsk)
        }

    private fun firstSenses(defsJson: String?, count: Int = 2): String? =
        parseSenses(defsJson).take(count).joinToString("; ").ifEmpty { null }

    /** All senses out of the stored JSON array-of-strings. Minimal hand parser; kept
     * dependency-free so core stays KMP-portable (no org.json on the JVM test path). */
    private fun parseSenses(defsJson: String?): List<String> {
        if (defsJson == null) return emptyList()
        val senses = mutableListOf<String>()
        var i = defsJson.indexOf('"')
        while (i >= 0) {
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
        return senses
    }

    // ---- word search / word page ----

    /** Words matching [query] — CJK matches simp/trad by prefix, latin matches pinyin
     * syllables or definition words (all tokens ANDed within a field). Run-together
     * pinyin ("qingchu") is segmented against the pack's own syllable inventory.
     *
     * Field priority: pinyin-field matches rank above defs-field matches — a pinyin
     * query must not surface words only because their English gloss mentions
     * "Qinghai". Within each field the ranking is learner-first IN SQL over the full
     * match set (a LIMIT before ranking buried every common word): HSK level, then
     * proper nouns last (CC-CEDICT capitalizes their pinyin), then word length. */
    fun searchWords(query: String, limit: Int = 20): List<WordHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hasCjk = q.any { isCjkChar(it) }
        val latinTokens = q.lowercase().split(" ")
            .map { sanitizeFts(it) }
            .filter { t -> t.isNotEmpty() && t.any { it in 'a'..'z' } }

        val ids = LinkedHashSet<String>()
        fun collect(match: String, demoteProperNouns: Boolean = false) {
            if (ids.size >= limit) return
            try {
                ids += rankedWordIds(match, limit.toLong(), demoteProperNouns)
            } catch (e: Exception) {
                // FTS unavailable or malformed match: skip this clause, keep the rest.
            }
        }

        if (hasCjk) {
            val cjk = sanitizeFts(q)
            collect("(simp:$cjk* OR trad:$cjk*)")
            // Greedy fallback for pasted/shared phrases: 你好吗 isn't an entry, but its
            // longest matching prefix 你好 is — shorten until something matches.
            var prefix = q.filter { isCjkChar(it) }
            while (ids.isEmpty() && prefix.length > 1) {
                prefix = prefix.dropLast(1)
                collect("(simp:$prefix* OR trad:$prefix*)")
            }
        }
        if (latinTokens.isNotEmpty()) {
            val syllables =
                if (latinTokens.size == 1) segmentPinyin(latinTokens[0], pinyinSyllables) ?: latinTokens
                else latinTokens
            // 'v' stands in for ü ("lv", "nv") and never appears in real pinyin.
            collect("(" + syllables.joinToString(" AND ") { "pinyin:${it.replace('v', 'u')}*" } + ")")
            // English-gloss matches demote proper nouns: "qing" must not surface
            // Qinghai counties. Sound matches don't — 中国 is capitalized AND common.
            collect("(" + latinTokens.joinToString(" AND ") { "defs:$it*" } + ")", demoteProperNouns = true)
            // Fuzzy fallback, same variant order as character search.
            if (ids.isEmpty()) {
                latinTokens.singleOrNull()?.let { token ->
                    for (variant in fuzzyPinyinVariants(token)) {
                        collect("pinyin:$variant*")
                        if (ids.isNotEmpty()) break
                    }
                }
            }
        }
        if (ids.isEmpty()) return emptyList()

        val rows = db.hanziQueries.wordsByIds(ids.toList()).executeAsList().associateBy { it.id }
        return ids.mapNotNull { rows[it] }
            .take(limit)
            .map { WordHit(it.id, it.simp, it.trad, it.pinyin, firstSenses(it.defs), it.hsk) }
    }

    /** Word ids for one FTS clause, ranked over the FULL match set in SQL:
     * corpus frequency first (OpenSubtitles, the user's "sorted by popularity"),
     * HSK as tiebreak and as fallback for the ~40% of words the corpus never saw. */
    private fun rankedWordIds(match: String, limit: Long, demoteProperNouns: Boolean = false): List<String> {
        val properNounTerm =
            if (demoteProperNouns) "(word.pinyin IS NULL), (word.pinyin GLOB '*[A-Z]*')," else ""
        val out = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT word.id FROM word_fts JOIN word ON word.id = word_fts.word_id
                WHERE word_fts MATCH ?
                ORDER BY $properNounTerm
                         (word.freq_rank IS NULL), word.freq_rank,
                         (word.hsk IS NULL), word.hsk,
                         LENGTH(word.simp), word.pinyin
                LIMIT ?
            """.trimIndent(),
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { out.add(it) }
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, match)
            bindLong(1, limit)
        }.value
        return out
    }

    /** Bare pinyin syllable inventory derived from the pack's own readings — the
     * segmentation dictionary for run-together pinyin queries. */
    private val pinyinSyllables: Set<String> by lazy {
        val out = mutableSetOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT DISTINCT value FROM reading WHERE system = 'pinyin'",
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { out.add(stripTones(it)) }
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ) { }.value
        out.filter { s -> s.isNotEmpty() && s.all { it in 'a'..'z' } }.toSet()
    }

    fun wordPage(wordId: String): WordPage? {
        val w = db.hanziQueries.wordById(wordId).executeAsOneOrNull() ?: return null
        // Constituent characters of both forms, first-seen order, deduped (说话/說話
        // shares 话/話 positions but the strip should show each distinct char once).
        // extractHanzi is code-point-correct for supplementary-plane characters.
        val charViews = extractHanzi(w.simp + w.trad).mapNotNull { ch ->
            db.hanziQueries.glyphByChar(ch).executeAsList().firstOrNull()
                ?.let { WordCharView(it.id, it.char, it.definition) }
        }
        val sentences = db.hanziQueries.sentencesForWord(w.simp, SENTENCE_LIMIT).executeAsList()
            .map { SentenceExample(it.zh, it.en) }
        val lookAlikes = lookAlikesForGlyphs(charViews.map { it.glyphId })
        return WordPage(
            w.id, w.simp, w.trad, w.pinyin, parseSenses(w.defs), w.hsk, w.freq_rank,
            charViews, sentences, lookAlikes,
        )
    }

    /**
     * Characters a learner might confuse with a word's [glyphIds]: visually-confusable pairs
     * (Unihan kSpoofingVariant) first — the highest-signal, rarest ones — then phonetic-series
     * siblings (share a phonetic component: 请/清/晴/情 all carry 青), common ones first. Deduped
     * by glyph, the word's own characters excluded, each carrying pinyin + a short gloss.
     */
    fun lookAlikesForGlyphs(
        glyphIds: List<String>,
        maxRank: Long = LOOKALIKE_MAX_RANK,
        limit: Int = LOOKALIKE_LIMIT,
    ): List<LookAlike> {
        if (glyphIds.isEmpty()) return emptyList()
        val self = glyphIds.toSet()
        val out = LinkedHashMap<String, LookAlike>()

        fun enrich(glyphId: String): LookAlike? {
            if (glyphId in self || glyphId in out) return null
            val g = db.hanziQueries.glyphWithPinyin(glyphId).executeAsOneOrNull() ?: return null
            return LookAlike(g.id, g.char, g.pinyin, g.definition?.let(::firstSense))
        }

        // Visual confusables of each character first (rare, so high-signal).
        for (gid in glyphIds) {
            for (row in db.hanziQueries.confusablesFor(gid).executeAsList()) {
                val other = if (row.glyph_a == gid) row.glyph_b else row.glyph_a
                enrich(other)?.let { out[it.glyphId] = it }
            }
        }
        // Then phonetic-series siblings, common ones first, until we hit the cap. Over-fetch
        // so self-exclusion and dedupe against the confusables above still leave enough to fill.
        val pool = (limit * LOOKALIKE_POOL_FACTOR).toLong()
        for (row in db.hanziQueries.seriesSiblings(glyphIds, maxRank, pool).executeAsList()) {
            if (out.size >= limit) break
            if (row.id in self || row.id in out) continue
            out[row.id] = LookAlike(row.id, row.char, row.pinyin, row.definition?.let(::firstSense))
        }
        return out.values.take(limit)
    }

    private companion object {
        /** A few examples is plenty on a word page; more would bury the definitions. */
        const val SENTENCE_LIMIT = 4L

        /** Word-page look-alikes: how many to show, the frequency ceiling for series
         * siblings (keep them to characters a learner actually meets), and how far to
         * over-fetch the candidate pool before deduping down to the cap. */
        const val LOOKALIKE_LIMIT = 12
        const val LOOKALIKE_MAX_RANK = 4000L
        const val LOOKALIKE_POOL_FACTOR = 4
    }

    private fun rawStringList(target: SqlDriver, sql: String, arg: String, limit: Long): List<String> {
        val out = mutableListOf<String>()
        target.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { out.add(it) }
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, arg)
            bindLong(1, limit)
        }.value
        return out
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

    /** Every visually-confusable pair in the pack (kSpoofingVariant). Kept for the
     * character page's "Easily confused with"; too rare-skewed to feed the drill. */
    fun confusablePairs(): List<ConfusablePair> =
        db.hanziQueries.allConfusables().executeAsList().map {
            ConfusablePair(it.glyph_a, it.glyph_b, charOfGlyphId(it.glyph_a), charOfGlyphId(it.glyph_b))
        }

    /**
     * Look-alike groups for the drill: common characters (freq_rank ≤ [maxRank]) that
     * share one component (清/情/晴 all carry 青) — the visual confusion a learner
     * actually faces. Each member needs a pinyin and a distinct first gloss so the
     * cue is unambiguous and a wrong answer teaches something. The pack's
     * kSpoofingVariant pairs can't feed this: of its 75 pairs exactly one is between
     * common characters.
     */
    fun drillSeries(maxRank: Long = 3000): List<List<DrillChoice>> {
        data class Row(val comp: String, val id: String, val char: String, val def: String?, val py: String?)
        val rows = mutableListOf<Row>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT cu.component_id, g.id, g.char, g.definition,
                       (SELECT r.value FROM reading r
                        WHERE r.glyph_id = g.id AND r.system = 'pinyin'
                        ORDER BY (r.freq IS NULL), r.freq DESC LIMIT 1)
                FROM component_use cu JOIN glyph g ON g.id = cu.glyph_id
                WHERE g.freq_rank <= ? AND cu.component_id NOT LIKE 'ids:%'
            """.trimIndent(),
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    rows.add(
                        Row(
                            cursor.getString(0)!!, cursor.getString(1)!!, cursor.getString(2)!!,
                            cursor.getString(3), cursor.getString(4),
                        ),
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindLong(0, maxRank) }.value

        return rows.groupBy { it.comp }.values.mapNotNull { members ->
            members.asSequence()
                .filter { it.def != null && it.py != null }
                .distinctBy { it.char }
                .map { DrillChoice(it.id, it.char, it.py!!, firstSense(it.def!!)) }
                .distinctBy { it.gloss.lowercase() }
                .toList()
                .takeIf { it.size >= 2 }
        }
    }

    /**
     * Component-compose search (spec §9 / M3.7): characters that contain EVERY one of
     * [parts], most-common first — for when the user can see a character's pieces
     * (氵 + 青 → 清) but can't type the whole thing. Each part also matches through
     * its positional variants (水 finds 氵-characters and vice versa) so the user
     * doesn't need to know which form the index stores.
     */
    fun composeSearch(parts: List<String>, limit: Int = 50): List<SearchResult> {
        if (parts.isEmpty()) return emptyList()
        var ids: Set<String>? = null
        for (part in parts.distinct()) {
            val forms = (listOf(part) + radicalFormsOf(part) + listOfNotNull(radicalParentOf(part))).distinct()
            val members = forms.flatMap { comp ->
                db.hanziQueries.charactersUsingComponent(comp).executeAsList().map { it.glyphId }
            }.toSet()
            ids = ids?.intersect(members) ?: members
            if (ids.isEmpty()) return emptyList()
        }
        return glyphSummaries(sortByFrequency(ids!!).take(limit))
    }

    /** [ids] as search-row summaries (char + pinyin + gloss), preserving input order —
     * for lists like the photo-finds feed that already know their glyph ids. */
    fun glyphSummaries(ids: List<String>): List<SearchResult> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(500)
            .flatMap { db.hanziQueries.glyphsByIds(it).executeAsList() }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toSearchResult() }
    }

    /**
     * Display text for study items of either kind, in the order given (spec §17). Lists,
     * practice cards and the home-screen widget all render items through this, so a word
     * and a character look like the same species of thing wherever they appear.
     *
     * Items missing from the pack are dropped — a list can outlive a pack update that
     * removed an entry, and a row with no text at all would read as a bug.
     */
    fun itemSummaries(items: List<StudyItem>): List<ItemSummary> {
        if (items.isEmpty()) return emptyList()
        val (words, chars) = items.partition { it.isWord }
        val glyphs = glyphSummaries(chars.map { it.itemId }).associateBy { it.glyphId }
        val wordRows = words.map { it.itemId }.chunked(500)
            .flatMap { db.hanziQueries.wordsByIds(it).executeAsList() }
            .associateBy { it.id }
        return items.mapNotNull { item ->
            if (item.isWord) {
                wordRows[item.itemId]?.let { w ->
                    ItemSummary(
                        item = item,
                        text = w.simp,
                        secondaryText = w.trad.takeIf { it != w.simp },
                        pinyin = w.pinyin,
                        gloss = firstSenses(w.defs),
                    )
                }
            } else {
                glyphs[item.itemId]?.let { g ->
                    ItemSummary(
                        item = item,
                        text = g.char,
                        secondaryText = g.tradChar,
                        pinyin = g.primaryPinyin,
                        gloss = g.definition?.let { displayGloss(it) },
                    )
                }
            }
        }
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

/** First sense only — drill cards and component hints want "clear, pure", not the
 * full semicolon-chained gloss. */
fun firstSense(definition: String): String = definition.split(";").first().trim()

private val kangxiSuffix = Regex("[;,]?\\s*Kangxi radical \\d+\\s*$", RegexOption.IGNORE_CASE)

/** Display form of a gloss for list rows: drop a trailing "Kangxi radical N" — it's
 * reference metadata that reads as noise next to real meanings ("blue, green; young;
 * Kangxi radical 174"). Glosses that are ONLY the radical note keep it. */
fun displayGloss(definition: String): String =
    definition.replace(kangxiSuffix, "").ifBlank { definition }

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
