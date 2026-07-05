"""Fetch the Chinese wiktextract dump (kaikki.org) and filter to single-character entries.

Provides glyph origins, definitions, readings, and embedded Baxter-Sagart & Zhengzhang
reconstructions (CC BY-SA 4.0). The full dump is ~1.1 GB and mostly multi-character words;
for M0 we only need single-hanzi entries. The filtered JSONL is the versioned snapshot
artifact; the manifest records the upstream URL/retrieved_at for provenance.

Download is via `curl -C -` (resume) with `--speed-limit`/`--speed-time` stall detection
and `--retry`, rather than urllib: this host serves an initial fast burst and then throttles
hard, and a plain urllib read (even with a per-read socket timeout) doesn't reliably notice
a connection that's still trickling a few bytes — it just never finishes. curl's stall
detection aborts and resumes instead of hanging for hours. Decompression/filtering happens
as a separate local-disk pass after the download completes, so a retry never re-downloads
already-fetched bytes.
"""
from __future__ import annotations

import gzip
import json
import subprocess
import unicodedata
import urllib.request
from email.utils import parsedate_to_datetime
from pathlib import Path

from fetchers.common import manifest_exists, snapshot_dir, write_manifest

# The .gz variant is ~150 MB on the wire vs. ~1.1 GB for the plain .jsonl — same content,
# far less to transfer.
URL = "https://kaikki.org/dictionary/Chinese/kaikki.org-dictionary-Chinese.jsonl.gz"
LICENSE = "CC BY-SA 4.0"
CHUNK_SIZE = 1 << 20
SOCKET_TIMEOUT = 30


def _is_single_hanzi(word: str) -> bool:
    if len(word) != 1:
        return False
    return "CJK UNIFIED IDEOGRAPH" in unicodedata.name(word, "")


def _remote_version() -> str:
    req = urllib.request.Request(URL, method="HEAD", headers={"User-Agent": "hanzi-dictionary-pipeline/0.1"})
    with urllib.request.urlopen(req, timeout=SOCKET_TIMEOUT) as resp:
        last_modified = resp.headers.get("Last-Modified")
    if not last_modified:
        raise RuntimeError("wiktextract dump response had no Last-Modified header; cannot version snapshot")
    return parsedate_to_datetime(last_modified).date().isoformat()


def _download(gz_path: Path) -> None:
    subprocess.run(
        [
            "curl", "-fL", "-C", "-",
            "--speed-limit", "10240", "--speed-time", "20",  # abort if <10KB/s for 20s
            "--retry", "30", "--retry-delay", "3", "--retry-all-errors",
            "-A", "hanzi-dictionary-pipeline/0.1",
            "-o", str(gz_path),
            URL,
        ],
        check=True,
    )


def _filter(gz_path: Path, filtered_path: Path) -> tuple[int, int]:
    kept = 0
    seen = 0
    with gzip.open(gz_path, "rt", encoding="utf-8") as src, filtered_path.open("w", encoding="utf-8") as out:
        for line in src:
            if not line.strip():
                continue
            seen += 1
            entry = json.loads(line)
            if _is_single_hanzi(entry.get("word", "")):
                out.write(json.dumps(entry, ensure_ascii=False) + "\n")
                kept += 1
    return kept, seen


def fetch(force: bool = False) -> Path:
    version = _remote_version()
    if not force and manifest_exists("wiktextract", version):
        print(f"wiktextract {version} already fetched, skipping")
        return snapshot_dir("wiktextract", version)

    out_dir = snapshot_dir("wiktextract", version)
    gz_path = out_dir / "raw.jsonl.gz"
    filtered_path = out_dir / "zh-single-char.jsonl"

    print("downloading (curl, resumable)...")
    _download(gz_path)
    print("decompressing + filtering to single-character entries...")
    kept, seen = _filter(gz_path, filtered_path)
    gz_path.unlink()

    write_manifest("wiktextract", URL, version, LICENSE, out_dir, [filtered_path])
    print(f"wiktextract {version}: kept {kept}/{seen} single-character entries -> {filtered_path}")
    return out_dir


if __name__ == "__main__":
    fetch()
