"""Learner-useful glosses for radical-form components (讠 氵 扌 …).

Unihan's kDefinition for many combining radical forms is pure metadata —
"simplified Kangxi radical 149" — which tells a learner nothing about what the
component *means*. Each such form has a canonical parent character (讠→言, 氵→水:
a fixed Unicode/paleographic fact, not interpretive etymology), so when a
definition is only radical metadata we derive a gloss from the parent's own
kDefinition instead: "words, speech (radical form of 言)".

Forms whose kDefinition already leads with a meaning ("heart; radical number 61")
are left alone — the meaning is already first.
"""
from __future__ import annotations

import re

# Combining/radical form -> the character it abbreviates. Only forms that appear as
# IDS components and whose kDefinition is (or may be) bare metadata need an entry.
RADICAL_PARENT: dict[str, str] = {
    "讠": "言", "訁": "言",
    "氵": "水", "氺": "水",
    "忄": "心",
    "扌": "手",
    "亻": "人",
    "犭": "犬",
    "饣": "食", "飠": "食",
    "纟": "糸", "糹": "糸",
    "钅": "金", "釒": "金",
    "衤": "衣",
    "礻": "示",
    "刂": "刀",
    "灬": "火",
    "冫": "冰",
    "艹": "艸",
    "辶": "辵",
    "廴": "辵",
    "攵": "攴",
    "牜": "牛",
    "罒": "网",
    "𤣩": "玉",
    "⺼": "肉",
}

# 阝 is two radicals in one shape depending on side; no single parent tells the truth.
SPECIAL_GLOSSES: dict[str, str] = {
    "阝": "mound 阜 (on the left) / city 邑 (on the right)",
}

_META_ONLY = re.compile(
    r"^\(?(simplified\s+)?(kangxi\s+)?radical(\s+number)?\s+\d+\)?$", re.IGNORECASE
)


def is_meta_only_definition(definition: str | None) -> bool:
    """True when the definition carries no meaning, only radical bookkeeping."""
    return definition is not None and bool(_META_ONLY.match(definition.strip()))


def _strip_radical_tail(definition: str) -> str:
    """'walk; KangXi radical 162' -> 'walk' (drop bookkeeping senses)."""
    senses = [s.strip() for s in definition.split(";")]
    kept = [s for s in senses if not re.search(r"radical(\s+number)?\s+\d+", s, re.IGNORECASE)]
    return "; ".join(kept) if kept else definition


def radical_gloss(char: str, definition: str | None, parent_definition_of: dict[str, str | None]) -> str | None:
    """The gloss to ship for [char], or None to keep [definition] as-is.

    parent_definition_of maps parent char -> its kDefinition (caller supplies it
    from the normalized entities).
    """
    if char in SPECIAL_GLOSSES:
        return SPECIAL_GLOSSES[char]
    if definition is not None and not is_meta_only_definition(definition):
        return None
    parent = RADICAL_PARENT.get(char)
    if parent is None:
        return None
    parent_def = parent_definition_of.get(parent)
    if not parent_def:
        return None
    return f"{_strip_radical_tail(parent_def)} (radical form of {parent})"


# --- Role glosses: what a component contributes, not what it means alone ---------------
#
# A component's own dictionary gloss is the wrong thing to print on a breakdown card
# whenever its modern standalone sense is a borrowing: 页 is "page" in the dictionary but
# lends 项 its "head", 王 is "king" but is 玉 "jade" in 玩/珠/环, 贝 is "sea shell" but means
# "money" in 财/购. makemeahanzi records the role meaning per character in
# etymology.hint, so the gloss for a component is the hint its characters agree on.
#
# The vote is over pictophonetic entries only. There, hint names the semantic component's
# contribution and is one word 99% of the time; on pictographic/ideographic entries it is
# instead a prose sentence about the whole character ("A person's head") and would poison
# the count.

MIN_ROLE_SUPPORT = 3

# Curated glosses OVERRIDE the vote; they are not a fallback for a low agreement score.
# Agreement is recorded but deliberately not a gate: the hints are free text, so a split
# usually means synonyms rather than doubt (艹 is plant/grass/herb 172/118/30, 木 is
# tree/wood 170/132) and gating on it would reject the two largest components in the pack
# while admitting nothing better. What actually goes wrong is specific and rare, so it is
# named here: 又 has no pictophonetic evidence at all (it is a semantic part almost only in
# ideographic characters like 及), 寸 has two votes, 攵 splits across a real disagreement
# rather than synonyms (script/strike/rap at 7/7/6), and 鬼's modal "demon" is narrower than
# the role it plays.
# The entries below the first group close gaps rather than correct errors: a component with
# too few pictophonetic characters to vote (人, against 亻's 141), or an unencoded form with
# no glyph row at all, which is why nothing else in the pipeline can reach it.
CURATED_ROLE_GLOSSES: dict[str, str] = {
    "又": "hand",
    "攵": "action, striking",
    "寸": "hand, measure",
    "鬼": "ghost, spirit",
    # 阝 is two radicals sharing one shape, and which one it is depends on the side it sits
    # on — the vote flattens that to "place", which is true but throws the distinction away.
    "阝": "mound (left) / city (right)",
    "人": "person",
    "⻏": "city",
    "𤣩": "jade",
    "龰": "foot",
    "冎": "bone",
}


def _role_hint(etymology: dict) -> str | None:
    """The one-word role meaning in a makemeahanzi etymology, or None if it isn't one.

    Hints longer than two words are prose about the character rather than a gloss for the
    part ("water in a spirit level", "simplified form of 鳥 bird") — excluded so they don't
    dilute the agreement ratio that decides whether the vote is trustworthy."""
    if etymology.get("type") != "pictophonetic":
        return None
    hint = (etymology.get("hint") or "").replace("\xa0", " ").strip().lower()
    if not hint or len(hint.split()) > 2:
        return None
    return hint


def vote_role_glosses(makemeahanzi_by_cp: dict[int, dict]) -> dict[str, tuple[str, str, int, float]]:
    """component char -> (gloss, source_id, support, agreement).

    Support is the number of characters voting for the winning hint, agreement its share of
    all votes for that component — both shipped so the app and a later audit can see how
    thin a gloss's evidence is."""
    votes: dict[str, dict[str, int]] = {}
    for entry in makemeahanzi_by_cp.values():
        etymology = entry.get("etymology") or {}
        component = etymology.get("semantic")
        hint = _role_hint(etymology)
        if component and hint:
            votes.setdefault(component, {})[hint] = votes.setdefault(component, {}).get(hint, 0) + 1

    out: dict[str, tuple[str, str, int, float]] = {}
    for component, counts in votes.items():
        gloss, support = max(counts.items(), key=lambda kv: (kv[1], kv[0]))
        agreement = support / sum(counts.values())
        if support >= MIN_ROLE_SUPPORT:
            out[component] = (gloss, "makemeahanzi", support, agreement)
    for component, gloss in CURATED_ROLE_GLOSSES.items():
        out[component] = (gloss, "curated", 0, 1.0)
    return out
