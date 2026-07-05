"""Parse each source's raw snapshot format into simple codepoint-keyed structures.

No interpretation/merging here — that's normalize.py and merge.py. Every parser
returns data keyed by the Unicode codepoint of the character (int), and every
codepoint anywhere in the pipeline is rendered as "U+XXXX" via glyph_id().
"""
from __future__ import annotations

import json
import re
import unicodedata
from collections import defaultdict
from pathlib import Path


def glyph_id(char: str) -> str:
    return f"U+{ord(char):04X}"


def char_of(glyph_id_str: str) -> str:
    return chr(int(glyph_id_str.removeprefix("U+"), 16))


_CP_RE = re.compile(r"^U\+([0-9A-Fa-f]+)$")


def cp_to_int(cp: str) -> int:
    m = _CP_RE.match(cp)
    if not m:
        raise ValueError(f"not a codepoint reference: {cp!r}")
    return int(m.group(1), 16)


def parse_unihan_file(path: Path) -> dict[int, dict[str, list[str]]]:
    """Generic Unihan tab file -> {codepoint_int: {field: [values]}}."""
    out: dict[int, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cp, field, value = line.split("\t", 2)
        out[cp_to_int(cp)][field].append(value)
    return out


def parse_hanyu_pinlu(readings: dict[int, dict[str, list[str]]]) -> dict[int, list[tuple[str, int]]]:
    """kHanyuPinlu values look like 'yī(32747) yì(20)' -> [(pinyin, freq), ...] sorted desc."""
    out: dict[int, list[tuple[str, int]]] = {}
    # syllable is "anything but whitespace/parens/digits" rather than an enumerated accent
    # class — hand-enumerating tone-mark vowels previously missed the acute (2nd-tone) set.
    entry_re = re.compile(r"([^\s()0-9]+)\((\d+)\)")
    for cp, fields in readings.items():
        for raw in fields.get("kHanyuPinlu", []):
            pairs = [(p, int(f)) for p, f in entry_re.findall(raw)]
            if pairs:
                out.setdefault(cp, []).extend(pairs)
    for cp in out:
        out[cp].sort(key=lambda pf: -pf[1])
    return out


def parse_makemeahanzi(path: Path) -> dict[int, dict]:
    out: dict[int, dict] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        entry = json.loads(line)
        char = entry["character"]
        if len(char) != 1:
            continue
        out[ord(char)] = entry
    return out


_PLACEHOLDER_RE = re.compile(r"[①-⓿]")  # circled digits used as cjkvi-ids anonymous refs


def is_placeholder_ids(ids: str) -> bool:
    return bool(_PLACEHOLDER_RE.search(ids)) or ids == "？"


def parse_cjkvi_ids(path: Path) -> dict[int, list[tuple[str | None, str]]]:
    """ids.txt: codepoint TAB char TAB variant[REGIONS]? (TAB variant[REGIONS]?)*
    Returns {codepoint_int: [(region_or_None, ids_string), ...]} with regions expanded
    (one entry per region letter in a bracket group, e.g. "x[GJK]" -> G, J, K entries).
    """
    region_re = re.compile(r"^(.*)\[([A-Z]+)\]$")
    out: dict[int, list[tuple[str | None, str]]] = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        cp = cp_to_int(parts[0])
        for variant in parts[2:]:
            m = region_re.match(variant)
            if m:
                ids_str, regions = m.group(1), m.group(2)
                for region in regions:
                    out[cp].append((region, ids_str))
            else:
                out[cp].append((None, variant))
    return out


def parse_opencc_char_table(path: Path) -> dict[int, list[int]]:
    """STCharacters.txt / TSCharacters.txt: key TAB value1 [value2 ...] -> {cp: [cp, ...]}."""
    out: dict[int, list[int]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, values = line.split("\t", 1)
        if len(key) != 1:
            continue
        out[ord(key)] = [ord(v) for v in values.split(" ") if len(v) == 1]
    return out


def parse_wiktextract_single_char(path: Path) -> dict[int, list[dict]]:
    """zh-single-char.jsonl may have multiple entries per character (different senses/etyma)."""
    out: dict[int, list[dict]] = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        entry = json.loads(line)
        char = entry["word"]
        out[ord(char)].append(entry)
    return out


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)
