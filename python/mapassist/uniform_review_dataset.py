"""Build a detector-independent development queue from complete recordings.

Unlike ``review_dataset``, this module never reads detector predictions. It
samples the declared gameplay intervals uniformly, so difficult and empty
frames have the same chance of reaching the manual annotation queue.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from .extract_frame import extract
from .orientation import from_manifest, resolve, rotation
from .review_dataset import _contact_sheet, _draw_overlay, _minimap_contact_sheet


SPLITS = ("train", "val", "test")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be four normalized numbers")
    result = [float(item) for item in value]
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def _intervals(value: object, match_id: str) -> list[tuple[int, int]]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"active_intervals_ms for {match_id} must be nonempty")
    result: list[tuple[int, int]] = []
    for item in value:
        if (not isinstance(item, list) or len(item) != 2 or
                any(not isinstance(number, int) or isinstance(number, bool)
                    for number in item)):
            raise ValueError(
                f"active_intervals_ms for {match_id} needs [start_ms, end_ms] pairs"
            )
        start, end = item
        if start < 0 or end <= start or (result and start <= result[-1][1]):
            raise ValueError(
                f"active_intervals_ms for {match_id} must be sorted and non-overlapping"
            )
        result.append((start, end))
    return result


def sample_timestamps(intervals: list[tuple[int, int]], count: int) -> list[int]:
    """Return deterministic midpoint samples over the combined interval length."""
    if count < 1:
        raise ValueError("sample count must be positive")
    duration = sum(end - start for start, end in intervals)
    if duration < count:
        raise ValueError("gameplay intervals are too short for unique millisecond samples")
    result = []
    for index in range(count):
        offset = (2 * index + 1) * duration // (2 * count)
        remaining = offset
        for start, end in intervals:
            length = end - start
            if remaining < length:
                result.append(start + remaining)
                break
            remaining -= length
        else:
            result.append(intervals[-1][1] - 1)
    if len(set(result)) != count:
        raise ValueError("could not construct unique sample timestamps")
    return result


def exclude_timestamp_windows(intervals: list[tuple[int, int]], timestamps: list[int],
                              gap_ms: int) -> list[tuple[int, int]]:
    """Remove half-open windows whose timestamps are within ``gap_ms`` of prior samples."""
    if not isinstance(gap_ms, int) or isinstance(gap_ms, bool) or gap_ms < 0:
        raise ValueError("exclude gap must be a nonnegative integer")
    remaining = list(intervals)
    for timestamp in sorted(set(timestamps)):
        if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp < 0:
            raise ValueError("excluded timestamps must be nonnegative integers")
        cut_start = max(0, timestamp - gap_ms)
        cut_end = timestamp + gap_ms + 1
        updated = []
        for start, end in remaining:
            if cut_end <= start or cut_start >= end:
                updated.append((start, end))
                continue
            if start < cut_start:
                updated.append((start, cut_start))
            if cut_end < end:
                updated.append((cut_end, end))
        remaining = updated
    return [interval for interval in remaining if interval[1] > interval[0]]


def _exclusions(path: Path | None) -> tuple[dict[str, list[int]], str | None]:
    if path is None:
        return {}, None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read exclusion manifest {path}: {error}") from error
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        raise ValueError("Exclusion manifest must use schema_version 1")
    result: dict[str, list[int]] = {}
    for match in data.get("matches", []):
        if not isinstance(match, dict) or not isinstance(match.get("id"), str):
            raise ValueError("Exclusion manifest has an invalid match")
        times = []
        for sample in match.get("samples", []):
            if not isinstance(sample, dict):
                raise ValueError("Exclusion manifest samples must be objects")
            timestamp = sample.get("at_ms")
            if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp < 0:
                raise ValueError("Exclusion manifest has an invalid at_ms")
            times.append(timestamp)
        if match["id"] in result:
            raise ValueError(f"Duplicate exclusion match id: {match['id']}")
        result[match["id"]] = times
    return result, _sha256(path)


def build(manifest: Path, output: Path, samples_per_match: int = 100,
          exclude_manifest: Path | None = None, exclude_gap_ms: int = 1000) -> dict:
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected source manifest schema_version 1")
    top_roi = _roi(data.get("roi"), "roi")
    top_widget_value = data.get("widget_roi")
    top_widget_roi = (_roi(top_widget_value, "widget_roi")
                      if top_widget_value is not None else None)
    top_orientation = from_manifest(data)
    excluded_by_match, exclusion_sha256 = _exclusions(exclude_manifest)
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Source manifest needs matches")

    output.mkdir(parents=True, exist_ok=True)
    exported = {
        "schema_version": 1,
        "kind": data.get("kind", "minimap_enemy"),
        "review_mode": "manual",
        "roi": top_roi,
        "sampling": {
            "strategy": "uniform_gameplay_interval_midpoints",
            "predictions_used_for_selection": False,
            "samples_per_match": samples_per_match,
            "exclude_gap_ms": exclude_gap_ms if exclude_manifest is not None else None,
            "exclude_manifest": str(exclude_manifest.resolve())
            if exclude_manifest is not None else None,
            "exclude_manifest_sha256": exclusion_sha256,
        },
        "warning": (
            "Every frame needs manual boxes or an explicit negative/excluded decision; "
            "there are no detector suggestions to accept."
        ),
        "matches": [],
    }
    if top_widget_roi is not None:
        exported["widget_roi"] = top_widget_roi
    if top_orientation is not None:
        exported["orientation"] = top_orientation
    summary = {split: {"matches": 0, "samples": 0} for split in SPLITS}
    seen_ids: set[str] = set()
    seen_videos: dict[Path, str] = {}

    for match in matches:
        match_id = match.get("id")
        split = match.get("split")
        if (not isinstance(match_id, str) or not match_id or
                match_id in seen_ids or any(character in match_id for character in "/\\")):
            raise ValueError(f"Invalid or duplicate match id: {match_id}")
        if split not in SPLITS:
            raise ValueError(f"Invalid split for {match_id}: {split}")
        seen_ids.add(match_id)
        video = (manifest.parent / match["video"]).resolve()
        if not video.is_file():
            raise FileNotFoundError(f"Missing video: {video}")
        previous_split = seen_videos.setdefault(video, split)
        if previous_split != split:
            raise ValueError(f"One recording cannot cross splits: {video}")
        intervals = _intervals(match.get("active_intervals_ms"), match_id)
        available_intervals = exclude_timestamp_windows(
            intervals, excluded_by_match.get(match_id, []), exclude_gap_ms
        )
        if not available_intervals:
            raise ValueError(f"No sampling time remains after exclusions for {match_id}")
        match_roi = _roi(match.get("roi", top_roi), f"roi for {match_id}")
        widget_value = match.get("widget_roi", top_widget_roi)
        match_widget_roi = (_roi(widget_value, f"widget_roi for {match_id}")
                            if widget_value is not None else None)
        match_orientation = resolve(
            video, from_manifest(match, f"{match_id}") or top_orientation)

        samples = []
        overlays = []
        for timestamp in sample_timestamps(available_intervals, samples_per_match):
            stem = f"{match_id}_{timestamp:09d}"
            frame = output / split / match_id / f"{stem}.png"
            overlay = output / split / match_id / f"{stem}-overlay.jpg"
            extract(video, timestamp, frame,
                    display_rotation=rotation(match_orientation))
            sample = {
                "at_ms": timestamp,
                "selection": "systematic_development",
                "suggested_boxes": [],
                "directions": [],
                "review_status": "pending",
                "reviewed_boxes": None,
                "frame": str(frame.relative_to(output)),
                "overlay": str(overlay.relative_to(output)),
            }
            _draw_overlay(frame, overlay, sample)
            samples.append(sample)
            overlays.append(overlay)

        _contact_sheet(overlays, output / "contact-sheets" / f"{match_id}.jpg")
        _minimap_contact_sheet(
            overlays, samples, match_roi,
            output / "contact-sheets" / f"{match_id}-minimap.jpg",
        )
        exported_match = {
            "id": match_id,
            "split": split,
            "video": str(video),
            "video_sha256": _sha256(video),
            "orientation": match_orientation,
            "active_intervals_ms": [list(item) for item in intervals],
            "samples": samples,
        }
        if "roi" in match:
            exported_match["roi"] = match_roi
        if "widget_roi" in match and match_widget_roi is not None:
            exported_match["widget_roi"] = match_widget_roi
        exported["matches"].append(exported_match)
        summary[split]["matches"] += 1
        summary[split]["samples"] += len(samples)

    (output / "review-manifest.json").write_text(
        json.dumps(exported, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples-per-match", type=int, default=100)
    parser.add_argument("--exclude-manifest", type=Path,
                        help="review manifest whose timestamps must not be sampled again")
    parser.add_argument("--exclude-gap-ms", type=int, default=1000,
                        help="also exclude this many milliseconds around prior samples")
    args = parser.parse_args()
    try:
        result = build(
            args.manifest, args.output, args.samples_per_match,
            args.exclude_manifest, args.exclude_gap_ms,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
