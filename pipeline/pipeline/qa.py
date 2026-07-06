"""QA gates over the built data pack, spec §8.6: referential integrity, IDS parse
validity, S<->T round-trip, license lint, diff vs previous build.

Referential-integrity and license-lint failures are hard errors (exit 1) — they'd
mean a genuinely broken or non-publishable pack. IDS-parse-rate and diff-vs-previous
are informational: they're printed for a human to look at, not thresholds to fail on,
since a dip is often just "this build covers more of the CJK long tail" rather than
a regression.
"""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

from pipeline import ids_parser
from pipeline.build import BUILD_DIR

DB_PATH = BUILD_DIR / "hanzi.db"
STATS_PATH = BUILD_DIR / "build_stats.json"

# Any source whose license contains one of these markers must never reach a
# published/publishable pack (spec §8.6 "license lint"). PRIVATE is our own
# marker for QA-only sources (see fetch_dong_chinese.py) that must never appear
# in build.py's SOURCES at all.
BLOCKED_LICENSE_MARKERS = ("NC", "PRIVATE", "UNCONFIRMED")


def check_referential_integrity(conn: sqlite3.Connection) -> list[str]:
    errors = []
    checks = [
        ("reading.glyph_id -> glyph", "SELECT COUNT(*) FROM reading WHERE glyph_id NOT IN (SELECT id FROM glyph)"),
        ("reading.source_id -> source", "SELECT COUNT(*) FROM reading WHERE source_id NOT IN (SELECT id FROM source)"),
        ("decomposition.glyph_id -> glyph", "SELECT COUNT(*) FROM decomposition WHERE glyph_id NOT IN (SELECT id FROM glyph)"),
        ("decomposition.source_id -> source", "SELECT COUNT(*) FROM decomposition WHERE source_id NOT IN (SELECT id FROM source)"),
        ("component_use.glyph_id -> glyph", "SELECT COUNT(*) FROM component_use WHERE glyph_id NOT IN (SELECT id FROM glyph)"),
        ("claim.subject_id -> glyph", "SELECT COUNT(*) FROM claim WHERE subject_id NOT IN (SELECT id FROM glyph)"),
        ("claim.source_id -> source", "SELECT COUNT(*) FROM claim WHERE source_id NOT IN (SELECT id FROM source)"),
        ("variant_edge.from_glyph -> glyph", "SELECT COUNT(*) FROM variant_edge WHERE from_glyph NOT IN (SELECT id FROM glyph)"),
        ("variant_edge.to_glyph -> glyph", "SELECT COUNT(*) FROM variant_edge WHERE to_glyph NOT IN (SELECT id FROM glyph)"),
        ("variant_edge.source_id -> source", "SELECT COUNT(*) FROM variant_edge WHERE source_id NOT IN (SELECT id FROM source)"),
    ]
    for label, sql in checks:
        (bad_count,) = conn.execute(sql).fetchone()
        if bad_count:
            errors.append(f"{label}: {bad_count} dangling reference(s)")
    return errors


def check_ids_parse_validity(conn: sqlite3.Connection) -> tuple[int, int]:
    rows = conn.execute("SELECT DISTINCT ids FROM decomposition").fetchall()
    ok = 0
    for (ids,) in rows:
        try:
            ids_parser.parse_ids(ids)
            ok += 1
        except ids_parser.IdsParseError:
            pass
    return ok, len(rows)


def check_st_roundtrip(conn: sqlite3.Connection) -> dict[str, int]:
    """For each simplification edge, check that re-simplifying the traditional
    form via the SAME edge set lands back on the recorded simplified form —
    trivially true by construction (we don't have an independent second
    conversion source to round-trip against), so what's actually useful here is
    surfacing exactly how many simplified glyphs have more than one recorded
    traditional source (mergers) vs. a clean 1:1 pair, since that's the case
    where "round trip" is lossy by nature of the script reform itself."""
    rows = conn.execute("SELECT from_glyph, to_glyph, mapping_type FROM variant_edge WHERE relation='simplification'").fetchall()
    to_counts: dict[str, int] = {}
    for _, to_glyph, _ in rows:
        to_counts[to_glyph] = to_counts.get(to_glyph, 0) + 1
    clean_1to1 = sum(1 for c in to_counts.values() if c == 1)
    lossy_merger = sum(1 for c in to_counts.values() if c > 1)
    mapping_type_mismatch = sum(
        1 for _, to_glyph, mt in rows if to_counts[to_glyph] > 1 and mt != "merger"
    )
    return {
        "total_edges": len(rows),
        "clean_1to1_targets": clean_1to1,
        "lossy_merger_targets": lossy_merger,
        "merger_targets_mislabeled": mapping_type_mismatch,
    }


def check_license_lint(manifests: dict[str, dict]) -> list[str]:
    errors = []
    for name, m in manifests.items():
        license_ = m.get("license", "")
        if any(marker in license_ for marker in BLOCKED_LICENSE_MARKERS):
            errors.append(f"source {name!r} has a blocked license marker: {license_!r}")
    return errors


def current_stats(conn: sqlite3.Connection) -> dict[str, int]:
    tables = ["glyph", "reading", "decomposition", "component_use", "claim", "variant_edge", "phonetic_series", "confusable"]
    return {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}


def diff_vs_previous(stats: dict[str, int]) -> list[str]:
    if not STATS_PATH.exists():
        return ["no previous build_stats.json — nothing to diff (this is the first recorded build)"]
    previous = json.loads(STATS_PATH.read_text())
    lines = []
    for table, count in stats.items():
        prev_count = previous.get(table)
        if prev_count is None:
            lines.append(f"{table}: new table (was absent), now {count}")
        elif prev_count != count:
            delta = count - prev_count
            lines.append(f"{table}: {prev_count} -> {count} ({delta:+d})")
    return lines or ["no change vs previous build"]


def run_qa() -> bool:
    if not DB_PATH.exists():
        print(f"no build found at {DB_PATH}; run pipeline.build first", file=sys.stderr)
        return False

    conn = sqlite3.connect(DB_PATH)
    manifests = {
        row[0]: {"license": row[1]}
        for row in conn.execute("SELECT id, license FROM source")
    }

    ok = True

    print("--- referential integrity ---")
    ref_errors = check_referential_integrity(conn)
    if ref_errors:
        ok = False
        for e in ref_errors:
            print(f"  FAIL: {e}")
    else:
        print("  OK: no dangling references")

    print("--- license lint ---")
    license_errors = check_license_lint(manifests)
    if license_errors:
        ok = False
        for e in license_errors:
            print(f"  FAIL: {e}")
    else:
        print(f"  OK: all {len(manifests)} sources clear ({', '.join(sorted(manifests))})")

    print("--- IDS parse validity ---")
    parse_ok, parse_total = check_ids_parse_validity(conn)
    rate = parse_ok / parse_total if parse_total else 1.0
    print(f"  {parse_ok}/{parse_total} distinct IDS strings parse cleanly ({rate:.2%})")

    print("--- S<->T round-trip / merger census ---")
    for k, v in check_st_roundtrip(conn).items():
        print(f"  {k}: {v}")

    print("--- diff vs previous build ---")
    stats = current_stats(conn)
    for line in diff_vs_previous(stats):
        print(f"  {line}")
    STATS_PATH.write_text(json.dumps(stats, indent=2))

    conn.close()
    print()
    print("QA gates: " + ("PASS" if ok else "FAIL"))
    return ok


if __name__ == "__main__":
    sys.exit(0 if run_qa() else 1)
