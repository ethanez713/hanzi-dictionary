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
import collections
import json
import re as _re_sent
from pathlib import Path

from fetchers.common import SNAPSHOT_ROOT
from pipeline import dong, load, lookalike, merge, normalize, parsers, phonetic, pinyin, quality, radicals
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

    known_words = {simp for _t, simp, _n, _d in entries}

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
            wid, simp, trad,
            pinyin.cedict_pinyin(pinyin.neutralize_potential_bu(simp, numbered, known_words)),
            json.dumps(defs, ensure_ascii=False), freq_ranks.get(simp), hsk_words.get(simp),
        ))
        for pos, g in enumerate(gids):
            word_char_rows.append((wid, g, pos))
    return word_rows, word_char_rows


_OPUS_LATIN = _re_sent.compile(r"[A-Za-z]")
_OPUS_CJK = _re_sent.compile(r"[一-鿿]")
_OPUS_JUNK = set("♪♫[]{}()<>@#*_=/\\|~^`")
_OPUS_END = set("。！？!?.…")
_OPUS_STRIP = "-–—•*\"“”'‘’ 　"  # leading/trailing dialogue dashes, bullets, quotes, spaces


def _clean_opus_zh(zh: str) -> str:
    """Strip subtitle framing (leading dialogue dashes, surrounding quotes)."""
    return zh.strip().strip(_OPUS_STRIP).strip()


def _opus_ok(zh: str, en: str, min_len: int, max_len: int) -> bool:
    """Keep only clean, sentence-shaped subtitle/talk pairs — the corpora are noisy (music
    cues, dialogue dashes, on-screen text, mis-alignments), and a bad example is worse than a
    missing one on a learner card. [zh] is already cleaned by [_clean_opus_zh]."""
    n = len(zh)
    if n < min_len or n > max_len:
        return False
    if not _OPUS_CJK.search(zh):
        return False
    if len(_OPUS_LATIN.findall(zh)) > 2:  # stray latin ok; a latin sentence is a mis-align
        return False
    if any(c in _OPUS_JUNK for c in zh):
        return False
    if zh[-1] not in _OPUS_END:  # require a real sentence end, not a mid-line fragment
        return False
    en = en.strip().strip(_OPUS_STRIP)
    if not (3 <= len(en) <= 160) or not any(c.isascii() and c.isalpha() for c in en):
        return False
    # Gross length mismatch is the tell-tale of a bad subtitle alignment (a 4-char line
    # translated as a 20-word sentence, or vice versa). English runs ~1-4 chars per hanzi.
    if not (0.8 * n <= len(en) <= 6 * n + 12):
        return False
    return True


def _opus_coverage(
    sources: list[tuple[Path, Path]],
    needed: set[str],
    words_in,
    per_word: int,
    min_len: int,
    max_len: int,
) -> dict[str, list[tuple[str, str]]]:
    """Stream aligned OPUS moses files **in priority order**, filling each still-needed word
    to [per_word] from the SHORTEST clean pairs a source offers before moving to the next,
    noisier source — so a clean TED sentence is never evicted by a shorter OpenSubtitles one.
    Memory is bounded by needed×per_word, not the 11M-line corpus."""
    result: dict[str, list[tuple[str, str]]] = {}
    remaining = set(needed)
    for zh_path, en_path in sources:
        if not remaining:
            break
        best: dict[str, list[tuple[int, str, str]]] = {}
        with zh_path.open(encoding="utf-8") as zf, en_path.open(encoding="utf-8") as ef:
            for zh_line, en_line in zip(zf, ef):
                zh = _clean_opus_zh(zh_line)
                if not (min_len <= len(zh) <= max_len):
                    continue
                en = en_line.strip().strip(_OPUS_STRIP)
                if not _opus_ok(zh, en, min_len, max_len):
                    continue
                for w in words_in(zh):
                    if w not in remaining:
                        continue
                    keep = per_word - len(result.get(w, ()))
                    if keep <= 0:
                        continue
                    bucket = best.setdefault(w, [])
                    key = (len(zh), zh, en)
                    if len(bucket) < keep:
                        bucket.append(key)
                        bucket.sort()
                    elif key < bucket[-1]:
                        bucket[-1] = key
                        bucket.sort()
        for w, lst in best.items():
            result.setdefault(w, []).extend((zh, en) for _, zh, en in lst)
            if len(result[w]) >= per_word:
                remaining.discard(w)
    return result


QUALITY_CACHE = Path(__file__).resolve().parents[1] / "data" / "derived" / "sentence-quality.jsonl"


def build_sentences(
    tatoeba_dir: Path,
    word_simps: set[str],
    opus_sources: list[tuple[Path, Path]] | None = None,
    opus_words: set[str] | None = None,
    per_word: int = 5,
    short_max: int = 34,
    coverage_max: int = 50,
    coverage_min: int = 2,
    opus_min_len: int = 6,
) -> list[tuple]:
    """Mandarin example sentences (with an English translation) as rows keyed by word simp.
    Rows: (simp, zh, en, tatoeba_id) — tatoeba_id is NULL for the OPUS-sourced rows.

    **Tatoeba first** (human-curated, cleanest): two passes over its shortest-first pool —
    pass 1 fills up to [per_word] short (≤[short_max]) examples per word, pass 2 tops words
    seen only in longer sentences (≤[coverage_max]) up to [coverage_min].

    **Then OPUS coverage fill** ([opus_sources], in priority order — TED2020 before the far
    larger, noisier OpenSubtitles): only words STILL under [coverage_min] get topped up, from
    the shortest clean pairs those corpora contain. This targets the ~1/3 of HSK words Tatoeba
    never mentions without ballooning already-covered common words, so the phone footprint
    stays a function of the per-word caps, not the multi-GB corpora.

    Tatoeba join is cmn_sentences -> links -> eng_sentences; English texts are loaded only for
    the linked subset so memory stays bounded despite the full English dump being large."""

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

    # Shortest first, so the examples we keep are the cleanest, in both passes.
    candidates = sorted(
        (len(cmn[cid]), cid, cmn[cid], eng[eid])
        for cid, eid in cmn_to_eng.items()
        if 0 < len(cmn[cid]) <= coverage_max
    )

    max_wl = min(max((len(s) for s in word_simps), default=0), 8)

    def words_in(zh: str) -> list[str]:
        """Words in [zh] by forward **maximal-match** segmentation, not raw substring.

        Substring matching produced false examples: 及其 "matched" inside 以及+其他, 水产品
        inside 补水+产品 — the word isn't really used, it just straddles a boundary. Greedy
        longest-word-at-each-position segmentation tokenises 以及其他 as 以及 · 其他, so 及其
        never surfaces from it. It can miss a legitimately-overlapping word, but for pulling
        example sentences that only removes examples where the word wasn't really the point."""
        n = len(zh)
        seen: set[str] = set()
        i = 0
        while i < n:
            matched = 0
            for length in range(min(max_wl, n - i), 1, -1):
                if zh[i:i + length] in word_simps:
                    seen.add(zh[i:i + length])
                    matched = length
                    break
            i += matched if matched else 1
        return list(seen)

    picked: dict[str, int] = {}
    added: set[tuple[str, str]] = set()  # (word, sentence) — dedup across sources
    rows: list[tuple] = []

    def take(sub: str, cid, zh: str, en: str, cap: int) -> None:
        if picked.get(sub, 0) < cap and (sub, zh) not in added:
            picked[sub] = picked.get(sub, 0) + 1
            added.add((sub, zh))
            rows.append((sub, zh, en, cid))

    # Pass 1: clean short examples.
    for ln, cid, zh, en in candidates:
        if ln > short_max:
            continue
        for sub in words_in(zh):
            take(sub, cid, zh, en, per_word)

    # Pass 2: coverage for words seen only in longer sentences.
    for _, cid, zh, en in candidates:
        for sub in words_in(zh):
            take(sub, cid, zh, en, coverage_min)

    # OPUS coverage fill: only HSK/common words ([opus_words]) that Tatoeba still leaves under
    # coverage_min. Restricting to words worth having keeps the noisier subtitle corpus off the
    # long tail (obscure words are fine to leave empty) and the pack small.
    if opus_sources:
        pool = opus_words if opus_words is not None else word_simps
        needed = {w for w in pool if picked.get(w, 0) < coverage_min}
        found = _opus_coverage(opus_sources, needed, words_in, coverage_min, opus_min_len, coverage_max)
        for sub, pairs in found.items():
            for zh, en in pairs:
                take(sub, None, zh, en, coverage_min)

    return mark_unnatural(rows)


# Scores at or below this are defective; a 3 is merely awkward and only worth hiding when
# the word can spare it. Measured on 400 labelled sentences: ~95% of what lands at <=2
# deserves it. See docs/sentence-quality-filter.md.
HIDE_ALWAYS = 2
HIDE_IF_SPARE = 3


def mark_unnatural(rows: list[tuple]) -> list[tuple]:
    """Attach each sentence's naturalness score and whether the app should show it.

    **Nothing is deleted.** The judgement comes from a small local model that is right about
    95% of the time at this threshold, which is good enough to hide a sentence behind a flag
    and nowhere near good enough to destroy the corpus: a reviewer can audit every call, and
    undoing the whole thing is a change to one WHERE clause rather than a re-fetch.

    Absent the cache this marks everything shown, so a clean checkout with no Ollama still
    builds a valid, publishable pack — the same contract the Dong lexicon has.

    **A word never loses its last example.** 9,325 words have exactly one sentence and 21,857
    have two, so hiding unconditionally would empty thousands of word pages to fix a handful;
    a word's best sentence is always shown, whatever it scored.
    """
    scores = quality.load_cache(QUALITY_CACHE)
    if not scores:
        print("  sentence quality: no scores cached, every sentence shown")
        return [row + (None, 1) for row in rows]

    by_word: dict[str, list[tuple]] = {}
    for row in rows:
        by_word.setdefault(row[0], []).append(row)

    out: list[tuple] = []
    hidden = spared = 0
    for word_rows in by_word.values():
        ranked = sorted(word_rows, key=lambda r: -scores.get(r[1], 5))
        for rank, row in enumerate(ranked):
            score = scores.get(row[1])
            bad = score is not None and (
                score <= HIDE_ALWAYS or (score <= HIDE_IF_SPARE and rank > 0))
            show = not bad or rank == 0
            if bad and show:
                spared += 1     # nothing better exists for this word
            elif bad:
                hidden += 1
            out.append(row + (score, 1 if show else 0))
    print(f"  sentence quality: {len(scores)} scored, {hidden} hidden, "
          f"{spared} shown anyway as a word's only example "
          f"({len(out) - hidden} of {len(out)} visible)")
    return out


_OPUS_LATIN = _re_sent.compile(r"[A-Za-z]")
_OPUS_CJK = _re_sent.compile(r"[一-鿿]")
_OPUS_JUNK = set("♪♫[]{}()<>@#*_=/\\|~^`")
_OPUS_END = set("。！？!?.…")
_OPUS_STRIP = "-–—•*\"“”'‘’ 　"  # leading/trailing dialogue dashes, bullets, quotes, spaces


def _clean_opus_zh(zh: str) -> str:
    """Strip subtitle framing (leading dialogue dashes, surrounding quotes)."""
    return zh.strip().strip(_OPUS_STRIP).strip()


def _opus_ok(zh: str, en: str, min_len: int, max_len: int) -> bool:
    """Keep only clean, sentence-shaped subtitle/talk pairs — the corpora are noisy (music
    cues, dialogue dashes, on-screen text, mis-alignments), and a bad example is worse than a
    missing one on a learner card. [zh] is already cleaned by [_clean_opus_zh]."""
    n = len(zh)
    if n < min_len or n > max_len:
        return False
    if not _OPUS_CJK.search(zh):
        return False
    if len(_OPUS_LATIN.findall(zh)) > 2:  # stray latin ok; a latin sentence is a mis-align
        return False
    if any(c in _OPUS_JUNK for c in zh):
        return False
    if zh[-1] not in _OPUS_END:  # require a real sentence end, not a mid-line fragment
        return False
    en = en.strip().strip(_OPUS_STRIP)
    if not (3 <= len(en) <= 160) or not any(c.isascii() and c.isalpha() for c in en):
        return False
    # Gross length mismatch is the tell-tale of a bad subtitle alignment (a 4-char line
    # translated as a 20-word sentence, or vice versa). English runs ~1-4 chars per hanzi.
    if not (0.8 * n <= len(en) <= 6 * n + 12):
        return False
    return True


def _opus_coverage(
    sources: list[tuple[Path, Path]],
    needed: set[str],
    words_in,
    per_word: int,
    min_len: int,
    max_len: int,
) -> dict[str, list[tuple[str, str]]]:
    """Stream aligned OPUS moses files **in priority order**, filling each still-needed word
    to [per_word] from the SHORTEST clean pairs a source offers before moving to the next,
    noisier source — so a clean TED sentence is never evicted by a shorter OpenSubtitles one.
    Memory is bounded by needed×per_word, not the 11M-line corpus."""
    result: dict[str, list[tuple[str, str]]] = {}
    remaining = set(needed)
    for zh_path, en_path in sources:
        if not remaining:
            break
        best: dict[str, list[tuple[int, str, str]]] = {}
        with zh_path.open(encoding="utf-8") as zf, en_path.open(encoding="utf-8") as ef:
            for zh_line, en_line in zip(zf, ef):
                zh = _clean_opus_zh(zh_line)
                if not (min_len <= len(zh) <= max_len):
                    continue
                en = en_line.strip().strip(_OPUS_STRIP)
                if not _opus_ok(zh, en, min_len, max_len):
                    continue
                for w in words_in(zh):
                    if w not in remaining:
                        continue
                    keep = per_word - len(result.get(w, ()))
                    if keep <= 0:
                        continue
                    bucket = best.setdefault(w, [])
                    key = (len(zh), zh, en)
                    if len(bucket) < keep:
                        bucket.append(key)
                        bucket.sort()
                    elif key < bucket[-1]:
                        bucket[-1] = key
                        bucket.sort()
        for w, lst in best.items():
            result.setdefault(w, []).extend((zh, en) for _, zh, en in lst)
            if len(result[w]) >= per_word:
                remaining.discard(w)
    return result


QUALITY_CACHE = Path(__file__).resolve().parents[1] / "data" / "derived" / "sentence-quality.jsonl"


def build_sentences(
    tatoeba_dir: Path,
    word_simps: set[str],
    opus_sources: list[tuple[Path, Path]] | None = None,
    opus_words: set[str] | None = None,
    per_word: int = 5,
    short_max: int = 34,
    coverage_max: int = 50,
    coverage_min: int = 2,
    opus_min_len: int = 6,
) -> list[tuple]:
    """Mandarin example sentences (with an English translation) as rows keyed by word simp.
    Rows: (simp, zh, en, tatoeba_id) — tatoeba_id is NULL for the OPUS-sourced rows.

    **Tatoeba first** (human-curated, cleanest): two passes over its shortest-first pool —
    pass 1 fills up to [per_word] short (≤[short_max]) examples per word, pass 2 tops words
    seen only in longer sentences (≤[coverage_max]) up to [coverage_min].

    **Then OPUS coverage fill** ([opus_sources], in priority order — TED2020 before the far
    larger, noisier OpenSubtitles): only words STILL under [coverage_min] get topped up, from
    the shortest clean pairs those corpora contain. This targets the ~1/3 of HSK words Tatoeba
    never mentions without ballooning already-covered common words, so the phone footprint
    stays a function of the per-word caps, not the multi-GB corpora.

    Tatoeba join is cmn_sentences -> links -> eng_sentences; English texts are loaded only for
    the linked subset so memory stays bounded despite the full English dump being large."""

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

    # Shortest first, so the examples we keep are the cleanest, in both passes.
    candidates = sorted(
        (len(cmn[cid]), cid, cmn[cid], eng[eid])
        for cid, eid in cmn_to_eng.items()
        if 0 < len(cmn[cid]) <= coverage_max
    )

    max_wl = min(max((len(s) for s in word_simps), default=0), 8)

    def words_in(zh: str) -> list[str]:
        """Words in [zh] by forward **maximal-match** segmentation, not raw substring.

        Substring matching produced false examples: 及其 "matched" inside 以及+其他, 水产品
        inside 补水+产品 — the word isn't really used, it just straddles a boundary. Greedy
        longest-word-at-each-position segmentation tokenises 以及其他 as 以及 · 其他, so 及其
        never surfaces from it. It can miss a legitimately-overlapping word, but for pulling
        example sentences that only removes examples where the word wasn't really the point."""
        n = len(zh)
        seen: set[str] = set()
        i = 0
        while i < n:
            matched = 0
            for length in range(min(max_wl, n - i), 1, -1):
                if zh[i:i + length] in word_simps:
                    seen.add(zh[i:i + length])
                    matched = length
                    break
            i += matched if matched else 1
        return list(seen)

    picked: dict[str, int] = {}
    added: set[tuple[str, str]] = set()  # (word, sentence) — dedup across sources
    rows: list[tuple] = []

    def take(sub: str, cid, zh: str, en: str, cap: int) -> None:
        if picked.get(sub, 0) < cap and (sub, zh) not in added:
            picked[sub] = picked.get(sub, 0) + 1
            added.add((sub, zh))
            rows.append((sub, zh, en, cid))

    # Pass 1: clean short examples.
    for ln, cid, zh, en in candidates:
        if ln > short_max:
            continue
        for sub in words_in(zh):
            take(sub, cid, zh, en, per_word)

    # Pass 2: coverage for words seen only in longer sentences.
    for _, cid, zh, en in candidates:
        for sub in words_in(zh):
            take(sub, cid, zh, en, coverage_min)

    # OPUS coverage fill: only HSK/common words ([opus_words]) that Tatoeba still leaves under
    # coverage_min. Restricting to words worth having keeps the noisier subtitle corpus off the
    # long tail (obscure words are fine to leave empty) and the pack small.
    if opus_sources:
        pool = opus_words if opus_words is not None else word_simps
        needed = {w for w in pool if picked.get(w, 0) < coverage_min}
        found = _opus_coverage(opus_sources, needed, words_in, coverage_min, opus_min_len, coverage_max)
        for sub, pairs in found.items():
            for zh, en in pairs:
                take(sub, None, zh, en, coverage_min)

    return mark_unnatural(rows)


# Scores below this are defective; a 3 is merely awkward and only worth dropping when the
# word can spare it. Measured on 400 labelled sentences: ~95% of what lands at <=2 deserves
# it, and ~95% of what lands at <=3 does too, but the 3 band is where the judgement is
# closest. See docs/sentence-quality-filter.md.
DROP_ALWAYS = 2
DROP_IF_SPARE = 3


def drop_unnatural(rows: list[tuple]) -> list[tuple]:
    """Remove sentences the local model judged unidiomatic, if the scores exist.

    Absent the cache this is a no-op, so a clean checkout with no Ollama still builds a
    valid, publishable pack — the same contract the Dong lexicon has.

    **A word never loses its last example.** 9,325 words have exactly one sentence and 21,857
    have two, so an unconditional cut would empty thousands of word pages to fix a handful.
    The filter can only ever make a word's examples better, never fewer than one."""
    scores = quality.load_cache(QUALITY_CACHE)
    if not scores:
        print("  sentence quality: no scores cached, keeping every sentence")
        return rows

    by_word: dict[str, list[tuple]] = {}
    for row in rows:
        by_word.setdefault(row[0], []).append(row)

    kept: list[tuple] = []
    dropped = spared = 0
    for word_rows in by_word.values():
        ranked = sorted(word_rows, key=lambda r: -scores.get(r[1], 5))
        for i, row in enumerate(ranked):
            score = scores.get(row[1])
            doomed = score is not None and (
                score <= DROP_ALWAYS or (score <= DROP_IF_SPARE and i > 0))
            if doomed and i > 0:
                dropped += 1
            elif doomed:
                spared += 1     # its word has nothing better; keep it rather than empty the page
                kept.append(row)
            else:
                kept.append(row)
    print(f"  sentence quality: {len(scores)} scored, {dropped} dropped, "
          f"{spared} kept as a word's only example")
    return kept


def single_char_cedict_defs(cedict_dir: Path) -> dict[str, list[str]]:
    """Simplified character -> its CC-CEDICT senses, which build_words drops as too short.

    Unihan's kDefinition is a classical gloss and routinely misses the sense a learner
    actually meets — 项 is "neck, nape of neck; sum; funds" there and "back of neck / item /
    thing / ... / classifier for principles, items, clauses, tasks" here.

    A character with several readings contributes all of them, but proper-noun readings go
    last: CC-CEDICT gives 项 a "surname Xiang" entry that would otherwise head the gloss.
    A capitalised pinyin token is CC-CEDICT's own marker for that."""
    entries = parsers.parse_cedict(cedict_dir / "cedict_ts.u8")
    by_char: dict[str, list[tuple[bool, list[str]]]] = {}
    for _trad, simp, numbered, defs in entries:
        if len(simp) != 1:
            continue
        is_proper = numbered[:1].isupper()
        by_char.setdefault(simp, []).append((is_proper, defs))

    out: dict[str, list[str]] = {}
    for char, readings in by_char.items():
        senses: list[str] = []
        for _proper, defs in sorted(readings, key=lambda r: r[0]):
            for d in defs:
                if d not in senses:
                    senses.append(d)
        if senses:
            out[char] = senses
    return out


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
    # Top-level IDS operator per glyph (⿰, ⿱, …) — how the character is laid out, which the
    # look-alike pairing needs and the pack schema has nowhere to keep.
    layout_by_id: dict[str, str] = {}

    cedict_defs = single_char_cedict_defs(snapshot_paths["cedict"])

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
        senses = cedict_defs.get(e.char)
        glyph_rows.append((
            gid, e.char, glyph_scripts(e), e.total_strokes, rank, e.hsk, definition,
            json.dumps(senses, ensure_ascii=False) if senses else None,
        ))

        for system, value, freq in dedupe_readings(e):
            reading_rows.append((gid, system, value, None, freq, "unihan" if system not in ("mc_baxter", "oc_zz", "oc_bs") else "wiktextract"))

        for d in e.decompositions:
            decomposition_rows.append((gid, d.ids, d.region, d.source))

        resolved = merge.resolve_decomposition(e)
        func_claim = merge.component_function_claim(e)
        semantic_component = func_claim.get("semantic_component") if func_claim else None
        phonetic_component = func_claim.get("phonetic_component") if func_claim else None
        phonetic_contradicted = merge.phonetic_claim_contradicted(e, entities)
        if resolved:
            if resolved.ids:
                layout_by_id[gid] = resolved.ids[0]
            rows = phonetic.compute_component_use(
                cp, entities, resolved, semantic_component, phonetic_component, phonetic_contradicted,
            )
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
    selected_gids = {f"U+{cp:04X}" for cp in selected}

    # ── Dong Chinese authoritative override (user decision 2026-09-01: "Dong wins where it
    # has data") ─────────────────────────────────────────────────────────────────────────
    # For the ~4.5k characters the Dong lexicon covers, its human-verified component functions
    # and formation type REPLACE the algorithmic detector's — higher quality on exactly the
    # common characters a learner meets. The detector still governs every character Dong does
    # not cover. (Dong is personal-build-only; see fetch_dong_chinese.py / ATTRIBUTIONS.
    # Optional: a clean checkout without the snapshot builds a detector-only, publishable pack.)
    # Dong's component functions are human-verified, so its bare assertion outranks
    # makemeahanzi's 0.6 — but only where nothing can be measured instead.
    DONG_ROLE_CONFIDENCE = 0.98

    try:
        dong_dir = latest_snapshot("dong-chinese-lexicon-PRIVATE")
    except FileNotFoundError:
        dong_dir = None
    dong_by_gid = {}
    if dong_dir is not None:
        manifests["dong"] = json.loads((dong_dir / "manifest.json").read_text())
        dong_by_gid = {
            f"U+{ord(ch):04X}": e
            for ch, e in dong.parse_dong(dong_dir / "index.js").items()
            if f"U+{ord(ch):04X}" in selected_gids
        }
    override = set(dong_by_gid)
    component_use_rows = [r for r in component_use_rows if r[0] not in override]
    component_use_objects = [o for o in component_use_objects if f"U+{o.glyph_id:04X}" not in override]
    claim_rows = [c for c in claim_rows if not (c[0] in override and c[1] == "component_function")]
    dong_retrieved = manifests["dong"]["retrieved_at"] if dong_by_gid else None
    for gid, e in dong_by_gid.items():
        cp = int(gid[2:], 16)
        for pos, (comp_char, fn) in enumerate(e.components):
            # Dong names the part and its role; the sound link is measured, never asserted.
            match_class, confidence, resolution, stratum = phonetic.evidence_for_asserted_role(
                cp, comp_char, entities, fn, DONG_ROLE_CONFIDENCE,
            )
            component_use_rows.append((gid, comp_char, pos, fn, match_class, confidence, resolution))
            component_use_objects.append(phonetic.ComponentUseRow(
                cp, comp_char, pos, fn, match_class, confidence, resolution, stratum,
            ))
        func_claim = {"formation_type": e.formation_type}
        sem = next((c for c, f in e.components if f == dong.MEANING), None)
        phon = next((c for c, f in e.components if f in (dong.SOUND, dong.BOTH)), None)
        if sem:
            func_claim["semantic_component"] = sem
        if phon:
            func_claim["phonetic_component"] = phon
        claim_rows.append((
            gid, "component_function", json.dumps(func_claim, ensure_ascii=False),
            "dong", None, 0.95, dong_retrieved,
        ))
    print(f"  Dong override: {len(override)} characters (human-verified component functions)")

    # Role glosses are voted from makemeahanzi and are therefore untouched by the Dong
    # override above, which only replaces component_use and the component_function claim.
    role_glosses = radicals.vote_role_glosses(
        {cp: e.makemeahanzi for cp, e in entities.items() if e.makemeahanzi}
    )
    component_gloss_rows = [
        (component, gloss, source, support, agreement)
        for component, (gloss, source, support, agreement) in sorted(role_glosses.items())
    ]
    semantic_uses = collections.Counter(
        r[1] for r in component_use_rows if r[3] in ("semantic", "both")
    )
    glossed = sum(n for c, n in semantic_uses.items() if c in role_glosses)
    unglossed = sorted(
        ((n, c) for c, n in semantic_uses.items() if c not in role_glosses), reverse=True
    )
    print(f"  component_gloss: {len(component_gloss_rows)} components "
          f"({sum(1 for r in component_gloss_rows if r[2] == 'curated')} curated) "
          f"covering {glossed}/{sum(semantic_uses.values())} semantic displays")
    if unglossed:
        print("    no role gloss (falls back to the standalone gloss): "
              + " ".join(f"{c}:{n}" for n, c in unglossed[:12]))

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
    # Optional OPUS coverage fill from **TED2020** (professional talk translations), if that
    # snapshot is present — a clean checkout builds Tatoeba-only. TED2020 alone lifts HSK
    # multi-char word coverage ~67%→95%.
    #
    # OpenSubtitles was evaluated and DELIBERATELY DROPPED: subtitle "translations" are
    # localised/condensed, not faithful (她是个模范 → "Lucky you, man"), with frequent
    # mis-alignments — wrong for a dictionary — while adding only ~4% more HSK coverage over
    # TED. Don't re-add it without solving the faithfulness problem.
    try:
        opus_root = latest_snapshot("opus")
    except FileNotFoundError:
        opus_root = None
    opus_sources = []
    if opus_root is not None:
        ted_zh = opus_root / "ted2020" / "TED2020.en-zh_cn.zh_cn"
        ted_en = opus_root / "ted2020" / "TED2020.en-zh_cn.en"
        if ted_zh.exists() and ted_en.exists():
            opus_sources.append((ted_zh, ted_en))
            manifests["opus"] = json.loads((opus_root / "manifest.json").read_text())
    # Fill OPUS coverage only for HSK / corpus-frequent words (row[6]=hsk, row[5]=freq_rank).
    opus_words = {row[1] for row in word_rows if row[5] is not None or row[6] is not None}
    sentence_rows = build_sentences(
        tatoeba_dir,
        word_simps={row[1] for row in word_rows},
        opus_sources=opus_sources or None,
        opus_words=opus_words,
    )

    series_rows_typed = phonetic.compute_phonetic_series(component_use_objects, freq_rank_map)
    phonetic_series_rows = [
        (s.component_id, s.member_count, s.reliability, s.best_stratum) for s in series_rows_typed
    ]

    # Confusables, in two layers (spec §9 IA item 8).
    #
    # kind='visual' — Unihan kSpoofingVariant: authoritative, Unicode-licensed, and tiny
    # (75 pairs here, only one of them between two common characters). It is a spoofing
    # defence, not a study aid, so on its own it left the app's look-alike surface empty
    # for nearly every character a learner opens.
    #
    # kind='shape' — computed from the Unihan shape encodings. This is the layer that
    # carries 己/已/巳 and 未/术/末: characters of the same size that differ by a stroke.
    #
    # kind='component' — pairs sharing a component distinctive enough (and large enough) to
    # dominate both characters: 斯/期 via 其, 斯/听 via 斤, 请/清/晴 via 青. Shape distance
    # can't reach these — its stroke gate is ±1 and 听 is five strokes lighter than 斯 — and
    # a bare "shares a part" rule would drag in 江/河. See pipeline/lookalike.py for the two
    # gates that separate the two cases.
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
    # Read back from data/derived/, derived once per Unihan snapshot rather than recomputed
    # on every build (lookalike.ensure_dataset explains why, and rebuilds if it has to).
    cached_pairs = lookalike.ensure_dataset(snapshot_paths["unihan"])
    selected_ids = {f"U+{cp:04X}" for cp in selected}
    freq_by_id = {f"U+{cp:04X}": rank for cp, rank in freq_rank_map.items()}
    for row in lookalike.confusable_rows(
        cached_pairs, selected_ids, freq_by_id, lookalike.MAX_FREQ_RANK,
    ):
        lo, hi = int(row[0][2:], 16), int(row[1][2:], 16)
        if (lo, hi) not in confusable_seen:   # a kSpoofingVariant pair is already stated
            confusable_seen.add((lo, hi))
            confusable_rows.append(row)

    # Stroke counts for the components themselves — they are not all selected glyphs (讠, 辶),
    # so they come from the entity table, and only for the parts actually referenced.
    referenced = {row[1] for row in component_use_rows if len(row[1]) == 1}
    component_strokes = {
        e.char: e.total_strokes for e in entities.values() if e.char in referenced
    }
    glyph_strokes = {row[0]: row[3] for row in glyph_rows}
    # A simplification edge already says 請/请 are two forms of one character; repeating it as
    # a look-alike would spend a drawer row on something the variants section states better.
    variant_pairs = frozenset(
        (min(v[0], v[1]), max(v[0], v[1])) for v in variant_edge_rows
    )
    for row in lookalike.component_pairs(
        component_use_rows, glyph_strokes, component_strokes, freq_by_id, layout_by_id,
        lookalike.MAX_FREQ_RANK, variant_pairs,
    ):
        lo, hi = int(row[0][2:], 16), int(row[1][2:], 16)
        if (lo, hi) not in confusable_seen:   # shape distance already vouched for it
            confusable_seen.add((lo, hi))
            confusable_rows.append(row)

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
    load.load_component_glosses(conn, component_gloss_rows)
    load.load_sentences(conn, sentence_rows)
    conn.commit()
    conn.close()

    print(f"built {db_path}")
    print(f"  glyphs={len(glyph_rows)} ({sum(1 for r in glyph_rows if r[7])} with CC-CEDICT senses) readings={len(reading_rows)} decompositions={len(decomposition_rows)}")
    print(f"  component_use={len(component_use_rows)} claims={len(claim_rows)}")
    print(f"  variant_edges={len(variant_edge_rows)} phonetic_series={len(phonetic_series_rows)} confusables={len(confusable_rows)}")
    print(f"  words={len(word_rows)} word_chars={len(word_char_rows)} sentences={len(sentence_rows)}")
    print(f"  component_glosses={len(component_gloss_rows)}")
    return db_path


if __name__ == "__main__":
    argparse.ArgumentParser(description="Build the full T1/T2 SQLite data pack").parse_args()
    build()
