"""Evaluate a frozen YOLOX checkpoint with a fixed red-ring candidate gate.

This is an offline development diagnostic. It runs one explicitly supplied
checkpoint on one COCO split and applies one fixed rule; it never searches for
thresholds. Low-confidence boxes pass only when their strict-red border has a
three-side plus opposite-side structure and reaches the configured ring score.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

from mapassist.detection_evaluate import _direction_state
from mapassist.roi_safety import normalized_roi

if __package__:
    from .evaluate_yolox_minimap import (
        _read_evaluation_annotations,
        _read_image_direction_rois,
    )
    from .train_yolox_minimap import (
        _device,
        _finish,
        _git_revision,
        _match_class_aware_pairs,
        _sha256,
        _split_predictions,
    )
    from .yolox_decode import resolve_classes
else:
    from evaluate_yolox_minimap import (
        _read_evaluation_annotations,
        _read_image_direction_rois,
    )
    from train_yolox_minimap import (
        _device,
        _finish,
        _git_revision,
        _match_class_aware_pairs,
        _sha256,
        _split_predictions,
    )
    from yolox_decode import resolve_classes  # type: ignore


@dataclass(frozen=True)
class HybridConfig:
    high_confidence: float = 0.807
    low_confidence: float = 0.45
    ring_score_min: int = 188
    red_min: int = 90
    red_dominance: float = 1.25
    band_ratio: float = 0.032
    min_band: int = 2
    min_sides: int = 3
    pixels_per_side: int = 3


def _prediction_entry(value: object) -> tuple[float, list[float], int]:
    """Normalize old prediction dictionaries and class-aware prediction rows."""
    if isinstance(value, dict):
        confidence = value.get("confidence")
        bbox = value.get("bbox")
        class_id = value.get("class_id", 0)
    elif isinstance(value, (list, tuple)) and len(value) in (2, 3):
        confidence, bbox = value[:2]
        class_id = value[2] if len(value) == 3 else 0
    else:
        raise ValueError(f"invalid prediction entry: {value!r}")
    if (not isinstance(confidence, (int, float)) or isinstance(confidence, bool) or
            not math.isfinite(float(confidence)) or not isinstance(bbox, list)):
        raise ValueError(f"invalid prediction confidence/bbox: {value!r}")
    if (not isinstance(class_id, int) or isinstance(class_id, bool) or class_id < 0):
        raise ValueError(f"invalid prediction class id: {class_id!r}")
    return float(confidence), bbox, int(class_id)


def _truth_entry(value: object) -> tuple[int, list[float]]:
    """Normalize legacy box-only truths to the historical class zero."""
    if (isinstance(value, (list, tuple)) and len(value) == 2 and
            isinstance(value[0], int) and not isinstance(value[0], bool) and
            isinstance(value[1], list)):
        return int(value[0]), value[1]
    if not isinstance(value, list):
        raise ValueError(f"invalid truth entry: {value!r}")
    return 0, value


def _validate_config(
    config: HybridConfig,
    *,
    input_size: int,
    iou_threshold: float,
    nms_threshold: float,
) -> None:
    """Reject unsafe or misleading CLI values before loading a model."""
    if not 64 <= input_size <= 2048 or input_size % 32:
        raise ValueError("--input-size must be a multiple of 32 between 64 and 2048")
    for name, value in (("IoU", iou_threshold), ("NMS", nms_threshold)):
        if not math.isfinite(value) or not 0 < value <= 1:
            raise ValueError(f"{name} threshold must be finite and between 0 and 1")
    for name, value in (("--high-confidence", config.high_confidence),
                        ("--low-confidence", config.low_confidence)):
        if not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError(f"{name} must be finite and between 0 and 1")
    if config.low_confidence >= config.high_confidence:
        raise ValueError("--low-confidence must be less than --high-confidence")
    if not 0 <= config.ring_score_min <= 100_000:
        raise ValueError("--ring-score-min must be between 0 and 100000")
    if not 0 <= config.red_min <= 255:
        raise ValueError("--red-min must be between 0 and 255")
    if not math.isfinite(config.red_dominance) or not 1 <= config.red_dominance <= 4:
        raise ValueError("--red-dominance must be finite and between 1 and 4")
    if not math.isfinite(config.band_ratio) or not 0 <= config.band_ratio <= 0.25:
        raise ValueError("--band-ratio must be finite and between 0 and 0.25")
    if not 1 <= config.min_band <= 128:
        raise ValueError("--min-band must be between 1 and 128 pixels")
    if not 3 <= config.min_sides <= 4:
        raise ValueError("--min-sides must be 3 or 4")
    if not 1 <= config.pixels_per_side <= 128:
        raise ValueError("--pixels-per-side must be between 1 and 128")


def _strict_red_ring_features(
    rgb_image: Any,
    bbox: Sequence[float],
    map_short_side: int,
    config: HybridConfig,
) -> dict[str, Any]:
    """Measure strict-red support around an XYWH box in the source crop.

    Pixel bounds use Python's nearest-even ``round`` and are clamped to the
    image, matching the validated reference evaluation script. The band width
    is based on the direction ROI's short side and capped at half the larger
    box dimension, also matching that reference.
    """
    import numpy as np

    if len(bbox) != 4:
        raise ValueError("bbox must contain x, y, width, and height")
    if map_short_side <= 0:
        raise ValueError("map_short_side must be positive")
    image = np.asarray(rgb_image)
    if image.ndim != 3 or image.shape[2] < 3:
        raise ValueError("rgb_image must be an HxWx3 RGB array")
    height, width = image.shape[:2]
    x, y, box_width, box_height = (float(value) for value in bbox)
    if not all(math.isfinite(value) for value in (x, y, box_width, box_height)):
        raise ValueError("bbox coordinates must be finite")
    x0 = max(0, min(width, round(x)))
    y0 = max(0, min(height, round(y)))
    x1 = max(0, min(width, round(x + box_width)))
    y1 = max(0, min(height, round(y + box_height)))
    if x1 <= x0 or y1 <= y0:
        return {
            "ring_score": 0,
            "ring_valid": False,
            "supported_sides": 0,
            "side_counts": [0, 0, 0, 0],
            "red_pixels": 0,
            "band_width": 0,
        }

    patch = image[y0:y1, x0:x1, :3].astype(np.int16, copy=False)
    red, green, blue = patch[..., 0], patch[..., 1], patch[..., 2]
    red_mask = ((red >= config.red_min) &
                (red >= green * config.red_dominance) &
                (red >= blue * config.red_dominance))
    patch_height, patch_width = red_mask.shape
    band = max(config.min_band, round(map_short_side * config.band_ratio))
    band = min(band, max(1, patch_width // 2, patch_height // 2))
    side_counts = [
        int(red_mask[:, :band].sum()),
        int(red_mask[:, -band:].sum()),
        int(red_mask[:band, :].sum()),
        int(red_mask[-band:, :].sum()),
    ]
    supported = [count >= config.pixels_per_side for count in side_counts]
    supported_sides = sum(supported)
    opposite_pair = ((supported[0] and supported[1]) or
                     (supported[2] and supported[3]))
    red_pixels = int(red_mask.sum())
    ring_score = (red_pixels + 2 * min(side_counts[0], side_counts[1]) +
                  2 * min(side_counts[2], side_counts[3]) +
                  3 * supported_sides)
    return {
        "ring_score": ring_score,
        "ring_valid": supported_sides >= config.min_sides and opposite_pair,
        "supported_sides": supported_sides,
        "side_counts": side_counts,
        "red_pixels": red_pixels,
        "band_width": band,
    }


def _hybrid_accept(confidence: float, features: dict[str, Any],
                   config: HybridConfig) -> bool:
    """Apply high-confidence passthrough and the fixed low-confidence gate."""
    if confidence >= config.high_confidence:
        return True
    if confidence < config.low_confidence:
        return False
    return bool(features["ring_valid"] and
                features["ring_score"] >= config.ring_score_min)


def _metric_summary(
    predictions: dict[int, list[dict[str, Any]]],
    truths: dict[int, list[tuple[int, list[float]]]],
    images: dict[int, dict[str, Any]],
    direction_rois: dict[int, list[float]],
    image_ids: Sequence[int],
    config: HybridConfig,
    iou_threshold: float,
    *,
    hybrid: bool,
    right_only: bool = False,
    class_id: int = 0,
) -> dict[str, Any]:
    tp = fp = fn = predicted_count = truth_count = 0
    for image_id in image_ids:
        image = images[image_id]
        image_width, image_height = int(image["width"]), int(image["height"])
        roi = direction_rois.get(image_id, [0.0, 0.0, 1.0, 1.0])

        def is_right(box: Sequence[float]) -> bool:
            normalized_box = [box[0] / image_width, box[1] / image_height,
                              box[2] / image_width, box[3] / image_height]
            return _direction_state(normalized_box, roi)[0] == "right"

        image_truths = [_truth_entry(item) for item in truths[image_id]]
        candidates = []
        for original in predictions[image_id]:
            confidence, bbox, prediction_class_id = _prediction_entry(original)
            candidate = (dict(original) if isinstance(original, dict) else {})
            candidate.update({"confidence": confidence, "bbox": bbox,
                              "class_id": prediction_class_id})
            # Legacy unit callers supplied only confidence and bbox.  Their
            # hybrid score is intentionally a rejected candidate unless they
            # provide the red-ring evidence explicitly.
            candidate.setdefault("ring_valid", False)
            candidate.setdefault("ring_score", 0)
            candidates.append(candidate)
        # The red-ring cue is an enemy marker. Player detections remain in the
        # model output but are excluded from this enemy-only diagnostic.
        image_truths = [item for item in image_truths if item[0] == class_id]
        candidates = [candidate for candidate in candidates
                      if candidate["class_id"] == class_id]
        if right_only:
            image_truths = [item for item in image_truths if is_right(item[1])]
            candidates = [candidate for candidate in candidates
                          if is_right(candidate["bbox"])]
        if hybrid:
            candidates = [candidate for candidate in candidates if _hybrid_accept(
                candidate["confidence"], candidate, config)]
        else:
            candidates = [candidate for candidate in candidates
                          if candidate["confidence"] >= config.high_confidence]

        boxes = [candidate["bbox"] for candidate in candidates]
        prediction_tuples = [(candidate["confidence"], candidate["bbox"],
                              candidate["class_id"])
                             for candidate in candidates]
        matched = len(_match_class_aware_pairs(
            prediction_tuples, image_truths, iou_threshold,
        ))
        tp += matched
        fp += len(boxes) - matched
        fn += len(image_truths) - matched
        predicted_count += len(boxes)
        truth_count += len(image_truths)

    result = _finish(tp, fp, fn)
    result.update({"predictions": predicted_count, "truth": truth_count})
    return result


def _evaluate_metrics(
    predictions: dict[int, list[dict[str, Any]]],
    truths: dict[int, list[list[float]] | list[tuple[int, list[float]]]],
    images: dict[int, dict[str, Any]],
    direction_rois: dict[int, list[float]],
    config: HybridConfig,
    iou_threshold: float,
    class_id: int = 0,
) -> dict[str, dict[str, dict[str, Any]]]:
    all_ids = list(truths)
    dense_ids = [image_id for image_id in all_ids if sum(
        1 for item in truths[image_id] if _truth_entry(item)[0] == class_id
    ) >= 3]
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for label, hybrid in (("baseline", False), ("hybrid", True)):
        result[label] = {
            "overall": _metric_summary(
                predictions, truths, images, direction_rois, all_ids,
                config, iou_threshold, hybrid=hybrid,
                class_id=class_id,
            ),
            "dense_3plus": _metric_summary(
                predictions, truths, images, direction_rois, dense_ids,
                config, iou_threshold, hybrid=hybrid,
                class_id=class_id,
            ),
            "right_direction": _metric_summary(
                predictions, truths, images, direction_rois, all_ids,
                config, iou_threshold, hybrid=hybrid, right_only=True,
                class_id=class_id,
            ),
        }
    return result


def _read_rgb_image(path: Path) -> Any:
    from PIL import Image

    with Image.open(path) as image:
        return image.convert("RGB").copy()


def _image_map_short_side(
    image: dict[str, Any],
    direction_roi: Sequence[float],
) -> int:
    width, height = int(image["width"]), int(image["height"])
    roi = normalized_roi(direction_roi, "COCO image direction_roi")
    left = round(roi[0] * width)
    top = round(roi[1] * height)
    right = round((roi[0] + roi[2]) * width)
    bottom = round((roi[1] + roi[3]) * height)
    return min(right - left, bottom - top)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True,
                        help="Local YOLOX checkout used for frozen inference")
    parser.add_argument("--data-dir", type=Path, required=True,
                        help="COCO dataset whose images and annotations use safe-ROI crops")
    parser.add_argument("--checkpoint", type=Path, required=True,
                        help="Explicit frozen YOLOX checkpoint; no training or selection runs")
    parser.add_argument("--output", type=Path, required=True,
                        help="JSON report path")
    parser.add_argument("--split", choices=("train", "val", "test"), default="val")
    parser.add_argument("--input-size", type=int, required=True,
                        help="Square YOLOX input size, a multiple of 32 from 64 to 2048")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--classes", nargs="+", metavar="CLASS",
                        help="Explicit canonical class order; otherwise model metadata/COCO is used")
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument("--high-confidence", type=float, default=0.807,
                        help="Baseline passthrough cutoff (default: 0.807)")
    parser.add_argument("--low-confidence", type=float, default=0.45,
                        help="Lowest confidence considered by the ring gate (default: 0.45)")
    parser.add_argument("--ring-score-min", type=int, default=188,
                        help="Minimum strict-red border score for gated candidates")
    parser.add_argument("--red-min", type=int, default=90,
                        help="Minimum red channel value (0-255)")
    parser.add_argument("--red-dominance", type=float, default=1.25,
                        help="Require R >= dominance * G and B")
    parser.add_argument("--band-ratio", type=float, default=0.032,
                        help="Border width as a fraction of direction-ROI short side")
    parser.add_argument("--min-band", type=int, default=2,
                        help="Minimum border width in pixels")
    parser.add_argument("--min-sides", type=int, default=3,
                        help="Require support on 3 or 4 sides, including an opposite pair")
    parser.add_argument("--pixels-per-side", type=int, default=3,
                        help="Minimum strict-red pixels for a side to count as supported")
    return parser


def _config_from_args(args: argparse.Namespace) -> HybridConfig:
    return HybridConfig(
        high_confidence=args.high_confidence,
        low_confidence=args.low_confidence,
        ring_score_min=args.ring_score_min,
        red_min=args.red_min,
        red_dominance=args.red_dominance,
        band_ratio=args.band_ratio,
        min_band=args.min_band,
        min_sides=args.min_sides,
        pixels_per_side=args.pixels_per_side,
    )


def main(argv: Sequence[str] | None = None) -> None:
    parser = _build_parser()
    args = parser.parse_args(argv)
    config = _config_from_args(args)
    try:
        _validate_config(config, input_size=args.input_size,
                         iou_threshold=args.iou_threshold,
                         nms_threshold=args.nms_threshold)
    except ValueError as error:
        parser.error(str(error))

    root = Path(__file__).resolve().parents[1]
    yolox_root = args.yolox_root.resolve()
    data_dir = args.data_dir.resolve()
    checkpoint_path = args.checkpoint.resolve()
    output_path = args.output.resolve()
    try:
        annotation_path, annotation = _read_evaluation_annotations(data_dir, args.split)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        parser.error(str(error))
    if not checkpoint_path.is_file():
        parser.error(f"Frozen checkpoint does not exist: {checkpoint_path}")
    if not yolox_root.is_dir():
        parser.error(f"YOLOX root does not exist: {yolox_root}")

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
    experiment_path = root / "training/yolox_nano_minimap.py"
    experiment = get_exp(str(experiment_path), None)
    experiment.class_names = list(class_names)
    experiment.num_classes = len(class_names)
    experiment.input_size = (args.input_size, args.input_size)
    experiment.test_size = experiment.input_size
    model = load_ckpt(experiment.get_model(),
                      checkpoint.get("model", checkpoint)
                      if isinstance(checkpoint, dict) else checkpoint)
    model.to(device)
    prediction_floor = min(0.001, config.low_confidence)
    raw_predictions, raw_truths = _split_predictions(
        model, device, data_dir, experiment.test_size, args.nms_threshold,
        args.split, pre_filter_confidence=prediction_floor, classes=class_names,
    )

    images = {int(item["id"]): item for item in annotation["images"]}
    truths = {int(image_id): boxes for image_id, boxes in raw_truths.items()}
    direction_rois = _read_image_direction_rois(annotation)
    predictions: dict[int, list[dict[str, Any]]] = {}
    split_name = f"{args.split}2017"
    for image_id, entries in raw_predictions.items():
        image_info = images[image_id]
        image_path = data_dir / split_name / image_info["file_name"]
        try:
            rgb_image = _read_rgb_image(image_path)
        except OSError as error:
            parser.error(f"Cannot read COCO crop {image_path}: {error}")
        expected_size = (int(image_info["width"]), int(image_info["height"]))
        if rgb_image.size != expected_size:
            parser.error(
                f"COCO crop size mismatch for {image_path}: "
                f"annotation says {expected_size}, image is {rgb_image.size}"
            )
        map_short_side = _image_map_short_side(
            image_info,
            direction_rois.get(image_id, [0.0, 0.0, 1.0, 1.0]),
        )
        candidates = []
        for confidence, bbox, class_id in entries:
            features = _strict_red_ring_features(
                rgb_image, bbox, map_short_side, config,
            )
            candidates.append({
                "confidence": float(confidence),
                "bbox": [float(value) for value in bbox],
                "class_id": int(class_id),
                **features,
            })
        predictions[image_id] = candidates

    if "minimap_enemy" not in class_names:
        parser.error("The red-ring hybrid evaluator requires a minimap_enemy class")
    enemy_class_id = class_names.index("minimap_enemy")
    metrics = _evaluate_metrics(
        predictions, truths, images, direction_rois, config, args.iou_threshold,
        class_id=enemy_class_id,
    )
    baseline_metrics = metrics["baseline"]["overall"]
    hybrid_metrics = metrics["hybrid"]["overall"]
    result = {
        "schema_version": 1,
        "design": "fixed_frozen_yolox_red_ring_hybrid_evaluation",
        "warning": (
            "Offline development prototype only. The reference thresholds were explored "
            "on video6 development data; this fixed-rule rerun is not an independent "
            "holdout result. It is not connected to Android, and video9 plus the "
            "independent-match and device gates remain unmet."
        ),
        "threshold_search": "none; one caller-supplied fixed rule was evaluated",
        "split": args.split,
        "device": str(device),
        "input_size": [args.input_size, args.input_size],
        "classes": list(class_names),
        "evaluated_class": "minimap_enemy",
        "inference": {
            "nms_threshold": args.nms_threshold,
            "prediction_floor": prediction_floor,
            "iou_threshold": args.iou_threshold,
        },
        "configuration": asdict(config),
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
            "truth_boxes": sum(len(boxes) for boxes in truths.values()),
            "crop_coordinates": "YOLOX predictions and red-ring pixel crops both use the same COCO safe-ROI image coordinates",
        },
        "source_hashes": {
            "hybrid_evaluator": {
                "path": str(Path(__file__).resolve()),
                "sha256": _sha256(Path(__file__).resolve()),
            },
            "baseline_evaluator": {
                "path": str(root / "training/evaluate_yolox_minimap.py"),
                "sha256": _sha256(root / "training/evaluate_yolox_minimap.py"),
            },
            "prediction_runner": {
                "path": str(root / "training/train_yolox_minimap.py"),
                "sha256": _sha256(root / "training/train_yolox_minimap.py"),
            },
            "model_experiment": {
                "path": str(experiment_path),
                "sha256": _sha256(experiment_path),
            },
            "native_red_pixel_reference": {
                "path": str(root / "native/src/mapassist.cpp"),
                "sha256": _sha256(root / "native/src/mapassist.cpp"),
            },
        },
        "yolox_revision": _git_revision(yolox_root),
        "metrics": metrics,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(json.dumps({
        "output": str(output_path),
        "split": args.split,
        "checkpoint_sha256": result["checkpoint"]["sha256"],
        "annotations_sha256": result["dataset"]["annotations_sha256"],
        "script_sha256": result["source_hashes"]["hybrid_evaluator"]["sha256"],
        "baseline": metrics["baseline"],
        "hybrid": metrics["hybrid"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
