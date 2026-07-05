"""Fetch cjkvi-ids (region-tagged IDS decompositions), GPL-licensed.

https://github.com/cjkvi/cjkvi-ids
"""
from __future__ import annotations

import shutil
from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/cjkvi/cjkvi-ids"
LICENSE = "GPL-2.0"

TRACKED_FILES = ["ids.txt"]


def fetch(force: bool = False) -> Path:
    tmp_clone = snapshot_dir("cjkvi-ids", "_tmp_clone")
    commit = git_shallow_clone(REPO_URL, tmp_clone)
    version = commit[:12]

    if not force and manifest_exists("cjkvi-ids", version):
        print(f"cjkvi-ids {version} already fetched, skipping")
        shutil.rmtree(tmp_clone)
        return snapshot_dir("cjkvi-ids", version)

    out_dir = snapshot_dir("cjkvi-ids", version)
    tracked = []
    for name in TRACKED_FILES:
        src = tmp_clone / name
        if not src.exists():
            raise FileNotFoundError(f"expected {name} in cjkvi-ids repo, not found")
        dest = out_dir / name
        dest.write_bytes(src.read_bytes())
        tracked.append(dest)

    shutil.rmtree(tmp_clone)

    write_manifest("cjkvi-ids", REPO_URL, version, LICENSE, out_dir, tracked)
    print(f"cjkvi-ids {version}: {len(tracked)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
