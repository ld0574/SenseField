"""Validate and resolve recording display orientation metadata."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any


VALID_ROTATIONS = {0, 90, 180, 270}


def _size(value: object, label: str) -> list[int]:
    if (not isinstance(value, list) or len(value) != 2 or
            any(not isinstance(item, int) or isinstance(item, bool) or item < 1
                for item in value)):
        raise ValueError(f"{label} must be [width, height] positive integers")
    return [int(value[0]), int(value[1])]


def normalize(value: object, label: str = "orientation") -> dict[str, Any]:
    """Return canonical orientation metadata without dropping audit fields."""
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    result = dict(value)
    rotation = result.get("display_rotation_degrees", result.get("display_rotation"))
    if (not isinstance(rotation, int) or isinstance(rotation, bool) or
            rotation % 90 != 0 or rotation % 360 not in VALID_ROTATIONS):
        raise ValueError(f"{label}.display_rotation_degrees must be 0, 90, 180, or 270")
    result["display_rotation_degrees"] = rotation % 360
    result.pop("display_rotation", None)
    if "source_coded_size" in result:
        result["source_coded_size"] = _size(result["source_coded_size"],
                                             f"{label}.source_coded_size")
    if "display_size" in result:
        result["display_size"] = _size(result["display_size"],
                                        f"{label}.display_size")
    return result


def from_manifest(data: dict, label: str = "manifest") -> dict[str, Any] | None:
    """Read either the structured orientation field or its compact alias."""
    value = data.get("orientation")
    if value is None and "display_rotation" in data:
        value = {"display_rotation_degrees": data["display_rotation"]}
    if value is None:
        return None
    return normalize(value, f"{label} orientation")


def rotation(value: object, label: str = "orientation") -> int:
    return int(normalize(value, label)["display_rotation_degrees"])


def probe(video: Path) -> dict[str, Any]:
    """Read the first video stream's coded size and Display Matrix rotation."""
    try:
        completed = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=width,height:stream_tags=rotate:stream_side_data=rotation",
             "-of", "json", str(video)],
            check=True, capture_output=True, text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError(f"Could not inspect video orientation: {video}") from error
    try:
        stream = json.loads(completed.stdout)["streams"][0]
        width = int(stream["width"])
        height = int(stream["height"])
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise ValueError(f"Video has no readable video stream: {video}") from error
    side_data = stream.get("side_data_list", [])
    angle: object = None
    for item in side_data if isinstance(side_data, list) else []:
        if isinstance(item, dict) and item.get("rotation") is not None:
            angle = item["rotation"]
            break
    if angle is None:
        angle = stream.get("tags", {}).get("rotate")
    try:
        degrees = int(angle or 0) % 360
    except (TypeError, ValueError) as error:
        raise ValueError(f"Video has invalid display rotation: {video}") from error
    if degrees not in VALID_ROTATIONS:
        raise ValueError(f"Video display rotation must be a multiple of 90: {degrees}")
    display = [height, width] if degrees in {90, 270} else [width, height]
    return {"source_coded_size": [width, height],
            "display_size": display,
            "display_rotation_degrees": degrees,
            "queue_frames_must_be_display_oriented": True}


def resolve(video: Path, metadata: object | None = None) -> dict[str, Any]:
    """Use declared metadata, otherwise inspect the recording once."""
    if metadata is not None:
        return normalize(metadata, "orientation")
    return probe(video)
