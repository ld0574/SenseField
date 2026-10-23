#!/usr/bin/env python3
"""Collapse the app's per-session unknown-element crops into global families.

Phase 1 harvest+dedup for the recognition flywheel. The app already exports
unknown-element representative crops (diagnostics/current/elements/uN-M.png) and
Match3ElementObserved/Sample events, but family ids reset every session. This
tool clusters crops across ALL recorded sessions into global families so the
human labels each distinct element once — turning O(crops) into O(families).

Requires the project venv (Pillow + numpy): .venv/bin/python.
It is an offline approximation of the on-device Match3AnimalAppearance.Body
descriptor (16x16 mean-abs-diff over 0..765), without the alignment/quadrant
search; the authoritative dedup stays on-device. Output is a review manifest —
it never writes ground-truth labels, only assembles what needs labeling.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
from PIL import Image

PATCH = 16
THRESHOLD = 0.12  # mirrors Match3AnimalAppearance.MAXIMUM


def descriptor(path: Path) -> np.ndarray:
    image = Image.open(path).convert("RGB").resize((PATCH, PATCH))
    return np.asarray(image, dtype=np.int32)


def distance(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.abs(a - b).sum() / (PATCH * PATCH * 765))


def session_events(session: Path) -> Dict[str, dict]:
    """family_id -> engine context (kind/permission/rule), read from events.jsonl."""
    context: Dict[str, dict] = {}
    events = session / "diagnostics/current/events.jsonl"
    if not events.is_file():
        return context
    for line in events.read_text(encoding="utf-8").splitlines():
        if "Match3Element" not in line:
            continue
        try:
            data = json.loads(line).get("data", {})
        except json.JSONDecodeError:
            continue
        family = data.get("family_id")
        if not family:
            continue
        context.setdefault(family, {"kind": data.get("kind"), "swap_permission": data.get("swap_permission"),
                                    "rule_supported": data.get("rule_supported"), "reason": data.get("reason")})
    return context


def collect(root: Path) -> List[dict]:
    crops: List[dict] = []
    for elements in sorted(root.glob("*/diagnostics/current/elements")):
        session = elements.parent.parent.parent
        context = session_events(session)
        for png in sorted(elements.glob("*.png")):
            family = png.stem.split("-", 1)[0]
            crops.append({"session": session.name, "family_id": family, "path": png,
                          "context": context.get(family, {})})
    return crops


def cluster(crops: List[dict], threshold: float) -> List[dict]:
    families: List[dict] = []
    for crop in crops:
        desc = descriptor(crop["path"])
        for family in families:
            if distance(desc, family["_descriptor"]) <= threshold:
                family["members"].append(crop)
                break
        else:
            families.append({"_descriptor": desc, "members": [crop]})
    return families


def manifest(families: List[dict], root: Path) -> dict:
    out = []
    for i, family in enumerate(sorted(families, key=lambda f: -len(f["members"])), start=1):
        members = family["members"]
        kinds = sorted({m["context"].get("kind") for m in members if m["context"].get("kind")})
        perms = sorted({m["context"].get("swap_permission") for m in members if m["context"].get("swap_permission")})
        sessions = sorted({m["session"] for m in members})
        out.append({
            "global_family": f"g{i}",
            "members": len(members),
            "sessions": sessions,
            "engine_kinds": kinds,
            "engine_swap_permissions": perms,
            "rule_supported": any(m["context"].get("rule_supported") for m in members),
            "representative_crop": str(members[0]["path"].relative_to(root)),
            "crops": [str(m["path"].relative_to(root)) for m in members],
        })
    return {"families": out}


def labels_todo(data: dict) -> dict:
    """Blank label skeleton for the human label-once step. Never pre-filled from engine guesses."""
    return {"samples": [{"family": f["global_family"], "representative_crop": f["representative_crop"],
                         "identity": "", "swappable": None, "rule": "", "source": "unknown-element-review"}
                        for f in data["families"]]}


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Cluster per-session unknown-element crops into global families")
    parser.add_argument("--root", type=Path, default=Path("validation/private"))
    parser.add_argument("--threshold", type=float, default=THRESHOLD)
    parser.add_argument("--out", type=Path, help="write the review manifest here")
    parser.add_argument("--labels-todo", type=Path, help="write a blank labels skeleton here")
    args = parser.parse_args(argv)

    crops = collect(args.root)
    if not crops:
        print(f"no unknown-element crops under {args.root}")
        return 1
    families = cluster(crops, args.threshold)
    data = manifest(families, args.root)
    sessions = sorted({c["session"] for c in crops})
    print(f"{len(crops)} crops across {len(sessions)} sessions -> {len(families)} global families "
          f"(threshold {args.threshold})")
    for family in data["families"]:
        print(f"  {family['global_family']}: {family['members']} crops  kinds={family['engine_kinds']}  "
              f"perms={family['engine_swap_permissions']}  sessions={len(family['sessions'])}")
    if args.out:
        args.out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.labels_todo:
        args.labels_todo.write_text(json.dumps(labels_todo(data), ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
