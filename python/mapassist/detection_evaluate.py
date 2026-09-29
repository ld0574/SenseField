"""Evaluate detector boxes against a manually reviewed frame queue.

Development queues may carry suggestions in the review manifest. A blinded
holdout queue instead loads a previously committed replay file at evaluation
time, after manual labels are complete.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Sequence

from .roi_safety import normalized_roi


FINAL_STATUSES = {"accepted", "corrected", "negative", "skip", "excluded"}
DIRECTIONS = ("left", "right", "up", "down")
DIRECTION_AMBIGUITY_MARGIN = 0.02


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _external_predictions(
    path: Path, kind: str, classes: Sequence[str] | None = None,
) -> dict[int, tuple[list[list[float]], list[str | None], list[str]]]:
    class_names = tuple(classes) if classes is not None else (kind,)
    if not class_names or any(not isinstance(name, str) or not name for name in class_names):
        raise ValueError("external prediction classes must be a non-empty sequence")
    records: dict[int, tuple[list[list[float]], list[str | None], list[str]]] = {}
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"Invalid prediction JSONL at {path}:{line_number}") from error
            timestamp = record.get("timestamp_ms")
            if (not isinstance(timestamp, int) or isinstance(timestamp, bool) or
                    timestamp < 0 or timestamp in records):
                raise ValueError(f"Invalid or duplicate timestamp at {path}:{line_number}")
            observations = [item for item in record.get("observations", [])
                            if isinstance(item, dict) and item.get("type") == kind]
            # Replay files produced by the category-aware detector carry the
            # richer rows in ``detections``.  Select only the requested class;
            # otherwise a player detection can leak into an enemy evaluation.
            detector_rows = [item for item in record.get("detections", [])
                             if isinstance(item, dict) and item.get("bbox_norm") is not None]
            if detector_rows and kind.startswith("minimap"):
                for item in detector_rows:
                    category = item.get("class_name", item.get("category"))
                    if category is None and len(class_names) > 1:
                        raise ValueError(
                            f"{path}:{line_number} detection category is required "
                            "for a multi-class prediction"
                        )
                observations = [item for item in detector_rows
                                if item.get("class_name", item.get("category", kind)) == kind]
            boxes = _boxes([item.get("bbox_norm") for item in observations],
                           f"{path}:{line_number} observations")
            directions = [item.get("direction") for item in observations]
            if any(item not in (None, *DIRECTIONS) for item in directions):
                raise ValueError(f"Invalid prediction direction at {path}:{line_number}")
            categories = []
            for item in observations:
                category = item.get("class_name", item.get("category"))
                if category is None:
                    if len(class_names) > 1:
                        raise ValueError(
                            f"{path}:{line_number} observation category is required "
                            "for a multi-class prediction"
                        )
                    category = kind
                if not isinstance(category, str) or category not in class_names:
                    raise ValueError(
                        f"{path}:{line_number} unknown prediction category {category!r}"
                    )
                categories.append(category)
            records[timestamp] = (boxes, directions, categories)
    if not records:
        raise ValueError("External prediction file is empty")
    return records


def _verify_prediction_commitment(review: dict, predictions: Path,
                                  metadata: Path) -> dict:
    commitment = review.get("prediction_commitment")
    if not isinstance(commitment, dict):
        raise ValueError("Blind review manifest has no prediction commitment")
    if _sha256(predictions) != commitment.get("predictions_sha256"):
        raise ValueError("External predictions do not match the frozen commitment")
    if _sha256(metadata) != commitment.get("metadata_sha256"):
        raise ValueError("Prediction metadata does not match the frozen commitment")
    provenance = json.loads(metadata.read_text(encoding="utf-8"))
    checks = {
        "video_sha256": provenance.get("video", {}).get("sha256"),
        "profile_sha256": provenance.get("profile", {}).get("sha256"),
        "native_library_sha256": provenance.get("native_library", {}).get("sha256"),
    }
    for key, actual in checks.items():
        if actual != commitment.get(key):
            raise ValueError(f"Prediction {key} does not match the frozen commitment")
    if provenance.get("predictions", {}).get("sha256") != commitment.get(
            "predictions_sha256"):
        raise ValueError("Prediction provenance contains a different output hash")
    return {**commitment, "predictions": str(predictions.resolve()),
            "metadata": str(metadata.resolve())}


def _boxes(value: object, label: str) -> list[list[float]]:
    if not isinstance(value, list):
        raise ValueError(f"{label} must be a list")
    result = []
    for box in value:
        if (not isinstance(box, list) or len(box) != 4 or
                any(not isinstance(item, (int, float)) or isinstance(item, bool)
                    for item in box)):
            raise ValueError(f"Invalid {label} box: {box}")
        x, y, width, height = (float(item) for item in box)
        if (x < 0 or y < 0 or width <= 0 or height <= 0 or
                x + width > 1.000001 or y + height > 1.000001):
            raise ValueError(f"{label} box is outside the normalized frame: {box}")
        result.append([x, y, width, height])
    return result


def _categories(value: object, count: int, classes: list[str], default: str,
                label: str) -> list[str]:
    """Validate a category array, preserving legacy single-class queues."""
    if value is None and len(classes) > 1 and count:
        raise ValueError(
            f"{label} categories are required for a multi-class manifest"
        )
    if value is None:
        return [default] * count
    if (not isinstance(value, list) or len(value) != count or
            any(not isinstance(item, str) or item not in classes for item in value)):
        raise ValueError(f"{label} categories must align with boxes and classes")
    return list(value)


def _iou(first: list[float], second: list[float]) -> float:
    left = max(first[0], second[0])
    top = max(first[1], second[1])
    right = min(first[0] + first[2], second[0] + second[2])
    bottom = min(first[1] + first[3], second[1] + second[3])
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = first[2] * first[3] + second[2] * second[3] - intersection
    return intersection / union if union > 0 else 0.0


def _match_boxes(predictions: list[list[float]], ground_truth: list[list[float]],
                 threshold: float) -> list[tuple[int, int, float]]:
    """Return a maximum one-to-one matching for boxes meeting the IoU threshold."""
    candidates: list[list[tuple[int, float]]] = []
    for prediction in predictions:
        compatible = [(index, _iou(prediction, truth))
                      for index, truth in enumerate(ground_truth)]
        candidates.append(sorted(
            ((index, overlap) for index, overlap in compatible if overlap >= threshold),
            key=lambda item: (-item[1], item[0]),
        ))

    truth_to_prediction: dict[int, int] = {}
    for root in sorted(range(len(predictions)),
                       key=lambda index: (len(candidates[index]), index)):
        queue = [root]
        seen_predictions = {root}
        seen_truth: set[int] = set()
        predecessor: dict[int, int] = {}
        incoming_truth: dict[int, int] = {}
        free_truth = None
        for prediction_index in queue:
            for truth_index, _ in candidates[prediction_index]:
                if truth_index in seen_truth:
                    continue
                seen_truth.add(truth_index)
                predecessor[truth_index] = prediction_index
                owner = truth_to_prediction.get(truth_index)
                if owner is None:
                    free_truth = truth_index
                    break
                if owner not in seen_predictions:
                    seen_predictions.add(owner)
                    incoming_truth[owner] = truth_index
                    queue.append(owner)
            if free_truth is not None:
                break
        while free_truth is not None:
            prediction_index = predecessor[free_truth]
            truth_to_prediction[free_truth] = prediction_index
            free_truth = incoming_truth.get(prediction_index)

    matches = [(prediction_index, truth_index,
                _iou(predictions[prediction_index], ground_truth[truth_index]))
               for truth_index, prediction_index in truth_to_prediction.items()]
    return sorted(matches)


def _match_boxes_by_class(
    predictions: list[list[float]],
    ground_truth: list[list[float]],
    prediction_categories: list[str] | None,
    truth_categories: list[str] | None,
    threshold: float,
) -> list[tuple[int, int, float]]:
    """Match boxes only when their canonical category names agree.

    Older single-class manifests do not carry category arrays.  In that case
    both sides are treated as one class so the historical behavior remains
    compatible.  The returned indices always refer to the original arrays.
    """
    if prediction_categories is None:
        prediction_categories = ["__default__"] * len(predictions)
    if truth_categories is None:
        truth_categories = ["__default__"] * len(ground_truth)
    if len(prediction_categories) != len(predictions):
        raise ValueError("prediction categories must align with prediction boxes")
    if len(truth_categories) != len(ground_truth):
        raise ValueError("truth categories must align with truth boxes")
    matches: list[tuple[int, int, float]] = []
    for category in sorted(set(prediction_categories) | set(truth_categories)):
        prediction_indices = [
            index for index, value in enumerate(prediction_categories)
            if value == category
        ]
        truth_indices = [
            index for index, value in enumerate(truth_categories)
            if value == category
        ]
        local = _match_boxes(
            [predictions[index] for index in prediction_indices],
            [ground_truth[index] for index in truth_indices],
            threshold,
        )
        matches.extend(
            (prediction_indices[prediction], truth_indices[truth], overlap)
            for prediction, truth, overlap in local
        )
    return sorted(matches)


def _direction_state(box: list[float], roi: list[float]) -> tuple[str | None, bool, float, float]:
    center_x = box[0] + box[2] * 0.5
    center_y = box[1] + box[3] * 0.5
    roi_center_x = roi[0] + roi[2] * 0.5
    roi_center_y = roi[1] + roi[3] * 0.5
    dx = (center_x - roi_center_x) / max(roi[2] * 0.5, 1e-9)
    dy = (center_y - roi_center_y) / max(roi[3] * 0.5, 1e-9)
    if abs(dx) < 0.15 and abs(dy) < 0.15:
        direction = None
    elif abs(dx) >= abs(dy):
        direction = "left" if dx < 0 else "right"
    else:
        direction = "up" if dy < 0 else "down"
    # Boxes are serialized as rounded normalized coordinates. Near the central
    # dead-zone or diagonal decision boundaries, a pixel of rounding can change
    # the cardinal label. Such locations are explicitly treated as unreliable.
    near_dead_zone = abs(max(abs(dx), abs(dy)) - 0.15) <= DIRECTION_AMBIGUITY_MARGIN
    near_diagonal = (direction is not None and
                     abs(abs(dx) - abs(dy)) <= DIRECTION_AMBIGUITY_MARGIN)
    return direction, near_dead_zone or near_diagonal, dx, dy


def _direction(box: list[float], roi: list[float]) -> str | None:
    direction, _, _, _ = _direction_state(box, roi)
    return direction


def _direction_reference_roi(data: dict, match: dict,
                             detector_roi: list[float]) -> list[float]:
    """Use the measured widget boundary for direction, falling back for old queues."""
    value = match.get("widget_roi")
    if value is None:
        value = data.get("widget_roi")
    if value is None:
        return detector_roi
    return normalized_roi(value, "direction/widget roi")


def _empty_metrics() -> dict:
    return {"frames": 0, "positive_frames": 0, "negative_frames": 0,
            "exact_frames": 0, "tp": 0, "fp": 0, "fn": 0,
            "iou_sum": 0.0, "directed_matches": 0, "correct_directions": 0,
            "ambiguous_direction_matches": 0}


def _accumulate(target: dict, frame: dict) -> None:
    target["frames"] += 1
    target["positive_frames"] += int(bool(frame["ground_truth"]))
    target["negative_frames"] += int(not frame["ground_truth"])
    target["exact_frames"] += int(frame["fp"] == 0 and frame["fn"] == 0)
    for key in ("tp", "fp", "fn", "directed_matches", "correct_directions",
                "ambiguous_direction_matches"):
        target[key] += frame[key]
    target["iou_sum"] += frame["iou_sum"]


def _finish(raw: dict) -> dict:
    predictions = raw["tp"] + raw["fp"]
    truth = raw["tp"] + raw["fn"]
    return {
        "frames": raw["frames"],
        "positive_frames": raw["positive_frames"],
        "negative_frames": raw["negative_frames"],
        "exact_frames": raw["exact_frames"],
        "exact_frame_accuracy": (round(raw["exact_frames"] / raw["frames"], 4)
                                 if raw["frames"] else None),
        "tp": raw["tp"], "fp": raw["fp"], "fn": raw["fn"],
        "precision": round(raw["tp"] / predictions, 4) if predictions else None,
        "recall": round(raw["tp"] / truth, 4) if truth else None,
        "mean_matched_iou": round(raw["iou_sum"] / raw["tp"], 4) if raw["tp"] else None,
        "directed_matches": raw["directed_matches"],
        "ambiguous_direction_matches": raw["ambiguous_direction_matches"],
        "direction_accuracy": (round(raw["correct_directions"] /
                                     raw["directed_matches"], 4)
                               if raw["directed_matches"] else None),
    }


def evaluate_review(review_manifest: Path, iou_threshold: float = 0.5,
                    allow_pending: bool = False, predictions: Path | None = None,
                    prediction_metadata: Path | None = None) -> dict:
    if not 0 < iou_threshold <= 1:
        raise ValueError("iou_threshold must be in (0, 1]")
    data = json.loads(review_manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected review manifest schema_version 1")
    roi_value = data.get("roi")
    if (not isinstance(roi_value, list) or len(roi_value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in roi_value)):
        raise ValueError("Review manifest needs a normalized roi")
    roi = [float(item) for item in roi_value]
    if (roi[0] < 0 or roi[1] < 0 or roi[2] <= 0 or roi[3] <= 0 or
            roi[0] + roi[2] > 1.000001 or roi[1] + roi[3] > 1.000001):
        raise ValueError("Review roi is outside the normalized frame")
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Review manifest needs matches")
    default_category = data.get("kind")
    if not isinstance(default_category, str) or not default_category:
        default_category = "__default__"
    declared_classes = data.get("classes")
    if declared_classes is None:
        declared_classes = [default_category]
    if (not isinstance(declared_classes, list) or not declared_classes or
            any(not isinstance(item, str) or not item.strip()
                for item in declared_classes)):
        raise ValueError("Review manifest classes must be a non-empty list")
    declared_classes = [item.strip() for item in declared_classes]
    if len(set(declared_classes)) != len(declared_classes):
        raise ValueError("Review manifest classes must be unique")
    if default_category not in declared_classes:
        default_category = declared_classes[0]

    external = None
    prediction_source = None
    if predictions is not None:
        predictions = predictions.resolve()
        if len(matches) != 1:
            raise ValueError("External predictions currently require exactly one review match")
        if data.get("review_mode") != "blind":
            raise ValueError("External predictions are only valid for a blind review manifest")
        sampling = data.get("sampling")
        if (not isinstance(sampling, dict) or
                sampling.get("predictions_used_for_selection") is not False):
            raise ValueError("Blind holdout sampling independence is not recorded")
        metadata = (prediction_metadata.resolve() if prediction_metadata is not None
                    else Path(f"{predictions}.meta.json"))
        if not predictions.is_file() or not metadata.is_file():
            raise FileNotFoundError("External predictions or metadata are missing")
        prediction_source = _verify_prediction_commitment(data, predictions, metadata)
        external = _external_predictions(predictions, data.get("kind"), declared_classes)
    elif prediction_metadata is not None:
        raise ValueError("--prediction-metadata requires --predictions")

    overall = _empty_metrics()
    per_class = {name: _empty_metrics() for name in declared_classes}
    splits: dict[str, dict] = {}
    by_match: dict[str, dict] = {}
    failures = []
    direction_failures = []
    direction_ambiguities = []
    skipped = 0
    excluded = 0
    pending = []
    total_samples = 0
    for match in matches:
        match_id = match.get("id")
        split = match.get("split")
        if not isinstance(match_id, str) or not match_id or not isinstance(split, str):
            raise ValueError("Each review match needs id and split")
        match_roi_value = match.get("roi", roi)
        if (not isinstance(match_roi_value, list) or len(match_roi_value) != 4 or
                any(not isinstance(item, (int, float)) or isinstance(item, bool)
                    for item in match_roi_value)):
            raise ValueError(f"{match_id} roi must be normalized [x, y, width, height]")
        match_roi = [float(item) for item in match_roi_value]
        if (match_roi[0] < 0 or match_roi[1] < 0 or match_roi[2] <= 0 or
                match_roi[3] <= 0 or match_roi[0] + match_roi[2] > 1.000001 or
                match_roi[1] + match_roi[3] > 1.000001):
            raise ValueError(f"{match_id} roi is outside the normalized frame")
        direction_roi = _direction_reference_roi(data, match, match_roi)
        match_metrics = by_match.setdefault(match_id, _empty_metrics())
        split_metrics = splits.setdefault(split, _empty_metrics())
        for sample in match.get("samples", []):
            total_samples += 1
            status = sample.get("review_status", "pending")
            key = f"{match_id}@{sample.get('at_ms')}"
            if status not in FINAL_STATUSES:
                pending.append(key)
                continue
            if status == "skip":
                skipped += 1
                continue
            if status == "excluded":
                excluded += 1
                continue
            if external is None:
                predicted = _boxes(sample.get("suggested_boxes"),
                                   f"{key} suggested_boxes")
                stored_directions = sample.get("directions")
                predicted_categories = _categories(
                    sample.get("suggested_categories"), len(predicted),
                    declared_classes, default_category, f"{key} suggested",
                )
            else:
                at_ms = sample.get("at_ms")
                if at_ms not in external:
                    raise ValueError(f"No frozen prediction frame for {key}")
                predicted, stored_directions, predicted_categories = external[at_ms]
                predicted_categories = _categories(
                    predicted_categories, len(predicted), declared_classes,
                    default_category, f"{key} predictions",
                )
            if status == "accepted":
                if data.get("review_mode") == "blind":
                    raise ValueError(f"{key} blind sample cannot use accepted status")
                truth = [box[:] for box in predicted]
                truth_categories = list(predicted_categories)
            elif status == "corrected":
                truth = _boxes(sample.get("reviewed_boxes"), f"{key} reviewed_boxes")
                if not truth:
                    raise ValueError(f"{key} corrected sample has no boxes; use negative")
                truth_categories = _categories(
                    sample.get("reviewed_categories"), len(truth), declared_classes,
                    default_category, f"{key} reviewed",
                )
            else:
                truth = []
                truth_categories = []
            if stored_directions is None:
                predicted_directions = [_direction(box, direction_roi) for box in predicted]
            else:
                if (not isinstance(stored_directions, list) or
                        len(stored_directions) != len(predicted) or
                        any(item not in (None, *DIRECTIONS) for item in stored_directions)):
                    raise ValueError(f"{key} directions must align with suggested_boxes")
                predicted_directions = stored_directions
            pairs = _match_boxes_by_class(
                predicted, truth, predicted_categories, truth_categories, iou_threshold,
            )
            matched_predictions = {prediction for prediction, _, _ in pairs}
            matched_truth = {ground_truth for _, ground_truth, _ in pairs}
            directed = []
            ambiguous_pairs = []
            for prediction, ground_truth, _ in pairs:
                truth_direction, ambiguous, dx, dy = _direction_state(
                    truth[ground_truth], direction_roi)
                if ambiguous:
                    ambiguous_pairs.append((prediction, ground_truth))
                    direction_ambiguities.append({
                        "match_id": match_id,
                        "split": split,
                        "at_ms": sample.get("at_ms"),
                        "selection": sample.get("selection"),
                        "prediction_index": prediction,
                        "predicted_direction": predicted_directions[prediction],
                        "derived_direction": truth_direction,
                        "normalized_offset": [round(dx, 5), round(dy, 5)],
                        "ground_truth_box": truth[ground_truth],
                    })
                elif truth_direction is not None:
                    directed.append((prediction, ground_truth))
            correct_directions = sum(
                predicted_directions[prediction] == _direction(
                    truth[ground_truth], direction_roi)
                for prediction, ground_truth in directed
            )
            for prediction, ground_truth in directed:
                truth_direction = _direction(truth[ground_truth], direction_roi)
                if predicted_directions[prediction] != truth_direction:
                    direction_failures.append({
                        "match_id": match_id,
                        "split": split,
                        "at_ms": sample.get("at_ms"),
                        "selection": sample.get("selection"),
                        "prediction_index": prediction,
                        "predicted_direction": predicted_directions[prediction],
                        "ground_truth_direction": truth_direction,
                        "predicted_box": predicted[prediction],
                        "ground_truth_box": truth[ground_truth],
                    })
            frame = {
                "ground_truth": truth,
                "tp": len(pairs),
                "fp": len(predicted) - len(pairs),
                "fn": len(truth) - len(pairs),
                "iou_sum": sum(overlap for _, _, overlap in pairs),
                "directed_matches": len(directed),
                "correct_directions": correct_directions,
                "ambiguous_direction_matches": len(ambiguous_pairs),
            }
            for target in (overall, split_metrics, match_metrics):
                _accumulate(target, frame)
            for class_name in declared_classes:
                class_prediction_indices = {
                    index for index, value in enumerate(predicted_categories)
                    if value == class_name
                }
                class_truth_indices = {
                    index for index, value in enumerate(truth_categories)
                    if value == class_name
                }
                class_pairs = [pair for pair in pairs
                               if predicted_categories[pair[0]] == class_name]
                class_frame = {
                    "ground_truth": [truth[index] for index in class_truth_indices],
                    "tp": len(class_pairs),
                    "fp": len(class_prediction_indices) - len(class_pairs),
                    "fn": len(class_truth_indices) - len(class_pairs),
                    "iou_sum": sum(pair[2] for pair in class_pairs),
                    "directed_matches": sum(
                        1 for prediction, ground_truth in directed
                        if predicted_categories[prediction] == class_name
                    ),
                    "correct_directions": sum(
                        1 for prediction, ground_truth in directed
                        if predicted_categories[prediction] == class_name and
                        predicted_directions[prediction] == _direction(
                            truth[ground_truth], direction_roi
                        )
                    ),
                    "ambiguous_direction_matches": sum(
                        1 for prediction, ground_truth in ambiguous_pairs
                        if predicted_categories[prediction] == class_name
                    ),
                }
                _accumulate(per_class[class_name], class_frame)
            if frame["fp"] or frame["fn"]:
                failures.append({
                    "match_id": match_id, "split": split,
                    "at_ms": sample.get("at_ms"), "selection": sample.get("selection"),
                    "fp": frame["fp"], "fn": frame["fn"],
                    "unmatched_predictions": [box for index, box in enumerate(predicted)
                                              if index not in matched_predictions],
                    "unmatched_ground_truth": [box for index, box in enumerate(truth)
                                               if index not in matched_truth],
                })
    if pending and not allow_pending:
        preview = ", ".join(pending[:5])
        suffix = "..." if len(pending) > 5 else ""
        raise ValueError(f"Review still has {len(pending)} pending samples: {preview}{suffix}")
    review_complete = not pending
    reviewed_samples = total_samples - len(pending)
    finished_overall = _finish(overall)
    targets = {"precision": 0.9, "recall": 0.8, "direction_accuracy": 0.9}
    target_results = {
        key: finished_overall[key] is not None and finished_overall[key] >= value
        for key, value in targets.items()
    }
    independent_holdout = bool(
        external is not None and data.get("review_mode") == "blind" and
        data.get("sampling", {}).get("predictions_used_for_selection") is False and
        all(match.get("split") == "test" for match in matches)
    )
    if independent_holdout:
        warning = ("Metrics cover uniformly sampled independent holdout frames. "
                   "They are not full-match event metrics, continuous-recording evidence, "
                   "or Android device latency measurements.")
    else:
        warning = ("Metrics cover the stratified review-frame sample and development matches; "
                   "they are not full-match event metrics or independent holdout results.")
    if pending:
        warning = (f"PARTIAL REPORT: {len(pending)} of {total_samples} samples are still pending. "
                   "Metrics cover only completed samples and cannot be used as a final result. "
                   + warning)
    return {
        "schema_version": 1,
        "kind": data.get("kind"),
        "classes": declared_classes,
        "iou_threshold": iou_threshold,
        "evaluation_design": ("independent_blind_frame_holdout"
                              if independent_holdout else "development_diagnostic"),
        "prediction_source": prediction_source,
        "warning": warning,
        "review_complete": review_complete,
        "total_samples": total_samples,
        "reviewed_samples": reviewed_samples,
        "pending_samples": len(pending),
        "completion_ratio": (round(reviewed_samples / total_samples, 4)
                             if total_samples else None),
        "skipped_frames": skipped,
        "excluded_frames": excluded,
        "overall": finished_overall,
        "per_class": {name: _finish(metrics) for name, metrics in per_class.items()},
        "splits": {name: _finish(metrics) for name, metrics in sorted(splits.items())},
        "matches": {name: _finish(metrics) for name, metrics in by_match.items()},
        "targets": targets,
        "sampled_frame_targets_met": review_complete and all(target_results.values()),
        "target_results": target_results,
        "failures": failures,
        "direction_failures": direction_failures,
        "direction_ambiguities": direction_ambiguities,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review_manifest", type=Path)
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    parser.add_argument(
        "--allow-pending", action="store_true",
        help="diagnose completed samples while clearly marking the report incomplete",
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--predictions", type=Path,
                        help="Frozen replay JSONL for a blind single-match holdout")
    parser.add_argument("--prediction-metadata", type=Path,
                        help="Replay provenance (default: <predictions>.meta.json)")
    args = parser.parse_args()
    try:
        result = evaluate_review(
            args.review_manifest, args.iou_threshold, args.allow_pending,
            args.predictions, args.prediction_metadata,
        )
        rendered = json.dumps(result, ensure_ascii=False, indent=2)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered + "\n", encoding="utf-8")
        print(rendered)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
