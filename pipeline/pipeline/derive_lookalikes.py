"""One-off derive step for the visually-confusable pair dataset.

    python -m pipeline.derive_lookalikes            # build it if it's missing/stale/corrupt
    python -m pipeline.derive_lookalikes --force    # rebuild regardless
    python -m pipeline.derive_lookalikes --check    # validate only, exit 1 if unusable

`pipeline.build` calls the same [lookalike.ensure_dataset] itself, so a normal build still
works from a clean checkout — this exists so the derive can be run (and its result checked)
without paying for a whole build, and so a stale dataset has an obvious manual fix.
"""
from __future__ import annotations

import argparse
import json
import sys

from pipeline import lookalike
from pipeline.build import latest_snapshot


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="rebuild even if the dataset is valid")
    parser.add_argument("--check", action="store_true", help="validate only; do not build")
    args = parser.parse_args()

    unihan_dir = latest_snapshot("unihan")
    version = json.loads((unihan_dir / "manifest.json").read_text())["version"]

    if args.check:
        problems = lookalike.validate(version)
        if problems:
            print(f"look-alike dataset unusable: {'; '.join(problems)}")
            return 1
        meta, rows = lookalike.read_dataset()
        print(f"look-alike dataset OK: {len(rows)} pairs from Unihan {meta.get('unihan_version')}")
        return 0

    rows = lookalike.ensure_dataset(unihan_dir, force=args.force)
    print(f"look-alike dataset ready: {len(rows)} pairs ({lookalike.DATASET_PATH})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
