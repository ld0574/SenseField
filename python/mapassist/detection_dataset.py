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
from .roi_safety import DEFAULT_ROI_EDGE_TOLERANCE_PX, inspect_box_roi


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


def _direction_roi_for_crop(widget_roi: list[float], frame_width: int,
                            frame_height: int,
                            crop: tuple[int, int, int, int]) -> list[float]:
    """Map a full-frame widget reference into one exported crop's coordinates."""
    crop_x, crop_y, crop_right, crop_bottom = crop
    crop_width = crop_right - crop_x
    crop_height = crop_bottom - crop_y
    widget_x = widget_roi[0] * frame_width
    widget_y = widget_roi[1] * frame_height
    widget_right = (widget_roi[0] + widget_roi[2]) * frame_width
    widget_bottom = (widget_roi[1] + widget_roi[3]) * frame_height
    if (widget_x < crop_x - 1e-6 or widget_y < crop_y - 1e-6 or
            widget_right > crop_right + 1e-6 or
            widget_bottom > crop_bottom + 1e-6):
        raise ValueError("widget_roi must be fully contained in the detector crop")
    return [
        (widget_x - crop_x) / crop_width,
        (widget_y - crop_y) / crop_height,
        (widget_right - widget_x) / crop_width,
        (widget_bottom - widget_y) / crop_height,
    ]


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
    default_widget_value = data.get("widget_roi")
    default_widget_roi = (_roi(default_widget_value, "Detection manifest widget_roi")
                          if default_widget_value is not None else None)
    default_orientation = from_manifest(data)
    if crop_roi and locator is None and default_roi is None and not all(
            isinstance(match, dict) and match.get("roi") is not None for match in matches
    ):
        raise ValueError("--crop-roi needs a top-level roi or one roi per match")
    if library is not None and locator is None:
        raise ValueError("--library requires --locator")
    adaptive_requested = locator is not None

    prepared: dict[str, list[tuple[str, Path, int, list[list[float]],
                                  list[float] | None, list[float] | None, int]]] = {
        split: [] for split in SPLITS
    }
    seen_ids: set[str] = set()
    video_splits: dict[Path, str] = {}
    verified_video_hashes: dict[Path, str] = {}
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
        expected_video_sha256 = match.get("video_sha256")
        if expected_video_sha256 is not None:
            if (not isinstance(expected_video_sha256, str) or
                    len(expected_video_sha256) != 64 or
                    any(character not in "0123456789abcdefABCDEF"
                        for character in expected_video_sha256)):
                raise ValueError(
                    f"{match_id} video_sha256 must be 64 hexadecimal characters"
                )
            expected_video_sha256 = expected_video_sha256.lower()
            actual_video_sha256 = verified_video_hashes.get(video)
            if actual_video_sha256 is None:
                actual_video_sha256 = _sha256(video)
                verified_video_hashes[video] = actual_video_sha256
            if actual_video_sha256 != expected_video_sha256:
                raise ValueError(
                    f"Source video hash mismatch for {match_id}: expected "
                    f"{expected_video_sha256}, got {actual_video_sha256}. "
                    "The recording was replaced; create a new match id and review queue."
                )
        prior = video_splits.setdefault(video, split)
        if prior != split:
            raise ValueError(f"One recording cannot cross splits: {video}")
        if not isinstance(frames, list) or not frames:
            raise ValueError(f"Match {match_id} needs annotated frames")
        match_roi = None
        if crop_roi and not adaptive_requested:
            match_roi = _roi(match.get("roi", default_roi), f"{match_id} roi")
        widget_roi_value = match.get("widget_roi", default_widget_roi)
        match_widget_roi = (_roi(widget_roi_value, f"{match_id} widget_roi")
                            if widget_roi_value is not None else None)
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
                                    match_widget_roi, rotation(match_orientation)))

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
            roi_boundary_contacts = []
            physical_edge_contacts = []
            split_name = SPLIT_DIRS[split]
            split_dir = output / split_name
            split_dir.mkdir(parents=True, exist_ok=True)
            for (match_id, video, at_ms, boxes, roi, widget_roi,
                 display_rotation) in frames:
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
                    for box_index, box in enumerate(boxes, start=1):
                        audit = inspect_box_roi(
                            box, roi, full_width, full_height,
                            DEFAULT_ROI_EDGE_TOLERANCE_PX,
                        )
                        if audit["outside"]:
                            frame_path.unlink(missing_ok=True)
                            raise ValueError(
                                f"Detection box would be silently clipped by the crop "
                                f"for {match_id}@{at_ms} ms, box {box_index} "
                                f"({', '.join(audit['outside'])}); expand the dataset ROI "
                                "and re-annotate before export"
                            )
                        if audit["crop_touches"]:
                            roi_boundary_contacts.append({
                                "match_id": match_id,
                                "at_ms": at_ms,
                                "box_index": box_index,
                                "sides": audit["crop_touches"],
                            })
                        if audit["frame_touches"]:
                            physical_edge_contacts.append({
                                "match_id": match_id,
                                "at_ms": at_ms,
                                "box_index": box_index,
                                "sides": audit["frame_touches"],
                            })
                    with Image.open(frame_path) as source:
                        cropped = source.crop((crop_x, crop_y, crop_right, crop_bottom))
                        cropped.save(frame_path)
                    width = crop_right - crop_x
                    height = crop_bottom - crop_y
                image_id = len(images) + 1
                image_record = {"id": image_id, "file_name": filename,
                                "width": width, "height": height}
                if widget_roi is not None:
                    image_record["direction_roi"] = _direction_roi_for_crop(
                        widget_roi, full_width, full_height,
                        (crop_x, crop_y, crop_x + width, crop_y + height),
                    )
                images.append(image_record)
                if not boxes:
                    negative_images += 1
                for x, y, w, h in boxes:
                    if roi is None:
                        px_box = [x * full_width, y * full_height,
                                  w * full_width, h * full_height]
                    else:
                        px_box = [round(value, 6) for value in
                                  (x * full_width - crop_x, y * full_height - crop_y,
                                   w * full_width, h * full_height)]
                    annotations.append({"id": len(annotations) + 1,
                                        "image_id": image_id, "category_id": 1,
                                        "bbox": px_box,
                                        "area": px_box[2] * px_box[3], "iscrowd": 0})
            annotation_dir = output / "annotations"
            annotation_dir.mkdir(parents=True, exist_ok=True)
            coco = {"images": images, "annotations": annotations,
                    "categories": [{"id": 1, "name": category,
                                    "supercategory": "game"}]}
            if default_widget_roi is not None or any(
                    match[5] is not None for match in frames):
                coco["info"] = {
                    "direction_reference": (
                        "Per-image direction_roi is normalized to the exported image "
                        "and identifies the minimap widget boundary."
                    ),
                }
            if crop_roi or adaptive is not None:
                coco.setdefault("info", {}).update({
                    "roi_boundary_audit": {
                        "schema_version": 1,
                        "edge_tolerance_px": DEFAULT_ROI_EDGE_TOLERANCE_PX,
                        "crop_edge_contacts": roi_boundary_contacts,
                        "physical_edge_contacts": physical_edge_contacts,
                        # Keep the initial key for readers of earlier reports.
                        "edge_contacts": roi_boundary_contacts,
                        "training_eligible": not roi_boundary_contacts,
                        "usable_for_training_or_evaluation": not roi_boundary_contacts,
                        "policy": (
                            "Target boxes within an expandable crop-edge safety band "
                            "require manual review. Physical frame-edge contacts are "
                            "reported separately because the source cannot be expanded."
                        ),
                    },
                })
            if adaptive is not None:
                coco.setdefault("info", {}).update({
                    "adaptive_crop": adaptive.provenance(),
                    "skipped_searching_samples": skipped_samples,
                })
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
