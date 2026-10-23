#!/usr/bin/env python3
"""Compile reviewed Match3 families from a persistent registry and session splits.

Identity labels alone never supply exchange or clearing rules. This replaces
the animal/coin-only seeding CLI with explicit three-axis human review.
"""
from __future__ import annotations
import argparse
import importlib.util
from pathlib import Path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--splits", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--max-per-family", type=int, default=24)
    parser.add_argument("--max-families", type=int, default=64)
    parser.add_argument("--max-bytes", type=int, default=1048576)
    args = parser.parse_args(argv)
    if not (1 <= args.max_per_family <= 24 and 1 <= args.max_families <= 64 and 1 <= args.max_bytes <= 1048576):
        parser.error("budgets must fit runtime limits: 24 exemplars, 64 families, 1MiB")
    spec = importlib.util.spec_from_file_location("match3_flywheel", Path(__file__).with_name("match3_flywheel.py"))
    pipeline = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pipeline)
    result = pipeline.build_gallery(pipeline.read(args.registry), pipeline.read(args.splits),
                                    args.max_per_family, args.max_families, args.max_bytes)
    pipeline.write(args.out, result)
    print(f"{result['id']}: {args.out} ({args.out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
