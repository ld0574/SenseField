"""Replay the native minimap locator on manually reviewed full-screen frames.

This diagnostic evaluates layout acquisition only.  It never runs YOLOX and
never reads detector suggestions, so it can be used before model evaluation.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import ctypes as C
import hashlib
import json
import math
import statistics
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image, UnidentifiedImageError

from .calibrate_minimap_anchor import _display_image, _sample_frame_paths
from .native import MinimapLocatorConfig, Rect, load_library


_STATE_NAMES = {0: "searching", 1: "locked", 2: "held"}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _number(data: dict[str, Any], key: str) -> float:
    value = data.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"Locator {key} must be numeric")
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"Locator {key} must be finite")
    return result


def _integer(data: dict[str, Any], key: str) -> int:
    value = _number(data, key)
    if not value.is_integer():
        raise ValueError(f"Locator {key} must be an integer")
    return int(value)


def parse_locator(data: object) -> tuple[MinimapLocatorConfig, bytes, dict[str, Any]]:
    """Validate a locator object shared by diagnostics and full ncnn replay."""
    if not isinstance(data, dict) or data.get("schema") != "mapassist.minimap_locator" or \
            data.get("schema_version") != 1 or data.get("version") != 1 or \
            data.get("coordinate_space") != "short_edge":
        raise ValueError("Unsupported minimap locator schema")
    base = data.get("base_rect_short")
    if not isinstance(base, list) or len(base) != 4:
        raise ValueError("Locator base_rect_short needs four numbers")
    if any(not isinstance(value, (int, float)) or isinstance(value, bool) or
           not math.isfinite(float(value)) for value in base):
        raise ValueError("Locator base_rect_short needs four finite numbers")
    base_values = [float(value) for value in base]
    if (base_values[0] < 0 or base_values[0] >= 4 or
            base_values[1] < 0 or base_values[1] >= 1 or
            base_values[2] <= 0.01 or base_values[2] >= 2 or
            base_values[3] <= 0.01 or base_values[3] >= 2 or
            base_values[0] + base_values[2] > 4.000001 or
            base_values[1] + base_values[3] > 1.000001):
        raise ValueError("Invalid minimap locator base rectangle")

    grid_width = _integer(data, "grid_width")
    grid_height = _integer(data, "grid_height")
    if not 4 <= grid_width <= 64 or not 4 <= grid_height <= 64:
        raise ValueError("Locator descriptor grid must be between 4 and 64")
    encoded = data.get("descriptor_b64")
    if not isinstance(encoded, str) or len(encoded) > 2_000_000:
        raise ValueError("Invalid or oversized locator descriptor_b64")
    try:
        descriptor = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("Invalid locator descriptor_b64") from error
    if len(descriptor) != grid_width * grid_height:
        raise ValueError("Locator descriptor length does not match its grid")

    expected_descriptor_sha256 = data.get("descriptor_sha256")
    actual_descriptor_sha256 = hashlib.sha256(descriptor).hexdigest()
    if (not isinstance(expected_descriptor_sha256, str) or
            len(expected_descriptor_sha256) != 64 or
            any(character not in "0123456789abcdefABCDEF"
                for character in expected_descriptor_sha256) or
            expected_descriptor_sha256.lower() != actual_descriptor_sha256):
        raise ValueError("Locator descriptor SHA-256 mismatch")
    quantization = data.get("quantization")
    if (not isinstance(quantization, dict) or quantization.get("dtype") != "int8" or
            _number(quantization, "scale") != 32.0 or
            _integer(quantization, "zero_point") != 0):
        raise ValueError("Unsupported locator descriptor quantization")

    search_radius_x = _number(data, "search_radius_x_short")
    search_radius_y = _number(data, "search_radius_y_short")
    position_step = _number(data, "position_step_short")
    min_scale = _number(data, "min_scale")
    max_scale = _number(data, "max_scale")
    scale_steps = _integer(data, "scale_steps")
    min_aspect = _number(data, "min_aspect")
    max_aspect = _number(data, "max_aspect")
    aspect_steps = _integer(data, "aspect_steps")
    min_score = _number(data, "min_score")
    confirm_frames = _integer(data, "confirm_frames")
    hold_frames = _integer(data, "hold_frames")
    refresh_frames = _integer(data, "refresh_frames")
    normalize_black_bars = data.get("normalize_black_bars")
    black_threshold = _integer(data, "black_threshold")
    preserve_base_roi = data.get("preserve_base_roi", False)
    if (not 0 <= search_radius_x <= 1 or not 0 <= search_radius_y <= 1 or
            not 0.0005 < position_step <= 0.25 or
            not 0.5 <= min_scale <= max_scale <= 2 or
            not 1 <= scale_steps <= 21 or
            not 0.5 <= min_aspect <= max_aspect <= 2 or
            not 1 <= aspect_steps <= 21 or
            not -1 <= min_score <= 1 or
            not 1 <= confirm_frames <= 30 or
            not 0 <= hold_frames <= 120 or
            not 1 <= refresh_frames <= 600 or
            not isinstance(normalize_black_bars, bool) or
            not isinstance(preserve_base_roi, bool) or
            not 0 <= black_threshold <= 64):
        raise ValueError("Invalid minimap locator search configuration")
    x_steps = math.ceil(search_radius_x / position_step)
    y_steps = math.ceil(search_radius_y / position_step)
    candidates = (x_steps * 2 + 1) * (y_steps * 2 + 1) * scale_steps * aspect_steps
    if candidates > 20_000 or candidates * grid_width * grid_height > 8_000_000:
        raise ValueError("Minimap locator search budget is too large")

    config = MinimapLocatorConfig(
        Rect(*base_values),
        search_radius_x,
        search_radius_y,
        position_step,
        min_scale,
        max_scale,
        scale_steps,
        min_aspect,
        max_aspect,
        aspect_steps,
        grid_width,
        grid_height,
        min_score,
        confirm_frames,
        hold_frames,
        refresh_frames,
        int(normalize_black_bars),
        black_threshold,
        int(preserve_base_roi),
    )
    return config, descriptor, data


def _load_locator(path: Path) -> tuple[MinimapLocatorConfig, bytes, dict[str, Any]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read locator {path}: {error}") from error
    if isinstance(data, dict) and data.get("schema") == "mapassist.minimap_locator":
        locator = data
    else:
        layout = data.get("layout") if isinstance(data, dict) else None
        locator = layout.get("minimap_locator") if isinstance(layout, dict) else None
        if locator is None:
            raise ValueError(
                "Expected a minimap locator document or GameProfile with "
                "layout.minimap_locator"
            )
    return parse_locator(locator)


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((len(ordered) - 1) * percentile + 0.5)))
    return ordered[index]


def _summary(records: list[dict[str, Any]]) -> dict[str, Any]:
    states = Counter(record["state"] for record in records)
    rois = [record["roi"] for record in records if record["state"] != 0]
    contents = [record["content"] for record in records]
    scores = [record["score"] for record in records]
    durations = [record["processing_ms"] for record in records]
    available = [record for record in records if record["state"] != 0]
    agreement = []
    for record in available:
        predicted = record["roi"]
        reference = record["reference_roi"]
        px0, py0, pw, ph = predicted
        rx0, ry0, rw, rh = reference
        px1, py1 = px0 + pw, py0 + ph
        rx1, ry1 = rx0 + rw, ry0 + rh
        intersection = max(0.0, min(px1, rx1) - max(px0, rx0)) * \
            max(0.0, min(py1, ry1) - max(py0, ry0))
        union = pw * ph + rw * rh - intersection
        width, height = record["frame_size"]
        edge_errors = [abs(px0 - rx0) * width, abs(py0 - ry0) * height,
                       abs(px1 - rx1) * width, abs(py1 - ry1) * height]
        agreement.append({
            "iou": intersection / union if union > 0 else 0.0,
            "reference_coverage": intersection / (rw * rh),
            "predicted_content_ratio": intersection / (pw * ph),
            "max_edge_error_px": max(edge_errors),
            "center_error_px": math.hypot(
                ((px0 + px1) - (rx0 + rx1)) * width / 2,
                ((py0 + py1) - (ry0 + ry1)) * height / 2,
            ),
        })
    result = {
        "frames": len(records),
        "states": {name: states[value] for value, name in _STATE_NAMES.items()},
        "available_ratio": round((states[1] + states[2]) / len(records), 6),
        "score": {
            "median": round(statistics.median(scores), 6),
            "minimum": round(min(scores), 6),
        },
        "roi_median": [round(statistics.median(row[index] for row in rois), 8)
                       for index in range(4)] if rois else None,
        "content_median": [round(statistics.median(row[index] for row in contents), 8)
                           for index in range(4)],
        "processing_ms": {
            "median": round(statistics.median(durations), 6),
            "p95": round(_percentile(durations, 0.95) or 0.0, 6),
            "maximum": round(max(durations), 6),
            "mean": round(statistics.mean(durations), 6),
        },
    }
    if agreement:
        ious = [item["iou"] for item in agreement]
        edge_errors = [item["max_edge_error_px"] for item in agreement]
        center_errors = [item["center_error_px"] for item in agreement]
        reference_coverage = [item["reference_coverage"] for item in agreement]
        predicted_content = [item["predicted_content_ratio"] for item in agreement]
        result["reference_crop_agreement"] = {
            "scope": "agreement with the reviewed dataset crop; not an independently traced HUD boundary",
            "available_frames": len(agreement),
            "iou_median": round(statistics.median(ious), 6),
            "iou_p05": round(_percentile(ious, 0.05) or 0.0, 6),
            "iou_minimum": round(min(ious), 6),
            "iou_at_least_0_8_ratio": round(sum(value >= 0.8 for value in ious) / len(ious), 6),
            "reference_coverage_median": round(statistics.median(reference_coverage), 6),
            "reference_coverage_p05": round(_percentile(reference_coverage, 0.05) or 0.0, 6),
            "predicted_content_ratio_median": round(statistics.median(predicted_content), 6),
            "max_edge_error_px_median": round(statistics.median(edge_errors), 3),
            "max_edge_error_px_p95": round(_percentile(edge_errors, 0.95) or 0.0, 3),
            "center_error_px_median": round(statistics.median(center_errors), 3),
            "center_error_px_p95": round(_percentile(center_errors, 0.95) or 0.0, 3),
        }
    return result


def evaluate(manifest: Path, locator_path: Path, library_path: Path | None = None) -> dict[str, Any]:
    """Run the native locator and return deterministic layout metrics."""
    manifest = manifest.resolve()
    locator_path = locator_path.resolve()
    try:
        manifest_data = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read review manifest {manifest}: {error}") from error
    if not isinstance(manifest_data, dict) or manifest_data.get("schema_version") != 1:
        raise ValueError("Expected review manifest schema_version 1")
    samples = _sample_frame_paths(manifest, manifest_data)
    config, descriptor_bytes, locator_data = _load_locator(locator_path)
    locator_document = json.loads(locator_path.read_text(encoding="utf-8"))
    locator_source_kind = (
        "locator" if locator_document.get("schema") == "mapassist.minimap_locator"
        else "game_profile"
    )
    library = load_library(library_path)
    descriptor = (C.c_int8 * len(descriptor_bytes)).from_buffer_copy(descriptor_bytes)
    handle = library.ma_minimap_locator_create(
        C.byref(config), descriptor, len(descriptor_bytes)
    )
    if not handle:
        raise ValueError("Native locator rejected the configuration")

    records: list[dict[str, Any]] = []
    current_match: str | None = None
    try:
        for (match_id, frame_path, reference_roi, expected_width, expected_height,
             orientation) in samples:
            if current_match != match_id:
                library.ma_minimap_locator_reset(handle)
                current_match = match_id
            frame = _display_image(frame_path, orientation).convert("RGBA")
            if frame.size != (expected_width, expected_height):
                raise ValueError(f"Frame dimensions changed while reading {frame_path}")
            raw = frame.tobytes()
            pixels = (C.c_uint8 * len(raw)).from_buffer_copy(raw)
            roi = Rect()
            content = Rect()
            score = C.c_float()
            started = time.perf_counter_ns()
            state = library.ma_minimap_locator_update(
                handle, pixels, frame.width, frame.height, frame.width * 4,
                C.byref(roi), C.byref(content), C.byref(score),
            )
            duration_ms = (time.perf_counter_ns() - started) / 1_000_000
            if state not in _STATE_NAMES:
                raise RuntimeError(f"Native locator returned unknown state {state}")
            records.append({
                "match": match_id,
                "state": state,
                "score": score.value,
                "roi": [roi.x, roi.y, roi.w, roi.h],
                "content": [content.x, content.y, content.w, content.h],
                "reference_roi": reference_roi,
                "frame_size": [frame.width, frame.height],
                "processing_ms": duration_ms,
            })
    finally:
        library.ma_minimap_locator_destroy(handle)

    matches = {}
    for match_id in dict.fromkeys(record["match"] for record in records):
        matches[match_id] = _summary(
            [record for record in records if record["match"] == match_id]
        )
    return {
        "schema_version": 1,
        "diagnostic": "native_minimap_locator_reviewed_frame_replay",
        "scope": "layout acquisition only; YOLOX is not executed",
        "manifest_sha256": _sha256(manifest),
        "locator_sha256": _sha256(locator_path),
        "locator_source_kind": locator_source_kind,
        "locator_descriptor_sha256": locator_data.get("descriptor_sha256"),
        "overall": _summary(records),
        "matches": matches,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="manually reviewed review-manifest.json")
    parser.add_argument(
        "--locator", type=Path, required=True,
        help=("calibrated mapassist.minimap_locator JSON or a GameProfile "
              "containing layout.minimap_locator"),
    )
    parser.add_argument("--library", type=Path,
                        help="native library (default: build/native/libmapassist)")
    parser.add_argument("--output", type=Path, help="write the JSON report here")
    args = parser.parse_args()
    try:
        result = evaluate(args.manifest, args.locator, args.library)
    except (OSError, ValueError, RuntimeError, KeyError, TypeError,
            UnidentifiedImageError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
