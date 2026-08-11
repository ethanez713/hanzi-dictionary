"""Visually-confusable character pairs: two layers, two different kinds of evidence.

Unihan's own kSpoofingVariant is the only *authoritative* "these look alike" assertion,
and it is deliberately tiny — 75 pairs in this pack, of which exactly one joins two common
characters. It exists to defend against spoofed identifiers, not to teach 己/已/巳 apart.

## Layer 1: shape-code distance (`compute_pairs`, the cached dataset)

What the literature does — Liu et al. 2011, whose confusion sets underpin the SIGHAN
Chinese spell-check tasks — is measure **shape-code distance**. Two encodings in Unihan
give that directly:

  * **kCangjie** — the character as a sequence of shape keys (妹 = VJD, 姑 = VJR). One key
    apart means one visual element differs.
  * **kFourCornerCode** — a digit per corner plus a supplementary digit, an index built
    precisely so that similar-looking characters file together.

Neither is sufficient alone. A short Cangjie code degenerates: 人 = O and 大 = K are "one
edit apart" the same way 妹 and 姑 are, but nobody confuses 人 with 大. So a candidate pair
must also be close in stroke count and share either its radical or its whole four-corner
code — the constraints that separate a shape near-miss from a coincidence of notation.

## Layer 2: a shared component big enough to dominate (`component_pairs`)

Shape-code distance only ever pairs characters of nearly the same size — its stroke gate is
±1 — so it cannot see the *other* everyday confusion: 斯 / 期 (both 其), 斯 / 听 (both 斤),
请 / 清 / 晴 (all 青). Those differ by several strokes but share most of their ink.

"Shares a component" on its own is the rule this file's first version rejected, and rightly:
by that rule 江/河 look alike (shared 氵) and, once a decomposition bottoms out, so do 大/不
(both "contain" 一). What makes the difference is *which* component is shared. Two gates
turn the rule from noise into signal:

  * **the component is distinctive** — it appears in at most [MAX_SHARED_FAMILY] of the
    characters common enough to ship. 其 is in 5 of them, 青 12, 斤 14; 氵 is in 179 and 口
    in 198. A part that files hundreds of characters together is a filing system, not a
    resemblance — nobody mistakes 江 for 河 because both are wet.
  * **the component is most of the character** — at least [MIN_SHARED_STROKES] strokes of
    its own, and at least a third of each character's total. 斤 is 4 of 听's 7 strokes and 4
    of 斯's 12, so it carries that pair; 一 and 丶 never clear the floor at all, which is
    what stops a decomposition that bottoms out in strokes from pairing everything.
  * **it sits in the same place in both** — same top-level IDS operator, same slot within it.
    Sharing a part you'd have to go looking for is not a resemblance: 的 and 怕 both contain
    白, on opposite sides, and nobody has ever mixed them up. 情/清/晴 keep 青 on the right in
    every member, which is exactly why that set is hard.

## Why layer 1 is a cached artifact

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

# Layer 2 (shared component) gates. See the module docstring for what each one rules out.
#
# How many *shippable* characters (freq_rank <= MAX_FREQ_RANK) may use a component before it
# stops being a resemblance and becomes a filing category. 20 keeps 其 (5), 寺 (8), 青 (12),
# 斤 (14) and drops 月 (72), 亻 (123), 木 (135), 氵 (179), 口 (198). It also bounds the yield:
# a family of n contributes n(n-1)/2 pairs, so nothing can explode.
MAX_SHARED_FAMILY = 20

# A component below this is a stroke, not a shape you mistake a whole character for.
MIN_SHARED_STROKES = 4

# ...and it has to be this much of each character, or a small part ties a simple character to
# an elaborate one. Expressed as a reciprocal so the test is integer arithmetic.
MIN_SHARED_FRACTION = 3   # component >= 1/3 of the character's strokes

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


def component_pairs(
    component_use: list[tuple],
    glyph_strokes: dict[str, int | None],
    component_strokes: dict[str, int | None],
    freq_rank: dict[str, int],
    layout: dict[str, str],
    max_freq_rank: int = MAX_FREQ_RANK,
    exclude: frozenset[tuple[str, str]] = frozenset(),
) -> list[tuple[str, str, str, str]]:
    """Layer 2: pairs that share a component big and distinctive enough to be confusable.

    [component_use] is the pack's own component_use rows — `(glyph_id, component_id, position,
    ...)`, where component_id is the component's character (or an "ids:" pseudo-ID for an
    unencoded shape, which is skipped: an unrenderable part can't be named as the thing two
    characters have in common). Only top-level components count, which is deliberate — 情
    shares 青 with 请 at the top level, and going deeper is how "contains 一" creeps back in.

    [layout] maps a glyph to its top-level IDS operator (⿰, ⿱, …); with `position` it says
    *where* the component sits, and two characters only look alike if it sits in the same
    place in both.

    [exclude] drops pairs already stated more precisely elsewhere; the build passes the
    simplification edges, so the drawer never offers 請 as a look-alike for 请.

    Rows are shaped for the `confusable` table, with the shared component in `diff` so the UI
    can say *why* — "shares 其" is the whole lesson.
    """
    # Two groupings, because the two gates ask different questions. The family is every common
    # character carrying the part, wherever it sits — that is what says whether the part is a
    # resemblance or a filing category. The slot is (part, top-level operator, position), and
    # only characters in the same slot are ever paired.
    family: dict[str, set[str]] = defaultdict(set)
    slots: dict[tuple[str, str, int], set[str]] = defaultdict(set)
    for row in component_use:
        gid, component, position = row[0], row[1], row[2]
        if len(component) != 1:          # "ids:⿰…" pseudo-component
            continue
        if freq_rank.get(gid, 10**9) > max_freq_rank:
            continue
        family[component].add(gid)
        operator = layout.get(gid)
        if operator is not None:
            slots[(component, operator, position)].add(gid)

    out: list[tuple[str, str, str, str]] = []
    for (component, _operator, _position), members in sorted(slots.items()):
        cs = component_strokes.get(component)
        if cs is None or cs < MIN_SHARED_STROKES:
            continue
        if len(family[component]) > MAX_SHARED_FAMILY:
            continue
        # The component has to be a third of each half, so the eligible members are decided
        # once per slot rather than per pair.
        eligible = sorted(
            gid for gid in members
            if (gs := glyph_strokes.get(gid)) is not None and cs * MIN_SHARED_FRACTION >= gs
        )
        for i, a in enumerate(eligible):
            for b in eligible[i + 1:]:
                if (a, b) not in exclude:
                    out.append((a, b, "component", component))
    return out
