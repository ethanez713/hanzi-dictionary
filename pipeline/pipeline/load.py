"""Write the resolved M0 dataset into a SQLite file matching schema.sql (spec §7).

Single core.db for M0 (private PoC). The GPL-layer split into core.db/ids.db
(spec §15) is a publication-time concern for M1, not needed for local validation.
"""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path

SCHEMA_PATH = Path(__file__).resolve().parent / "schema.sql"


def init_db(db_path: Path) -> sqlite3.Connection:
    if db_path.exists():
        db_path.unlink()
    conn = sqlite3.connect(db_path)
    conn.executescript(SCHEMA_PATH.read_text())
    return conn


def load_sources(conn: sqlite3.Connection, manifests: dict[str, dict]) -> None:
    rows = [
        (name, name, m["license"], m["url"], m["version"], m["retrieved_at"])
        for name, m in manifests.items()
    ]
    conn.executemany("INSERT INTO source VALUES (?,?,?,?,?,?)", rows)


def load_glyphs(conn: sqlite3.Connection, glyphs: list[tuple]) -> None:
    conn.executemany("INSERT INTO glyph VALUES (?,?,?,?,?,?,?)", glyphs)


def load_readings(conn: sqlite3.Connection, readings: list[tuple]) -> None:
    conn.executemany("INSERT INTO reading (glyph_id, system, value, sense_hint, freq, source_id) VALUES (?,?,?,?,?,?)", readings)


def load_decompositions(conn: sqlite3.Connection, rows: list[tuple]) -> None:
    conn.executemany("INSERT INTO decomposition (glyph_id, ids, region, source_id) VALUES (?,?,?,?)", rows)


def load_component_use(conn: sqlite3.Connection, rows: list[tuple]) -> None:
    conn.executemany(
        "INSERT INTO component_use (glyph_id, component_id, position, function, match_class, confidence, resolution) VALUES (?,?,?,?,?,?,?)",
        rows,
    )


def load_claims(conn: sqlite3.Connection, rows: list[tuple]) -> None:
    conn.executemany(
        "INSERT INTO claim (subject_id, predicate, value, source_id, source_ref, confidence, retrieved_at) VALUES (?,?,?,?,?,?,?)",
        rows,
    )


def load_variant_edges(conn: sqlite3.Connection, rows: list[tuple]) -> None:
    conn.executemany(
        "INSERT INTO variant_edge (from_glyph, to_glyph, relation, mapping_type, sense_condition, source_id) VALUES (?,?,?,?,?,?)",
        rows,
    )


def load_phonetic_series(conn: sqlite3.Connection, rows: list[tuple]) -> None:
    conn.executemany("INSERT INTO phonetic_series VALUES (?,?,?,?)", rows)


def load_confusables(conn: sqlite3.Connection, rows: list[tuple]) -> None:
    conn.executemany("INSERT INTO confusable (glyph_a, glyph_b, kind, diff) VALUES (?,?,?,?)", rows)
