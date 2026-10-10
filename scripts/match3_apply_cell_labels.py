#!/usr/bin/env python3
"""Expand per-family truth codes to per-cell labels, for scoring.

Phase 1 label-once -> propagate. Takes the family->cells map from
match3_label_cells.py and a compact family->code map (the human's one-time
labels), and writes a per-cell labels.json consumable by
scripts/evaluate_match3_accuracy.py. One label per family propagates to every
member cell, so humans label O(families), not O(cells).

Codes: R O Y G B P (ordinary animals) | coin snow egg cookie empty
       special (usable but non-ordinary) | unknown (genuinely unidentifiable).
special/unknown carry swappable=null so they are not asserted as a known family.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Optional

ANIMALS = {"R", "O", "Y", "G", "B", "P"}
OBSTACLES = {
    "coin": ("COIN", "coin"), "snow": ("SNOW", "snow"), "egg": ("EGG", "egg"),
    "cookie": ("COOKIE", "cookie"), "empty": ("EMPTY", "empty"),
}


def record(code: str) -> dict:
    if code in ANIMALS:
        return {"family": f"ANIMAL:{code}", "kind": "ANIMAL", "color": code, "swappable": True, "rule": "ordinary_animal"}
    if code in OBSTACLES:
        kind, rule = OBSTACLES[code]
        return {"family": kind, "kind": kind, "color": "", "swappable": False, "rule": rule}
    if code == "special":
        return {"family": "SPECIAL", "kind": "SPECIAL", "color": "", "swappable": None, "rule": "special_unverified"}
    if code == "iceflower":
        return {"family": "ICEFLOWER", "kind": "ICEFLOWER", "color": "", "swappable": False, "rule": "multistage_iceflower"}
    if code == "honey":
        return {"family": "HONEY", "kind": "HONEY", "color": "", "swappable": False, "rule": "single_clear_honey"}
    if code == "ice":
        # A bare ice surface is a covering layer, not a cell identity. Scored on
        # the separate ice axis, so keep it out of identity accuracy for now.
        return {"family": "ICE", "kind": "SURFACE", "color": "", "swappable": False, "rule": "ice_surface"}
    if code == "unknown":
        return {"family": "UNKNOWN", "kind": "UNKNOWN", "color": "", "swappable": None, "rule": "unverified"}
    raise ValueError(f"unknown label code: {code}")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Expand family truth codes into per-cell labels")
    parser.add_argument("--families", required=True, type=Path, help="cell-families.json from match3_label_cells.py")
    parser.add_argument("--codes", required=True, type=Path, help='{"labels": {"c1": "O", ...}}')
    parser.add_argument("--out", required=True, type=Path, help="per-cell labels.json")
    args = parser.parse_args(argv)

    families = {f["family"]: f for f in json.loads(args.families.read_text(encoding="utf-8"))["families"]}
    codes: Dict[str, str] = json.loads(args.codes.read_text(encoding="utf-8"))["labels"]
    samples = []
    missing = []
    for family_id, family in families.items():
        if family_id not in codes:
            missing.append(family_id)
            continue
        base = record(codes[family_id])
        for cell_id in family["cells"]:
            samples.append({"id": cell_id, "source": f"family:{family_id}", **base})
    args.out.write_text(json.dumps({"samples": samples}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(samples)} labeled cells from {len(codes)} families -> {args.out}")
    if missing:
        print(f"  unlabeled families ({len(missing)}): {', '.join(missing)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
