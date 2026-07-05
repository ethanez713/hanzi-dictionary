"""Fetch the Chinese wiktextract dump (kaikki.org) and filter to single-character entries.

Provides glyph origins, definitions, readings, and embedded Baxter-Sagart & Zhengzhang
reconstructions (CC BY-SA 4.0). The full dump is ~1.1 GB and mostly multi-character words;
for M0 we only need single-hanzi entries, so we stream-filter and discard the raw download
rather than retaining it. The filtered JSONL is the versioned snapshot artifact; the
manifest records the upstream URL/retrieved_at for provenance.
"""
from __future__ import annotations

import json
import unicodedata
import urllib.request
from email.utils import parsedate_to_datetime
from pathlib import Path

from fetchers.common import manifest_exists, snapshot_dir, write_manifest

URL = "https://kaikki.org/dictionary/Chinese/kaikki.org-dictionary-Chinese.jsonl"
LICENSE = "CC BY-SA 4.0"
CHUNK_SIZE = 1 << 20


def _is_single_hanzi(word: str) -> bool:
    if len(word) != 1:
        return False
    return "CJK UNIFIED IDEOGRAPH" in unicodedata.name(word, "")


def _remote_version() -> str:
    req = urllib.request.Request(URL, method="HEAD", headers={"User-Agent": "hanzi-dictionary-pipeline/0.1"})
    with urllib.request.urlopen(req) as resp:
        last_modified = resp.headers.get("Last-Modified")
    if not last_modified:
        raise RuntimeError("wiktextract dump response had no Last-Modified header; cannot version snapshot")
    return parsedate_to_datetime(last_modified).date().isoformat()


def fetch(force: bool = False) -> Path:
    version = _remote_version()
    if not force and manifest_exists("wiktextract", version):
        print(f"wiktextract {version} already fetched, skipping")
        return snapshot_dir("wiktextract", version)

    out_dir = snapshot_dir("wiktextract", version)
    filtered_path = out_dir / "zh-single-char.jsonl"

    req = urllib.request.Request(URL, headers={"User-Agent": "hanzi-dictionary-pipeline/0.1"})
    kept = 0
    seen = 0
    buf = b""
    with urllib.request.urlopen(req) as resp, filtered_path.open("w", encoding="utf-8") as out:
        while chunk := resp.read(CHUNK_SIZE):
            buf += chunk
            *lines, buf = buf.split(b"\n")
            for line in lines:
                if not line.strip():
                    continue
                seen += 1
                entry = json.loads(line)
                if _is_single_hanzi(entry.get("word", "")):
                    out.write(json.dumps(entry, ensure_ascii=False) + "\n")
                    kept += 1
        if buf.strip():
            seen += 1
            entry = json.loads(buf)
            if _is_single_hanzi(entry.get("word", "")):
                out.write(json.dumps(entry, ensure_ascii=False) + "\n")
                kept += 1

    write_manifest("wiktextract", URL, version, LICENSE, out_dir, [filtered_path])
    print(f"wiktextract {version}: kept {kept}/{seen} single-character entries -> {filtered_path}")
    return out_dir


if __name__ == "__main__":
    fetch()
