"""Build a manual review queue for the full-screen minimap boundary.

The source manifest supplies recordings, gameplay intervals, splits, and the
existing coarse minimap ROI.  That ROI is only a suggestion.  Reviewers must
adjust one box to the visible outer minimap boundary on every sampled frame.
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
from .uniform_review_dataset import SPLITS, _intervals, _roi, sample_timestamps


FULL_FRAME = [0.0, 0.0, 1.0, 1.0]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def build(manifest: Path, output: Path, samples_per_match: int = 16) -> dict:
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected source manifest schema_version 1")
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Source manifest needs matches")
    default_suggestion = _roi(data.get("roi"), "roi")
    top_orientation = from_manifest(data)

    output.mkdir(parents=True, exist_ok=True)
    exported = {
        "schema_version": 1,
        "kind": "minimap_region",
        "review_mode": "manual",
        "roi": FULL_FRAME,
        "sampling": {
            "strategy": "uniform_gameplay_interval_midpoints",
            "predictions_used_for_selection": False,
            "samples_per_match": samples_per_match,
            "suggestion": "source_manifest_coarse_roi",
        },
        "annotation_instruction": (
            "Keep exactly one box and fit it to the visible outer minimap boundary. "
            "The green box is only a coarse suggestion. Exclude non-gameplay screens."
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
        if (not isinstance(match_id, str) or not match_id or match_id in seen_ids or
                any(character in match_id for character in "/\\")):
            raise ValueError(f"Invalid or duplicate match id: {match_id}")
        if split not in SPLITS:
            raise ValueError(f"Invalid split for {match_id}: {split}")
        seen_ids.add(match_id)
        video = (manifest.parent / match["video"]).resolve()
        if not video.is_file():
            raise FileNotFoundError(f"Missing video: {video}")
        prior_split = seen_videos.setdefault(video, split)
        if prior_split != split:
            raise ValueError(f"One recording cannot cross splits: {video}")
        intervals = _intervals(match.get("active_intervals_ms"), match_id)
        suggestion = _roi(match.get("roi", default_suggestion), f"roi for {match_id}")
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
                "selection": "minimap_layout_systematic",
                "suggested_boxes": [suggestion],
                "directions": ["minimap"],
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
            overlays, samples, suggestion,
            output / "contact-sheets" / f"{match_id}-coarse-minimap.jpg",
        )
        exported["matches"].append({
            "id": match_id,
            "split": split,
            "video": str(video),
            "video_sha256": _sha256(video),
            "orientation": match_orientation,
            "active_intervals_ms": [list(item) for item in intervals],
            "coarse_minimap_roi": suggestion,
            "samples": samples,
        })
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
    parser.add_argument("--samples-per-match", type=int, default=16)
    args = parser.parse_args()
    try:
        result = build(args.manifest, args.output, args.samples_per_match)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
