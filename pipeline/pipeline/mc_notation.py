"""Baxter Middle Chinese notation: onset/rime/tone split and comparison tiers.

Mirrors pinyin.py's structure. Baxter's system (used verbatim by Wiktionary's
'Middle-Chinese'-tagged sounds, e.g. 'khwenX', 'maeX') writes tone as a suffix —
X (rising), H (departing), otherwise level, or entering when the rime already
ends in -p/-t/-k — and writes the medial glide (j/w/jw) as part of the syllable
body rather than the onset, so it's grouped with the rime here, same as pinyin's
medials are part of the "final" rather than the initial.
"""
from __future__ import annotations

# Longest-first so e.g. "tsrh" matches before "tsr" before "ts" before "t".
_ONSETS = sorted(
    [
        "tsrh", "tsyh",
        "tsr", "tsy", "dzr", "dzy",
        "ts", "dz", "ph", "th", "kh", "ng", "nr", "ny", "sr", "zr", "sy", "zy", "gh",
        "p", "b", "m", "t", "d", "n", "s", "z", "k", "g", "h", "x", "y", "l", "q", "'",
    ],
    key=len, reverse=True,
)

MATCH_RANK = {"exact": 4, "tone": 3, "initial": 2, "rime": 1}


def split_tone(syllable: str) -> tuple[str, str]:
    """Returns (base, tone) where tone is level|rising|departing|entering."""
    s = syllable.strip()
    if s.endswith("X"):
        return s[:-1], "rising"
    if s.endswith("H"):
        return s[:-1], "departing"
    if s and s[-1] in "ptk":
        return s, "entering"
    return s, "level"


def split_onset_rime(base: str) -> tuple[str, str]:
    for onset in _ONSETS:
        if base.startswith(onset) and len(base) > len(onset):
            return onset, base[len(onset):]
    return "", base


def compare_syllables(a: str, b: str) -> str | None:
    base_a, tone_a = split_tone(a)
    base_b, tone_b = split_tone(b)
    onset_a, rime_a = split_onset_rime(base_a)
    onset_b, rime_b = split_onset_rime(base_b)
    if onset_a == onset_b and rime_a == rime_b:
        return "exact" if tone_a == tone_b else "tone"
    if onset_a == onset_b:
        return "initial"
    if rime_a == rime_b:
        return "rime"
    return None


def best_match(readings_a: list[str], readings_b: list[str]) -> str | None:
    best: str | None = None
    for a in readings_a:
        for b in readings_b:
            m = compare_syllables(a, b)
            if m and (best is None or MATCH_RANK[m] > MATCH_RANK[best]):
                best = m
    return best
