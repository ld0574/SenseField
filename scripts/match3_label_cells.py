#!/usr/bin/env python3
"""Cluster holdout board cells into families and build a human review sheet.

Phase 1 label-once support. Reads predictions.json (produced with per-cell
`envelope` by Match3HoldoutAccuracyInstrumentedTest), clusters every cell by its
16x16 appearance into families, and renders one montage PNG so a human can label
each distinct family once (identity / swappable / rule). Labels then propagate to
all member cells via scripts/match3_apply_cell_labels.py to score accuracy.

The engine's own kind/color/permission are shown ONLY as context on the sheet;
they are never written as ground truth. Requires .venv/bin/python (Pillow+numpy).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
from PIL import Image, ImageDraw

PATCH = 16
THRESHOLD = 0.10


def envelope_to_rgb(envelope: List[int]) -> np.ndarray:
    arr = np.array(envelope, dtype=np.int64).reshape(PATCH, PATCH)
    r = (arr >> 16) & 255
    g = (arr >> 8) & 255
    b = arr & 255
    return np.stack([r, g, b], axis=-1).astype(np.int32)


def distance(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.abs(a - b).sum() / (PATCH * PATCH * 765))


def cluster(samples: List[dict], threshold: float) -> List[dict]:
    families: List[dict] = []
    for sample in samples:
        if "envelope" not in sample:
            continue
        desc = envelope_to_rgb(sample["envelope"])
        for family in families:
            if distance(desc, family["_descriptor"]) <= threshold:
                family["members"].append(sample)
                break
        else:
            families.append({"_descriptor": desc, "members": [sample]})
    families.sort(key=lambda f: -len(f["members"]))
    return families


def _majority(members: List[dict], key: str) -> str:
    counts: Dict[str, int] = {}
    for m in members:
        counts[str(m.get(key, ""))] = counts.get(str(m.get(key, "")), 0) + 1
    return max(counts, key=counts.get)


def summarize(families: List[dict]) -> List[dict]:
    out = []
    for i, family in enumerate(families, start=1):
        members = family["members"]
        out.append({
            "family": f"c{i}",
            "members": len(members),
            "engine_kind": _majority(members, "kind"),
            "engine_color": _majority(members, "color"),
            "engine_swap_permission": _majority(members, "swap_permission"),
            "cells": [m["id"] for m in members],
        })
    return out


def montage(families: List[dict], summary: List[dict], out: Path, tile: int = 96) -> None:
    cols = 6
    rows = (len(families) + cols - 1) // cols
    label_h = 34
    canvas = Image.new("RGB", (cols * tile, rows * (tile + label_h)), (248, 248, 248))
    draw = ImageDraw.Draw(canvas)
    for idx, (family, info) in enumerate(zip(families, summary)):
        row, col = divmod(idx, cols)
        x, y = col * tile, row * (tile + label_h)
        img = Image.fromarray(family["_descriptor"].astype("uint8"), "RGB").resize((tile, tile), Image.NEAREST)
        canvas.paste(img, (x, y))
        perm = info["engine_swap_permission"].replace("UNKNOWN", "UNK")
        draw.rectangle([x, y + tile, x + tile, y + tile + label_h], fill=(255, 255, 255))
        draw.text((x + 2, y + tile + 1), f"{info['family']} n{info['members']}", fill=(0, 0, 0))
        draw.text((x + 2, y + tile + 13), f"{info['engine_kind']}{info['engine_color']}/{perm}", fill=(80, 80, 80))
        draw.text((x + 2, y + tile + 24), "", fill=(80, 80, 80))
        draw.rectangle([x, y, x + tile - 1, y + tile + label_h - 1], outline=(200, 200, 200))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Cluster holdout cells into families and render a review sheet")
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--threshold", type=float, default=THRESHOLD)
    parser.add_argument("--sheet", type=Path, required=True, help="montage PNG for human review")
    parser.add_argument("--families", type=Path, help="write family->cells map here")
    parser.add_argument("--labels-todo", type=Path, help="write a blank labels skeleton here")
    args = parser.parse_args(argv)

    samples = json.loads(args.predictions.read_text(encoding="utf-8"))["samples"]
    families = cluster(samples, args.threshold)
    summary = summarize(families)
    montage(families, summary, args.sheet)
    labeled_cells = sum(f["members"] for f in summary)
    print(f"{labeled_cells} cells -> {len(families)} families (threshold {args.threshold}); sheet: {args.sheet}")
    for info in summary:
        print(f"  {info['family']}: n{info['members']}  engine={info['engine_kind']}{info['engine_color']}"
              f"/{info['engine_swap_permission']}")
    if args.families:
        args.families.write_text(json.dumps({"families": summary}, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.labels_todo:
        todo = {"samples": [{"family": f["family"], "identity": "", "swappable": None, "rule": "",
                             "source": "cell-family-review"} for f in summary]}
        args.labels_todo.write_text(json.dumps(todo, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
