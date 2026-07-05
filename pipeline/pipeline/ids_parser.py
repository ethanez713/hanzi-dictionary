"""Minimal IDS (Ideographic Description Sequence) tree parser.

Only extracts *direct* (first-level) components — enough for component_use rows.
Recursing further is unnecessary for M0: unencoded compound operands get a
deterministic "ids:<subtree>" pseudo-ID per spec §7, and their own internal
structure isn't needed until a later milestone wants nested breakdowns.
"""
from __future__ import annotations

BINARY_OPERATORS = set("⿰⿱⿴⿵⿶⿷⿸⿹⿺⿻⿼⿽⿾⿿")
TERNARY_OPERATORS = set("⿲⿳")
ALL_OPERATORS = BINARY_OPERATORS | TERNARY_OPERATORS

Node = str | tuple[str, list["Node"]]


class IdsParseError(ValueError):
    pass


def parse_ids(ids: str) -> Node:
    if not ids:
        raise IdsParseError("empty IDS string")
    node, pos = _parse(ids, 0)
    if pos != len(ids):
        raise IdsParseError(f"trailing data in IDS string: {ids!r}")
    return node


def _parse(s: str, pos: int) -> tuple[Node, int]:
    if pos >= len(s):
        raise IdsParseError(f"unexpected end of IDS string: {s!r}")
    ch = s[pos]
    if ch in ALL_OPERATORS:
        arity = 3 if ch in TERNARY_OPERATORS else 2
        children: list[Node] = []
        pos += 1
        for _ in range(arity):
            child, pos = _parse(s, pos)
            children.append(child)
        return (ch, children), pos
    return ch, pos + 1


def serialize(node: Node) -> str:
    if isinstance(node, str):
        return node
    op, children = node
    return op + "".join(serialize(c) for c in children)


def direct_components(ids: str) -> list[str]:
    """Top-level operands: a literal char for a leaf, "ids:<subtree>" pseudo-ID for a
    compound operand without its own codepoint. Returns [ids] unchanged if atomic."""
    root = parse_ids(ids)
    if isinstance(root, str):
        return [root]
    _, children = root
    out = []
    for child in children:
        out.append(child if isinstance(child, str) else f"ids:{serialize(child)}")
    return out
