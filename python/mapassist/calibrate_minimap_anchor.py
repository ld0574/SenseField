"""Calibrate a resolution-independent minimap appearance descriptor.

The input is a manually reviewed ``review-manifest.json``.  Reviewed frames
are cropped with their match-specific (or top-level) minimap ROI, resized to a
small grayscale grid, and aggregated with a per-pixel median.  The resulting
descriptor is deliberately prediction-independent: suggested detector boxes
in a review manifest are never read.

The JSON written by :func:`calibrate` is shaped so it can be copied into a
GameProfile's ``layout.minimap_locator`` object.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import sys
from pathlib import Path
from statistics import median
from typing import Iterable

from PIL import Image, UnidentifiedImageError

from .orientation import from_manifest


_IGNORED_STATUSES = {"pending", "skip", "excluded"}
_VALID_STATUSES = {"accepted", "corrected", "negative"}
_QUANTIZATION_SCALE = 32.0


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                or not math.isfinite(float(item)) for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def _content_rect(image: Image.Image, threshold: int = 12) -> tuple[int, int, int, int]:
    """Mirror the native locator's conservative black-bar normalization."""
    rgb = image.convert("RGB")
    width, height = rgb.size
    pixels = rgb.load()

    def mostly_black_column(x: int) -> bool:
        black = 0
        for index in range(24):
            y = index * (height - 1) // 23
            if max(pixels[x, y]) <= threshold:
                black += 1
        return black >= 23

    def mostly_black_row(y: int) -> bool:
        black = 0
        for index in range(24):
            x = index * (width - 1) // 23
            if max(pixels[x, y]) <= threshold:
                black += 1
        return black >= 23

    max_x = max(0, math.floor(width * 0.20))
    max_y = max(0, math.floor(height * 0.20))
    left = 0
    while left < max_x and mostly_black_column(left):
        left += 1
    right = width
    while right > width - max_x and mostly_black_column(right - 1):
        right -= 1
    top = 0
    while top < max_y and mostly_black_row(top):
        top += 1
    bottom = height
    while bottom > height - max_y and mostly_black_row(bottom - 1):
        bottom -= 1
    if right - left < width // 2 or bottom - top < height // 2:
        return 0, 0, width, height
    return left, top, right - left, bottom - top


def _short_rect(roi: list[float], width: int, height: int,
                content: tuple[int, int, int, int]) -> list[float]:
    content_x, content_y, content_width, content_height = content
    short_edge = min(content_width, content_height)
    x = roi[0] * width
    y = roi[1] * height
    roi_width = roi[2] * width
    roi_height = roi[3] * height
    tolerance = 1e-4
    if (x < content_x - tolerance or y < content_y - tolerance or
            x + roi_width > content_x + content_width + tolerance or
            y + roi_height > content_y + content_height + tolerance):
        raise ValueError("Minimap ROI is outside the normalized active content")
    return [(x - content_x) / short_edge, (y - content_y) / short_edge,
            roi_width / short_edge, roi_height / short_edge]


def _crop_grid(image: Image.Image, roi: list[float], grid_width: int,
               grid_height: int) -> list[int]:
    width, height = image.size
    left = math.floor(roi[0] * width)
    top = math.floor(roi[1] * height)
    right = math.ceil((roi[0] + roi[2]) * width)
    bottom = math.ceil((roi[1] + roi[3]) * height)
    if left < 0 or top < 0 or right > width or bottom > height or right <= left or bottom <= top:
        raise ValueError(f"ROI produces an invalid crop for {width}x{height} frame")
    crop = image.convert("L").crop((left, top, right, bottom))
    resized = crop.resize((grid_width, grid_height), Image.Resampling.BILINEAR)
    # Pillow 14 renamed getdata(); support both the current and newer APIs.
    flattened = getattr(resized, "get_flattened_data", None)
    return list(flattened() if flattened is not None else resized.getdata())


def _quantize(values: Iterable[float]) -> bytes:
    # The normalized values are encoded as signed int8 with a fixed scale.
    # Keeping the scale in the output makes decoding unambiguous while still
    # preserving the compact representation used by the Android profile.
    encoded = bytearray()
    for value in values:
        quantized = int(round(value * _QUANTIZATION_SCALE))
        quantized = max(-128, min(127, quantized))
        encoded.append(quantized & 0xFF)
    return bytes(encoded)


def _display_image(path: Path, orientation: dict | None) -> Image.Image:
    """Load queue pixels in the same orientation as an Android screen frame.

    Older review queues can contain coded portrait pixels plus an EXIF rotation,
    while newer queues store physically oriented pixels.  The manifest display
    size makes those cases unambiguous and avoids applying EXIF twice.
    """
    with Image.open(path) as opened:
        image = opened.copy()
    if orientation is None:
        return image
    display_size = orientation.get("display_size")
    coded_size = orientation.get("source_coded_size")
    if not isinstance(display_size, list) or len(display_size) != 2:
        raise ValueError("Review orientation needs display_size")
    expected = (int(display_size[0]), int(display_size[1]))
    if image.size == expected:
        return image
    if (not isinstance(coded_size, list) or len(coded_size) != 2 or
            image.size != (int(coded_size[0]), int(coded_size[1]))):
        raise ValueError(
            f"Review frame {path} is {image.width}x{image.height}; expected "
            f"display {expected[0]}x{expected[1]} or coded "
            f"{coded_size}"
        )
    transpose = {
        90: Image.Transpose.ROTATE_90,
        180: Image.Transpose.ROTATE_180,
        270: Image.Transpose.ROTATE_270,
    }
    rotation = int(orientation["display_rotation_degrees"])
    if rotation in transpose:
        image = image.transpose(transpose[rotation])
    if image.size != expected:
        raise ValueError(
            f"Review frame rotation produced {image.width}x{image.height}; "
            f"expected {expected[0]}x{expected[1]}"
        )
    return image


def _sample_frame_paths(
    manifest_path: Path, data: dict
) -> list[tuple[str, Path, list[float], int, int, dict | None]]:
    """Return reviewed frame paths, display sizes, ROIs, and orientation."""
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Review manifest needs matches")
    default_roi = data.get("roi")
    if default_roi is not None:
        default_roi = _roi(default_roi, "Review manifest roi")

    result: list[tuple[str, Path, list[float], int, int, dict | None]] = []
    seen_ids: set[str] = set()
    match_sizes: dict[str, tuple[int, int]] = {}
    default_orientation = from_manifest(data)
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError("Review manifest matches must be objects")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id or match_id in seen_ids:
            raise ValueError(f"Invalid or duplicate match id: {match_id}")
        seen_ids.add(match_id)
        match_orientation = from_manifest(match, f"match {match_id}") or default_orientation
        match_roi_value = match.get("roi", default_roi)
        if match_roi_value is None:
            # A match without a usable ROI cannot contribute a calibration
            # sample.  Report this only when it actually has a valid sample;
            # an entirely excluded match is harmless.
            match_roi = None
        else:
            match_roi = _roi(match_roi_value, f"{match_id} roi")
        samples = match.get("samples")
        if not isinstance(samples, list):
            raise ValueError(f"{match_id} samples must be a list")
        for sample in samples:
            if not isinstance(sample, dict):
                raise ValueError(f"{match_id} sample must be an object")
            status = sample.get("review_status", "pending")
            if status in _IGNORED_STATUSES:
                continue
            if status not in _VALID_STATUSES:
                raise ValueError(f"Unknown review status for {match_id}: {status}")
            if match_roi is None:
                raise ValueError(f"{match_id} needs a minimap roi for reviewed samples")
            frame_value = sample.get("frame")
            if not isinstance(frame_value, str) or not frame_value:
                raise ValueError(f"{match_id} reviewed sample needs a frame path")
            frame_path = Path(frame_value)
            if not frame_path.is_absolute():
                frame_path = (manifest_path.parent / frame_path).resolve()
            if not frame_path.is_file():
                raise ValueError(f"Missing reviewed frame for {match_id}: {frame_path}")
            try:
                image = _display_image(frame_path, match_orientation)
                width, height = image.size
            except (OSError, UnidentifiedImageError) as error:
                raise ValueError(f"Cannot read reviewed frame {frame_path}: {error}") from error
            if width < 2 or height < 2:
                raise ValueError(f"Invalid frame dimensions for {frame_path}: {width}x{height}")
            prior_size = match_sizes.setdefault(match_id, (width, height))
            if prior_size != (width, height):
                raise ValueError(
                    f"Inconsistent frame dimensions for {match_id}: "
                    f"{prior_size[0]}x{prior_size[1]} and {width}x{height}"
                )
            result.append((match_id, frame_path, match_roi, width, height,
                           match_orientation))
    if not result:
        raise ValueError("Review manifest has no valid reviewed frames")
    return result


def calibrate(manifest: Path, output: Path, grid_width: int = 20,
              grid_height: int = 20) -> dict:
    """Build and write a deterministic ``layout.minimap_locator`` object."""
    if grid_width < 4 or grid_height < 4:
        raise ValueError("grid width and height must be at least 4")
    if grid_width > 64 or grid_height > 64:
        raise ValueError("grid width and height must not exceed 64")
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read review manifest {manifest}: {error}") from error
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        raise ValueError("Expected review manifest schema_version 1")

    samples = _sample_frame_paths(manifest, data)
    grids: list[list[int]] = []
    match_rects: dict[str, list[list[float]]] = {}
    for match_id, frame_path, roi, width, height, orientation in samples:
        image = _display_image(frame_path, orientation)
        grids.append(_crop_grid(image, roi, grid_width, grid_height))
        match_rects.setdefault(match_id, []).append(
            _short_rect(roi, width, height, _content_rect(image))
        )

    median_grid = [float(median(column)) for column in zip(*grids)]
    mean = sum(median_grid) / len(median_grid)
    variance = sum((value - mean) ** 2 for value in median_grid) / len(median_grid)
    stddev = math.sqrt(variance)
    if stddev < 1e-6:
        raise ValueError("Calibrated minimap descriptor has no visual variation")
    normalized = [(value - mean) / stddev for value in median_grid]
    # Give every match equal weight even when recordings contributed different
    # numbers of reviewed frames. A per-match median also suppresses a transient
    # black loading edge in an otherwise stable recording.
    rects = [
        [median([rect[index] for rect in values]) for index in range(4)]
        for values in match_rects.values()
    ]
    base_rect = [median([rect[index] for rect in rects]) for index in range(4)]
    base_rect = [round(value, 8) for value in base_rect]
    descriptor = _quantize(normalized)

    locator = {
        "schema": "mapassist.minimap_locator",
        "schema_version": 1,
        "version": 1,
        "coordinate_space": "short_edge",
        "base_rect_short": base_rect,
        "grid_width": grid_width,
        "grid_height": grid_height,
        "descriptor_b64": base64.b64encode(descriptor).decode("ascii"),
        "descriptor_sha256": hashlib.sha256(descriptor).hexdigest(),
        "search_radius_x_short": 0.1,
        "search_radius_y_short": 0.04,
        "position_step_short": 0.01,
        "min_scale": 0.9,
        "max_scale": 1.1,
        "scale_steps": 5,
        "min_aspect": 0.95,
        "max_aspect": 1.05,
        "aspect_steps": 3,
        "min_score": 0.4,
        "confirm_frames": 2,
        "hold_frames": 6,
        "refresh_frames": 30,
        "normalize_black_bars": True,
        "black_threshold": 12,
        # The appearance matcher tends to align the stable map interior. Keep
        # the coarse profile rectangle in the detector crop so edge icons are
        # not removed by a tighter, higher-scoring anchor candidate.
        "preserve_base_roi": True,
        "training_frame_count": len(samples),
        "training_match_count": len(rects),
        "input_manifest_sha256": _sha256(manifest),
        "normalization": {"mean": round(mean, 8), "stddev": round(stddev, 8)},
        "quantization": {"dtype": "int8", "scale": _QUANTIZATION_SCALE, "zero_point": 0},
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(locator, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                      encoding="utf-8")
    return locator


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="manually reviewed review-manifest.json")
    parser.add_argument("--output", type=Path, required=True,
                        help="output GameProfile layout.minimap_locator JSON")
    parser.add_argument("--grid-width", type=int, default=20)
    parser.add_argument("--grid-height", type=int, default=20)
    args = parser.parse_args()
    try:
        result = calibrate(args.manifest, args.output, args.grid_width, args.grid_height)
    except (OSError, ValueError, KeyError, TypeError, UnidentifiedImageError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps({"output": str(args.output),
                      "training_frame_count": result["training_frame_count"],
                      "training_match_count": result["training_match_count"]},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
