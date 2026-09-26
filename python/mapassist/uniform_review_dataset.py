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


def build(manifest: Path, output: Path, samples_per_match: int = 100) -> dict:
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected source manifest schema_version 1")
    top_roi = _roi(data.get("roi"), "roi")
    top_orientation = from_manifest(data)
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
        },
        "warning": (
            "Every frame needs manual boxes or an explicit negative/excluded decision; "
            "there are no detector suggestions to accept."
        ),
        "matches": [],
    }
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
        match_roi = _roi(match.get("roi", top_roi), f"roi for {match_id}")
        match_orientation = resolve(
            video, from_manifest(match, f"{match_id}") or top_orientation)

        samples = []
        overlays = []
        for timestamp in sample_timestamps(intervals, samples_per_match):
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
    args = parser.parse_args()
    try:
        result = build(args.manifest, args.output, args.samples_per_match)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
