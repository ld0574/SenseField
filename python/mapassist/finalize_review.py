"""Convert a fully reviewed frame queue into a detection dataset manifest."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .orientation import from_manifest


FINAL_STATUSES = {"accepted", "corrected", "negative", "skip", "excluded"}


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def finalize(review_manifest: Path, output: Path) -> dict:
    data = json.loads(review_manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected review manifest schema_version 1")
    kind = data.get("kind")
    if not isinstance(kind, str) or not kind:
        raise ValueError("Review manifest needs a kind")
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Review manifest needs matches")
    default_roi_value = data.get("roi")
    default_roi = (_roi(default_roi_value, "Review roi")
                   if default_roi_value is not None else None)
    default_orientation = from_manifest(data)

    pending = []
    exported_matches = []
    status_counts = {status: 0 for status in sorted(FINAL_STATUSES)}
    total_boxes = 0
    for match in matches:
        frames = []
        for sample in match.get("samples", []):
            status = sample.get("review_status")
            if status not in FINAL_STATUSES:
                pending.append(f"{match.get('id')}@{sample.get('at_ms')}")
                continue
            status_counts[status] += 1
            if status in {"skip", "excluded"}:
                continue
            if status == "accepted":
                boxes = sample.get("suggested_boxes")
            elif status == "corrected":
                boxes = sample.get("reviewed_boxes")
            else:
                boxes = []
            if not isinstance(boxes, list):
                raise ValueError(
                    f"{match.get('id')}@{sample.get('at_ms')} needs reviewed boxes"
                )
            frames.append({"at_ms": sample["at_ms"], "boxes": boxes})
            total_boxes += len(boxes)
        if frames:
            exported = {"id": match["id"], "video": match["video"],
                        "split": match["split"], "frames": frames}
            match_orientation = from_manifest(match, f"{match.get('id')}")
            if match_orientation is not None:
                exported["orientation"] = match_orientation
            match_roi_value = match.get("roi")
            if match_roi_value is not None:
                exported["roi"] = _roi(match_roi_value, f"{match.get('id')} roi")
            exported_matches.append(exported)
    if pending:
        preview = ", ".join(pending[:5])
        suffix = "..." if len(pending) > 5 else ""
        raise ValueError(f"Review still has {len(pending)} pending samples: {preview}{suffix}")
    if not exported_matches:
        raise ValueError("Review has no accepted, corrected, or negative samples")

    result = {"schema_version": 1, "category": kind, "matches": exported_matches}
    if default_roi is not None:
        result["roi"] = default_roi
    if default_orientation is not None:
        result["orientation"] = default_orientation
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    return {"matches": len(exported_matches),
            "frames": sum(len(match["frames"]) for match in exported_matches),
            "boxes": total_boxes, "statuses": status_counts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review_manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(finalize(args.review_manifest, args.output),
                         ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
