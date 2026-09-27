"""Evaluate one frozen YOLOX checkpoint on a fixed COCO split and threshold.

Unlike the training loop, this command never searches the evaluated split for a
better confidence threshold.  By default it reads the threshold stored in the
best checkpoint, which was selected on the development validation match.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from mapassist.detection_evaluate import _direction, _direction_state, _iou, _match_boxes
from mapassist.roi_safety import (
    assert_coco_boxes_within_images,
    assert_coco_roi_safe,
)

if __package__:
    from .train_yolox_minimap import (
        _device,
        _finish,
        _git_revision,
        _sha256,
        _split_predictions,
    )
else:
    from train_yolox_minimap import (
        _device,
        _finish,
        _git_revision,
        _sha256,
        _split_predictions,
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


def _read_evaluation_annotations(data_dir: Path, split: str) -> tuple[Path, dict]:
    split_name = f"{split}2017"
    annotation_path = data_dir / "annotations" / f"instances_{split_name}.json"
    annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
    assert_coco_roi_safe(annotation, split)
    assert_coco_boxes_within_images(annotation, split)
    return annotation_path, annotation


def _fixed_metrics(predictions: dict[int, list[tuple[float, list[float]]]],
                   truths: dict[int, list[list[float]]], confidence: float,
                   iou_threshold: float,
                   image_sizes: dict[int, tuple[int, int]]) -> tuple[dict, list[dict]]:
    tp = fp = fn = 0
    directed_matches = correct_directions = ambiguous_directions = 0
    per_image = []
    for image_id, ground_truth in truths.items():
        boxes = [box for score, box in predictions[image_id] if score >= confidence]
        pairs = _match_boxes(boxes, ground_truth, iou_threshold)
        matched = len(pairs)
        width, height = image_sizes[image_id]
        roi = [0.0, 0.0, 1.0, 1.0]
        image_directed = image_correct = image_ambiguous = 0
        for prediction_index, truth_index, _ in pairs:
            prediction = boxes[prediction_index]
            truth = ground_truth[truth_index]
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
            "truth": len(ground_truth),
            "predicted": len(boxes),
            "tp": matched,
            "fp": len(boxes) - matched,
            "fn": len(ground_truth) - matched,
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
    metrics.update({
        "directed_matches": directed_matches,
        "correct_directions": correct_directions,
        "ambiguous_direction_matches": ambiguous_directions,
        "direction_accuracy": (round(correct_directions / directed_matches, 6)
                               if directed_matches else None),
    })
    return metrics, per_image


def _direction_event_metrics(
    predictions: dict[int, list[tuple[float, list[float]]]],
    truths: dict[int, list[list[float]]],
    confidence: float,
    iou_threshold: float,
    image_sizes: dict[int, tuple[int, int]],
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
    roi = [0.0, 0.0, 1.0, 1.0]

    for image_id, ground_truth in truths.items():
        width, height = image_sizes[image_id]
        ground_truth_by_direction: dict[str, list[list[float]]] = {}
        for truth in ground_truth:
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
        for score, prediction in predictions.get(image_id, []):
            if score < confidence:
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
    }


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
    confidence = (args.confidence if args.confidence is not None
                  else _checkpoint_threshold(checkpoint))
    exp_path = root / "training/yolox_nano_minimap.py"
    exp = get_exp(str(exp_path), None)
    exp.input_size = (args.input_size, args.input_size)
    exp.test_size = exp.input_size
    model = load_ckpt(exp.get_model(), checkpoint.get("model", checkpoint))
    model.to(device)
    predictions, truths = _split_predictions(
        model, device, data_dir, exp.test_size, args.nms_threshold, args.split,
        pre_filter_confidence=min(confidence, 0.01),
    )
    image_sizes = {
        item["id"]: (int(item["width"]), int(item["height"]))
        for item in annotation["images"]
    }
    metrics, per_image = _fixed_metrics(
        predictions, truths, confidence, args.iou_threshold, image_sizes
    )
    direction_events = _direction_event_metrics(
        predictions, truths, confidence, args.iou_threshold, image_sizes
    )

    if args.split == "val" and args.confidence is None:
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
        threshold_relation = "checkpoint_validation_to_different_split"
        warning = (
            "The confidence came from checkpoint validation rather than this split. "
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
        "confidence_source": ("command_line" if args.confidence is not None
                              else "checkpoint_validation"),
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
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "split": args.split,
        "confidence": confidence,
        **metrics,
        "direction_events": direction_events,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
