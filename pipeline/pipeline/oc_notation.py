"""Zhengzhang Old Chinese notation: onset/rime/tone split and comparison tiers.

Mirrors pinyin.py's structure. Wiktionary stores Zhengzhang OC as IPA between
slashes with a leading asterisk, e.g. '/*mraːʔ/', '/*kʰluds/'. Used instead of
Baxter-Sagart notation for the OC stratum: Zhengzhang has ~3x the coverage in
the wiktextract dump (24k vs 7.5k entries) and its plain-IPA form is far more
tractable to parse than B-S's bracket-and-superscript notation for uncertain
segments — both are "embedded ... reconstructions" per spec §5's source table,
so either is a legitimate OC stratum source.

OC onset clusters (pre-initial + main initial, e.g. sesquisyllabic "kʰl-",
"ŋɡ-") are far more varied than Baxter MC's, so rather than an enumerated
onset table this splits at the first vowel letter: everything before is
"onset" (however long), everything from the vowel on is "rime" (including
coda and the -ʔ/-s tone suffixes, matching how OC tonogenesis actually
attached them).
"""
from __future__ import annotations

_VOWELS = set("aeiouɯ")
MATCH_RANK = {"exact": 4, "tone": 3, "initial": 2, "rime": 1}


def strip_slashes(raw: str) -> str:
    s = raw.strip()
    if s.startswith("/*"):
        s = s[2:]
    elif s.startswith("/"):
        s = s[1:]
    if s.endswith("/"):
        s = s[:-1]
    return s.replace("?", "")  # rare uncertainty marker, not a phoneme


def split_tone(syllable: str) -> tuple[str, str]:
    """Returns (base, tone) where tone is rising|departing|other. OC *-ʔ and *-s
    are reconstructed tonogenesis suffixes (-> MC rising/departing), not root
    phonemes, so they're stripped here rather than folded into the rime."""
    s = syllable
    if s.endswith("ʔ"):
        return s[:-1], "rising"
    if s.endswith("s") and len(s) > 1:
        return s[:-1], "departing"
    return s, "other"


def split_onset_rime(base: str) -> tuple[str, str]:
    for i, ch in enumerate(base):
        if ch in _VOWELS:
            onset, rime = base[:i], base[i:]
            # medial glides (j/w) pattern with the rime, not the onset proper —
            # same treatment as Baxter MC's j/w medials and pinyin's medial vowels —
            # so e.g. "pl-" and "plj-" compare as the same onset "pl" with the
            # glide folded into the rime, matching how these reconstructions
            # actually group sesquisyllabic onset clusters vs. their medials.
            while onset and onset[-1] in "jw":
                rime = onset[-1] + rime
                onset = onset[:-1]
            return onset, rime
    return base, ""  # no vowel found (rare/malformed entry): treat as bare onset


def compare_syllables(a: str, b: str) -> str | None:
    base_a, tone_a = split_tone(strip_slashes(a))
    base_b, tone_b = split_tone(strip_slashes(b))
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
