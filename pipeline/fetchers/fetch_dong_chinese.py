"""Fetch peterolson/chinese-lexicon (built for dong-chinese.com) for PRIVATE VALIDATION
ONLY — human-verified component function labels for ~3,800 characters.

Not part of fetch_all/FETCHERS: spec §5 flags this source's license as unconfirmed for
publication ("confirm license before ingesting"), but explicitly allows private QA use
regardless ("usable privately for validation regardless", spec §12 M0). This snapshot
must never be merged into the published dataset or the app data pack.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/peterolson/chinese-lexicon"
LICENSE = "UNCONFIRMED — private validation use only, not for publication (spec §5)"

TRACKED_FILES = ["etymology/index.js", "etymology/etymologyCommands.js"]


def fetch(force: bool = False) -> Path:
    tmp_clone = snapshot_dir("dong-chinese-lexicon-PRIVATE", "_tmp_clone")
    commit = git_shallow_clone(REPO_URL, tmp_clone)
    version = commit[:12]

    if not force and manifest_exists("dong-chinese-lexicon-PRIVATE", version):
        print(f"dong-chinese-lexicon (private) {version} already fetched, skipping")
        shutil.rmtree(tmp_clone)
        return snapshot_dir("dong-chinese-lexicon-PRIVATE", version)

    out_dir = snapshot_dir("dong-chinese-lexicon-PRIVATE", version)
    tracked = []
    for rel in TRACKED_FILES:
        src = tmp_clone / rel
        if not src.exists():
            raise FileNotFoundError(f"expected {rel} in chinese-lexicon repo, not found")
        dest = out_dir / Path(rel).name
        dest.write_bytes(src.read_bytes())
        tracked.append(dest)

    shutil.rmtree(tmp_clone)

    write_manifest("dong-chinese-lexicon-PRIVATE", REPO_URL, version, LICENSE, out_dir, tracked)
    print(f"dong-chinese-lexicon (private) {version}: {len(tracked)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
