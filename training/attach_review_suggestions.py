"""Attach model suggestions to a prediction-independent manual review queue.

The queue must already exist.  This tool does not choose frames and never marks
them reviewed; it only seeds editable boxes so a human can confirm, fix, or
reject every image in the annotation website.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _box(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be [x, y, width, height]")
    box = [float(item) for item in value]
    x, y, width, height = box
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return [round(item, 8) for item in box]


def _roi(value: object, label: str) -> list[float]:
    return _box(value, label)


def attach(manifest: Path, prelabels: Path) -> dict:
    data = json.loads(manifest.read_text(encoding="utf-8"))
    predictions = json.loads(prelabels.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1 or not isinstance(data.get("matches"), list):
        raise ValueError("Review manifest must use schema_version 1")
    if data.get("review_mode") != "manual":
        raise ValueError("Suggestions can only be attached to a manual development queue")
    sampling = data.get("sampling")
    if not isinstance(sampling, dict) or sampling.get("predictions_used_for_selection") is not False:
        raise ValueError("Queue must declare predictions_used_for_selection=false")
    if predictions.get("schema_version") != 1 or not isinstance(
            predictions.get("matches"), list):
        raise ValueError("Prelabels must use schema_version 1")

    default_roi = _roi(data.get("roi"), "review roi")
    expected: dict[tuple[str, int], tuple[dict, list[float]]] = {}
    for match in data["matches"]:
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id:
            raise ValueError("Review manifest contains an invalid match id")
        roi = _roi(match.get("roi", default_roi), f"review roi for {match_id}")
        for sample in match.get("samples", []):
            timestamp = sample.get("at_ms")
            key = (match_id, timestamp)
            if (not isinstance(timestamp, int) or isinstance(timestamp, bool) or
                    key in expected):
                raise ValueError(f"Invalid or duplicate review sample: {key}")
            expected[key] = (sample, roi)

    supplied: set[tuple[str, int]] = set()
    suggestion_count = 0
    suggested_images = 0
    for match in predictions["matches"]:
        match_id = match.get("id")
        for sample in match.get("samples", []):
            timestamp = sample.get("at_ms")
            key = (match_id, timestamp)
            if key not in expected:
                raise ValueError(f"Unexpected prelabel sample: {key}")
            if key in supplied:
                raise ValueError(f"Duplicate prelabel sample: {key}")
            supplied.add(key)
            target, roi = expected[key]
            if sample.get("frame") != target.get("frame"):
                raise ValueError(f"Frame path mismatch for {match_id}@{timestamp}")
            boxes = []
            confidences = []
            for index, suggestion in enumerate(sample.get("suggestions", [])):
                if not isinstance(suggestion, dict):
                    raise ValueError(f"Invalid suggestion for {match_id}@{timestamp}")
                box = _box(suggestion.get("bbox"),
                           f"suggestion {index} for {match_id}@{timestamp}")
                x, y, width, height = box
                rx, ry, rwidth, rheight = roi
                if (x < rx - 0.000001 or y < ry - 0.000001 or
                        x + width > rx + rwidth + 0.000001 or
                        y + height > ry + rheight + 0.000001):
                    raise ValueError(
                        f"Suggestion {index} for {match_id}@{timestamp} is outside the roi"
                    )
                confidence = suggestion.get("confidence")
                if (not isinstance(confidence, (int, float)) or
                        isinstance(confidence, bool) or not 0 <= confidence <= 1):
                    raise ValueError(
                        f"Invalid confidence for {match_id}@{timestamp} suggestion {index}"
                    )
                boxes.append(box)
                confidences.append(round(float(confidence), 6))
            target["suggested_boxes"] = boxes
            target["suggestion_confidences"] = confidences
            suggestion_count += len(boxes)
            suggested_images += bool(boxes)

    missing = sorted(set(expected) - supplied)
    if missing:
        preview = ", ".join(f"{match}@{timestamp}" for match, timestamp in missing[:3])
        raise ValueError(f"Prelabels are missing {len(missing)} review samples: {preview}")

    model_value = predictions.get("model")
    model = Path(model_value) if isinstance(model_value, str) else None
    model_sha256 = _sha256(model) if model is not None and model.is_file() else None
    data["suggestion_provenance"] = {
        "prelabels": str(prelabels.resolve()),
        "prelabels_sha256": _sha256(prelabels),
        "model": str(model.resolve()) if model is not None and model.is_file() else model_value,
        "model_sha256": model_sha256,
        "input_size": predictions.get("input_size"),
        "confidence": predictions.get("confidence"),
        "nms_threshold": predictions.get("nms_threshold"),
        "predictions_used_for_selection": False,
        "manual_confirmation_required": True,
    }
    data["warning"] = (
        "Model boxes are editable suggestions, not ground truth. A human must inspect every "
        "frame and save corrected boxes, negative, or excluded; direct acceptance is disabled."
    )
    temporary = manifest.with_suffix(manifest.suffix + ".tmp")
    temporary.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(temporary, manifest)
    return {
        "images": len(expected),
        "images_with_suggestions": suggested_images,
        "suggestions": suggestion_count,
        "manifest": str(manifest),
        "prelabels_sha256": data["suggestion_provenance"]["prelabels_sha256"],
        "model_sha256": model_sha256,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("prelabels", type=Path)
    args = parser.parse_args()
    print(json.dumps(attach(args.manifest, args.prelabels), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
