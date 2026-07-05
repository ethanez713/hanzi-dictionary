"""Fetch OpenCC S<->T character conversion tables (Apache-2.0).

https://github.com/BYVoid/OpenCC — data/dictionary/{ST,TS}Characters.txt.
Character-level tables only for M0; phrase-level tables deferred (word table is v2+).
"""
from __future__ import annotations

import shutil
from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/BYVoid/OpenCC"
LICENSE = "Apache-2.0"

TRACKED_FILES = ["data/dictionary/STCharacters.txt", "data/dictionary/TSCharacters.txt"]


def fetch(force: bool = False) -> Path:
    tmp_clone = snapshot_dir("opencc", "_tmp_clone")
    commit = git_shallow_clone(REPO_URL, tmp_clone)
    version = commit[:12]

    if not force and manifest_exists("opencc", version):
        print(f"opencc {version} already fetched, skipping")
        shutil.rmtree(tmp_clone)
        return snapshot_dir("opencc", version)

    out_dir = snapshot_dir("opencc", version)
    tracked = []
    for rel in TRACKED_FILES:
        src = tmp_clone / rel
        if not src.exists():
            raise FileNotFoundError(f"expected {rel} in OpenCC repo, not found")
        dest = out_dir / Path(rel).name
        dest.write_bytes(src.read_bytes())
        tracked.append(dest)

    shutil.rmtree(tmp_clone)

    write_manifest("opencc", REPO_URL, version, LICENSE, out_dir, tracked)
    print(f"opencc {version}: {len(tracked)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
