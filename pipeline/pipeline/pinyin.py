"""Pinyin syllable comparison: tone-mark stripping, initial/final split, and match
tiers (exact/tone/initial/rime) — the pinyin stratum of the phonetic detector
(spec §8.5; MC/OC strata deferred, per §15, to parsers not yet written)."""
from __future__ import annotations

_TONE_MARKS: dict[str, tuple[str, int]] = {}
_TONE_VOWELS = {
    "a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò",
    "u": "ūúǔù", "ü": "ǖǘǚǜ", "v": "ǖǘǚǜ",
    "A": "ĀÁǍÀ", "E": "ĒÉĚÈ", "I": "ĪÍǏÌ", "O": "ŌÓǑÒ",
    "U": "ŪÚǓÙ",
}
for _base, _marked in _TONE_VOWELS.items():
    for _tone_idx, _ch in enumerate(_marked, start=1):
        _TONE_MARKS[_ch] = (_base.lower(), _tone_idx)

# Pinyin initials, longest first so "zh"/"ch"/"sh" are matched before "z"/"c"/"s".
_INITIALS = sorted(
    ["zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
     "j", "q", "x", "r", "z", "c", "s", "y", "w"],
    key=len, reverse=True,
)

MATCH_RANK = {"exact": 4, "tone": 3, "initial": 2, "rime": 1}


def strip_tone(syllable: str) -> tuple[str, int | None]:
    """'zhōng' -> ('zhong', 1). Numeral-suffixed input ('zhong1') also supported."""
    syllable = syllable.strip().lower()
    if syllable and syllable[-1].isdigit():
        return syllable[:-1], int(syllable[-1])
    base_chars = []
    tone = None
    for ch in syllable:
        if ch in _TONE_MARKS:
            base_ch, t = _TONE_MARKS[ch]
            base_chars.append(base_ch)
            tone = t
        else:
            base_chars.append(ch)
    return "".join(base_chars), tone


def split_initial_final(base_syllable: str) -> tuple[str, str]:
    for initial in _INITIALS:
        if base_syllable.startswith(initial) and len(base_syllable) > len(initial):
            return initial, base_syllable[len(initial):]
    return "", base_syllable


def compare_syllables(a: str, b: str) -> str | None:
    """Return the best match tier between two pinyin syllables, or None."""
    base_a, tone_a = strip_tone(a)
    base_b, tone_b = strip_tone(b)
    if base_a == base_b:
        return "exact" if tone_a == tone_b else "tone"
    init_a, final_a = split_initial_final(base_a)
    init_b, final_b = split_initial_final(base_b)
    if init_a and init_a == init_b:
        return "initial"
    if final_a == final_b:
        return "rime"
    return None


def best_match(readings_a: list[str], readings_b: list[str]) -> str | None:
    """Compare every reading of a against every reading of b; return the best tier
    found, per spec §15 ('compares against all readings ... takes the best match')."""
    best: str | None = None
    for a in readings_a:
        for b in readings_b:
            m = compare_syllables(a, b)
            if m and (best is None or MATCH_RANK[m] > MATCH_RANK[best]):
                best = m
    return best
