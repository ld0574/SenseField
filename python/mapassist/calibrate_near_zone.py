"""Derive the near-zone radius from human-marked screen-edge moments.

A reviewer watches a development recording and writes down the moments when an
enemy hero first appears at the edge of the main screen. The tool looks up the
nearest frame of a ``training/replay_yolox_ncnn.py`` JSONL, takes the most
confident ``minimap_player`` detection and the nearest ``minimap_enemy``
detection, and measures their distance in map short-edge units using the
profile's ``rois.minimap_direction``. Frames without a player, without an
enemy, or with a second enemy almost as close are skipped and listed. The
median becomes ``enter_radius`` and ``exit_radius`` is 1.25 times that.

Sealed recordings must not be used for calibration.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import statistics
from pathlib import Path
from typing import Any

from .native import read_profile, relation_config_from_profile

EXIT_RATIO = 1.25


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _centre(bbox: list[float]) -> tuple[float, float]:
    return bbox[0] + bbox[2] * 0.5, bbox[1] + bbox[3] * 0.5


def map_short_px(profile_json: dict[str, Any], width: int, height: int) -> float:
    rois = profile_json.get("rois", {})
    if "minimap_direction" not in rois:
        raise ValueError("profile needs rois.minimap_direction (the map body)")
    body = rois["minimap_direction"]
    return min(body[2] * width, body[3] * height)


def frame_distance(detections: list[dict[str, Any]], width: int, height: int,
                   short_px: float, ambiguity_ratio: float) -> tuple[float | None, str]:
    """Distance of the nearest enemy, or None with the reason it was skipped."""
    players = [item for item in detections if item.get("class_name") == "minimap_player"]
    enemies = [item for item in detections if item.get("class_name") == "minimap_enemy"]
    if not players:
        return None, "no_player"
    if not enemies:
        return None, "no_enemy"
    player = _centre(max(players, key=lambda item: item["confidence"])["bbox_norm"])
    distances = sorted(
        math.hypot((_centre(item["bbox_norm"])[0] - player[0]) * width / short_px,
                   (_centre(item["bbox_norm"])[1] - player[1]) * height / short_px)
        for item in enemies)
    if len(distances) > 1 and distances[1] <= distances[0] * ambiguity_ratio:
        return None, "ambiguous_second_enemy"
    return distances[0], "ok"


def calibrate(profile: Path, replay: Path, moments: Path, frame_size: tuple[int, int],
              max_offset_ms: int = 100, ambiguity_ratio: float = 1.25) -> dict[str, Any]:
    _, profile_json = read_profile(profile)
    width, height = frame_size
    short_px = map_short_px(profile_json, width, height)
    frames = []
    with replay.open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            frames.append((int(record["timestamp_ms"]), record.get("detections", [])))
    if not frames:
        raise ValueError("replay JSONL is empty")
    samples, skipped = [], []
    with moments.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            at_ms = int(row["timestamp_ms"])
            timestamp, detections = min(frames, key=lambda item: abs(item[0] - at_ms))
            if abs(timestamp - at_ms) > max_offset_ms:
                skipped.append({"timestamp_ms": at_ms, "reason": "no_frame_in_range"})
                continue
            distance, reason = frame_distance(detections, width, height, short_px,
                                              ambiguity_ratio)
            if distance is None:
                skipped.append({"timestamp_ms": at_ms, "reason": reason})
            else:
                samples.append({"timestamp_ms": at_ms, "frame_ms": timestamp,
                                "distance": round(distance, 4)})
    distances = [item["distance"] for item in samples]
    result: dict[str, Any] = {
        "schema_version": 1,
        "method": "median nearest-enemy distance at human-marked screen-edge moments, "
                  "map short-edge units; exit = 1.25 x enter",
        "frame_size": [width, height],
        "map_short_px": round(short_px, 3),
        "samples": samples,
        "skipped": skipped,
        "inputs": {"profile_sha256": _sha256(profile), "replay_sha256": _sha256(replay),
                   "moments_sha256": _sha256(moments)},
    }
    if distances:
        enter = round(statistics.median(distances), 4)
        result["median"] = enter
        result["quartiles"] = ([round(value, 4) for value in
                                statistics.quantiles(distances, n=4)]
                               if len(distances) >= 2 else None)
        result["suggested"] = {"enter_radius": enter,
                               "exit_radius": round(enter * EXIT_RATIO, 4)}
    return result


def apply(profile_path: Path, report: dict[str, Any]) -> None:
    """Write the suggested radii into a profile and mark it calibrated."""
    data = json.loads(profile_path.read_text(encoding="utf-8"))
    section = data.get("minimap_relation")
    if not isinstance(section, dict):
        raise ValueError(f"{profile_path} has no minimap_relation section")
    section["enter_radius"] = report["suggested"]["enter_radius"]
    section["exit_radius"] = report["suggested"]["exit_radius"]
    section["calibration"] = {
        "status": "calibrated",
        "method": report["method"],
        "tool": "python -m mapassist.calibrate_near_zone",
        "samples": len(report["samples"]),
        "moments_sha256": report["inputs"]["moments_sha256"],
        "replay_sha256": report["inputs"]["replay_sha256"],
    }
    relation_config_from_profile(data)
    temporary = profile_path.with_name(profile_path.name + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                         encoding="utf-8")
    os.replace(temporary, profile_path)


def _frame_size(replay: Path, explicit: str | None) -> tuple[int, int]:
    if explicit:
        width, height = explicit.lower().split("x")
        return int(width), int(height)
    meta = Path(f"{replay}.meta.json")
    if not meta.is_file():
        raise ValueError("pass --frame-size WxH or keep the replay .meta.json next to it")
    stats = json.loads(meta.read_text(encoding="utf-8"))["stats"]
    return int(stats["width"]), int(stats["height"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--moments", type=Path, required=True,
                        help="CSV with a timestamp_ms column (replay media time)")
    parser.add_argument("--frame-size", help="WxH; defaults to the replay .meta.json")
    parser.add_argument("--max-offset-ms", type=int, default=100)
    parser.add_argument("--ambiguity-ratio", type=float, default=1.25)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apply", type=Path, nargs="+", metavar="PROFILE",
                        help="write the suggested radii into these profiles")
    parser.add_argument("--min-samples", type=int, default=15)
    args = parser.parse_args()
    try:
        report = calibrate(args.profile, args.replay, args.moments,
                           _frame_size(args.replay, args.frame_size),
                           args.max_offset_ms, args.ambiguity_ratio)
        if args.apply:
            if len(report["samples"]) < args.min_samples:
                raise ValueError(f"only {len(report['samples'])} usable samples; "
                                 f"--apply needs at least {args.min_samples}")
            for path in args.apply:
                apply(path, report)
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
