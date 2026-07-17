"""Fetch CC-CEDICT (MDBG export, CC BY-SA 4.0) — word-level definitions (spec §5, M3.5).

The export is versioned by the release date embedded in its own header
(``#! date=...``), so we download to a temp name first, read the date, then move the
file into its dated snapshot dir.
"""
from __future__ import annotations

import gzip
import re
from pathlib import Path

from fetchers.common import SNAPSHOT_ROOT, download, manifest_exists, snapshot_dir, write_manifest

URL = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
LICENSE = "CC BY-SA 4.0"


def fetch(force: bool = False) -> Path:
    tmp = SNAPSHOT_ROOT / "cedict" / "_tmp" / "cedict.txt.gz"
    download(URL, tmp)
    text = gzip.decompress(tmp.read_bytes()).decode("utf-8")

    m = re.search(r"^#! date=(\S+)", text, re.MULTILINE)
    version = m.group(1).split("T")[0] if m else "unknown"

    if not force and manifest_exists("cedict", version):
        print(f"cedict {version} already fetched, skipping")
        tmp.unlink()
        tmp.parent.rmdir()
        return snapshot_dir("cedict", version)

    out_dir = snapshot_dir("cedict", version)
    dest = out_dir / "cedict_ts.u8"
    dest.write_text(text, encoding="utf-8")
    tmp.unlink()
    tmp.parent.rmdir()

    write_manifest("cedict", URL, version, LICENSE, out_dir, [dest])
    print(f"cedict {version}: {sum(1 for l in text.splitlines() if not l.startswith('#'))} entries -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
