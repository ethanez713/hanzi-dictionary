"""Run all source fetchers. Each fetcher is idempotent (skips if already fetched)."""
from __future__ import annotations

import argparse

from fetchers import fetch_cjkvi_ids, fetch_hsk, fetch_makemeahanzi, fetch_opencc, fetch_unihan, fetch_wiktextract

FETCHERS = [
    ("unihan", fetch_unihan.fetch),
    ("makemeahanzi", fetch_makemeahanzi.fetch),
    ("cjkvi-ids", fetch_cjkvi_ids.fetch),
    ("opencc", fetch_opencc.fetch),
    ("wiktextract", fetch_wiktextract.fetch),
    ("hsk", fetch_hsk.fetch),
]


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch all M0 pipeline sources")
    parser.add_argument("--force", action="store_true", help="re-fetch even if a snapshot already exists")
    parser.add_argument(
        "--only", nargs="+", choices=[name for name, _ in FETCHERS], help="restrict to specific sources"
    )
    args = parser.parse_args()

    for name, fetch_fn in FETCHERS:
        if args.only and name not in args.only:
            continue
        print(f"=== fetching {name} ===")
        fetch_fn(force=args.force)


if __name__ == "__main__":
    main()
