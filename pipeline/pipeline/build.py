"""Pipeline orchestrator: fetch snapshots -> normalize -> merge -> compute -> load SQLite.

Builds a data pack covering full T1+T2 (spec §6, milestone M1): every character
with both a decomposition and a reading (T2, ~20k+ per spec, ~42k in practice —
more coverage than the spec's estimate, which is fine), tagging which subset
additionally has Middle+Old Chinese data (T1, the ~4-5k with full drift
analysis). T3 (decomposition-only, no reading at all) is deliberately out of
scope: those are overwhelmingly obscure historical/dialectal CJK Extension
characters far outside what spec §3's target user (a 300-2,000-character
learner) would ever look up, so building it isn't worth the processing cost
for a personal project. Run `python -m pipeline.build` from pipeline/ after
`python -m fetchers.fetch_all`.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from fetchers.common import SNAPSHOT_ROOT
from pipeline import load, merge, normalize, parsers, phonetic, pinyin, radicals
from pipeline.normalize import GlyphEntity

SOURCES = ["unihan", "makemeahanzi", "cjkvi-ids", "opencc", "wiktextract", "hsk", "cedict", "wordfreq"]
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


def has_mc_oc(e: GlyphEntity) -> bool:
    return any(r.system == "mc_baxter" for r in e.readings) and any(r.system == "oc_zz" for r in e.readings)


def select_t1_t2(entities: dict[int, GlyphEntity]) -> list[int]:
    """T2 membership (has decomposition + a Mandarin reading) is the inclusion
    filter; ordering by frequency (where known) just makes freq_rank meaningful
    for display/sorting, it isn't a cutoff. Characters with no frequency data
    sort after all ranked ones, ordered by codepoint for determinism."""
    candidates = [
        cp for cp, e in entities.items()
        if e.decompositions and any(r.system == "pinyin" for r in e.readings)
    ]

    def sort_key(cp: int) -> tuple[int, int]:
        freq = total_freq(entities[cp])
        return (0, -freq) if freq > 0 else (1, cp)

    candidates.sort(key=sort_key)
    return candidates


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


def build_words(snapshot_paths: dict[str, Path], glyph_ids: set[str]) -> tuple[list[tuple], list[tuple]]:
    """CC-CEDICT entries -> word/word_char rows (spec §7, M3.5). Only multi-character
    words whose every character is in the pack are kept, so word_char links always
    resolve; single-char entries are skipped (glyph.definition covers those).
    word.hsk comes from the word-level HSK list; freq_rank from the OpenSubtitles
    frequency list (FrequencyWords), NULL for words the corpus never saw."""
    entries = parsers.parse_cedict(snapshot_paths["cedict"] / "cedict_ts.u8")
    hsk_words = parsers.parse_hsk_word_levels(snapshot_paths["hsk"] / "complete.min.json")
    freq_ranks = parsers.parse_word_frequencies(snapshot_paths["wordfreq"] / "zh_cn_full.txt")

    word_rows: list[tuple] = []
    word_char_rows: list[tuple] = []
    for trad, simp, numbered, defs in entries:
        if len(simp) < 2:
            continue
        gids = [f"U+{ord(ch):04X}" for ch in simp]
        if any(g not in glyph_ids for g in gids):
            continue
        wid = f"w{len(word_rows) + 1:06d}"
        word_rows.append((
            wid, simp, trad, pinyin.cedict_pinyin(numbered),
            json.dumps(defs, ensure_ascii=False), freq_ranks.get(simp), hsk_words.get(simp),
        ))
        for pos, g in enumerate(gids):
            word_char_rows.append((wid, g, pos))
    return word_rows, word_char_rows


def build_sentences(
    tatoeba_dir: Path,
    word_simps: set[str],
    per_word: int = 3,
    max_len: int = 30,
) -> list[tuple]:
    """Tatoeba Mandarin sentences (with an English translation) as example rows keyed by
    word simp. Keeps the shortest few sentences containing each word, so common words get
    clean, short examples. Rows: (simp, zh, en, tatoeba_id).

    Join is cmn_sentences -> links -> eng_sentences. English texts are loaded only for the
    subset actually linked from a Mandarin sentence, so memory stays bounded despite the
    full English dump being large."""

    # Tatoeba rows are "id \t lang \t text"; ids are positive integers. The .isdigit() guards
    # skip a malformed line in a future dump rather than crashing the whole build on int().
    def load_all(path: Path) -> dict[int, str]:
        out: dict[int, str] = {}
        with path.open(encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3 and parts[0].isdigit():
                    out[int(parts[0])] = parts[2]
        return out

    def load_ids(path: Path) -> set[int]:
        ids: set[int] = set()
        with path.open(encoding="utf-8") as f:
            for line in f:
                head = line.partition("\t")[0]
                if head.isdigit():
                    ids.add(int(head))
        return ids

    def load_texts(path: Path, wanted: set[int]) -> dict[int, str]:
        out: dict[int, str] = {}
        with path.open(encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3 and parts[0].isdigit():
                    sid = int(parts[0])
                    if sid in wanted:
                        out[sid] = parts[2]
        return out

    cmn = load_all(tatoeba_dir / "cmn_sentences.tsv")
    cmn_ids = set(cmn)
    eng_ids = load_ids(tatoeba_dir / "eng_sentences.tsv")

    # First English translation per Mandarin sentence (links are bidirectional in the dump,
    # so scanning the cmn->eng direction covers every pair).
    cmn_to_eng: dict[int, int] = {}
    with (tatoeba_dir / "links.csv").open(encoding="utf-8") as f:
        for line in f:
            a, _, b = line.partition("\t")
            a, b = a.strip(), b.strip()
            if not (a.isdigit() and b.isdigit()):
                continue
            ai = int(a)
            if ai in cmn_ids and ai not in cmn_to_eng and int(b) in eng_ids:
                cmn_to_eng[ai] = int(b)
    eng = load_texts(tatoeba_dir / "eng_sentences.tsv", set(cmn_to_eng.values()))

    # Shortest first, so the examples we keep are the cleanest.
    candidates = sorted(
        (len(cmn[cid]), cid, cmn[cid], eng[eid])
        for cid, eid in cmn_to_eng.items()
        if 0 < len(cmn[cid]) <= max_len
    )

    max_wl = min(max((len(s) for s in word_simps), default=0), 8)
    picked: dict[str, int] = {}
    rows: list[tuple] = []
    for _, cid, zh, en in candidates:
        n = len(zh)
        seen: set[str] = set()
        for i in range(n):
            for length in range(2, max_wl + 1):
                if i + length > n:
                    break
                sub = zh[i:i + length]
                if sub in seen or sub not in word_simps:
                    continue
                seen.add(sub)
                if picked.get(sub, 0) < per_word:
                    picked[sub] = picked.get(sub, 0) + 1
                    rows.append((sub, zh, en, cid))
    return rows


def build() -> Path:
    snapshot_paths = {name: latest_snapshot(name) for name in SOURCES}
    manifests = load_manifests(snapshot_paths)

    print("normalizing entities from raw sources...")
    entities = normalize.build_entities(snapshot_paths)
    print(f"  {len(entities)} distinct codepoints across all sources")

    selected = select_t1_t2(entities)
    t1_count = sum(1 for cp in selected if has_mc_oc(entities[cp]))
    print(f"selected {len(selected)} T1+T2 characters ({t1_count} of them T1: have MC+OC data too)")

    # freq_rank reflects real frequency data only; T2 members without any
    # kHanyuPinlu data get freq_rank=NULL rather than an arbitrary tail rank.
    ranked_cps = [cp for cp in selected if total_freq(entities[cp]) > 0]
    freq_rank_map = {cp: rank for rank, cp in enumerate(ranked_cps, start=1)}

    glyph_rows = []
    reading_rows = []
    decomposition_rows = []
    component_use_rows: list = []
    component_use_objects: list[phonetic.ComponentUseRow] = []
    claim_rows = []

    # Parent definitions for radical-gloss derivation (讠's useful gloss lives on 言).
    parent_defs = {
        parent: (entities.get(ord(parent)).definition if ord(parent) in entities else None)
        for parent in radicals.RADICAL_PARENT.values()
    }

    for cp in selected:
        e = entities[cp]
        gid = f"U+{cp:04X}"
        rank = freq_rank_map.get(cp)
        definition = radicals.radical_gloss(e.char, e.definition, parent_defs) or e.definition
        glyph_rows.append((gid, e.char, glyph_scripts(e), e.total_strokes, rank, e.hsk, definition))

        for system, value, freq in dedupe_readings(e):
            reading_rows.append((gid, system, value, None, freq, "unihan" if system not in ("mc_baxter", "oc_zz", "oc_bs") else "wiktextract"))

        for d in e.decompositions:
            decomposition_rows.append((gid, d.ids, d.region, d.source))

        resolved = merge.resolve_decomposition(e)
        func_claim = merge.component_function_claim(e)
        semantic_component = func_claim.get("semantic_component") if func_claim else None
        phonetic_component = func_claim.get("phonetic_component") if func_claim else None
        if resolved:
            rows = phonetic.compute_component_use(cp, entities, resolved, semantic_component, phonetic_component)
            for row in rows:
                component_use_objects.append(row)
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

    word_rows, word_char_rows = build_words(snapshot_paths, glyph_ids={f"U+{cp:04X}" for cp in selected})

    # Example sentences (Tatoeba) — resolved on its own so the SOURCES list that drives
    # normalize stays character-data only; its manifest still joins the source table.
    tatoeba_dir = latest_snapshot("tatoeba")
    manifests["tatoeba"] = json.loads((tatoeba_dir / "manifest.json").read_text())
    sentence_rows = build_sentences(tatoeba_dir, word_simps={row[1] for row in word_rows})

    series_rows_typed = phonetic.compute_phonetic_series(component_use_objects, freq_rank_map)
    phonetic_series_rows = [
        (s.component_id, s.member_count, s.reliability, s.best_stratum) for s in series_rows_typed
    ]

    # Confusables from Unihan kSpoofingVariant (authoritative visually-confusable pairs,
    # Unicode-licensed). This replaces the spec's proposed stroke-median + IDS-differs-by-one
    # computation: a bare IDS-differs-by-one marks every same-radical char (江/河) as
    # confusable, which is noise, and doing it precisely needs the stroke-median (visual)
    # signal from makemeahanzi's graphics.txt (Arphic PL), not yet ingested. kSpoofingVariant
    # is the same product signal (spec §9 IA item 8) from a clean, authoritative source.
    confusable_seen: set[tuple[int, int]] = set()
    confusable_rows = []
    for cp in selected:
        for other in entities[cp].spoofing_variants:
            if other not in selected_set:
                continue
            lo, hi = sorted((cp, other))
            if (lo, hi) in confusable_seen:
                continue
            confusable_seen.add((lo, hi))
            confusable_rows.append((f"U+{lo:04X}", f"U+{hi:04X}", "visual", None))

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    db_path = BUILD_DIR / "hanzi.db"
    conn = load.init_db(db_path)
    load.load_sources(conn, manifests)
    load.load_glyphs(conn, glyph_rows)
    load.load_readings(conn, reading_rows)
    load.load_decompositions(conn, decomposition_rows)
    load.load_component_use(conn, component_use_rows)
    load.load_claims(conn, claim_rows)
    load.load_variant_edges(conn, variant_edge_rows)
    load.load_phonetic_series(conn, phonetic_series_rows)
    load.load_confusables(conn, confusable_rows)
    load.load_words(conn, word_rows)
    load.load_word_chars(conn, word_char_rows)
    load.load_sentences(conn, sentence_rows)
    conn.commit()
    conn.close()

    print(f"built {db_path}")
    print(f"  glyphs={len(glyph_rows)} readings={len(reading_rows)} decompositions={len(decomposition_rows)}")
    print(f"  component_use={len(component_use_rows)} claims={len(claim_rows)}")
    print(f"  variant_edges={len(variant_edge_rows)} phonetic_series={len(phonetic_series_rows)} confusables={len(confusable_rows)}")
    print(f"  words={len(word_rows)} word_chars={len(word_char_rows)} sentences={len(sentence_rows)}")
    return db_path


if __name__ == "__main__":
    argparse.ArgumentParser(description="Build the full T1/T2 SQLite data pack").parse_args()
    build()
