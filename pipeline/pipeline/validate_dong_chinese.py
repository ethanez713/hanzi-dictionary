"""Validate the pinyin-stratum phonetic detector against Dong Chinese's human-verified
component-function labels (spec §12 M0: 'automated agreement check ... with manual
review only of disagreements'). PRIVATE QA ONLY — see fetch_dong_chinese.py docstring;
this data and script must never feed the published dataset or app data pack.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from fetchers.common import SNAPSHOT_ROOT

DB_PATH = Path(__file__).resolve().parent.parent / "data" / "build" / "hanzi.db"

# (call, char, left, right) — only the first 3 string args matter for our comparison;
# every semphon/phonsem/semsem call in the source is single-line (verified by
# inspection), so a line-level regex is sufficient and avoids needing a JS parser.
_CALL_RE = re.compile(
    r'\b(semphon|phonsem|semsem)\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"'
)


@dataclass
class DongEntry:
    char: str
    kind: str          # phonosemantic | semantic-semantic
    meaning_component: str
    sound_component: str | None  # None for semsem (no phonetic component claimed)


def _unescape(s: str) -> str:
    return s.replace('\\"', '"').replace("\\\\", "\\")


def parse_dong_chinese(index_js_path: Path) -> dict[str, DongEntry]:
    text = index_js_path.read_text(encoding="utf-8")
    entries: dict[str, DongEntry] = {}
    for call, char, left, right in _CALL_RE.findall(text):
        char, left, right = _unescape(char), _unescape(left), _unescape(right)
        if len(char) != 1:
            continue
        if call == "semphon":
            entries[char] = DongEntry(char, "phonosemantic", left, right)
        elif call == "phonsem":
            # phonsem(char, left, right, ...) == semphon(char, right, left, ...) (spec's own source reverses it)
            entries[char] = DongEntry(char, "phonosemantic", right, left)
        elif call == "semsem":
            entries[char] = DongEntry(char, "semantic-semantic", left, right)
    return entries


def latest_dong_snapshot() -> Path:
    root = SNAPSHOT_ROOT / "dong-chinese-lexicon-PRIVATE"
    versions = [d for d in root.iterdir() if d.is_dir() and (d / "manifest.json").exists()]
    if not versions:
        raise FileNotFoundError("no dong-chinese-lexicon snapshot; run fetchers.fetch_dong_chinese first")
    return max(versions, key=lambda d: json.loads((d / "manifest.json").read_text())["retrieved_at"])


def run_validation() -> None:
    dong_dir = latest_dong_snapshot()
    dong_entries = parse_dong_chinese(dong_dir / "index.js")
    print(f"parsed {len(dong_entries)} human-verified etymology entries from Dong Chinese")

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row

    agree = disagree = no_component_use = not_in_our_db = 0
    false_positives = 0  # semsem chars where we claimed a phonetic match anyway
    disagreements: list[str] = []

    for char, entry in dong_entries.items():
        gid = f"U+{ord(char):04X}"
        glyph = conn.execute("SELECT id FROM glyph WHERE id=?", (gid,)).fetchone()
        if not glyph:
            not_in_our_db += 1
            continue

        our_components = conn.execute(
            "SELECT component_id, function FROM component_use WHERE glyph_id=?", (gid,)
        ).fetchall()
        if not our_components:
            no_component_use += 1
            continue
        our_by_component = {row["component_id"]: row["function"] for row in our_components}

        if entry.kind == "phonosemantic":
            our_function = our_by_component.get(entry.sound_component)
            if our_function in ("phonetic", "both"):
                agree += 1
            else:
                disagree += 1
                disagreements.append(
                    f"{char}: Dong says sound={entry.sound_component!r} meaning={entry.meaning_component!r}; "
                    f"we computed {our_by_component}"
                )
        else:  # semantic-semantic: neither component should show as phonetic
            if any(f in ("phonetic", "both") for f in our_by_component.values()):
                false_positives += 1
                disagreements.append(f"{char}: Dong says semantic-semantic; we computed {our_by_component}")

    total_compared = agree + disagree
    print(f"\nphonosemantic comparisons: {total_compared}")
    if total_compared:
        print(f"  agree:    {agree} ({agree / total_compared:.1%})")
        print(f"  disagree: {disagree} ({disagree / total_compared:.1%})")
    print(f"semantic-semantic false-positive checks: {false_positives} flagged")
    print(f"skipped (not in our top-N build): {not_in_our_db}")
    print(f"skipped (no decomposition/component_use computed): {no_component_use}")

    if disagreements:
        print(f"\n--- disagreements for manual review ({len(disagreements)}) ---")
        for line in disagreements:
            print(" ", line)


if __name__ == "__main__":
    argparse.ArgumentParser(description="Validate phonetic detector vs Dong Chinese (private QA)").parse_args()
    run_validation()
