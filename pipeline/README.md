# Pipeline

M0 data pipeline: fetch the 5 core sources, normalize/merge them per-codepoint, run the
pinyin-stratum phonetic detector, and load a SQLite pack for validation.

No third-party Python dependencies — stdlib only (`urllib`, `zipfile`, `sqlite3`, `git`
via subprocess for the GitHub-hosted sources). Keeps the supply-chain surface at zero
for now; add pinned+hash-locked deps only if a later milestone genuinely needs one.

```
python3 -m venv .venv && source .venv/bin/activate  # or ./.venv/bin/python directly
python -m fetchers.fetch_all          # downloads Unihan, makemeahanzi, cjkvi-ids,
                                       # OpenCC, and the ~1.1 GB wiktextract Chinese
                                       # dump (filtered to single-character entries)
python -m pipeline.build --top-n 1000 # writes data/build/hanzi-m0.db
```

Fetchers are idempotent (skip already-fetched snapshots; `--force` to re-fetch) and
write an immutable versioned snapshot + manifest (url/version/license/sha256) under
`data/snapshots/<source>/<version>/`, per spec §8.1.

## Layout

- `fetchers/` — one module per source, all writing through `fetchers/common.py`'s
  shared download/git-clone/manifest helpers.
- `pipeline/parsers.py` — raw-format parsing per source, keyed by codepoint.
- `pipeline/normalize.py` — entity resolution: one `GlyphEntity` per codepoint, unioned
  across all 5 sources.
- `pipeline/merge.py` — decomposition priority resolution (cjkvi-ids > makemeahanzi,
  region rule), S/T variant edge + mapping_type classification.
- `pipeline/ids_parser.py` — minimal IDS tree parser (first-level components only).
- `pipeline/pinyin.py` / `pipeline/phonetic.py` — the phonetic detector (pinyin stratum
  only for M0; MC/OC strata need dedicated notation parsers, deferred per spec §15).
- `pipeline/load.py` / `pipeline/build.py` — SQLite writer and CLI orchestrator.
- `pipeline/schema.sql` — canonical schema (spec §7); M0 populates glyph, reading,
  decomposition, component_use, claim, variant_edge, phonetic_series, source. The
  word/word_char/script_form/confusable/audio tables are declared but stay empty
  until M1+.
