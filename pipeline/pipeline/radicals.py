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
