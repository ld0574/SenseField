"""Download and checksum the exact streaming ASR model revision."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from .asr import resolve_verified_snapshot


def main() -> None:
    parser = argparse.ArgumentParser(description="Download and verify pinned Paraformer streaming weights.")
    parser.add_argument("--cache-dir", default=os.environ.get("ASSISTANT_GATEWAY_MODEL_CACHE"))
    args = parser.parse_args()
    snapshot, manifest = resolve_verified_snapshot(Path(args.cache_dir).expanduser() if args.cache_dir else None)
    print(f"Verified {manifest['repo_id']} at {manifest['revision']}")
    print(f"Snapshot: {snapshot}")
