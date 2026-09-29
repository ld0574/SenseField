"""Evaluate one frozen YOLOX checkpoint on a fixed COCO split and threshold.

By default, the command evaluates only the confidence stored in the checkpoint
(or supplied with --confidence). Optional threshold sweeps add a diagnostic
curve to the output without changing the primary fixed-confidence metrics.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from decimal import Decimal
from fractions import Fraction
from pathlib import Path

from mapassist.detection_evaluate import _direction, _direction_state, _iou
from mapassist.roi_safety import (
    assert_coco_boxes_within_images,
    assert_coco_roi_safe,
    normalized_roi,
)

if __package__:
    from .train_yolox_minimap import (
        _device,
        _finish,
        _git_revision,
        _match_class_aware_pairs,
        _sha256,
        _split_predictions,
    )
    from .yolox_decode import (
        DEFAULT_CLASSES,
        confidence_from_metadata,
        confidence_thresholds,
        resolve_classes,
    )
else:
    from train_yolox_minimap import (
        _device,
        _finish,
        _git_revision,
        _match_class_aware_pairs,
        _sha256,
        _split_predictions,
    )
    from yolox_decode import (  # type: ignore
        DEFAULT_CLASSES,
        confidence_from_metadata,
        confidence_thresholds,
        resolve_classes,
    )


def _checkpoint_threshold(checkpoint: dict) -> float:
    try:
        value = checkpoint["validation"]["selected"]["confidence"]
    except (KeyError, TypeError) as error:
        raise ValueError(
            "Checkpoint has no validation-selected confidence; pass --confidence"
        ) from error
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError("Checkpoint validation confidence is invalid")
    return float(value)


def _prediction_entry(value: object) -> tuple[float, list[float], int]:
    """Normalize legacy ``(score, box)`` and class-aware prediction rows."""
    if not isinstance(value, (list, tuple)) or len(value) not in (2, 3):
        raise ValueError(f"prediction must be (score, box[, class_id]): {value!r}")
    score, box = value[0], value[1]
    if (not isinstance(score, (int, float)) or isinstance(score, bool) or
            not math.isfinite(float(score)) or not isinstance(box, list)):
        raise ValueError(f"invalid prediction row: {value!r}")
    class_id = value[2] if len(value) == 3 else 0
    if (not isinstance(class_id, int) or isinstance(class_id, bool) or class_id < 0):
        raise ValueError(f"invalid prediction class id: {class_id!r}")
    return float(score), box, int(class_id)


def _truth_entry(value: object) -> tuple[int, list[float]]:
    """Normalize legacy boxes and ``(class_id, box)`` truth rows."""
    if (isinstance(value, (list, tuple)) and len(value) == 2 and
            isinstance(value[0], int) and not isinstance(value[0], bool) and
            isinstance(value[1], list)):
        class_id = int(value[0])
        box = value[1]
    else:
        class_id = 0
        box = value
    if not isinstance(box, list):
        raise ValueError(f"truth must be a box or (class_id, box): {value!r}")
    return class_id, box


def _read_evaluation_annotations(data_dir: Path, split: str) -> tuple[Path, dict]:
    split_name = f"{split}2017"
    annotation_path = data_dir / "annotations" / f"instances_{split_name}.json"
    annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
    assert_coco_roi_safe(annotation, split)
    assert_coco_boxes_within_images(annotation, split)
    return annotation_path, annotation


def _fixed_metrics(predictions: dict[int, list[tuple[float, list[float], int]]],
                   truths: dict[int, list[tuple[int, list[float]]]], confidence: object,
                   iou_threshold: float,
                   image_sizes: dict[int, tuple[int, int]],
                   direction_rois: dict[int, list[float]] | None = None,
                   classes=DEFAULT_CLASSES,
                   ) -> tuple[dict, list[dict]]:
    class_names = tuple(classes)
    thresholds = confidence_thresholds(confidence, class_names)
    tp = fp = fn = 0
    directed_matches = correct_directions = ambiguous_directions = 0
    per_class_counts = {
        name: {"tp": 0, "fp": 0, "fn": 0} for name in class_names
    }
    per_image = []
    for image_id, ground_truth in truths.items():
        normalized_predictions = [_prediction_entry(item)
                                  for item in predictions[image_id]]
        normalized_truths = [_truth_entry(item) for item in ground_truth]
        candidates = [item for item in normalized_predictions
                      if 0 <= item[2] < len(class_names) and
                      item[0] >= thresholds[class_names[item[2]]]]
        boxes = [box for _score, box, _class_id in candidates]
        truth_boxes = [box for _class_id, box in normalized_truths]
        pairs = _match_class_aware_pairs(candidates, normalized_truths, iou_threshold)
        matched = len(pairs)
        matched_predictions = {prediction for prediction, _truth, _ in pairs}
        matched_truths = {truth for _prediction, truth, _ in pairs}
        for class_id, class_name in enumerate(class_names):
            class_predictions = [
                index for index, item in enumerate(candidates) if item[2] == class_id
            ]
            class_truths = [
                index for index, item in enumerate(normalized_truths) if item[0] == class_id
            ]
            per_class_counts[class_name]["tp"] += sum(
                1 for prediction, truth, _ in pairs
                if candidates[prediction][2] == class_id and
                normalized_truths[truth][0] == class_id
            )
            per_class_counts[class_name]["fp"] += sum(
                1 for index in class_predictions if index not in matched_predictions
            )
            per_class_counts[class_name]["fn"] += sum(
                1 for index in class_truths if index not in matched_truths
            )
        width, height = image_sizes[image_id]
        roi = (direction_rois or {}).get(image_id, [0.0, 0.0, 1.0, 1.0])
        image_directed = image_correct = image_ambiguous = 0
        for prediction_index, truth_index, _ in pairs:
            prediction = boxes[prediction_index]
            truth = truth_boxes[truth_index]
            normalized_prediction = [
                prediction[0] / width, prediction[1] / height,
                prediction[2] / width, prediction[3] / height,
            ]
            normalized_truth = [
                truth[0] / width, truth[1] / height,
                truth[2] / width, truth[3] / height,
            ]
            truth_direction, ambiguous, _, _ = _direction_state(normalized_truth, roi)
            if ambiguous:
                image_ambiguous += 1
            elif truth_direction is not None:
                image_directed += 1
                if _direction(normalized_prediction, roi) == truth_direction:
                    image_correct += 1
        image_result = {
            "image_id": image_id,
            "truth": len(truth_boxes),
            "predicted": len(boxes),
            "tp": matched,
            "fp": len(boxes) - matched,
            "fn": len(truth_boxes) - matched,
            "directed_matches": image_directed,
            "correct_directions": image_correct,
            "ambiguous_direction_matches": image_ambiguous,
        }
        per_image.append(image_result)
        tp += image_result["tp"]
        fp += image_result["fp"]
        fn += image_result["fn"]
        directed_matches += image_directed
        correct_directions += image_correct
        ambiguous_directions += image_ambiguous
    metrics = _finish(tp, fp, fn)
    metrics["per_class"] = {
        name: _finish(values["tp"], values["fp"], values["fn"])
        for name, values in per_class_counts.items()
    }
    metrics.update({
        "directed_matches": directed_matches,
        "correct_directions": correct_directions,
        "ambiguous_direction_matches": ambiguous_directions,
        "direction_accuracy": (round(correct_directions / directed_matches, 6)
                               if directed_matches else None),
    })
    return metrics, per_image


def _confidence_curve(
    predictions: dict[int, list[tuple[float, list[float], int]]],
    truths: dict[int, list[tuple[int, list[float]]]],
    thresholds: list[float],
    iou_threshold: float,
    classes=DEFAULT_CLASSES,
) -> list[dict]:
    """Return box-level metrics at each requested confidence cutoff."""
    curve = []
    for confidence in thresholds:
        tp = fp = fn = 0
        for image_id, ground_truth in truths.items():
            normalized_predictions = [_prediction_entry(item)
                                      for item in predictions[image_id]]
            normalized_truths = [_truth_entry(item) for item in ground_truth]
            candidates = [item for item in normalized_predictions if item[0] >= confidence]
            matched = len(_match_class_aware_pairs(
                candidates, normalized_truths, iou_threshold,
            ))
            tp += matched
            fp += len(candidates) - matched
            fn += len(normalized_truths) - matched
        curve.append({"confidence": confidence, **_finish(tp, fp, fn)})
    return curve


def _confidence_sweep_summary(curve: list[dict], minimum_precision: float = 0.90) -> dict:
    """Select the precision-gated recall point and the global max-F1 point.

    Metric comparisons use the integer counts, so a displayed precision rounded
    to 0.900000 cannot accidentally pass the gate when the exact ratio is lower.
    Ties retain the first point; callers provide thresholds in ascending order.
    """
    if not curve:
        raise ValueError("confidence curve must not be empty")
    minimum = Fraction(str(minimum_precision))

    def ratio(item: dict, numerator: str, denominator: int) -> Fraction:
        return Fraction(int(item[numerator]), denominator) if denominator else Fraction(0)

    def precision(item: dict) -> Fraction:
        return ratio(item, "tp", int(item["tp"]) + int(item["fp"]))

    def recall(item: dict) -> Fraction:
        return ratio(item, "tp", int(item["tp"]) + int(item["fn"]))

    def f1(item: dict) -> Fraction:
        tp, fp, fn = int(item["tp"]), int(item["fp"]), int(item["fn"])
        denominator = 2 * tp + fp + fn
        return Fraction(2 * tp, denominator) if denominator else Fraction(0)

    eligible = [item for item in curve if precision(item) >= minimum]
    max_recall = (max(eligible, key=lambda item: (
        recall(item), f1(item), precision(item),
    )) if eligible else None)
    max_f1 = max(curve, key=lambda item: (
        f1(item), recall(item), precision(item),
    ))
    return {
        "minimum_precision": minimum_precision,
        "precision_eligible_thresholds": len(eligible),
        "max_recall_at_minimum_precision": max_recall,
        "max_f1": max_f1,
        "max_recall_tie_break": "higher f1, then precision; equal points keep the lowest confidence",
        "max_f1_tie_break": "higher recall, then precision; equal points keep the lowest confidence",
    }


def _resolve_sweep_thresholds(
    threshold_range: list[float] | None,
    threshold_step: float | None,
    explicit_thresholds: list[float] | None,
) -> list[float] | None:
    """Validate and expand optional confidence sweep CLI values."""
    if explicit_thresholds is not None and (
        threshold_range is not None or threshold_step is not None
    ):
        raise ValueError("--thresholds cannot be combined with --threshold-range/--threshold-step")
    if threshold_range is None:
        if threshold_step is not None:
            raise ValueError("--threshold-step requires --threshold-range")
        if explicit_thresholds is None:
            return None
        values = explicit_thresholds
    else:
        if threshold_step is None:
            raise ValueError("--threshold-range requires --threshold-step")
        if len(threshold_range) != 2:
            raise ValueError("--threshold-range requires MIN and MAX")
        minimum, maximum = threshold_range
        if not all(math.isfinite(value) and 0 <= value <= 1
                   for value in (minimum, maximum)):
            raise ValueError("--threshold-range values must be finite and between 0 and 1")
        if minimum > maximum:
            raise ValueError("--threshold-range MIN must be less than or equal to MAX")
        if not math.isfinite(threshold_step) or threshold_step <= 0:
            raise ValueError("--threshold-step must be finite and greater than 0")
        start, stop, step = map(lambda value: Decimal(str(value)),
                                (minimum, maximum, threshold_step))
        values = []
        current = start
        while current <= stop:
            values.append(float(current))
            if len(values) > 10000:
                raise ValueError("confidence sweep cannot contain more than 10000 thresholds")
            current += step
    if not values:
        raise ValueError("confidence sweep must include at least one threshold")
    if any(not math.isfinite(value) or not 0 <= value <= 1 for value in values):
        raise ValueError("--thresholds values must be finite and between 0 and 1")
    normalized = sorted(set(values))
    if len(normalized) != len(values):
        raise ValueError("confidence sweep thresholds must be unique")
    if len(normalized) > 10000:
        raise ValueError("confidence sweep cannot contain more than 10000 thresholds")
    return normalized


def _direction_event_metrics(
    predictions: dict[int, list[tuple[float, list[float], int]]],
    truths: dict[int, list[tuple[int, list[float]]]],
    confidence: object,
    iou_threshold: float,
    image_sizes: dict[int, tuple[int, int]],
    direction_rois: dict[int, list[float]] | None = None,
    classes=DEFAULT_CLASSES,
) -> dict:
    """Score the unique cardinal directions present in each frame.

    Ground-truth boxes with ambiguous or center directions are excluded. Multiple
    boxes in the same direction contribute one event per frame. ``set`` scores
    direction-set overlap alone; ``iou_gated`` additionally requires a predicted
    box in that direction to overlap a non-ambiguous ground-truth box of the same
    direction by at least ``iou_threshold``.
    """
    set_tp = set_fp = set_fn = 0
    gated_tp = gated_fp = gated_fn = 0
    truth_boxes = usable_truth_boxes = ambiguous_truth_boxes = 0
    center_truth_boxes = truth_events = 0
    direction_rois = direction_rois or {}
    class_names = tuple(classes)
    confidence_map = confidence_thresholds(confidence, class_names)
    event_class_ids = ({class_names.index("minimap_enemy")}
                       if "minimap_enemy" in class_names else
                       ({0} if len(class_names) == 1 else set()))

    for image_id, ground_truth in truths.items():
        width, height = image_sizes[image_id]
        roi = direction_rois.get(image_id, [0.0, 0.0, 1.0, 1.0])
        ground_truth_by_direction: dict[str, list[list[float]]] = {}
        normalized_truths = [_truth_entry(item) for item in ground_truth]
        for truth_class_id, truth in normalized_truths:
            if truth_class_id not in event_class_ids:
                continue
            truth_boxes += 1
            normalized_truth = [
                truth[0] / width, truth[1] / height,
                truth[2] / width, truth[3] / height,
            ]
            direction, ambiguous, _, _ = _direction_state(normalized_truth, roi)
            if ambiguous:
                ambiguous_truth_boxes += 1
            elif direction is None:
                center_truth_boxes += 1
            else:
                usable_truth_boxes += 1
                ground_truth_by_direction.setdefault(direction, []).append(truth)

        ground_truth_directions = set(ground_truth_by_direction)
        truth_events += len(ground_truth_directions)

        predicted_by_direction: dict[str, list[list[float]]] = {}
        for item in predictions.get(image_id, []):
            score, prediction, class_id = _prediction_entry(item)
            if (class_id not in event_class_ids or
                    class_id < 0 or class_id >= len(class_names) or
                    score < confidence_map[class_names[class_id]]):
                continue
            normalized_prediction = [
                prediction[0] / width, prediction[1] / height,
                prediction[2] / width, prediction[3] / height,
            ]
            direction = _direction(normalized_prediction, roi)
            if direction is not None:
                predicted_by_direction.setdefault(direction, []).append(prediction)

        predicted_directions = set(predicted_by_direction)
        image_set_tp = len(ground_truth_directions & predicted_directions)
        set_tp += image_set_tp
        set_fp += len(predicted_directions) - image_set_tp
        set_fn += len(ground_truth_directions) - image_set_tp

        image_gated_directions = {
            direction
            for direction in ground_truth_directions & predicted_directions
            if any(
                _iou(prediction, truth) >= iou_threshold
                for prediction in predicted_by_direction[direction]
                for truth in ground_truth_by_direction[direction]
            )
        }
        image_gated_tp = len(image_gated_directions)
        gated_tp += image_gated_tp
        gated_fp += len(predicted_directions) - image_gated_tp
        gated_fn += len(ground_truth_directions) - image_gated_tp

    return {
        "set": _finish(set_tp, set_fp, set_fn),
        "iou_gated": _finish(gated_tp, gated_fp, gated_fn),
        "truth_boxes": truth_boxes,
        "usable_truth_boxes": usable_truth_boxes,
        "ambiguous_truth_boxes": ambiguous_truth_boxes,
        "center_truth_boxes": center_truth_boxes,
        "truth_events": truth_events,
        "direction_reference_images": len(direction_rois),
    }


def _read_image_direction_rois(annotation: dict) -> dict[int, list[float]]:
    """Read widget references normalized to each COCO image/crop."""
    result = {}
    for image in annotation.get("images", []):
        value = image.get("direction_roi")
        if value is None:
            continue
        image_id = image.get("id")
        if (not isinstance(image_id, int) or isinstance(image_id, bool) or
                not isinstance(value, list)):
            raise ValueError("COCO image direction_roi must be a normalized rectangle")
        try:
            result[image_id] = normalized_roi(value, "COCO image direction_roi")
        except ValueError as error:
            raise ValueError(
                "COCO image direction_roi must be a finite normalized rectangle"
            ) from error
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--split", choices=("train", "val", "test"), default="test")
    parser.add_argument("--input-size", type=int, required=True)
    parser.add_argument(
        "--confidence", type=float,
        help="Frozen confidence selected without inspecting this split; defaults to checkpoint",
    )
    parser.add_argument("--classes", nargs="+", metavar="CLASS",
                        help="Explicit canonical class order; otherwise model metadata/COCO is used")
    sweep = parser.add_mutually_exclusive_group()
    sweep.add_argument(
        "--threshold-range", nargs=2, type=float, metavar=("MIN", "MAX"),
        help="Add a diagnostic curve from MIN through MAX, including MAX when the step lands on it",
    )
    sweep.add_argument(
        "--thresholds", nargs="+", type=float, metavar="CONFIDENCE",
        help="Add a diagnostic confidence curve at explicit confidence values",
    )
    parser.add_argument(
        "--threshold-step", type=float,
        help="Positive step for --threshold-range; the MAX endpoint is included when reached",
    )
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument("--device", default="auto")
    args = parser.parse_args()
    if args.input_size < 64 or args.input_size % 32:
        parser.error("--input-size must be a positive multiple of 32")
    if args.confidence is not None and not 0 <= args.confidence <= 1:
        parser.error("--confidence must be between 0 and 1")
    if not 0 < args.iou_threshold <= 1 or not 0 < args.nms_threshold <= 1:
        parser.error("IoU and NMS thresholds must be between 0 and 1")
    try:
        sweep_thresholds = _resolve_sweep_thresholds(
            args.threshold_range, args.threshold_step, args.thresholds,
        )
    except ValueError as error:
        parser.error(str(error))

    root = Path(__file__).resolve().parents[1]
    yolox_root = args.yolox_root.resolve()
    data_dir = args.data_dir.resolve()
    checkpoint_path = args.checkpoint.resolve()
    output = args.output.resolve()
    try:
        annotation_path, annotation = _read_evaluation_annotations(data_dir, args.split)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        parser.error(str(error))
    sys.path.insert(0, str(yolox_root))
    os.environ["MAPASSIST_COCO_DIR"] = str(data_dir)

    import torch
    from yolox.exp import get_exp
    from yolox.utils import load_ckpt

    device = _device(torch, args.device)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    metadata_path = checkpoint_path.parent / "metrics.json"
    model_metadata = metadata_path if metadata_path.is_file() else None
    class_names = resolve_classes(
        classes=args.classes, metadata=model_metadata, coco=annotation,
    )
    try:
        checkpoint_confidence = _checkpoint_threshold(checkpoint)
    except ValueError:
        checkpoint_confidence = None
    metadata_has_confidence = False
    if model_metadata is not None:
        metadata_document = json.loads(model_metadata.read_text(encoding="utf-8"))
        postprocess = metadata_document.get("postprocess", {})
        if postprocess is None:
            postprocess = {}
        if not isinstance(postprocess, dict):
            raise ValueError("model metadata postprocess must be an object")
        metadata_has_confidence = any(key in postprocess for key in
                                      ("confidence_by_class", "confidence"))
        metadata_has_confidence = metadata_has_confidence or any(
            key in metadata_document for key in ("confidence_by_class", "confidence")
        )
    if args.confidence is not None:
        confidence = args.confidence
        confidence_source = "command_line"
    elif metadata_has_confidence:
        confidence = confidence_from_metadata(
            model_metadata, class_names,
            0.0 if checkpoint_confidence is None else checkpoint_confidence,
        )
        confidence_source = "model_metadata"
    else:
        if checkpoint_confidence is None:
            raise ValueError(
                "Checkpoint has no validation-selected confidence; pass --confidence"
            )
        confidence = checkpoint_confidence
        confidence_source = "checkpoint_validation"
    exp_path = root / "training/yolox_nano_minimap.py"
    exp = get_exp(str(exp_path), None)
    exp.class_names = list(class_names)
    exp.num_classes = len(class_names)
    exp.input_size = (args.input_size, args.input_size)
    exp.test_size = exp.input_size
    model = load_ckpt(exp.get_model(), checkpoint.get("model", checkpoint))
    model.to(device)
    prefilter_confidence = min(confidence_thresholds(confidence, class_names).values())
    if sweep_thresholds is not None:
        prefilter_confidence = min(prefilter_confidence, min(sweep_thresholds))
    prefilter_confidence = min(prefilter_confidence, 0.01)
    predictions, truths = _split_predictions(
        model, device, data_dir, exp.test_size, args.nms_threshold, args.split,
        pre_filter_confidence=prefilter_confidence,
        classes=class_names,
    )
    image_sizes = {
        item["id"]: (int(item["width"]), int(item["height"]))
        for item in annotation["images"]
    }
    direction_rois = _read_image_direction_rois(annotation)
    metrics, per_image = _fixed_metrics(
        predictions, truths, confidence, args.iou_threshold, image_sizes,
        direction_rois, class_names,
    )
    direction_events = _direction_event_metrics(
        predictions, truths, confidence, args.iou_threshold, image_sizes,
        direction_rois, class_names,
    )
    confidence_sweep = None
    if sweep_thresholds is not None:
        curve = _confidence_curve(
            predictions, truths, sweep_thresholds, args.iou_threshold, class_names,
        )
        confidence_sweep = {
            "thresholds": curve,
            "summary": _confidence_sweep_summary(curve),
        }

    if args.split == "val" and args.confidence is None and confidence_source == "checkpoint_validation":
        threshold_relation = "selected_on_evaluated_validation_split"
        warning = (
            "The checkpoint confidence was selected on this same validation split. "
            "These are development metrics, not an independent holdout result."
        )
    elif args.confidence is not None:
        threshold_relation = "caller_supplied"
        warning = (
            "The caller supplied the confidence threshold. This file alone cannot prove "
            "that the evaluated split was excluded from threshold or model selection."
        )
    else:
        threshold_relation = ("model_metadata_to_split" if confidence_source == "model_metadata"
                              else "checkpoint_validation_to_different_split")
        warning = (
            "The confidence came from frozen model metadata or checkpoint validation rather than this split. "
            "Independent-test claims still require external proof that these frames and "
            "labels were excluded from model and configuration selection."
        )
    result = {
        "schema_version": 1,
        "design": "fixed_threshold_coco_split_evaluation",
        "warning": warning,
        "split": args.split,
        "device": str(device),
        "input_size": [args.input_size, args.input_size],
        "confidence": confidence,
        "confidence_source": confidence_source,
        "confidence_by_class": confidence_thresholds(confidence, class_names),
        "threshold_relation": threshold_relation,
        "iou_threshold": args.iou_threshold,
        "nms_threshold": args.nms_threshold,
        "checkpoint": {
            "path": str(checkpoint_path),
            "sha256": _sha256(checkpoint_path),
            "epoch": checkpoint.get("epoch") if isinstance(checkpoint, dict) else None,
        },
        "dataset": {
            "path": str(data_dir),
            "annotations": str(annotation_path),
            "annotations_sha256": _sha256(annotation_path),
            "images": len(truths),
            "truth_boxes": sum(len(items) for items in truths.values()),
        },
        "experiment": {"path": str(exp_path), "sha256": _sha256(exp_path)},
        "yolox_revision": _git_revision(yolox_root),
        "metrics": metrics,
        "direction_events": direction_events,
        "per_image": per_image,
    }
    if confidence_sweep is not None:
        result["confidence_sweep"] = confidence_sweep
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "split": args.split,
        "confidence": confidence,
        **metrics,
        "direction_events": direction_events,
        **({"confidence_sweep": {
            "threshold_count": len(confidence_sweep["thresholds"]),
            "summary": confidence_sweep["summary"],
        }} if confidence_sweep is not None else {}),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
