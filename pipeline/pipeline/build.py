"""M0 orchestrator: fetch snapshots -> normalize -> merge -> compute -> load SQLite.

Builds a data pack for the top ~1,000 characters by Unihan kHanyuPinlu frequency
(spec milestone M0). Run `python -m pipeline.build` from pipeline/ after
`python -m fetchers.fetch_all`.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from fetchers.common import SNAPSHOT_ROOT
from pipeline import load, merge, normalize, phonetic
from pipeline.normalize import GlyphEntity

SOURCES = ["unihan", "makemeahanzi", "cjkvi-ids", "opencc", "wiktextract"]
DEFAULT_TOP_N = 1000
BUILD_DIR = Path(__file__).resolve().parent.parent / "data" / "build"


def latest_snapshot(source: str) -> Path:
    source_dir = SNAPSHOT_ROOT / source
    versions = [d for d in source_dir.iterdir() if d.is_dir() and (d / "manifest.json").exists()]
    if not versions:
        raise FileNotFoundError(f"no fetched snapshot for source {source!r}; run fetchers.fetch_all first")
    return max(versions, key=lambda d: json.loads((d / "manifest.json").read_text())["retrieved_at"])


def load_manifests(snapshot_paths: dict[str, Path]) -> dict[str, dict]:
    return {name: json.loads((path / "manifest.json").read_text()) for name, path in snapshot_paths.items()}


def total_freq(e: GlyphEntity) -> int:
    return sum(r.freq for r in e.readings if r.freq)


def select_top_n(entities: dict[int, GlyphEntity], n: int) -> list[int]:
    candidates = [(cp, total_freq(e)) for cp, e in entities.items() if total_freq(e) > 0]
    candidates.sort(key=lambda pair: -pair[1])
    return [cp for cp, _ in candidates[:n]]


def glyph_scripts(e: GlyphEntity) -> str:
    is_simp_script = bool(e.traditional_variants or e.opencc_traditional_of)
    is_trad_script = bool(e.simplified_variants or e.opencc_simplified_of)
    if is_simp_script and not is_trad_script:
        return "simp"
    if is_trad_script and not is_simp_script:
        return "trad"
    return "both"


def dedupe_readings(e: GlyphEntity) -> list[tuple[str, str, int | None]]:
    seen: dict[tuple[str, str], int | None] = {}
    for r in e.readings:
        key = (r.system, r.value)
        if key not in seen or (r.freq is not None and seen[key] is None):
            seen[key] = r.freq
    return [(system, value, freq) for (system, value), freq in seen.items()]


def build(top_n: int = DEFAULT_TOP_N) -> Path:
    snapshot_paths = {name: latest_snapshot(name) for name in SOURCES}
    manifests = load_manifests(snapshot_paths)

    print("normalizing entities from raw sources...")
    entities = normalize.build_entities(snapshot_paths)
    print(f"  {len(entities)} distinct codepoints across all sources")

    selected = select_top_n(entities, top_n)
    print(f"selected top {len(selected)} characters by kHanyuPinlu frequency")

    glyph_rows = []
    reading_rows = []
    decomposition_rows = []
    component_use_rows: list = []
    claim_rows = []

    for rank, cp in enumerate(selected, start=1):
        e = entities[cp]
        gid = f"U+{cp:04X}"
        glyph_rows.append((gid, e.char, glyph_scripts(e), e.total_strokes, rank, None))

        for system, value, freq in dedupe_readings(e):
            reading_rows.append((gid, system, value, None, freq, "unihan"))

        for d in e.decompositions:
            decomposition_rows.append((gid, d.ids, d.region, d.source))

        resolved = merge.resolve_decomposition(e)
        func_claim = merge.component_function_claim(e)
        semantic_component = func_claim.get("semantic_component") if func_claim else None
        if resolved:
            rows = phonetic.compute_component_use(cp, entities, resolved, semantic_component)
            for row in rows:
                component_use_rows.append((
                    gid, row.component_id, row.position, row.function,
                    row.match_class, row.confidence, row.resolution,
                ))

        if func_claim:
            claim_rows.append((
                gid, "component_function", json.dumps(func_claim, ensure_ascii=False),
                "makemeahanzi", None, 0.6, manifests["makemeahanzi"]["retrieved_at"],
            ))
        etymology_text = merge.etymology_text_claim(e)
        if etymology_text:
            claim_rows.append((
                gid, "etymology_text", json.dumps(etymology_text, ensure_ascii=False),
                "wiktextract", None, 0.7, manifests["wiktextract"]["retrieved_at"],
            ))

    selected_set = set(selected)
    drafts = merge.resolve_variant_edges(entities)
    variant_edge_rows = [
        (f"U+{d.from_glyph:04X}", f"U+{d.to_glyph:04X}", "simplification", d.mapping_type, None, "unihan")
        for d in drafts
        if d.from_glyph in selected_set and d.to_glyph in selected_set
    ]

    freq_rank = {cp: rank for rank, cp in enumerate(selected, start=1)}
    phonetic_component_use = [
        phonetic.ComponentUseRow(glyph_id=int(gid[2:], 16), component_id=comp, position=pos,
                                  function=func, match_class=mc, confidence=conf, resolution=res)
        for (gid, comp, pos, func, mc, conf, res) in component_use_rows
    ]
    series_rows_typed = phonetic.compute_phonetic_series(phonetic_component_use, freq_rank)
    phonetic_series_rows = [
        (s.component_id, s.member_count, s.reliability, s.best_stratum) for s in series_rows_typed
    ]

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    db_path = BUILD_DIR / "hanzi-m0.db"
    conn = load.init_db(db_path)
    load.load_sources(conn, manifests)
    load.load_glyphs(conn, glyph_rows)
    load.load_readings(conn, reading_rows)
    load.load_decompositions(conn, decomposition_rows)
    load.load_component_use(conn, component_use_rows)
    load.load_claims(conn, claim_rows)
    load.load_variant_edges(conn, variant_edge_rows)
    load.load_phonetic_series(conn, phonetic_series_rows)
    conn.commit()
    conn.close()

    print(f"built {db_path}")
    print(f"  glyphs={len(glyph_rows)} readings={len(reading_rows)} decompositions={len(decomposition_rows)}")
    print(f"  component_use={len(component_use_rows)} claims={len(claim_rows)}")
    print(f"  variant_edges={len(variant_edge_rows)} phonetic_series={len(phonetic_series_rows)}")
    return db_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build the M0 SQLite data pack")
    parser.add_argument("--top-n", type=int, default=DEFAULT_TOP_N)
    args = parser.parse_args()
    build(top_n=args.top_n)
