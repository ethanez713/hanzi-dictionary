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

Yield on this pack: ~2.5k pairs among common characters, covering 148 of the 300
most-frequent (447 of the top 1000), and the pairs it finds are the classic ones —
己/已/巴, 未/术/末, 土/士, 王/玉, 大/天/太, 日/旧/旦/曰.
"""
from __future__ import annotations

from collections import defaultdict
from typing import Iterable

# A pair is only worth showing if a learner will actually meet both halves; unranked
# characters make the drawer a list of curiosities. Matches the app's own look-alike
# frequency ceiling.
MAX_FREQ_RANK = 4000

# Two glyphs more than this many strokes apart do not look alike, whatever their codes say.
# This, with the radical/four-corner agreement below, is what makes one-key Cangjie edits
# safe even on the very short codes: 人 = O and 大 = K are one edit apart on paper, but
# nothing else about them agrees. Gating on stroke count and shape family instead of on
# code length keeps the pairs those short codes DO get right — 大/天/太, 己/已/巴, 日/旦/曰.
MAX_STROKE_DELTA = 1


def _one_edit(a: str, b: str) -> bool:
    """True when b is a one-key substitution, insertion or deletion away from a."""
    if a == b:
        return True
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) == 1
    if abs(len(a) - len(b)) == 1:
        short, long = (a, b) if len(a) < len(b) else (b, a)
        return any(long[:i] + long[i + 1:] == short for i in range(len(long)))
    return False


def compute_shape_confusables(
    entities: dict[int, object],
    selected: Iterable[int],
    freq_rank: dict[int, int],
    max_freq_rank: int = MAX_FREQ_RANK,
) -> list[tuple[str, str, str, str]]:
    """-> [(glyph_a, glyph_b, kind, diff)] rows for the `confusable` table, kind='shape'.

    `diff` records which encoding vouched for the pair ("cangjie" / "four_corner"), so the
    provenance survives into the pack rather than being lost in a boolean.
    """
    pool = [cp for cp in selected if freq_rank.get(cp, 10**9) <= max_freq_rank]

    # Index by Cangjie length so each character is only compared against codes that could
    # be within one edit of it, and by four-corner code for the exact-match half.
    by_len: dict[int, list[int]] = defaultdict(list)
    by_corner: dict[str, list[int]] = defaultdict(list)
    for cp in pool:
        e = entities[cp]
        if e.cangjie:
            by_len[len(e.cangjie)].append(cp)
        if e.four_corner:
            by_corner[e.four_corner].append(cp)

    def plausible(a, b) -> bool:
        """Shape codes propose; strokes and radical dispose."""
        if a.total_strokes is None or b.total_strokes is None:
            return False
        if abs(a.total_strokes - b.total_strokes) > MAX_STROKE_DELTA:
            return False
        same_radical = a.radical is not None and a.radical == b.radical
        same_corner = a.four_corner is not None and a.four_corner == b.four_corner
        return same_radical or same_corner

    rows: dict[tuple[int, int], str] = {}

    for cp in pool:
        a = entities[cp]
        if not a.cangjie:
            continue
        for length in (len(a.cangjie) - 1, len(a.cangjie), len(a.cangjie) + 1):
            for other in by_len.get(length, ()):
                if other == cp:
                    continue
                b = entities[other]
                if _one_edit(a.cangjie, b.cangjie) and plausible(a, b):
                    rows.setdefault(tuple(sorted((cp, other))), "cangjie")

    # Same four corners in the same order is its own claim of similarity, and it catches
    # pairs whose Cangjie keying diverges (the two systems decompose differently).
    for members in by_corner.values():
        for i, cp in enumerate(members):
            for other in members[i + 1:]:
                if plausible(entities[cp], entities[other]):
                    rows.setdefault((min(cp, other), max(cp, other)), "four_corner")

    return [
        (f"U+{lo:04X}", f"U+{hi:04X}", "shape", basis)
        for (lo, hi), basis in sorted(rows.items())
    ]
