"""Export manually boxed game frames to a local COCO detection dataset."""

from __future__ import annotations

import argparse
import json
import re
import sys
import math
from pathlib import Path

from PIL import Image

from .extract_frame import extract
from .orientation import from_manifest, resolve, rotation


SPLITS = ("train", "val", "test")
SPLIT_DIRS = {split: f"{split}2017" for split in SPLITS}
MATCH_ID = re.compile(r"^[A-Za-z0-9_-]+$")


def _boxes(frame: dict) -> list[list[float]]:
    boxes = frame.get("boxes")
    if not isinstance(boxes, list):
        raise ValueError("Every frame needs a boxes list; use [] for a negative frame")
    validated = []
    for box in boxes:
        if not isinstance(box, list) or len(box) != 4 or any(
            not isinstance(value, (int, float)) or isinstance(value, bool)
            for value in box
        ):
            raise ValueError(f"Expected normalized [x, y, width, height]: {box}")
        x, y, width, height = box
        if not (0 <= x < 1 and 0 <= y < 1 and width > 0 and height > 0
                and x + width <= 1 and y + height <= 1):
            raise ValueError(f"Detection box outside frame: {box}")
        validated.append([float(value) for value in box])
    return validated


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


def export(manifest: Path, output: Path, crop_roi: bool = False) -> dict:
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected detection manifest schema_version 1")
    matches = data.get("matches")
    category = data.get("category", "main_enemy")
    if not isinstance(category, str) or not MATCH_ID.fullmatch(category):
        raise ValueError(f"Invalid detection category: {category}")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Detection manifest needs at least one match")
    default_roi_value = data.get("roi")
    default_roi = (_roi(default_roi_value, "Detection manifest roi")
                   if default_roi_value is not None else None)
    default_orientation = from_manifest(data)
    if crop_roi and default_roi is None and not all(
            isinstance(match, dict) and match.get("roi") is not None for match in matches
    ):
        raise ValueError("--crop-roi needs a top-level roi or one roi per match")

    prepared: dict[str, list[tuple[str, Path, int, list[list[float]],
                                  list[float] | None, int]]] = {
        split: [] for split in SPLITS
    }
    seen_ids: set[str] = set()
    video_splits: dict[Path, str] = {}
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError("Each match must be an object")
        match_id = match.get("id")
        split = match.get("split")
        video_name = match.get("video")
        frames = match.get("frames")
        if not isinstance(match_id, str) or not MATCH_ID.fullmatch(match_id):
            raise ValueError(f"Invalid match id: {match_id}")
        if match_id in seen_ids:
            raise ValueError(f"Duplicate match id: {match_id}")
        seen_ids.add(match_id)
        if split not in SPLITS:
            raise ValueError(f"Invalid match split: {split}")
        if not isinstance(video_name, str) or not video_name:
            raise ValueError(f"Missing video for match {match_id}")
        video = (manifest.parent / video_name).resolve()
        if not video.is_file():
            raise ValueError(f"Video does not exist: {video}")
        prior = video_splits.setdefault(video, split)
        if prior != split:
            raise ValueError(f"One recording cannot cross splits: {video}")
        if not isinstance(frames, list) or not frames:
            raise ValueError(f"Match {match_id} needs annotated frames")
        match_roi = None
        if crop_roi:
            match_roi = _roi(match.get("roi", default_roi), f"{match_id} roi")
        match_orientation = resolve(
            video, from_manifest(match, f"{match_id}") or default_orientation)
        seen_times: set[int] = set()
        for frame in frames:
            if not isinstance(frame, dict):
                raise ValueError(f"Each annotated frame in {match_id} must be an object")
            at_ms = frame.get("at_ms")
            if not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0:
                raise ValueError(f"Invalid frame time in {match_id}: {at_ms}")
            if at_ms in seen_times:
                raise ValueError(f"Duplicate frame time in {match_id}: {at_ms}")
            seen_times.add(at_ms)
            prepared[split].append((match_id, video, at_ms, _boxes(frame), match_roi,
                                    rotation(match_orientation)))

    # Validate all metadata before writing output so bad splits do not produce partial datasets.
    summary = {}
    for split, frames in prepared.items():
        images = []
        annotations = []
        split_name = SPLIT_DIRS[split]
        split_dir = output / split_name
        split_dir.mkdir(parents=True, exist_ok=True)
        for image_id, (match_id, video, at_ms, boxes, roi, display_rotation) in enumerate(
                frames, start=1):
            filename = f"{match_id}_{at_ms:09d}.png"
            frame_path = split_dir / filename
            full_width, full_height = extract(
                video, at_ms, frame_path, display_rotation=display_rotation)
            crop_x = 0
            crop_y = 0
            width = full_width
            height = full_height
            if roi is not None:
                crop_x = math.floor(roi[0] * full_width)
                crop_y = math.floor(roi[1] * full_height)
                crop_right = math.ceil((roi[0] + roi[2]) * full_width)
                crop_bottom = math.ceil((roi[1] + roi[3]) * full_height)
                with Image.open(frame_path) as source:
                    cropped = source.crop((crop_x, crop_y, crop_right, crop_bottom))
                    cropped.save(frame_path)
                width = crop_right - crop_x
                height = crop_bottom - crop_y
            images.append({"id": image_id, "file_name": filename,
                           "width": width, "height": height})
            for x, y, w, h in boxes:
                if roi is None:
                    px_box = [x * full_width, y * full_height,
                              w * full_width, h * full_height]
                else:
                    left = max(float(crop_x), x * full_width)
                    top = max(float(crop_y), y * full_height)
                    right = min(float(crop_x + width), (x + w) * full_width)
                    bottom = min(float(crop_y + height), (y + h) * full_height)
                    if right <= left or bottom <= top:
                        raise ValueError(
                            f"Detection box outside {match_id} roi at {at_ms} ms: "
                            f"{[x, y, w, h]}"
                        )
                    px_box = [round(value, 6) for value in
                              (left - crop_x, top - crop_y,
                               right - left, bottom - top)]
                annotations.append({"id": len(annotations) + 1, "image_id": image_id,
                                    "category_id": 1, "bbox": px_box,
                                    "area": px_box[2] * px_box[3], "iscrowd": 0})
        annotation_dir = output / "annotations"
        annotation_dir.mkdir(parents=True, exist_ok=True)
        coco = {"images": images, "annotations": annotations,
                "categories": [{"id": 1, "name": category, "supercategory": "game"}]}
        (annotation_dir / f"instances_{split_name}.json").write_text(
            json.dumps(coco, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary[split] = {"images": len(images), "boxes": len(annotations),
                          "negative_images": sum(1 for _, _, _, boxes, _, _ in frames
                                                 if not boxes)}
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Private per-match frame and box annotations")
    parser.add_argument("--output", type=Path, required=True, help="Private COCO dataset directory")
    parser.add_argument("--crop-roi", action="store_true",
                        help="Crop each image to the manifest or per-match roi")
    args = parser.parse_args()
    try:
        print(json.dumps(export(args.manifest, args.output, crop_roi=args.crop_roi),
                         ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
