-- Canonical schema per spec §7. codepoint (as "U+XXXX") is the glyph primary key;
-- S/T are variant_edges, not separate entities. Unencoded components use "ids:<n>"
-- pseudo-IDs in component_id / component_use.glyph_id-shaped columns.
--
-- M0 populates: source, glyph, reading, decomposition, component_use, claim,
-- variant_edge, phonetic_series, word, word_char. script_form/audio are
-- declared now (SRS/word-level tiers are schema-ready) but stay empty until M1+.

CREATE TABLE source (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    license     TEXT NOT NULL,
    url         TEXT NOT NULL,
    version     TEXT NOT NULL,
    ingest_date TEXT NOT NULL
);

CREATE TABLE glyph (
    id           TEXT PRIMARY KEY,   -- "U+4E2D"
    char         TEXT NOT NULL,
    scripts      TEXT NOT NULL,      -- simp|trad|both
    stroke_count INTEGER,
    freq_rank    INTEGER,
    hsk          INTEGER,
    definition   TEXT,               -- short English gloss (Unihan kDefinition), classical
    cedict_defs  TEXT                 -- JSON array of CC-CEDICT senses, the modern gloss
);
CREATE INDEX idx_glyph_char ON glyph(char);
CREATE INDEX idx_glyph_freq_rank ON glyph(freq_rank);

CREATE TABLE reading (
    glyph_id   TEXT NOT NULL REFERENCES glyph(id),
    system     TEXT NOT NULL,   -- pinyin|jyutping|mc_baxter|oc_bs|oc_zz|jp_on|jp_kun|kor|viet
    value      TEXT NOT NULL,
    sense_hint TEXT,
    freq       INTEGER,         -- from kHanyuPinlu where available; NULL otherwise
    source_id  TEXT NOT NULL REFERENCES source(id)
);
CREATE INDEX idx_reading_glyph ON reading(glyph_id);

CREATE TABLE decomposition (
    glyph_id  TEXT NOT NULL REFERENCES glyph(id),
    ids       TEXT NOT NULL,   -- IDS operator tree
    region    TEXT,            -- G|T|J|... or NULL if untagged
    source_id TEXT NOT NULL REFERENCES source(id)
);
CREATE INDEX idx_decomposition_glyph ON decomposition(glyph_id);

CREATE TABLE component_use (
    glyph_id     TEXT NOT NULL REFERENCES glyph(id),
    component_id TEXT NOT NULL,   -- glyph.id or "ids:<n>" pseudo-ID
    position     INTEGER NOT NULL,
    function     TEXT NOT NULL,   -- semantic|phonetic|both|empty|unknown
    match_class  TEXT,            -- exact|tone|initial|rime|oc_only|opaque (computed)
    confidence   REAL,
    resolution   TEXT NOT NULL    -- computed|agreement|single_source|conflict
);
CREATE INDEX idx_component_use_glyph ON component_use(glyph_id);
CREATE INDEX idx_component_use_component ON component_use(component_id);

CREATE TABLE claim (
    subject_id  TEXT NOT NULL,     -- usually a glyph.id
    predicate   TEXT NOT NULL,     -- component_function|original_meaning|formation_type|etymology_text|simplification_note
    value       TEXT NOT NULL,     -- JSON
    source_id   TEXT NOT NULL REFERENCES source(id),
    source_ref  TEXT,
    confidence  REAL,
    retrieved_at TEXT NOT NULL
);
CREATE INDEX idx_claim_subject ON claim(subject_id);

CREATE TABLE variant_edge (
    from_glyph     TEXT NOT NULL REFERENCES glyph(id),
    to_glyph       TEXT NOT NULL REFERENCES glyph(id),
    relation       TEXT NOT NULL,   -- simplified_of|traditional_of|semantic_variant|specialized_semantic_variant|z_variant
    mapping_type   TEXT,            -- systematic|cursive|merger|new_coinage|irregular (unclassified)
    sense_condition TEXT,
    source_id      TEXT NOT NULL REFERENCES source(id)
);
CREATE INDEX idx_variant_edge_from ON variant_edge(from_glyph);
CREATE INDEX idx_variant_edge_to ON variant_edge(to_glyph);

CREATE TABLE phonetic_series (
    component_id  TEXT PRIMARY KEY,
    member_count  INTEGER NOT NULL,
    reliability   REAL NOT NULL,
    best_stratum  TEXT NOT NULL   -- pinyin|mc|oc (last stratum where the link held)
);

CREATE TABLE word (
    id        TEXT PRIMARY KEY,
    simp      TEXT NOT NULL,
    trad      TEXT NOT NULL,
    pinyin    TEXT,
    defs      TEXT,   -- JSON
    freq_rank INTEGER,
    hsk       INTEGER
);

CREATE TABLE word_char (
    word_id  TEXT NOT NULL REFERENCES word(id),
    glyph_id TEXT NOT NULL REFERENCES glyph(id),
    position INTEGER NOT NULL
);
CREATE INDEX idx_word_char_glyph ON word_char(glyph_id);

-- Example sentences (Tatoeba, CC BY 2.0 FR), keyed by the word's simplified form so any
-- word entry sharing that form finds them. A handful of short Mandarin sentences per word,
-- each with an English translation where Tatoeba links one.
-- Sentences judged unidiomatic are NOT deleted, only marked shown=0. The judgement is a
-- local model's and can be wrong, so the corpus stays recoverable and the ranking stays
-- auditable: flipping the filter in sentencesForWord restores everything.
CREATE TABLE sentence (
    simp       TEXT NOT NULL,   -- word.simp this sentence exemplifies
    zh         TEXT NOT NULL,   -- Mandarin sentence
    en         TEXT,            -- English translation (may be absent)
    tatoeba_id INTEGER,         -- source cmn sentence id (attribution / dedup)
    quality    INTEGER,         -- 1-5 naturalness, NULL when never scored
    shown      INTEGER NOT NULL DEFAULT 1   -- 0 = filtered out of the app, still stored
);
CREATE INDEX idx_sentence_simp ON sentence(simp);

-- What a component contributes to the characters it appears in, which is not the same as
-- what it means on its own (页 is "page" alone but lends 项 its "head"). Voted from
-- makemeahanzi role hints or curated; see pipeline/radicals.py.
CREATE TABLE component_gloss (
    component_id TEXT PRIMARY KEY,   -- glyph.char or combining form, as in component_use
    gloss        TEXT NOT NULL,
    source_id    TEXT NOT NULL,      -- makemeahanzi | curated
    support      INTEGER NOT NULL,   -- characters voting for this gloss (0 when curated)
    agreement    REAL NOT NULL       -- support / all votes for this component
);

CREATE TABLE script_form (
    glyph_id  TEXT NOT NULL REFERENCES glyph(id),
    era       TEXT NOT NULL,
    image_ref TEXT NOT NULL,
    source_id TEXT NOT NULL REFERENCES source(id)
);

CREATE TABLE confusable (
    glyph_a TEXT NOT NULL REFERENCES glyph(id),
    glyph_b TEXT NOT NULL REFERENCES glyph(id),
    kind    TEXT NOT NULL,
    diff    TEXT
);

CREATE TABLE audio (
    reading_rowid INTEGER NOT NULL,
    file          TEXT NOT NULL,
    source        TEXT NOT NULL  -- tts|recorded
);
