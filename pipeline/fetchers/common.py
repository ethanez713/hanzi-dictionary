"""Shared helpers for source fetchers: streaming download, git snapshot, manifest writing.

Every fetcher writes to data/snapshots/<source>/<version>/ and drops a manifest.json
next to the data recording url/version/license/retrieved_at/sha256, per spec §8.1
(immutable versioned snapshots + license metadata).
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import urllib.request
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path

SNAPSHOT_ROOT = Path(__file__).resolve().parent.parent / "data" / "snapshots"
CHUNK_SIZE = 1 << 20  # 1 MiB


@dataclass
class Manifest:
    source: str
    url: str
    version: str
    license: str
    retrieved_at: str
    files: dict[str, str]  # relative path -> sha256


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(CHUNK_SIZE), b""):
            h.update(chunk)
    return h.hexdigest()


def snapshot_dir(source: str, version: str) -> Path:
    d = SNAPSHOT_ROOT / source / version
    d.mkdir(parents=True, exist_ok=True)
    return d


def manifest_exists(source: str, version: str) -> bool:
    return (snapshot_dir(source, version) / "manifest.json").exists()


def write_manifest(source: str, url: str, version: str, license_: str, out_dir: Path, tracked_files: list[Path]) -> None:
    files = {str(p.relative_to(out_dir)): _sha256_file(p) for p in tracked_files if p.is_file()}
    manifest = Manifest(
        source=source,
        url=url,
        version=version,
        license=license_,
        retrieved_at=datetime.now(timezone.utc).isoformat(),
        files=files,
    )
    (out_dir / "manifest.json").write_text(json.dumps(asdict(manifest), indent=2, ensure_ascii=False))


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "hanzi-dictionary-pipeline/0.1"})
    with urllib.request.urlopen(req) as resp, dest.open("wb") as out:
        while chunk := resp.read(CHUNK_SIZE):
            out.write(chunk)


def git_shallow_clone(repo_url: str, dest: Path) -> str:
    """Shallow-clone repo_url into dest, return the checked-out commit hash."""
    if dest.exists():
        subprocess.run(["rm", "-rf", str(dest)], check=True)
    subprocess.run(["git", "clone", "--depth", "1", repo_url, str(dest)], check=True, capture_output=True)
    commit = subprocess.run(
        ["git", "-C", str(dest), "rev-parse", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()
    return commit
