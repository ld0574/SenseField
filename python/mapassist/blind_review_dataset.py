"""Build a prediction-independent frame queue for a blinded holdout review."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from .extract_frame import extract
from .orientation import resolve, rotation
from .review_dataset import _contact_sheet, _draw_overlay, _minimap_contact_sheet


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _normalized_roi(value: list[float]) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError("roi must be four normalized numbers")
    roi = [float(item) for item in value]
    if not all(math.isfinite(item) for item in roi):
        raise ValueError("roi must contain finite normalized numbers")
    x, y, width, height = roi
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError("roi is outside the normalized frame")
    return roi


def _sample_indices(frame_count: int, sample_count: int) -> list[int]:
    """Choose deterministic midpoint frames without consulting predictions."""
    if frame_count < 1:
        raise ValueError("frame_count must be positive")
    if sample_count < 1 or sample_count > frame_count:
        raise ValueError("sample_count must be between 1 and frame_count")
    indices = [int((index + 0.5) * frame_count / sample_count)
               for index in range(sample_count)]
    if len(set(indices)) != sample_count or indices[-1] >= frame_count:
        raise ValueError("could not construct unique holdout sample frames")
    return indices


def _prediction_commitment(metadata: Path, video: Path) -> tuple[int, int, dict]:
    data = json.loads(metadata.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("prediction metadata must use schema_version 1")
    video_record = data.get("video")
    prediction_record = data.get("predictions")
    stats = data.get("stats")
    if not all(isinstance(item, dict) for item in
               (video_record, prediction_record, stats)):
        raise ValueError("prediction metadata is incomplete")
    video_hash = _sha256(video)
    if video_record.get("sha256") != video_hash:
        raise ValueError("prediction metadata belongs to a different video")
    fps = stats.get("fps")
    frame_count = stats.get("frames")
    if (not isinstance(fps, int) or isinstance(fps, bool) or fps < 1 or
            not isinstance(frame_count, int) or isinstance(frame_count, bool) or
            frame_count < 1):
        raise ValueError("prediction metadata needs positive integer fps and frames")
    commitment = {
        "metadata_sha256": _sha256(metadata),
        "predictions_sha256": prediction_record.get("sha256"),
        "video_sha256": video_hash,
        "profile_sha256": data.get("profile", {}).get("sha256"),
        "native_library_sha256": data.get("native_library", {}).get("sha256"),
    }
    if any(not isinstance(value, str) or len(value) != 64
           for value in commitment.values()):
        raise ValueError("prediction metadata is missing a required SHA-256")
    return fps, frame_count, commitment


def build(video: Path, prediction_metadata: Path, output: Path, match_id: str,
          roi: list[float], sample_count: int = 60,
          widget_roi: list[float] | None = None,
          label_roi: list[float] | None = None) -> dict:
    video = video.resolve()
    prediction_metadata = prediction_metadata.resolve()
    if not video.is_file():
        raise FileNotFoundError(f"Missing video: {video}")
    if not prediction_metadata.is_file():
        raise FileNotFoundError(f"Missing prediction metadata: {prediction_metadata}")
    if not match_id or any(character in match_id for character in "/\\"):
        raise ValueError("match_id must be a nonempty path-safe name")
    roi = _normalized_roi(roi)
    if widget_roi is not None:
        widget_roi = _normalized_roi(widget_roi)
    if label_roi is not None:
        label_roi = _normalized_roi(label_roi)
    orientation = resolve(video)
    fps, frame_count, commitment = _prediction_commitment(
        prediction_metadata, video)
    indices = _sample_indices(frame_count, sample_count)

    output.mkdir(parents=True, exist_ok=True)
    samples = []
    overlays = []
    for frame_index in indices:
        timestamp = round(frame_index * 1000 / fps)
        stem = f"{match_id}_{timestamp:09d}"
        frame = output / "test" / match_id / f"{stem}.png"
        overlay = output / "test" / match_id / f"{stem}-overlay.jpg"
        extract(video, timestamp, frame,
                display_rotation=rotation(orientation))
        sample = {
            "at_ms": timestamp,
            "frame_index": frame_index,
            "selection": "systematic_blind",
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
        overlays, samples, roi,
        output / "contact-sheets" / f"{match_id}-minimap.jpg",
    )
    manifest = {
        "schema_version": 1,
        "kind": "minimap_enemy",
        "review_mode": "blind",
        "roi": roi,
        "orientation": orientation,
        "warning": (
            "Frames were sampled uniformly without detector suggestions. "
            "Use corrected for targets, negative for target-free gameplay, "
            "and excluded only for non-gameplay or transition frames."
        ),
        "sampling": {
            "strategy": "uniform_midpoint_frames",
            "predictions_used_for_selection": False,
            "fps": fps,
            "source_frame_count": frame_count,
            "sample_count": sample_count,
        },
        "prediction_commitment": commitment,
        "matches": [{
            "id": match_id,
            "split": "test",
            "video": str(video),
            "video_sha256": commitment["video_sha256"],
            "samples": samples,
        }],
    }
    if widget_roi is not None:
        manifest["widget_roi"] = widget_roi
    if label_roi is not None:
        manifest["label_roi"] = label_roi
    (output / "review-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    summary = {
        "match_id": match_id,
        "split": "test",
        "review_mode": "blind",
        "sample_count": sample_count,
        "first_at_ms": samples[0]["at_ms"],
        "last_at_ms": samples[-1]["at_ms"],
        "prediction_commitment": commitment,
    }
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--prediction-metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--match-id", default="holdout")
    parser.add_argument("--sample-count", type=int, default=60)
    parser.add_argument("--roi", type=float, nargs=4, required=True,
                        metavar=("X", "Y", "WIDTH", "HEIGHT"))
    parser.add_argument("--widget-roi", type=float, nargs=4,
                        metavar=("X", "Y", "WIDTH", "HEIGHT"),
                        help="Optional normalized minimap widget/direction reference")
    parser.add_argument("--label-roi", type=float, nargs=4,
                        metavar=("X", "Y", "WIDTH", "HEIGHT"),
                        help="Optional normalized annotation-center boundary")
    args = parser.parse_args()
    try:
        result = build(args.video, args.prediction_metadata, args.output,
                       args.match_id, args.roi, args.sample_count,
                       args.widget_roi, args.label_roi)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
