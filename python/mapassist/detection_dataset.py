"""Export manually boxed game frames to a local COCO detection dataset."""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import re
import sys
import math
from pathlib import Path

from PIL import Image

from .extract_frame import extract
from .minimap_locator_evaluate import _load_locator
from .native import Rect, default_library_path, load_library
from .orientation import from_manifest, resolve, rotation


SPLITS = ("train", "val", "test")
SPLIT_DIRS = {split: f"{split}2017" for split in SPLITS}
MATCH_ID = re.compile(r"^[A-Za-z0-9_-]+$")
LOCATOR_STATES = {0: "searching", 1: "locked", 2: "held"}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


class _AdaptiveCropper:
    """Resolve each reviewed frame independently with the native locator."""

    def __init__(self, locator_path: Path, library_path: Path | None):
        self.locator_path = locator_path.resolve()
        self.library_path = (library_path or default_library_path()).resolve()
        config, descriptor_bytes, locator_data = _load_locator(self.locator_path)
        self.config = config
        self.locator_data = locator_data
        self.library = load_library(self.library_path)
        self.descriptor = (C.c_int8 * len(descriptor_bytes)).from_buffer_copy(
            descriptor_bytes
        )
        self.handle = self.library.ma_minimap_locator_create(
            C.byref(self.config), self.descriptor, len(descriptor_bytes)
        )
        if not self.handle:
            raise ValueError("Native locator rejected the adaptive crop configuration")

    def close(self) -> None:
        if self.handle:
            self.library.ma_minimap_locator_destroy(self.handle)
            self.handle = None

    def __enter__(self) -> "_AdaptiveCropper":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def locate(self, frame_path: Path) -> tuple[str, list[float] | None, float]:
        """Return a crop without allowing one sparse sample to affect another.

        Runtime confirmation normally comes from adjacent 12 FPS frames. Review
        samples are seconds apart, so the same display frame is repeated only
        for the configured confirmation count after a reset. This preserves the
        confirmation rule while preventing held state from leaking across large
        gaps in a sparse annotation set.
        """
        self.library.ma_minimap_locator_reset(self.handle)
        with Image.open(frame_path) as source:
            frame = source.convert("RGBA")
        raw = frame.tobytes()
        pixels = (C.c_uint8 * len(raw)).from_buffer_copy(raw)
        roi = Rect()
        content = Rect()
        score = C.c_float()
        state = 0
        for _ in range(max(1, int(self.config.confirm_frames))):
            state = self.library.ma_minimap_locator_update(
                self.handle, pixels, frame.width, frame.height, frame.width * 4,
                C.byref(roi), C.byref(content), C.byref(score),
            )
            if state not in LOCATOR_STATES:
                raise RuntimeError(f"Native locator returned unknown state {state}")
            if state != 0:
                break
        resolved = ([float(roi.x), float(roi.y), float(roi.w), float(roi.h)]
                    if state != 0 else None)
        return LOCATOR_STATES[state], resolved, float(score.value)

    def provenance(self) -> dict:
        return {
            "mode": "adaptive_minimap_locator",
            "locator_path": str(self.locator_path),
            "locator_sha256": _sha256(self.locator_path),
            "locator_descriptor_sha256": self.locator_data["descriptor_sha256"],
            "native_library_path": str(self.library_path),
            "native_library_sha256": _sha256(self.library_path),
            "sparse_frame_policy": "reset_then_repeat_current_frame_for_confirmation",
        }


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


def export(manifest: Path, output: Path, crop_roi: bool = False,
           locator: Path | None = None, library: Path | None = None) -> dict:
    manifest = manifest.resolve()
    output = output.resolve()
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
    if crop_roi and locator is None and default_roi is None and not all(
            isinstance(match, dict) and match.get("roi") is not None for match in matches
    ):
        raise ValueError("--crop-roi needs a top-level roi or one roi per match")
    if library is not None and locator is None:
        raise ValueError("--library requires --locator")
    adaptive_requested = locator is not None

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
        if crop_roi and not adaptive_requested:
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
    adaptive = _AdaptiveCropper(locator, library) if locator is not None else None
    summary = {}
    try:
        for split, frames in prepared.items():
            images = []
            annotations = []
            locator_states = {name: 0 for name in LOCATOR_STATES.values()}
            skipped_searching = 0
            skipped_positive_images = 0
            skipped_boxes = 0
            skipped_samples = []
            negative_images = 0
            split_name = SPLIT_DIRS[split]
            split_dir = output / split_name
            split_dir.mkdir(parents=True, exist_ok=True)
            for match_id, video, at_ms, boxes, roi, display_rotation in frames:
                filename = f"{match_id}_{at_ms:09d}.png"
                frame_path = split_dir / filename
                full_width, full_height = extract(
                    video, at_ms, frame_path, display_rotation=display_rotation)
                if adaptive is not None:
                    state, roi, score = adaptive.locate(frame_path)
                    locator_states[state] += 1
                    if roi is None:
                        skipped_searching += 1
                        skipped_positive_images += int(bool(boxes))
                        skipped_boxes += len(boxes)
                        skipped_samples.append({
                            "match_id": match_id,
                            "at_ms": at_ms,
                            "box_count": len(boxes),
                            "score": round(score, 6),
                        })
                        frame_path.unlink(missing_ok=True)
                        continue
                crop_x = 0
                crop_y = 0
                width = full_width
                height = full_height
                if roi is not None:
                    crop_x = math.floor(roi[0] * full_width)
                    crop_y = math.floor(roi[1] * full_height)
                    crop_right = math.ceil((roi[0] + roi[2]) * full_width)
                    crop_bottom = math.ceil((roi[1] + roi[3]) * full_height)
                    if (crop_x < 0 or crop_y < 0 or crop_right > full_width or
                            crop_bottom > full_height or crop_right <= crop_x or
                            crop_bottom <= crop_y):
                        raise ValueError(
                            f"Locator produced invalid roi for {match_id} at {at_ms} ms: {roi}"
                        )
                    with Image.open(frame_path) as source:
                        cropped = source.crop((crop_x, crop_y, crop_right, crop_bottom))
                        cropped.save(frame_path)
                    width = crop_right - crop_x
                    height = crop_bottom - crop_y
                image_id = len(images) + 1
                images.append({"id": image_id, "file_name": filename,
                               "width": width, "height": height})
                if not boxes:
                    negative_images += 1
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
                    annotations.append({"id": len(annotations) + 1,
                                        "image_id": image_id, "category_id": 1,
                                        "bbox": px_box,
                                        "area": px_box[2] * px_box[3], "iscrowd": 0})
            annotation_dir = output / "annotations"
            annotation_dir.mkdir(parents=True, exist_ok=True)
            coco = {"images": images, "annotations": annotations,
                    "categories": [{"id": 1, "name": category,
                                    "supercategory": "game"}]}
            if adaptive is not None:
                coco["info"] = {
                    "adaptive_crop": adaptive.provenance(),
                    "skipped_searching_samples": skipped_samples,
                }
            (annotation_dir / f"instances_{split_name}.json").write_text(
                json.dumps(coco, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            summary[split] = {"images": len(images), "boxes": len(annotations),
                              "negative_images": negative_images}
            if adaptive is not None:
                summary[split].update({
                    "locator_states": locator_states,
                    "skipped_searching": skipped_searching,
                    "skipped_positive_images": skipped_positive_images,
                    "skipped_boxes": skipped_boxes,
                })
        return summary
    finally:
        if adaptive is not None:
            adaptive.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Private per-match frame and box annotations")
    parser.add_argument("--output", type=Path, required=True, help="Private COCO dataset directory")
    parser.add_argument("--crop-roi", action="store_true",
                        help="Crop each image to the manifest or per-match roi")
    parser.add_argument(
        "--locator", type=Path,
        help=("Crop with a mapassist.minimap_locator JSON or GameProfile; "
              "each sparse reviewed frame is resolved independently"),
    )
    parser.add_argument("--library", type=Path,
                        help="native library used by --locator")
    args = parser.parse_args()
    try:
        print(json.dumps(export(args.manifest, args.output, crop_roi=args.crop_roi,
                                locator=args.locator, library=args.library),
                         ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
