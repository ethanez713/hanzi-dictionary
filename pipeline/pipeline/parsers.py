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


def parse_hsk_char_levels(path: Path) -> dict[int, int]:
    """complete.min.json entry: {"s": simplified, "l": ["n1","o2",...], ...}.
    Returns {codepoint_int: lowest new-HSK (n1..n7) level of any word containing it}.
    n7 is the combined HSK 7-9 band; we record it as level 7."""
    data = json.loads(path.read_text(encoding="utf-8"))
    out: dict[int, int] = {}
    for entry in data:
        word = entry.get("s", "")
        new_levels = [int(code[1:]) for code in entry.get("l", []) if code.startswith("n") and code[1:].isdigit()]
        if not new_levels:
            continue
        level = min(new_levels)
        for ch in word:
            if "CJK UNIFIED IDEOGRAPH" in unicodedata.name(ch, ""):
                cp = ord(ch)
                if cp not in out or level < out[cp]:
                    out[cp] = level
    return out


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


_CEDICT_LINE = re.compile(r"^(\S+) (\S+) \[([^\]]*)\] /(.+)/$")


def parse_cedict(path: Path) -> list[tuple[str, str, str, list[str]]]:
    """CC-CEDICT line 'Trad Simp [pin1 yin1] /def1/def2/' -> (trad, simp, numbered_pinyin,
    [defs]). Comment lines skipped; malformed lines dropped rather than crashing."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        m = _CEDICT_LINE.match(line)
        if not m:
            continue
        trad, simp, pinyin, defs_raw = m.groups()
        defs = [d for d in defs_raw.split("/") if d]
        out.append((nfc(trad), nfc(simp), pinyin, defs))
    return out


def parse_hsk_word_levels(path: Path) -> dict[str, int]:
    """complete.min.json -> {simplified word: lowest new-HSK level} (word-level
    counterpart of parse_hsk_char_levels; n7 = the combined 7-9 band)."""
    data = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, int] = {}
    for entry in data:
        word = entry.get("s", "")
        new_levels = [int(code[1:]) for code in entry.get("l", []) if code.startswith("n") and code[1:].isdigit()]
        if word and new_levels:
            level = min(new_levels)
            if word not in out or level < out[word]:
                out[word] = level
    return out


def parse_word_frequencies(path: Path) -> dict[str, int]:
    """FrequencyWords zh_cn_full.txt ("word count" lines, sorted most-frequent-first)
    -> {word: rank}. Rank is the 1-based line position; gaps left by entries the pack
    doesn't carry are harmless since only relative order is consumed."""
    out: dict[str, int] = {}
    with path.open(encoding="utf-8") as f:
        for rank, line in enumerate(f, start=1):
            word = line.split(" ", 1)[0].strip()
            if word and word not in out:
                out[word] = rank
    return out
