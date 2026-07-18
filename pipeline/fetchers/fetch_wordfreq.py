"""Fetch the OpenSubtitles-derived Chinese word frequency list (hermitdave/
FrequencyWords, content CC BY-SA 4.0) to populate word.freq_rank.

Chosen over SUBTLEX-CH (research-use terms) and wordfreq (msgpack data needing a
non-stdlib parser): same subtitle-corpus lineage, plain "word count" text lines the
stdlib parses in one pass, and a license the pack already carries via CC-CEDICT.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

from fetchers.common import download, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/hermitdave/FrequencyWords"
RAW_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/zh_cn/zh_cn_full.txt"
LICENSE = "CC BY-SA 4.0"


def _head_commit() -> str:
    out = subprocess.run(
        ["git", "ls-remote", REPO_URL, "HEAD"], check=True, capture_output=True, text=True
    ).stdout
    return out.split()[0][:12]


def fetch(force: bool = False) -> Path:
    version = _head_commit()

    if not force and manifest_exists("wordfreq", version):
        print(f"wordfreq {version} already fetched, skipping")
        return snapshot_dir("wordfreq", version)

    out_dir = snapshot_dir("wordfreq", version)
    dest = out_dir / "zh_cn_full.txt"
    try:
        download(RAW_URL, dest)
    except Exception:
        shutil.rmtree(out_dir, ignore_errors=True)
        raise

    write_manifest("wordfreq", REPO_URL, version, LICENSE, out_dir, [dest])
    print(f"wordfreq {version}: zh_cn_full.txt -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
