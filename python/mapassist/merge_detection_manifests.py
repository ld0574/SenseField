"""Merge reviewed detection manifests and explicitly assign development splits."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

from .orientation import from_manifest


SPLITS = {"train", "val", "test"}


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


def merge(manifests: list[Path], output: Path,
          split_overrides: dict[str, str] | None = None) -> dict:
    if not manifests:
        raise ValueError("At least one detection manifest is required")
    split_overrides = split_overrides or {}
    category = None
    matches = []
    seen_ids: set[str] = set()
    seen_videos: set[Path] = set()
    used_overrides: set[str] = set()
    orientations = []
    for manifest in manifests:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if data.get("schema_version") != 1:
            raise ValueError(f"Expected schema_version 1: {manifest}")
        current_category = data.get("category")
        if not isinstance(current_category, str) or not current_category:
            raise ValueError(f"Missing category: {manifest}")
        if category is None:
            category = current_category
        elif current_category != category:
            raise ValueError(
                f"Detection categories differ: {category} and {current_category}"
            )
        default_roi = data.get("roi")
        default_widget_value = data.get("widget_roi")
        default_widget_roi = (_roi(default_widget_value, f"{manifest} widget_roi")
                              if default_widget_value is not None else None)
        default_orientation = from_manifest(data, str(manifest))
        orientations.append(default_orientation)
        source_matches = data.get("matches")
        if not isinstance(source_matches, list) or not source_matches:
            raise ValueError(f"Detection manifest has no matches: {manifest}")
        for source in source_matches:
            match_id = source.get("id")
            if not isinstance(match_id, str) or not match_id:
                raise ValueError(f"Invalid match id in {manifest}")
            if match_id in seen_ids:
                raise ValueError(f"Duplicate match id: {match_id}")
            seen_ids.add(match_id)
            video_value = source.get("video")
            if not isinstance(video_value, str) or not video_value:
                raise ValueError(f"Missing video for {match_id}")
            video = (manifest.parent / video_value).resolve()
            if video in seen_videos:
                raise ValueError(f"Duplicate recording: {video}")
            seen_videos.add(video)
            split = split_overrides.get(match_id, source.get("split"))
            if split not in SPLITS:
                raise ValueError(f"Invalid split for {match_id}: {split}")
            if match_id in split_overrides:
                used_overrides.add(match_id)
            roi = _roi(source.get("roi", default_roi), f"{match_id} roi")
            widget_value = source.get("widget_roi", default_widget_roi)
            widget_roi = (_roi(widget_value, f"{match_id} widget_roi")
                          if widget_value is not None else None)
            exported = copy.deepcopy(source)
            exported["video"] = str(video)
            exported["split"] = split
            exported["roi"] = roi
            if widget_roi is not None:
                exported["widget_roi"] = widget_roi
            else:
                exported.pop("widget_roi", None)
            if (exported.get("orientation") is None and
                    default_orientation is not None):
                exported["orientation"] = default_orientation
            matches.append(exported)
    unused = sorted(set(split_overrides) - used_overrides)
    if unused:
        raise ValueError(f"Split overrides did not match a recording: {', '.join(unused)}")
    result = {"schema_version": 1, "category": category, "matches": matches}
    if orientations and all(item == orientations[0] for item in orientations):
        if orientations[0] is not None:
            result["orientation"] = orientations[0]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    counts = {split: 0 for split in sorted(SPLITS)}
    frames = {split: 0 for split in sorted(SPLITS)}
    boxes = {split: 0 for split in sorted(SPLITS)}
    for match in matches:
        split = match["split"]
        counts[split] += 1
        frames[split] += len(match.get("frames", []))
        boxes[split] += sum(len(frame.get("boxes", []))
                            for frame in match.get("frames", []))
    return {split: {"matches": counts[split], "frames": frames[split],
                    "boxes": boxes[split]} for split in sorted(SPLITS)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifests", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--split", action="append", default=[], metavar="MATCH=SPLIT")
    args = parser.parse_args()
    overrides = {}
    for value in args.split:
        if "=" not in value:
            parser.error(f"Invalid --split value: {value}")
        match_id, split = value.split("=", 1)
        if not match_id or split not in SPLITS or match_id in overrides:
            parser.error(f"Invalid --split value: {value}")
        overrides[match_id] = split
    try:
        summary = merge(args.manifests, args.output, overrides)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
