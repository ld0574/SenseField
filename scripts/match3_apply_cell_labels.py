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
import importlib.util
from pathlib import Path
from typing import Dict, List, Optional

ANIMALS = {"R", "O", "Y", "G", "B", "P"}
OBSTACLES = {
    "coin": ("COIN", "coin"), "snow": ("SNOW", "snow"), "egg": ("EGG", "egg"),
    "cookie": ("COOKIE", "cookie"), "empty": ("EMPTY", "empty"),
}


DEFAULT_ARCH = {**{code: "ordinary" for code in ANIMALS}, "coin": "single", "snow": "single",
                "honey": "single", "honey_cover":"cover", "lock":"cover", "iceflower": "multistage", "cookie": "large", "ice": "cover",
                "egg": "spawner", "empty": "none", "special": "special", "unknown": "unknown"}
_spec=importlib.util.spec_from_file_location("match3_rules",Path(__file__).with_name("match3_rules.py"))
_rules=importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_rules)
TYPED={kind.lower():kind for kind in _rules.KIND_RULE if kind not in {"ANIMAL","COIN","SNOW","ICEFLOWER","HONEY","EGG","COOKIE"}}
DEFAULT_ARCH.update({code:"state" for code in TYPED})


def record(code: str, archetype: str = "unknown", swappable: Optional[bool] = None, binding=None) -> dict:
    if code not in DEFAULT_ARCH:
        raise ValueError(f"unknown label code: {code}")
    allowed={DEFAULT_ARCH[code],"unknown"}
    if code in ANIMALS:
        allowed.update({"frozen","locked","line_horizontal","line_vertical","blast","honey_cover","cage"})
    if archetype not in allowed:
        raise ValueError(f"unsupported identity/mechanic combination: {code}/{archetype}")
    if swappable is not None and type(swappable) is not bool:
        raise ValueError("swappable must be true, false or null")
    if binding is None and swappable is True and (code not in ANIMALS or archetype != "ordinary"):
        raise ValueError("exchange permission needs a reviewed ordinary animal rule")
    result = _identity(code)
    result["archetype"] = archetype
    result["swappable"] = swappable
    if binding is not None:
        if archetype=="unknown":
            raise ValueError("unknown mechanics cannot acquire a reviewed state")
        _rules.validate_binding(binding,result['kind'],result['color'])
        state=binding['state'];cover=state.get('cover','UNKNOWN' if result['kind']=='ANIMAL' else 'NONE')
        effect=state.get('effect','UNKNOWN' if result['kind']=='ANIMAL' else 'NONE')
        if code in ANIMALS:
            expected={'FROZEN_ICE':'frozen','LOCK':'locked','HONEY':'honey_cover','CAGE':'cage'}.get(cover)
            if expected is None:
                expected={'HORIZONTAL':'line_horizontal','VERTICAL':'line_vertical','BLAST':'blast','NONE':'ordinary'}.get(effect)
            if expected!=archetype:
                raise ValueError('state differs from independently reviewed archetype')
        certain=(state.get('gate')=='OPEN' and cover=='NONE' and state.get('floor_ice',-1)>=0
                 and state.get('layers',-1)>=0 and effect not in {'BLAST','UNKNOWN'})
        permission=(True if certain else False if cover in {'FROZEN_ICE','LOCK'} or state.get('gate')=='CLOSED' else None)
        if result['kind'] not in {'ANIMAL','BIRD'}:
            permission=False
        if swappable is not permission:
            raise ValueError('state differs from independently reviewed exchange permission')
        result.update(rule_binding=binding,rule=binding['rule_id'],rule_id=binding['rule_id'],rule_version=binding['rule_version'])
    if archetype == "unknown" or code in ANIMALS and archetype!='ordinary' and binding is None:
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
    if code == "honey_cover":
        return {"family":"HONEY_COVER","kind":"SURFACE","color":"","swappable":False,"rule":"unverified"}
    if code == "lock":
        # A HUD icon is not a covered animal. Cell review must bind the
        # independently observed foreground colour and LOCK cover separately.
        return {"family":"LOCK","kind":"SURFACE","color":"","swappable":False,"rule":"unverified"}
    if code == "ice":
        # A bare ice surface is a covering layer, not a cell identity. Scored on
        # the separate ice axis, so keep it out of identity accuracy for now.
        return {"family": "ICE", "kind": "SURFACE", "color": "", "swappable": False, "rule": "ice_surface"}
    if code == "unknown":
        return {"family": "UNKNOWN", "kind": "UNKNOWN", "color": "", "swappable": None, "rule": "unverified"}
    if code in TYPED:
        kind=TYPED[code]
        return {"family":kind,"kind":kind,"color":"","swappable":None,"rule":"unverified"}
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
                      review.get("swappable", {}).get(family_id),review.get("bindings",{}).get(family_id))
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
