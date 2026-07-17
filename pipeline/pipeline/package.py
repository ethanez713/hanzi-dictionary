"""Split the unified build into core.db (BY-SA-side sources + computed layer) and
ids.db (cjkvi-ids-derived decompositions, GPL) per spec §15 — keeping license layers
separate in the shipped pack exactly as they'd be in the published dump. The app
opens core.db and ATTACHes ids.db at runtime for cross-database queries; they are
not meant to be merged back into one file.

Also builds an FTS5 index in core.db over char + all pinyin readings (spec §9's
"SQLite+FTS5" stack / search-ranking design), and exports an open JSONL dump with
per-record attribution for the published dataset repo (spec §8.7).
"""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from pipeline.build import BUILD_DIR

SOURCE_DB = BUILD_DIR / "hanzi.db"
CORE_DB = BUILD_DIR / "core.db"
IDS_DB = BUILD_DIR / "ids.db"
JSONL_DUMP = BUILD_DIR / "hanzi-dataset.jsonl"
HSK_LEVELS = BUILD_DIR / "hsk-levels.json"

# Must match HanziDatabase.Schema.version in the app's SQLDelight-generated code, so
# Android's SupportSQLiteOpenHelper opens the prebuilt pack without firing onCreate/
# onUpgrade against it (the app ships these read-only, never migrates them).
SCHEMA_USER_VERSION = 1

DECOMPOSITION_DDL = """
CREATE TABLE decomposition (
    glyph_id  TEXT NOT NULL,
    ids       TEXT NOT NULL,
    region    TEXT,
    source_id TEXT NOT NULL
);
CREATE INDEX idx_decomposition_glyph ON decomposition(glyph_id);
"""


def _copy_schema_and_data(src: sqlite3.Connection, dest_path: Path, tables: list[str], where: dict[str, str] | None = None) -> None:
    where = where or {}
    dest_path.unlink(missing_ok=True)
    dest = sqlite3.connect(dest_path)
    for table in tables:
        (ddl,) = src.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()
        dest.execute(ddl)
        for (index_ddl,) in src.execute(
            "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", (table,)
        ):
            dest.execute(index_ddl)
        clause = where.get(table, "")
        rows = src.execute(f"SELECT * FROM {table} {clause}").fetchall()
        if rows:
            placeholders = ",".join("?" * len(rows[0]))
            dest.executemany(f"INSERT INTO {table} VALUES ({placeholders})", rows)
    dest.execute(f"PRAGMA user_version = {SCHEMA_USER_VERSION}")
    dest.commit()
    dest.close()


def build_ids_db(src: sqlite3.Connection) -> None:
    IDS_DB.unlink(missing_ok=True)
    dest = sqlite3.connect(IDS_DB)
    dest.executescript(DECOMPOSITION_DDL)
    rows = src.execute("SELECT glyph_id, ids, region, source_id FROM decomposition WHERE source_id='cjkvi-ids'").fetchall()
    dest.executemany("INSERT INTO decomposition VALUES (?,?,?,?)", rows)
    dest.execute(f"PRAGMA user_version = {SCHEMA_USER_VERSION}")
    dest.commit()
    dest.close()
    print(f"ids.db: {len(rows)} cjkvi-ids decomposition rows (GPL)")


def build_core_db(src: sqlite3.Connection) -> None:
    core_tables = [
        "source", "glyph", "reading", "decomposition", "component_use",
        "claim", "variant_edge", "phonetic_series", "word", "word_char",
        "script_form", "confusable", "audio",
    ]
    _copy_schema_and_data(
        src, CORE_DB, core_tables,
        where={"decomposition": "WHERE source_id != 'cjkvi-ids'"},
    )

    core = sqlite3.connect(CORE_DB)
    # definition joins the index so English search works (spec §9 ranking "English FTS");
    # unicode61's diacritic folding is what lets toneless "qing" match "qǐng" in pinyin.
    core.executescript("""
        CREATE VIRTUAL TABLE glyph_fts USING fts5(glyph_id UNINDEXED, char, pinyin, definition);
    """)
    rows = core.execute("""
        SELECT g.id, g.char, COALESCE(GROUP_CONCAT(DISTINCT r.value), ''), COALESCE(g.definition, '')
        FROM glyph g LEFT JOIN reading r ON r.glyph_id = g.id AND r.system = 'pinyin'
        GROUP BY g.id
    """).fetchall()
    core.executemany("INSERT INTO glyph_fts VALUES (?,?,?,?)", rows)

    # Word search (spec §9 ranking "word", §15 word pages): unicode61 keeps contiguous
    # CJK as one token, so prefix queries like simp:说* match 说话; diacritic folding
    # lets "shuo hua" match the tone-marked pinyin column.
    core.executescript("""
        CREATE VIRTUAL TABLE word_fts USING fts5(word_id UNINDEXED, simp, trad, pinyin, defs);
    """)
    word_rows = core.execute(
        "SELECT id, simp, trad, COALESCE(pinyin,''), COALESCE(defs,'') FROM word"
    ).fetchall()
    core.executemany("INSERT INTO word_fts VALUES (?,?,?,?,?)", word_rows)
    core.commit()

    (non_cjkvi_decomp_count,) = core.execute("SELECT COUNT(*) FROM decomposition").fetchone()
    (glyph_count,) = core.execute("SELECT COUNT(*) FROM glyph").fetchone()
    core.close()
    print(f"core.db: {glyph_count} glyphs, {non_cjkvi_decomp_count} non-cjkvi-ids decomposition rows, FTS5 index built")


def export_jsonl(src: sqlite3.Connection) -> None:
    sources = {row[0]: dict(zip(("name", "license", "url", "version"), row[1:])) for row in src.execute("SELECT id, name, license, url, version FROM source")}

    glyphs = src.execute("SELECT id, char, scripts, stroke_count, freq_rank, hsk, definition FROM glyph ORDER BY id").fetchall()
    readings = _group_by(src, "SELECT glyph_id, system, value, sense_hint, freq, source_id FROM reading", key_idx=0)
    decompositions = _group_by(src, "SELECT glyph_id, ids, region, source_id FROM decomposition", key_idx=0)
    component_uses = _group_by(src, "SELECT glyph_id, component_id, position, function, match_class, confidence, resolution FROM component_use", key_idx=0)
    claims = _group_by(src, "SELECT subject_id, predicate, value, source_id, source_ref, confidence, retrieved_at FROM claim", key_idx=0)

    with JSONL_DUMP.open("w", encoding="utf-8") as out:
        for gid, char, scripts, stroke_count, freq_rank, hsk, definition in glyphs:
            record = {
                "id": gid, "char": char, "scripts": scripts, "stroke_count": stroke_count,
                "freq_rank": freq_rank, "hsk": hsk, "definition": definition,
                "readings": [
                    {"system": s, "value": v, "sense_hint": sh, "freq": f,
                     "source": sources.get(src_id, {}).get("name", src_id), "license": sources.get(src_id, {}).get("license")}
                    for (_, s, v, sh, f, src_id) in readings.get(gid, [])
                ],
                "decompositions": [
                    {"ids": ids, "region": region,
                     "source": sources.get(src_id, {}).get("name", src_id), "license": sources.get(src_id, {}).get("license")}
                    for (_, ids, region, src_id) in decompositions.get(gid, [])
                ],
                "component_use": [
                    {"component_id": comp, "position": pos, "function": func,
                     "match_class": mc, "confidence": conf, "resolution": res}
                    for (_, comp, pos, func, mc, conf, res) in component_uses.get(gid, [])
                ],
                "claims": [
                    {"predicate": pred, "value": json.loads(val), "source": sources.get(src_id, {}).get("name", src_id),
                     "license": sources.get(src_id, {}).get("license"), "source_ref": sref, "confidence": conf, "retrieved_at": ra}
                    for (_, pred, val, src_id, sref, conf, ra) in claims.get(gid, [])
                ],
            }
            out.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(f"hanzi-dataset.jsonl: {len(glyphs)} records with per-field source+license attribution")


def _group_by(conn: sqlite3.Connection, sql: str, key_idx: int) -> dict[str, list[tuple]]:
    out: dict[str, list[tuple]] = {}
    for row in conn.execute(sql):
        out.setdefault(row[key_idx], []).append(row)
    return out


def export_hsk_levels(src: sqlite3.Connection) -> None:
    """Per-level character lists (as glyph ids) for the app's HSK-level import picker
    (spec §7). Bundled as an app asset, not part of the published dataset repo."""
    levels: dict[str, list[str]] = {}
    for glyph_id, hsk in src.execute("SELECT id, hsk FROM glyph WHERE hsk IS NOT NULL ORDER BY freq_rank IS NULL, freq_rank"):
        levels.setdefault(str(hsk), []).append(glyph_id)
    HSK_LEVELS.write_text(json.dumps(levels, ensure_ascii=False, indent=0))
    total = sum(len(v) for v in levels.values())
    print(f"hsk-levels.json: {total} characters across {len(levels)} HSK levels")


def package() -> None:
    if not SOURCE_DB.exists():
        raise FileNotFoundError(f"no build found at {SOURCE_DB}; run pipeline.build first")
    src = sqlite3.connect(SOURCE_DB)
    build_ids_db(src)
    build_core_db(src)
    export_jsonl(src)
    export_hsk_levels(src)
    src.close()


if __name__ == "__main__":
    package()
