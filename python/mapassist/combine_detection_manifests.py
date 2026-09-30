"""Combine additional reviewed frames for the same recordings into one manifest."""

from __future__ import annotations

import argparse
import copy
import json
import math
import os
import re
import sys
import tempfile
from pathlib import Path

from .dataset_scope import validate_dataset_scope
from .orientation import from_manifest


SPLITS = {"train", "val", "test"}
CATEGORY = re.compile(r"^[A-Za-z0-9_-]+$")


def _has_symlink_component(path: Path) -> bool:
    candidate = path.expanduser()
    if not candidate.is_absolute():
        candidate = Path.cwd() / candidate
    for component in (candidate, *candidate.parents):
        try:
            if component.is_symlink():
                return True
        except OSError as error:
            raise ValueError(f"cannot inspect output path: {path}") from error
    return False


def _output_path(manifests: list[Path], output: Path) -> Path:
    raw = Path(output).expanduser()
    if _has_symlink_component(raw):
        raise ValueError(f"output must not use a symlink path: {output}")
    try:
        resolved = raw.resolve()
    except (OSError, RuntimeError, ValueError) as error:
        raise ValueError(f"cannot resolve output path: {output}") from error
    if resolved.exists() and not resolved.is_file():
        raise ValueError(f"output must be a file path: {output}")
    for manifest in manifests:
        input_path = Path(manifest).expanduser().resolve()
        if resolved == input_path:
            raise ValueError(f"output must not overwrite input manifest: {manifest}")
        if resolved.exists() and input_path.exists():
            try:
                same_file = os.path.samefile(resolved, input_path)
            except OSError:
                same_file = False
            if same_file:
                raise ValueError(f"output must not overwrite input manifest: {manifest}")
    return resolved


def _write_json_atomic(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(item) for item in value]
    if not all(math.isfinite(item) for item in result):
        raise ValueError(f"{label} must contain finite normalized numbers")
    x, y, width, height = result
    if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1 or y + height > 1:
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def _classes(data: dict, category: str, label: str) -> list[str]:
    value = data.get("classes")
    if value is None:
        value = [category]
    if (not isinstance(value, list) or not value or
            any(not isinstance(item, str) or not item.strip() for item in value)):
        raise ValueError(f"{label} classes must be a non-empty list of names")
    result = [item.strip() for item in value]
    if len(set(result)) != len(result):
        raise ValueError(f"{label} classes must not contain duplicates")
    if category not in result:
        raise ValueError(f"{label} category {category!r} is not in classes")
    return result


def combine(manifests: list[Path], output: Path,
            split_overrides: dict[str, str] | None = None) -> dict:
    if not manifests:
        raise ValueError("At least one detection manifest is required")
    output = _output_path(manifests, output)
    split_overrides = split_overrides or {}
    category = None
    classes: list[str] | None = None
    grouped: dict[Path, dict] = {}
    ids: dict[str, Path] = {}
    used_overrides: set[str] = set()
    orientations = []
    scope_seen = False
    merged_scope: dict | None = None
    for manifest in manifests:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if data.get("schema_version") != 1 or not isinstance(data.get("matches"), list):
            raise ValueError(f"Invalid detection manifest: {manifest}")
        current_scope = (
            validate_dataset_scope(
                data["dataset_scope"],
                f"{manifest} dataset_scope",
                require_training_truth=True,
            )
            if "dataset_scope" in data else None
        )
        if (isinstance(current_scope, dict) and
                current_scope["training_truth"] is False):
            raise ValueError(
                f"{manifest} dataset_scope.training_truth=false cannot be merged; "
                "export diagnostic data separately"
            )
        if scope_seen and current_scope != merged_scope:
            raise ValueError(
                "Detection manifest dataset_scope metadata conflicts; all inputs "
                "must omit it or use exactly the same training scope"
            )
        if not scope_seen:
            scope_seen = True
            merged_scope = current_scope
        current_category = data.get("category", "main_enemy")
        if not isinstance(current_category, str) or not CATEGORY.fullmatch(current_category):
            raise ValueError(f"Invalid detection category in {manifest}: {current_category}")
        if category is None:
            category = current_category
        elif current_category != category:
            raise ValueError("Detection categories differ")
        current_classes = _classes(data, current_category, str(manifest))
        if classes is None:
            classes = current_classes
        elif current_classes != classes:
            raise ValueError("Detection class order differs")
        default_roi = data.get("roi")
        default_widget_value = data.get("widget_roi")
        default_widget_roi = (_roi(default_widget_value, f"{manifest} widget_roi")
                              if default_widget_value is not None else None)
        default_label_value = data.get("label_roi")
        default_label_roi = (_roi(default_label_value, f"{manifest} label_roi")
                             if default_label_value is not None else None)
        default_orientation = from_manifest(data, str(manifest))
        orientations.append(default_orientation)
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
            video_sha256 = source.get("video_sha256")
            if video_sha256 is not None:
                if (not isinstance(video_sha256, str) or len(video_sha256) != 64 or
                        any(character not in "0123456789abcdefABCDEF"
                            for character in video_sha256)):
                    raise ValueError(
                        f"{match_id} video_sha256 must be 64 hexadecimal characters"
                    )
                video_sha256 = video_sha256.lower()
            prior_video = ids.setdefault(match_id, video)
            if prior_video != video:
                raise ValueError(f"Match id {match_id} refers to different recordings")
            roi = _roi(source.get("roi", default_roi), f"{match_id} roi")
            widget_value = source.get("widget_roi", default_widget_roi)
            widget_roi = (_roi(widget_value, f"{match_id} widget_roi")
                          if widget_value is not None else None)
            label_value = source.get("label_roi")
            if label_value is None:
                label_value = default_label_roi
            if label_value is None:
                # Legacy manifests used the widget boundary for annotation centers.
                label_value = widget_roi
            label_roi = (_roi(label_value, f"{match_id} label_roi")
                         if label_value is not None else None)
            match_orientation = from_manifest(source, f"{match_id}") or default_orientation
            split = split_overrides.get(match_id, source.get("split"))
            if split not in SPLITS:
                raise ValueError(f"Invalid split for {match_id}: {split}")
            if match_id in split_overrides:
                used_overrides.add(match_id)
            record = grouped.setdefault(video, {
                "id": match_id, "video": str(video), "split": split,
                "roi": roi, "widget_roi": widget_roi, "label_roi": label_roi,
                "orientation": match_orientation, "video_sha256": video_sha256,
                "frames": {},
            })
            if (record["id"] != match_id or record["roi"] != roi or
                    record["widget_roi"] != widget_roi or
                    record["label_roi"] != label_roi or
                    record["split"] != split or
                    record["orientation"] != match_orientation or
                    record["video_sha256"] != video_sha256):
                raise ValueError(f"Metadata differs for repeated recording {match_id}")
            for frame in source.get("frames", []):
                timestamp = frame.get("at_ms")
                boxes = frame.get("boxes")
                if not isinstance(timestamp, int) or not isinstance(boxes, list):
                    raise ValueError(f"Invalid frame in {match_id}")
                frame_categories = frame.get("categories")
                if frame_categories is None:
                    if len(classes) > 1 and boxes:
                        raise ValueError(
                            f"{match_id}@{timestamp} categories are required for a multi-class manifest"
                        )
                    frame_categories = [current_category] * len(boxes)
                if (not isinstance(frame_categories, list) or
                        len(frame_categories) != len(boxes) or
                        any(not isinstance(item, str) or item not in classes
                            for item in frame_categories)):
                    raise ValueError(
                        f"Invalid categories for {match_id}@{timestamp}; they must align with boxes"
                    )
                frame_value = {"boxes": boxes, "categories": list(frame_categories)}
                previous = record["frames"].setdefault(timestamp, frame_value)
                if previous != frame_value:
                    raise ValueError(
                        f"Conflicting labels for {match_id}@{timestamp}; review manually"
                    )
    unused = sorted(set(split_overrides) - used_overrides)
    if unused:
        raise ValueError(f"Split overrides did not match: {', '.join(unused)}")
    matches = []
    for record in sorted(grouped.values(), key=lambda item: item["id"]):
        exported = {
            "id": record["id"], "video": record["video"],
            "split": record["split"], "roi": record["roi"],
            "frames": [{"at_ms": timestamp, **frame}
                       for timestamp, frame in sorted(record["frames"].items())],
        }
        if record["widget_roi"] is not None:
            exported["widget_roi"] = record["widget_roi"]
        if record["label_roi"] is not None:
            exported["label_roi"] = record["label_roi"]
        if record["video_sha256"] is not None:
            exported["video_sha256"] = record["video_sha256"]
        matches.append(exported)
        if record["orientation"] is not None:
            matches[-1]["orientation"] = record["orientation"]
    result = {"schema_version": 1, "category": category,
              "classes": classes, "matches": matches}
    if merged_scope is not None:
        result["dataset_scope"] = copy.deepcopy(merged_scope)
    if orientations and all(item == orientations[0] for item in orientations):
        if orientations[0] is not None:
            result["orientation"] = orientations[0]
    _write_json_atomic(output, result)
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
