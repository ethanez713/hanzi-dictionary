"""Fetch HSK vocabulary (drkameleon/complete-hsk-vocabulary, MIT) for HSK-level tagging.

Provides HSK 3.0 level membership, used to populate glyph.hsk (per-character level =
the lowest level of any word containing it) and to seed the app's HSK-level import
picker (spec §7, §15 "community MIT-licensed repos — word lists as facts; low risk").
"""
from __future__ import annotations

import shutil
from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/drkameleon/complete-hsk-vocabulary"
LICENSE = "MIT"

TRACKED_FILES = ["complete.min.json"]


def fetch(force: bool = False) -> Path:
    tmp_clone = snapshot_dir("hsk", "_tmp_clone")
    commit = git_shallow_clone(REPO_URL, tmp_clone)
    version = commit[:12]

    if not force and manifest_exists("hsk", version):
        print(f"hsk {version} already fetched, skipping")
        shutil.rmtree(tmp_clone)
        return snapshot_dir("hsk", version)

    out_dir = snapshot_dir("hsk", version)
    tracked = []
    for name in TRACKED_FILES:
        src = tmp_clone / name
        if not src.exists():
            raise FileNotFoundError(f"expected {name} in HSK repo, not found")
        dest = out_dir / name
        dest.write_bytes(src.read_bytes())
        tracked.append(dest)

    shutil.rmtree(tmp_clone)

    write_manifest("hsk", REPO_URL, version, LICENSE, out_dir, tracked)
    print(f"hsk {version}: {len(tracked)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
