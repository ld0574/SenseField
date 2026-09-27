"""Remap detection ROIs from reviewed minimap widget bounds.

The layout manifest is treated as the source of truth. Every selected match
must have exactly one normalized widget box in each layout frame, and those
boxes must be identical. The safe ROI expands that widget by a fixed fraction
of the display's short side on every edge, clipped to the display frame.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

from .orientation import from_manifest, probe


SCHEMA_VERSION = 1
DEFAULT_PADDING_SHORT_SIDE_FRACTION = 0.035
DEFAULT_WIDGET_CENTER_TOLERANCE_PX = 4.0


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _read_manifest(path: Path, label: str) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Could not read {label} manifest {path}: {error}") from error
    if not isinstance(document, dict):
        raise ValueError(f"{label} manifest must be a JSON object: {path}")
    version = document.get("schema_version")
    if (not isinstance(version, int) or isinstance(version, bool) or
            version != SCHEMA_VERSION):
        raise ValueError(f"{label} manifest schema_version must be {SCHEMA_VERSION}: {path}")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError(f"{label} manifest must contain a non-empty matches list: {path}")
    return document


def _match_index(document: dict[str, Any], manifest_path: Path,
                 label: str) -> dict[str, tuple[dict[str, Any], Path]]:
    indexed: dict[str, tuple[dict[str, Any], Path]] = {}
    recordings: dict[Path, str] = {}
    for position, match in enumerate(document["matches"]):
        if not isinstance(match, dict):
            raise ValueError(f"{label} match #{position + 1} must be an object")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id.strip():
            raise ValueError(f"{label} match #{position + 1} has an invalid id")
        if match_id in indexed:
            raise ValueError(f"Duplicate {label} match id: {match_id}")
        video_value = match.get("video")
        if not isinstance(video_value, str) or not video_value.strip():
            raise ValueError(f"{label} match {match_id} has no video path")
        video = (manifest_path.parent / video_value).resolve()
        if not video.is_file():
            raise ValueError(f"{label} match {match_id} video does not exist: {video}")
        prior_id = recordings.get(video)
        if prior_id is not None:
            raise ValueError(
                f"{label} matches {prior_id} and {match_id} refer to the same video: {video}"
            )
        recordings[video] = match_id
        indexed[match_id] = (match, video)
    return indexed


def _normalized_box(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(number, (int, float)) or isinstance(number, bool)
                for number in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(number) for number in value]
    if not all(math.isfinite(number) for number in result):
        raise ValueError(f"{label} must contain finite numbers")
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000000001 or y + height > 1.000000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def _effective_orientation(match: dict[str, Any], document: dict[str, Any],
                           label: str) -> dict[str, Any] | None:
    match_orientation = from_manifest(match, label)
    document_orientation = from_manifest(document, f"{label} manifest")
    if (match_orientation is not None and document_orientation is not None and
            match_orientation != document_orientation):
        raise ValueError(f"{label} orientation conflicts with its manifest defaults")
    return match_orientation or document_orientation


def _check_orientation_compatibility(
    source: dict[str, Any], source_document: dict[str, Any],
    layout_orientation: dict[str, Any], match_id: str,
) -> None:
    source_orientation = _effective_orientation(
        source, source_document, f"detection match {match_id}"
    )
    if source_orientation is None:
        return
    for key in ("source_coded_size", "display_size", "display_rotation_degrees"):
        if key in source_orientation and key in layout_orientation:
            if source_orientation[key] != layout_orientation[key]:
                raise ValueError(
                    f"{match_id} detection/layout {key} does not match"
                )


def _layout_widget(match: dict[str, Any], match_id: str) -> tuple[list[float], int]:
    frames = match.get("frames")
    if not isinstance(frames, list) or not frames:
        raise ValueError(f"Layout match {match_id} must contain frames")
    canonical: list[float] | None = None
    timestamps: set[int] = set()
    for index, frame in enumerate(frames):
        if not isinstance(frame, dict):
            raise ValueError(f"Layout match {match_id} frame #{index + 1} must be an object")
        timestamp = frame.get("at_ms")
        if (not isinstance(timestamp, int) or isinstance(timestamp, bool) or
                timestamp < 0 or timestamp in timestamps):
            raise ValueError(f"Layout match {match_id} has an invalid/duplicate frame timestamp")
        timestamps.add(timestamp)
        boxes = frame.get("boxes")
        if not isinstance(boxes, list) or len(boxes) != 1:
            raise ValueError(
                f"Layout match {match_id} frame {timestamp} must have exactly one widget box"
            )
        box = _normalized_box(boxes[0], f"Layout match {match_id} frame {timestamp} box")
        if canonical is None:
            canonical = box
        elif box != canonical:
            raise ValueError(
                f"Layout match {match_id} widget boxes differ across frames; "
                "review the layout instead of silently aggregating it"
            )
    assert canonical is not None
    return canonical, len(frames)


def _inspect_widget_centers(
    match: dict[str, Any], match_id: str, widget_roi: list[float],
    display_width: int, display_height: int, tolerance_px: float,
) -> list[dict[str, Any]]:
    """Allow only small center-outside quantization and report every exception."""
    frames = match.get("frames")
    if not isinstance(frames, list) or not frames:
        raise ValueError(f"Detection match {match_id} must contain frames")
    widget_x, widget_y, widget_width, widget_height = widget_roi
    widget_right = widget_x + widget_width
    widget_bottom = widget_y + widget_height
    exceptions = []
    seen_timestamps: set[int] = set()
    for frame_index, frame in enumerate(frames):
        if not isinstance(frame, dict):
            raise ValueError(
                f"Detection match {match_id} frame #{frame_index + 1} must be an object"
            )
        at_ms = frame.get("at_ms")
        if (not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0 or
                at_ms in seen_timestamps):
            raise ValueError(
                f"Detection match {match_id} has an invalid/duplicate frame timestamp"
            )
        seen_timestamps.add(at_ms)
        boxes = frame.get("boxes")
        if not isinstance(boxes, list):
            raise ValueError(f"Detection match {match_id}@{at_ms} boxes must be a list")
        for box_index, value in enumerate(boxes, start=1):
            box = _normalized_box(
                value, f"Detection match {match_id}@{at_ms} box {box_index}"
            )
            x, y, width, height = box
            center_x = x + width / 2
            center_y = y + height / 2
            distances = {}
            if center_x < widget_x:
                distances["left"] = (widget_x - center_x) * display_width
            elif center_x > widget_right:
                distances["right"] = (center_x - widget_right) * display_width
            if center_y < widget_y:
                distances["top"] = (widget_y - center_y) * display_height
            elif center_y > widget_bottom:
                distances["bottom"] = (center_y - widget_bottom) * display_height
            if not distances:
                continue
            outside_px = max(distances.values())
            if outside_px > tolerance_px + 1e-9:
                sides = ", ".join(distances)
                raise ValueError(
                    f"{match_id}@{at_ms} box {box_index} center is "
                    f"{outside_px:.3f} px outside widget ({sides}); "
                    f"tolerance is {tolerance_px:g} px"
                )
            exceptions.append({
                "match_id": match_id,
                "at_ms": at_ms,
                "box_index": box_index,
                "outside_px": round(outside_px, 6),
                "sides": list(distances),
            })
    return exceptions


def _safe_roi(widget_roi: list[float], width: int, height: int,
              padding_fraction: float) -> list[float]:
    if width <= 0 or height <= 0:
        raise ValueError("Display dimensions must be positive")
    padding_pixels = min(width, height) * padding_fraction
    padding_x = padding_pixels / width
    padding_y = padding_pixels / height
    x, y, box_width, box_height = widget_roi
    left = max(0.0, x - padding_x)
    top = max(0.0, y - padding_y)
    right = min(1.0, x + box_width + padding_x)
    bottom = min(1.0, y + box_height + padding_y)
    return [left, top, right - left, bottom - top]


def remap_manifest(
    detection_manifest: Path,
    layout_manifest: Path,
    *,
    padding_short_side_fraction: float = DEFAULT_PADDING_SHORT_SIDE_FRACTION,
    widget_center_tolerance_px: float = DEFAULT_WIDGET_CENTER_TOLERANCE_PX,
    include_matches: set[str] | None = None,
    exclude_matches: set[str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return a remapped manifest and audit summary without writing either input."""
    if (not isinstance(padding_short_side_fraction, (int, float)) or
            isinstance(padding_short_side_fraction, bool) or
            not math.isfinite(float(padding_short_side_fraction)) or
            padding_short_side_fraction < 0 or padding_short_side_fraction >= 0.5):
        raise ValueError("Padding fraction must be finite and in [0, 0.5)")
    padding_short_side_fraction = float(padding_short_side_fraction)
    if (not isinstance(widget_center_tolerance_px, (int, float)) or
            isinstance(widget_center_tolerance_px, bool)):
        raise ValueError("Widget center tolerance must be finite and nonnegative")
    try:
        widget_center_tolerance_px = float(widget_center_tolerance_px)
    except (OverflowError, ValueError) as error:
        raise ValueError(
            "Widget center tolerance must be finite and nonnegative"
        ) from error
    if not math.isfinite(widget_center_tolerance_px) or widget_center_tolerance_px < 0:
        raise ValueError("Widget center tolerance must be finite and nonnegative")
    detection_manifest = detection_manifest.resolve()
    layout_manifest = layout_manifest.resolve()
    detection = _read_manifest(detection_manifest, "Detection")
    layout = _read_manifest(layout_manifest, "Layout")
    detections = _match_index(detection, detection_manifest, "Detection")
    layouts = _match_index(layout, layout_manifest, "Layout")

    include_matches = set(include_matches) if include_matches is not None else None
    exclude_matches = set(exclude_matches or ())
    unknown_excluded = sorted(exclude_matches - detections.keys())
    if unknown_excluded:
        raise ValueError(f"Unknown excluded match id(s): {', '.join(unknown_excluded)}")
    if include_matches is not None:
        unknown_included = sorted(include_matches - detections.keys())
        if unknown_included:
            raise ValueError(f"Unknown included match id(s): {', '.join(unknown_included)}")
        overlap = sorted(include_matches & exclude_matches)
        if overlap:
            raise ValueError(f"Match id(s) cannot be both included and excluded: {', '.join(overlap)}")
        selected_ids = include_matches
    else:
        selected_ids = set(detections) - exclude_matches
    if not selected_ids:
        raise ValueError("No matches remain after applying match filters")

    # Compare every shared ID, including filtered-out entries, so stale recording
    # mappings cannot be hidden by the selection flags. Layout manifests may have
    # additional matches (for example video8) that are not in the detector source.
    for match_id in sorted(set(detections) & set(layouts)):
        if detections[match_id][1] != layouts[match_id][1]:
            raise ValueError(
                f"Match id {match_id} maps to different videos in detection and layout manifests"
            )
    missing_layout = sorted(selected_ids - layouts.keys())
    if missing_layout:
        raise ValueError(f"Missing layout match id(s): {', '.join(missing_layout)}")

    remapped = copy.deepcopy(detection)
    remapped["matches"] = []
    audit_matches: dict[str, Any] = {}
    widget_center_exceptions: list[dict[str, Any]] = []
    for match_id, (source, video_path) in detections.items():
        if match_id not in selected_ids:
            continue
        layout_match, _ = layouts[match_id]
        widget_roi, frame_count = _layout_widget(layout_match, match_id)
        layout_orientation = _effective_orientation(
            layout_match, layout, f"layout match {match_id}"
        )
        if layout_orientation is None:
            layout_orientation = probe(video_path)
        display_size = layout_orientation.get("display_size")
        if (not isinstance(display_size, list) or len(display_size) != 2 or
                any(not isinstance(dimension, int) or isinstance(dimension, bool) or
                    dimension <= 0 for dimension in display_size)):
            raise ValueError(f"Layout match {match_id} needs a valid display_size")
        _check_orientation_compatibility(source, detection, layout_orientation, match_id)

        center_exceptions = _inspect_widget_centers(
            source, match_id, widget_roi, display_size[0], display_size[1],
            widget_center_tolerance_px,
        )
        widget_center_exceptions.extend(center_exceptions)

        actual_sha256 = _sha256(video_path)
        prior_sha256 = source.get("video_sha256")
        if prior_sha256 is not None:
            if (not isinstance(prior_sha256, str) or len(prior_sha256) != 64 or
                    any(char not in "0123456789abcdefABCDEF" for char in prior_sha256)):
                raise ValueError(f"{match_id} video_sha256 must be 64 hexadecimal characters")
            if prior_sha256.lower() != actual_sha256:
                raise ValueError(f"{match_id} video_sha256 does not match its video file")

        safe_roi = _safe_roi(widget_roi, display_size[0], display_size[1],
                             padding_short_side_fraction)
        exported = copy.deepcopy(source)
        exported["roi"] = safe_roi
        exported["widget_roi"] = widget_roi
        exported["video_sha256"] = actual_sha256
        remapped["matches"].append(exported)
        audit_matches[match_id] = {
            "layout_frame_count": frame_count,
            "widget_roi": widget_roi,
            "roi": safe_roi,
            "display_size": display_size,
            "video_sha256": actual_sha256,
        }

    remapped["roi_remap_audit"] = {
        "schema_version": SCHEMA_VERSION,
        "method": "layout-widget-expanded-by-short-side-fraction",
        "padding_short_side_fraction": padding_short_side_fraction,
        "widget_center_tolerance_px": widget_center_tolerance_px,
        "widget_center_tolerance_policy": (
            "box centers may lie outside widget bounds only within the configured "
            "display-pixel tolerance"
        ),
        "widget_center_tolerance_exceptions": widget_center_exceptions,
        "padding_definition": (
            "expand every widget edge by fraction * min(display_width, display_height), "
            "then clip to the display frame"
        ),
        "layout_manifest": str(layout_manifest),
        "layout_manifest_sha256": _sha256(layout_manifest),
        "layout_frame_policy": "exactly one identical widget box in every frame",
        "matches": audit_matches,
    }
    summary = {
        "matches": len(remapped["matches"]),
        "frames": sum(len(match.get("frames", [])) for match in remapped["matches"]),
        "boxes": sum(len(frame.get("boxes", []))
                     for match in remapped["matches"]
                     for frame in match.get("frames", [])),
        "match_ids": [match["id"] for match in remapped["matches"]],
        "roi_by_match": {match["id"]: match["roi"] for match in remapped["matches"]},
    }
    return remapped, summary


def write_manifest_atomic(document: dict[str, Any], output: Path) -> None:
    """Write JSON in the destination directory and atomically replace output."""
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=output.parent,
            prefix=f".{output.name}.", suffix=".tmp", delete=False,
        ) as stream:
            temporary_path = Path(stream.name)
            json.dump(document, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def remap_to_file(
    detection_manifest: Path,
    layout_manifest: Path,
    output: Path,
    *,
    padding_short_side_fraction: float = DEFAULT_PADDING_SHORT_SIDE_FRACTION,
    widget_center_tolerance_px: float = DEFAULT_WIDGET_CENTER_TOLERANCE_PX,
    include_matches: set[str] | None = None,
    exclude_matches: set[str] | None = None,
) -> dict[str, Any]:
    detection_resolved = detection_manifest.resolve()
    layout_resolved = layout_manifest.resolve()
    output_resolved = output.resolve()
    if output_resolved in {detection_resolved, layout_resolved}:
        raise ValueError("Output path must not overwrite either input manifest")
    document, summary = remap_manifest(
        detection_manifest, layout_manifest,
        padding_short_side_fraction=padding_short_side_fraction,
        widget_center_tolerance_px=widget_center_tolerance_px,
        include_matches=include_matches, exclude_matches=exclude_matches,
    )
    write_manifest_atomic(document, output)
    summary["output"] = str(output_resolved)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("detection_manifest", type=Path)
    parser.add_argument("layout_manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--padding-short-side-fraction", type=float,
        default=DEFAULT_PADDING_SHORT_SIDE_FRACTION,
        help="padding on each edge as a fraction of the display short side (default: 0.035)",
    )
    parser.add_argument(
        "--widget-center-tolerance-px", type=float,
        default=DEFAULT_WIDGET_CENTER_TOLERANCE_PX,
        help=("maximum distance in display pixels for a box center just outside "
              "the widget bounds (default: 4.0; larger distances are rejected)"),
    )
    parser.add_argument(
        "--include-match", action="append", default=None, metavar="ID",
        help="keep only these match IDs; may be repeated",
    )
    parser.add_argument(
        "--exclude-match", action="append", default=[], metavar="ID",
        help="omit this match ID; may be repeated",
    )
    args = parser.parse_args()
    try:
        result = remap_to_file(
            args.detection_manifest, args.layout_manifest, args.output,
            padding_short_side_fraction=args.padding_short_side_fraction,
            widget_center_tolerance_px=args.widget_center_tolerance_px,
            include_matches=set(args.include_match) if args.include_match is not None else None,
            exclude_matches=set(args.exclude_match),
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
