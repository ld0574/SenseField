"""Evaluate a frozen single-class YOLOX screen-region locator.

This evaluator intentionally selects the highest-confidence box per frame, as
the Android locator will do, and reports crop-boundary metrics that ordinary
object-detection precision and recall hide.  The evaluated COCO split must
contain exactly one ``minimap_region`` ground-truth box per image.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys
from pathlib import Path
from typing import Any

from mapassist.detection_evaluate import _iou

if __package__:
    from .evaluate_yolox_minimap import _checkpoint_threshold
    from .train_yolox_minimap import _device, _git_revision, _sha256, _split_predictions
else:
    from evaluate_yolox_minimap import _checkpoint_threshold
    from train_yolox_minimap import _device, _git_revision, _sha256, _split_predictions


def _percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((len(ordered) - 1) * fraction)))
    return ordered[index]


def _intersection(first: list[float], second: list[float]) -> float:
    left = max(first[0], second[0])
    top = max(first[1], second[1])
    right = min(first[0] + first[2], second[0] + second[2])
    bottom = min(first[1] + first[3], second[1] + second[3])
    return max(0.0, right - left) * max(0.0, bottom - top)


def _expand_box(box: list[float], width: int, height: int,
                margin_short_edge: float) -> list[float]:
    margin = min(width, height) * margin_short_edge
    left = max(0.0, box[0] - margin)
    top = max(0.0, box[1] - margin)
    right = min(float(width), box[0] + box[2] + margin)
    bottom = min(float(height), box[1] + box[3] + margin)
    return [left, top, right - left, bottom - top]


def locator_metrics(
    predictions: dict[int, list[tuple[float, list[float]]]],
    truths: dict[int, list[list[float]]],
    confidence: float,
    images: dict[int, dict[str, Any]],
    margin_short_edge: float = 0.0,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Measure the box that deployment would select for every locator frame."""
    if set(predictions) != set(truths) or set(images) != set(truths):
        raise ValueError("Predictions, truths and image metadata must cover identical IDs")
    if not 0 <= confidence <= 1:
        raise ValueError("confidence must be between 0 and 1")
    if not 0 <= margin_short_edge <= 0.25:
        raise ValueError("margin_short_edge must be between 0 and 0.25")

    per_image: list[dict[str, Any]] = []
    agreements: list[dict[str, float]] = []
    missing = extras = 0
    for image_id in sorted(truths):
        ground_truth = truths[image_id]
        if len(ground_truth) != 1:
            raise ValueError(
                f"Locator image {image_id} must have exactly one truth box; "
                f"found {len(ground_truth)}"
            )
        candidates = sorted(
            ((score, box) for score, box in predictions[image_id] if score >= confidence),
            key=lambda item: item[0],
            reverse=True,
        )
        metadata = images[image_id]
        width, height = int(metadata["width"]), int(metadata["height"])
        item: dict[str, Any] = {
            "image_id": image_id,
            "file_name": metadata.get("file_name"),
            "frame_size": [width, height],
            "eligible_predictions": len(candidates),
        }
        extras += max(0, len(candidates) - 1)
        if not candidates:
            missing += 1
            item["selected"] = None
            per_image.append(item)
            continue

        score, raw_predicted = candidates[0]
        predicted = _expand_box(raw_predicted, width, height, margin_short_edge)
        truth = ground_truth[0]
        overlap = _iou(predicted, truth)
        intersection = _intersection(predicted, truth)
        predicted_area = predicted[2] * predicted[3]
        truth_area = truth[2] * truth[3]
        px0, py0, pw, ph = predicted
        tx0, ty0, tw, th = truth
        px1, py1 = px0 + pw, py0 + ph
        tx1, ty1 = tx0 + tw, ty0 + th
        edge_errors = [
            abs(px0 - tx0), abs(py0 - ty0), abs(px1 - tx1), abs(py1 - ty1)
        ]
        center_error = math.hypot(
            (px0 + px1 - tx0 - tx1) / 2,
            (py0 + py1 - ty0 - ty1) / 2,
        )
        agreement = {
            "iou": overlap,
            "truth_coverage": intersection / truth_area if truth_area else 0.0,
            "predicted_content_ratio": (
                intersection / predicted_area if predicted_area else 0.0
            ),
            "max_edge_error_px": max(edge_errors),
            "max_edge_error_short_edge": max(edge_errors) / min(width, height),
            "center_error_px": center_error,
        }
        agreements.append(agreement)
        item.update({
            "selected": {
                "confidence": score,
                "raw_bbox": raw_predicted,
                "crop_bbox": predicted,
            },
            "truth": truth,
            **agreement,
        })
        per_image.append(item)

    def summary(values: list[float], *, digits: int = 6) -> dict[str, float | None]:
        if not values:
            return {"minimum": None, "median": None, "p95": None, "maximum": None}
        return {
            "minimum": round(min(values), digits),
            "median": round(statistics.median(values), digits),
            "p95": round(_percentile(values, 0.95) or 0.0, digits),
            "maximum": round(max(values), digits),
        }

    ious = [item["iou"] for item in agreements]
    coverage = [item["truth_coverage"] for item in agreements]
    content = [item["predicted_content_ratio"] for item in agreements]
    edge_px = [item["max_edge_error_px"] for item in agreements]
    edge_short = [item["max_edge_error_short_edge"] for item in agreements]
    centers = [item["center_error_px"] for item in agreements]
    total = len(truths)
    metrics = {
        "frames": total,
        "margin_short_edge": margin_short_edge,
        "frames_with_prediction": len(agreements),
        "missing_frames": missing,
        "extra_predictions": extras,
        "single_prediction_ratio": round(
            sum(item["eligible_predictions"] == 1 for item in per_image) / total, 6
        ) if total else None,
        "iou": summary(ious),
        "iou_at_least": {
            str(threshold): round(sum(value >= threshold for value in ious) / total, 6)
            for threshold in (0.5, 0.75, 0.9)
        },
        "truth_coverage": summary(coverage),
        "predicted_content_ratio": summary(content),
        "max_edge_error_px": summary(edge_px, digits=3),
        "max_edge_error_short_edge": summary(edge_short),
        "center_error_px": summary(centers, digits=3),
    }
    return metrics, per_image


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--split", choices=("train", "val", "test"), default="val")
    parser.add_argument("--input-size", type=int, required=True)
    parser.add_argument("--confidence", type=float)
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument(
        "--margin-short-edge", type=float, default=0.0,
        help="expand each selected edge by this fraction of the frame short edge",
    )
    parser.add_argument("--device", default="auto")
    args = parser.parse_args()
    if args.input_size < 64 or args.input_size % 32:
        parser.error("--input-size must be a positive multiple of 32")
    if args.confidence is not None and not 0 <= args.confidence <= 1:
        parser.error("--confidence must be between 0 and 1")
    if not 0 < args.nms_threshold <= 1:
        parser.error("--nms-threshold must be between 0 and 1")
    if not 0 <= args.margin_short_edge <= 0.25:
        parser.error("--margin-short-edge must be between 0 and 0.25")

    root = Path(__file__).resolve().parents[1]
    yolox_root = args.yolox_root.resolve()
    data_dir = args.data_dir.resolve()
    checkpoint_path = args.checkpoint.resolve()
    output = args.output.resolve()
    sys.path.insert(0, str(yolox_root))
    os.environ["MAPASSIST_COCO_DIR"] = str(data_dir)

    import torch
    from yolox.exp import get_exp
    from yolox.utils import load_ckpt

    device = _device(torch, args.device)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    confidence = (
        args.confidence if args.confidence is not None else _checkpoint_threshold(checkpoint)
    )
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
    split_name = f"{args.split}2017"
    annotation_path = data_dir / "annotations" / f"instances_{split_name}.json"
    annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
    images = {int(item["id"]): item for item in annotation["images"]}
    metrics, per_image = locator_metrics(
        predictions, truths, confidence, images, args.margin_short_edge
    )

    result = {
        "schema_version": 1,
        "design": "single_region_locator_boundary_evaluation",
        "warning": (
            "This split was used during model selection and is development evidence, "
            "not an independent holdout."
            if args.split == "val" else
            "Independent-test claims require proof that this split was excluded from "
            "training, threshold selection and configuration decisions."
        ),
        "selection_policy": "highest_confidence_box_per_frame",
        "margin_short_edge": args.margin_short_edge,
        "split": args.split,
        "device": str(device),
        "input_size": [args.input_size, args.input_size],
        "confidence": confidence,
        "confidence_source": (
            "command_line" if args.confidence is not None else "checkpoint_validation"
        ),
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
        },
        "experiment": {"path": str(exp_path), "sha256": _sha256(exp_path)},
        "yolox_revision": _git_revision(yolox_root),
        "metrics": metrics,
        "per_image": per_image,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), **metrics}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
