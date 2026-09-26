"""Combine additional reviewed frames for the same recordings into one manifest."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


SPLITS = {"train", "val", "test"}
CATEGORY = re.compile(r"^[A-Za-z0-9_-]+$")


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1 or y + height > 1:
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def combine(manifests: list[Path], output: Path,
            split_overrides: dict[str, str] | None = None) -> dict:
    if not manifests:
        raise ValueError("At least one detection manifest is required")
    split_overrides = split_overrides or {}
    category = None
    grouped: dict[Path, dict] = {}
    ids: dict[str, Path] = {}
    used_overrides: set[str] = set()
    for manifest in manifests:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if data.get("schema_version") != 1 or not isinstance(data.get("matches"), list):
            raise ValueError(f"Invalid detection manifest: {manifest}")
        current_category = data.get("category", "main_enemy")
        if not isinstance(current_category, str) or not CATEGORY.fullmatch(current_category):
            raise ValueError(f"Invalid detection category in {manifest}: {current_category}")
        if category is None:
            category = current_category
        elif current_category != category:
            raise ValueError("Detection categories differ")
        default_roi = data.get("roi")
        for source in data["matches"]:
            match_id = source.get("id")
            if not isinstance(match_id, str) or not match_id:
                raise ValueError(f"Invalid match id in {manifest}")
            video_value = source.get("video")
            if not isinstance(video_value, str) or not video_value:
                raise ValueError(f"Missing video for {match_id}")
            video = (manifest.parent / video_value).resolve()
            if not video.is_file():
                raise ValueError(f"Missing video for {match_id}: {video}")
            prior_video = ids.setdefault(match_id, video)
            if prior_video != video:
                raise ValueError(f"Match id {match_id} refers to different recordings")
            roi = _roi(source.get("roi", default_roi), f"{match_id} roi")
            split = split_overrides.get(match_id, source.get("split"))
            if split not in SPLITS:
                raise ValueError(f"Invalid split for {match_id}: {split}")
            if match_id in split_overrides:
                used_overrides.add(match_id)
            record = grouped.setdefault(video, {
                "id": match_id, "video": str(video), "split": split,
                "roi": roi, "frames": {},
            })
            if (record["id"] != match_id or record["roi"] != roi or
                    record["split"] != split):
                raise ValueError(f"Metadata differs for repeated recording {match_id}")
            for frame in source.get("frames", []):
                timestamp = frame.get("at_ms")
                boxes = frame.get("boxes")
                if not isinstance(timestamp, int) or not isinstance(boxes, list):
                    raise ValueError(f"Invalid frame in {match_id}")
                previous = record["frames"].setdefault(timestamp, boxes)
                if previous != boxes:
                    raise ValueError(
                        f"Conflicting labels for {match_id}@{timestamp}; review manually"
                    )
    unused = sorted(set(split_overrides) - used_overrides)
    if unused:
        raise ValueError(f"Split overrides did not match: {', '.join(unused)}")
    matches = []
    for record in sorted(grouped.values(), key=lambda item: item["id"]):
        matches.append({
            "id": record["id"], "video": record["video"],
            "split": record["split"], "roi": record["roi"],
            "frames": [{"at_ms": timestamp, "boxes": boxes}
                       for timestamp, boxes in sorted(record["frames"].items())],
        })
    result = {"schema_version": 1, "category": category, "matches": matches}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    return {
        "matches": len(matches),
        "frames": sum(len(match["frames"]) for match in matches),
        "boxes": sum(len(frame["boxes"]) for match in matches
                     for frame in match["frames"]),
        "splits": {split: sum(len(match["frames"]) for match in matches
                              if match["split"] == split)
                   for split in sorted(SPLITS)},
    }


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
        result = combine(args.manifests, args.output, overrides)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
