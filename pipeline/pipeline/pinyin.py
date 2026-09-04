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


def mark_syllable(base: str, tone: int | None) -> str:
    """'zhong', 1 -> 'zhōng'. Placement rule: the mark goes on 'a' or 'e' if present,
    on the 'o' of 'ou', else on the last vowel. Tone 5/None (neutral) stays bare."""
    base = base.replace("u:", "ü").replace("v", "ü")
    if not tone or tone == 5:
        return base
    vowels = "aeiouü"
    idx = -1
    if "a" in base:
        idx = base.index("a")
    elif "e" in base:
        idx = base.index("e")
    elif "ou" in base:
        idx = base.index("o")
    else:
        for i, ch in enumerate(base):
            if ch in vowels:
                idx = i
    if idx == -1:
        return base
    marked_set = "ǖǘǚǜ" if base[idx] == "ü" else _TONE_VOWELS.get(base[idx])
    if marked_set is None:
        return base
    return base[:idx] + marked_set[tone - 1] + base[idx + 1:]


def cedict_pinyin(numbered: str) -> str:
    """CC-CEDICT reading field -> display pinyin: 'Zhong1 guo2' -> 'Zhōng guó'.
    Case is preserved (proper nouns); non-pinyin tokens (e.g. '·', 'xx') pass through."""
    out = []
    for token in numbered.split(" "):
        if token and token[-1].isdigit():
            base, tone = token[:-1], int(token[-1])
            was_title = base[:1].isupper()
            marked = mark_syllable(base.lower(), tone)
            out.append(marked[:1].upper() + marked[1:] if was_title else marked)
        else:
            out.append(token)
    return " ".join(out)


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


def neutralize_potential_bu(simp: str, numbered: str, known_words: set[str]) -> str:
    """Fix CC-CEDICT's inconsistent tone on the 不 of a potential complement (V不C).

    The 不 in 受不了 / 来不及 is unstressed in speech, but CC-CEDICT tags it bu4 in some
    entries and bu5 in others with no pattern to it — 及 as a complement is split 2/2, 得 is
    16/1. So the tone tagged on 不 is not evidence of anything and cannot be voted on.

    A V不C is only rewritten when its affirmative V得C is also a CC-CEDICT entry, which is
    what makes it a potential complement rather than a plain negation. Without that test the
    same shape sweeps up 三不管, 且不说 and the transliteration 冬不拉. The cost of the test
    is that a V不C whose affirmative CC-CEDICT happens to lack (等不及) is left alone."""
    if len(simp) != 3 or simp[1] != "不":
        return numbered
    tokens = numbered.split(" ")
    if len(tokens) != 3 or tokens[1].lower() != "bu4":
        return numbered
    if simp[0] + "得" + simp[2] not in known_words:
        return numbered
    return " ".join([tokens[0], "bu5", tokens[2]])
