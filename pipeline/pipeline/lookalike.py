"""Visually-confusable character pairs, computed from Unihan shape encodings.

Unihan's own kSpoofingVariant is the only *authoritative* "these look alike" assertion,
and it is deliberately tiny — 75 pairs in this pack, of which exactly one joins two common
characters. It exists to defend against spoofed identifiers, not to teach 己/已/巳 apart.

The alternative the app shipped first — "shares a phonetic component" — answers a
different question. 请/清/晴 do look alike, but so, by that rule, do 江/河 (shared 氵) and,
once the decomposition bottoms out in strokes, 大/不 (both "contain" 一). Sharing a part is
a fact about how a character is *built*, not about whether you can tell two apart at a
glance.

What the literature actually does — Liu et al. 2011, whose confusion sets underpin the
SIGHAN Chinese spell-check tasks — is measure **shape-code distance**. Two encodings in
Unihan give that directly:

  * **kCangjie** — the character as a sequence of shape keys (妹 = VJD, 姑 = VJR). One key
    apart means one visual element differs.
  * **kFourCornerCode** — a digit per corner plus a supplementary digit, an index built
    precisely so that similar-looking characters file together.

Neither is sufficient alone. A short Cangjie code degenerates: 人 = O and 大 = K are "one
edit apart" the same way 妹 and 姑 are, but nobody confuses 人 with 大. So a candidate pair
must also be close in stroke count and share either its radical or its whole four-corner
code — the constraints that separate a shape near-miss from a coincidence of notation.

## Why this is a cached artifact

The pairs depend only on Unihan, never on the rest of the build, so they are derived **once**
into `data/derived/lookalikes.tsv` and read back on every subsequent build. Computing them
inline meant every build re-parsed the shape tables and re-ran the pairing — work that is
identical until Unihan itself changes, and enough extra resident memory on a 6.6 GB box to
get the build OOM-killed. [ensure_dataset] rebuilds only when the file is missing, stale
(different Unihan snapshot or pairing rules) or corrupt; [validate] is what decides.

Run it by hand with `python -m pipeline.derive_lookalikes` (`--check` to validate only).
"""
from __future__ import annotations

import datetime as _dt
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

DERIVED_DIR = Path(__file__).resolve().parent.parent / "data" / "derived"
DATASET_PATH = DERIVED_DIR / "lookalikes.tsv"

# Bump when the pairing rules change: a dataset generated under older rules is stale even
# though its Unihan snapshot still matches, and validate() will call for a rebuild.
RULE_VERSION = 1

# A pair is only worth shipping if a learner will meet both halves; unranked characters turn
# the drawer into a list of curiosities. Applied at pack-build time, not derive time, so the
# cached dataset stays independent of how any one build ranks characters.
MAX_FREQ_RANK = 4000

# Two glyphs more than this many strokes apart do not look alike, whatever their codes say.
# This, with the radical/four-corner agreement below, is what makes one-key Cangjie edits
# safe even on the very short codes: 人 = O and 大 = K are one edit apart on paper, but
# nothing else about them agrees. Gating on stroke count and shape family instead of on
# code length keeps the pairs those short codes DO get right — 大/天/太, 己/已/巴, 日/旦/曰.
MAX_STROKE_DELTA = 1

# Sanity floor for a complete dataset. The real yield is ~29k pairs across all of Unihan;
# anything under this means a truncated write or a half-parsed snapshot, not a lean corpus.
MIN_PAIRS = 15_000

# Pairs every correct dataset must contain — the textbook 形近字 confusions. A dataset that
# parses but has lost these is corrupt in a way a row count would never catch.
SPOT_CHECKS: tuple[tuple[str, str], ...] = (
    ("U+5DF1", "U+5DF2"),   # 己 / 已
    ("U+672A", "U+672B"),   # 未 / 末
    ("U+5927", "U+5929"),   # 大 / 天
    ("U+59B9", "U+59D1"),   # 妹 / 姑
    ("U+65E5", "U+65E6"),   # 日 / 旦
)


@dataclass(slots=True)
class Shape:
    """Everything about a character's *form* that the pairing rules read."""
    cangjie: str | None = None
    four_corner: str | None = None
    strokes: int | None = None
    radical: str | None = None


def load_shape_data(unihan_dir: Path) -> dict[int, Shape]:
    """Shape encodings straight from the Unihan snapshot.

    Deliberately independent of the rest of the pipeline: this is the whole input, so the
    derive step can run on its own without building anything else.
    """
    from pipeline import parsers

    shapes: dict[int, Shape] = defaultdict(Shape)
    dictlike = parsers.parse_unihan_file(
        unihan_dir / "Unihan_DictionaryLikeData.txt", fields={"kCangjie", "kFourCornerCode"},
    )
    for cp, fields in dictlike.items():
        s = shapes[cp]
        if fields.get("kCangjie"):
            s.cangjie = fields["kCangjie"][0].strip() or None
        if fields.get("kFourCornerCode"):
            # A character may list several four-corner codes; the first is the primary.
            s.four_corner = fields["kFourCornerCode"][0].split(" ")[0] or None

    irg = parsers.parse_unihan_file(
        unihan_dir / "Unihan_IRGSources.txt", fields={"kTotalStrokes", "kRSUnicode"},
    )
    for cp, fields in irg.items():
        if cp not in shapes:
            continue
        s = shapes[cp]
        if fields.get("kTotalStrokes"):
            try:
                s.strokes = int(fields["kTotalStrokes"][0].split(" ")[0])
            except ValueError:
                pass
        if fields.get("kRSUnicode"):
            # "radical.additional_strokes", sometimes with a ' marking a simplified radical
            # form — the radical number alone identifies the shape family.
            s.radical = fields["kRSUnicode"][0].split(" ")[0].split(".")[0].rstrip("'")
    return dict(shapes)


def _deletion_keys(code: str) -> list[str]:
    """The code itself plus every one-character deletion.

    Two strings are within one edit (substitution, insertion or deletion) exactly when
    these sets intersect — the symmetric-delete trick. Without it, finding one-edit
    neighbours means comparing every code against every other code of a similar length,
    which is ~400M comparisons across Unihan and far too slow to run at all.
    """
    return [code] + [code[:i] + code[i + 1:] for i in range(len(code))]


def _plausible(a: Shape, b: Shape) -> bool:
    """Shape codes propose; strokes and radical dispose."""
    if a.strokes is None or b.strokes is None:
        return False
    if abs(a.strokes - b.strokes) > MAX_STROKE_DELTA:
        return False
    same_radical = a.radical is not None and a.radical == b.radical
    same_corner = a.four_corner is not None and a.four_corner == b.four_corner
    return same_radical or same_corner


def compute_pairs(shapes: dict[int, Shape]) -> list[tuple[int, int, str]]:
    """-> sorted [(cp_a, cp_b, basis)], basis being which encoding vouched for the pair."""
    pairs: dict[tuple[int, int], str] = {}

    by_key: dict[str, list[int]] = defaultdict(list)
    for cp, s in shapes.items():
        if s.cangjie and s.strokes is not None:
            for key in _deletion_keys(s.cangjie):
                by_key[key].append(cp)
    for members in by_key.values():
        # A bucket holds codes that agree once one key is dropped; the gate below decides
        # which of those are actually look-alikes.
        for i, cp in enumerate(members):
            for other in members[i + 1:]:
                if _plausible(shapes[cp], shapes[other]):
                    pairs.setdefault((min(cp, other), max(cp, other)), "cangjie")

    # Same four corners in the same order is its own claim of similarity, and it catches
    # pairs whose Cangjie keying diverges (the two systems decompose differently).
    by_corner: dict[str, list[int]] = defaultdict(list)
    for cp, s in shapes.items():
        if s.four_corner:
            by_corner[s.four_corner].append(cp)
    for members in by_corner.values():
        for i, cp in enumerate(members):
            for other in members[i + 1:]:
                if _plausible(shapes[cp], shapes[other]):
                    pairs.setdefault((min(cp, other), max(cp, other)), "four_corner")

    return sorted((lo, hi, basis) for (lo, hi), basis in pairs.items())


# ---- the cached dataset ----------------------------------------------------------------

def write_dataset(pairs: list[tuple[int, int, str]], unihan_version: str, path: Path = DATASET_PATH) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# hanzi-dictionary derived dataset: visually-confusable character pairs",
        "# Generated by pipeline/lookalike.py from Unihan shape encodings (kCangjie,",
        "# kFourCornerCode, kTotalStrokes, kRSUnicode). Unicode License v3.",
        f"#unihan_version\t{unihan_version}",
        f"#rule_version\t{RULE_VERSION}",
        f"#pair_count\t{len(pairs)}",
        f"#generated\t{_dt.datetime.now(_dt.timezone.utc).isoformat()}",
    ]
    lines += [f"U+{lo:04X}\tU+{hi:04X}\t{basis}" for lo, hi, basis in pairs]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def read_dataset(path: Path = DATASET_PATH) -> tuple[dict[str, str], list[tuple[str, str, str]]]:
    """-> (metadata, rows). Raises OSError if absent; malformed rows are the caller's
    problem to catch via [validate]."""
    meta: dict[str, str] = {}
    rows: list[tuple[str, str, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            if "\t" in line:
                key, _, value = line[1:].partition("\t")
                meta[key] = value
            continue
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) == 3:
            rows.append((parts[0], parts[1], parts[2]))
    return meta, rows


def validate(unihan_version: str, path: Path = DATASET_PATH) -> list[str]:
    """-> list of reasons the cached dataset can't be trusted; empty means good.

    Checks presence, provenance (same Unihan snapshot, same pairing rules), completeness
    (the header's own count, and a floor), shape of every row, and that the textbook pairs
    actually made it in. A truncated or half-written file fails on the count; a file left
    over from older rules fails on rule_version.
    """
    if not path.exists():
        return [f"{path.name} is missing"]
    try:
        meta, rows = read_dataset(path)
    except (OSError, UnicodeDecodeError) as e:
        return [f"{path.name} is unreadable: {e}"]

    problems: list[str] = []
    if meta.get("unihan_version") != unihan_version:
        problems.append(
            f"built from Unihan {meta.get('unihan_version', '?')}, snapshot is {unihan_version}",
        )
    if meta.get("rule_version") != str(RULE_VERSION):
        problems.append(f"built under rule_version {meta.get('rule_version', '?')}, current is {RULE_VERSION}")
    declared = meta.get("pair_count")
    if declared is not None and declared.isdigit() and int(declared) != len(rows):
        problems.append(f"truncated: header says {declared} pairs, file holds {len(rows)}")
    if len(rows) < MIN_PAIRS:
        problems.append(f"only {len(rows)} pairs, expected at least {MIN_PAIRS}")

    malformed = [r for r in rows[:1000] if not (r[0].startswith("U+") and r[1].startswith("U+") and r[2])]
    if malformed:
        problems.append(f"{len(malformed)} malformed rows (e.g. {malformed[0]})")

    present = {(a, b) for a, b, _ in rows}
    missing = [p for p in SPOT_CHECKS if p not in present]
    if missing:
        problems.append("missing known pairs: " + ", ".join(f"{a}/{b}" for a, b in missing))
    return problems


def ensure_dataset(unihan_dir: Path, path: Path = DATASET_PATH, force: bool = False) -> list[tuple[str, str, str]]:
    """The build's entry point: a valid dataset, rebuilding it only if it has to.

    [unihan_dir] is the snapshot directory; its name is the version the dataset is stamped
    with, which is what makes "the snapshot moved on" detectable.
    """
    import json

    unihan_version = json.loads((unihan_dir / "manifest.json").read_text())["version"]
    problems = ["forced rebuild"] if force else validate(unihan_version, path)
    if problems:
        print(f"deriving look-alike pairs ({'; '.join(problems)})...")
        pairs = compute_pairs(load_shape_data(unihan_dir))
        write_dataset(pairs, unihan_version, path)
        remaining = validate(unihan_version, path)
        if remaining:
            raise RuntimeError(f"freshly derived look-alike dataset is still invalid: {remaining}")
        print(f"  wrote {len(pairs)} pairs -> {path}")
    _, rows = read_dataset(path)
    return rows


def confusable_rows(
    rows: list[tuple[str, str, str]],
    selected: set[str],
    freq_rank: dict[str, int],
    max_freq_rank: int,
) -> list[tuple[str, str, str, str]]:
    """The cached pairs narrowed to what this pack should ship: both halves present, and
    both common enough that a learner will actually meet them (an unranked near-twin is a
    curiosity, not a confusion). Rows are shaped for the `confusable` table."""
    out = []
    for a, b, basis in rows:
        if a in selected and b in selected:
            if freq_rank.get(a, 10**9) <= max_freq_rank and freq_rank.get(b, 10**9) <= max_freq_rank:
                out.append((a, b, "shape", basis))
    return out
