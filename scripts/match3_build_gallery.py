#!/usr/bin/env python3
"""Seed a sidecar exemplar gallery of ordinary-animal body references.

Phase 1 gallery, first slice. The engine abstains on real swappable animals
(swap_miss) because it has only ~3 full-tile body references per colour. This
tool takes human-confirmed ordinary-animal cells from the TRAIN boards only,
dedups their 16x16 body envelopes per colour, and writes
assets/match3-gallery-v1.json (ordinary_envelopes), loaded alongside the capped
fixed catalog. Test boards are never used here, so the holdout stays honest.

Requires .venv/bin/python (numpy).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np

PATCH = 16
ANIMALS = set("ROYGBP")


def descriptor(envelope: List[int]) -> np.ndarray:
    arr = np.array(envelope, dtype=np.int64).reshape(PATCH * PATCH)
    return np.stack([(arr >> 16) & 255, (arr >> 8) & 255, arr & 255], axis=-1).astype(np.int32)


def distance(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.abs(a - b).sum() / (PATCH * PATCH * 765))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Build the ordinary-animal body gallery from train boards")
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--labels", required=True, type=Path)
    parser.add_argument("--train", required=True, help="comma-separated train board file names (animal bodies)")
    parser.add_argument("--coin-train", default="", help="separate train boards for coin templates (if coins sit elsewhere)")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--threshold", type=float, default=0.06, help="per-colour dedup distance")
    parser.add_argument("--max-per-color", type=int, default=24)
    args = parser.parse_args(argv)

    train = set(args.train.split(","))
    preds = {s["id"]: s for s in json.loads(args.predictions.read_text(encoding="utf-8"))["samples"]}
    labels = {s["id"]: s for s in json.loads(args.labels.read_text(encoding="utf-8"))["samples"]}

    per_color: Dict[str, List[np.ndarray]] = {}
    entries = []
    kept = {c: 0 for c in ANIMALS}
    for cell_id, truth in labels.items():
        if cell_id.split(":", 1)[0] not in train:
            continue
        if truth.get("kind") != "ANIMAL" or truth.get("swappable") is not True:
            continue
        color = truth.get("color")
        pred = preds.get(cell_id)
        if color not in ANIMALS or pred is None or "envelope" not in pred:
            continue
        desc = descriptor(pred["envelope"])
        bucket = per_color.setdefault(color, [])
        if any(distance(desc, seen) <= args.threshold for seen in bucket):
            continue  # near-duplicate body already represented
        if len(bucket) >= args.max_per_color:
            continue
        bucket.append(desc)
        entries.append({"rule": "ordinary_uncovered_animal", "color": color, "source": cell_id,
                        "pixels": [int(v) for v in pred["envelope"]]})
        kept[color] += 1

    # Obstacle gallery: coin templates from train coin cells (inset patch + centred
    # disc mask that excludes the cell-corner background).
    mask = [((i % PATCH) - 7.5) ** 2 + ((i // PATCH) - 7.5) ** 2 <= 49 for i in range(PATCH * PATCH)]
    cells = []
    coin_descs: List[np.ndarray] = []
    coin_train = set(args.coin_train.split(",")) if args.coin_train else train
    for cell_id, truth in labels.items():
        if cell_id.split(":", 1)[0] not in coin_train or truth.get("kind") != "COIN":
            continue
        pred = preds.get(cell_id)
        if pred is None or "patch" not in pred:
            continue
        desc = descriptor(pred["patch"])
        if any(distance(desc, seen) <= args.threshold for seen in coin_descs) or len(coin_descs) >= args.max_per_color:
            continue
        coin_descs.append(desc)
        cells.append({"kind": "coin", "rule": "coin", "source": cell_id,
                      "pixels": [int(v) for v in pred["patch"]], "mask": mask})

    gallery = {"format": "match3-gallery-v1", "id": "gallery-ordinary-bodies-train",
               "ordinary_envelopes": entries, "cells": cells}
    args.out.write_text(json.dumps(gallery, ensure_ascii=False), encoding="utf-8")
    size = args.out.stat().st_size
    print(f"gallery: {len(entries)} body refs + {len(cells)} coin refs from {len(train)} train boards "
          f"-> {args.out} ({size} bytes)")
    print(f"  per colour: {kept}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
