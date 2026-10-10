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


DEFAULT_ARCH = {**{code: "ordinary" for code in ANIMALS}, "coin": "single", "snow": "single",
                "honey": "single", "iceflower": "multistage", "cookie": "large", "ice": "cover",
                "egg": "spawner", "empty": "none", "special": "special", "unknown": "unknown"}


def record(code: str, archetype: str = "unknown", swappable: Optional[bool] = None) -> dict:
    if code not in DEFAULT_ARCH:
        raise ValueError(f"unknown label code: {code}")
    if archetype not in {DEFAULT_ARCH[code], "unknown"}:
        raise ValueError(f"unsupported identity/mechanic combination: {code}/{archetype}")
    if swappable is not None and type(swappable) is not bool:
        raise ValueError("swappable must be true, false or null")
    if swappable is True and (code not in ANIMALS or archetype != "ordinary"):
        raise ValueError("exchange permission needs a reviewed ordinary animal rule")
    result = _identity(code)
    result["archetype"] = archetype
    result["swappable"] = swappable
    if archetype == "unknown":
        result["rule"] = "unverified"
    if code == "ice" and archetype == "cover" and swappable is False:
        result["bare_ice"] = True
    return result


def _identity(code: str) -> dict:
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

    families = {f.get("family", f.get("global_family")): f for f in json.loads(args.families.read_text(encoding="utf-8"))["families"]}
    review = json.loads(args.codes.read_text(encoding="utf-8"))
    codes: Dict[str, str] = review["labels"]
    samples = []
    missing = []
    for family_id, family in families.items():
        if family_id not in codes:
            missing.append(family_id)
            continue
        base = record(codes[family_id], review.get("archetypes", {}).get(family_id, "unknown"),
                      review.get("swappable", {}).get(family_id))
        for cell_id in family.get("cells", []):
            samples.append({"id": cell_id, "source": f"family:{family_id}", "family_id": family_id,
                            "reviewed": review.get("reviewed", {}).get(family_id) is True, **base})
    args.out.write_text(json.dumps({"samples": samples}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(samples)} labeled cells from {len(codes)} families -> {args.out}")
    if missing:
        print(f"  unlabeled families ({len(missing)}): {', '.join(missing)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
