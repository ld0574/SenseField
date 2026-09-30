"""Train and validate YOLOX-Nano on a cropped minimap COCO dataset.

The upstream YOLOX trainer assumes CUDA. This small, single-device runner keeps
the model and data format unchanged while supporting CUDA, Apple MPS, and CPU.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from mapassist.detection_evaluate import _match_boxes
from mapassist.dataset_scope import coco_dataset_scope
from mapassist.roi_safety import (
    assert_coco_boxes_within_images,
    assert_coco_roi_safe,
)

try:  # Supports both package imports and ``python training/...``.
    from .yolox_decode import (
        normalize_classes,
        resolve_classes,
        yolox_tensor_contract,
    )
except ImportError:  # pragma: no cover - command-line execution path.
    from yolox_decode import (  # type: ignore
        normalize_classes,
        resolve_classes,
        yolox_tensor_contract,
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _git_revision(path: Path) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        text=True,
        capture_output=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def _match(predictions: list[list[float]], truths: list[list[float]],
           threshold: float = 0.5) -> int:
    return len(_match_boxes(predictions, truths, threshold))


def _finish(tp: int, fp: int, fn: int) -> dict[str, Any]:
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = (2 * precision * recall / (precision + recall)
          if precision is not None and recall is not None and precision + recall else None)
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(precision, 6) if precision is not None else None,
        "recall": round(recall, 6) if recall is not None else None,
        "f1": round(f1, 6) if f1 is not None else None,
    }


def _metric_value(metric: dict[str, Any], name: str) -> float:
    """Return a sortable metric value, treating an undefined metric as zero."""
    value = metric.get(name)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else 0.0


def _selection_key(metric: dict[str, Any], minimum_precision: float) -> tuple[float, ...]:
    """Return the explicit priority used for thresholds and checkpoints."""
    if _metric_value(metric, "precision") >= minimum_precision:
        return (
            1.0,
            _metric_value(metric, "recall"),
            _metric_value(metric, "f1"),
            _metric_value(metric, "precision"),
        )
    return (0.0, _metric_value(metric, "f1"), _metric_value(metric, "recall"))


def _selection_policy(minimum_precision: float) -> tuple[str, str]:
    """Describe the precision-gated selection policy and its metric ordering."""
    threshold = f"{minimum_precision:g}"
    return (
        f"highest_recall_with_precision_at_least_{threshold}",
        "recall_then_f1_then_precision_if_precision_meets_minimum_else_f1_then_recall",
    )


def _is_classification_head_key(key: str) -> bool:
    """Return whether a state-dict key belongs to the class prediction head.

    A one-class YOLOX checkpoint can be used to initialize a multi-class model:
    the class prediction filters are the only tensors whose shape changes when
    ``num_classes`` changes.  Backbone, box-regression, objectness, and feature
    tower tensors must still match exactly.
    """
    return key.startswith("head.cls_preds.")


def _load_checkpoint_audited(
    model: Any,
    checkpoint_state: Any,
) -> tuple[Any, dict[str, Any]]:
    """Load a checkpoint while recording every key and rejecting unsafe gaps.

    YOLOX's upstream ``load_ckpt`` logs and skips every shape mismatch.  That
    is too permissive for a class-count change because a malformed backbone or
    box/objectness tensor could otherwise be silently left at random
    initialization.  Only ``head.cls_preds.*`` shape mismatches are allowed;
    all other mismatches and missing model keys fail loudly.
    """
    if not isinstance(checkpoint_state, dict):
        raise ValueError("checkpoint model state must be a dictionary")
    model_state = model.state_dict()
    load_dict: dict[str, Any] = {}
    loaded_keys: list[str] = []
    skipped_shape_mismatches: list[dict[str, Any]] = []
    missing_keys: list[str] = []
    unexpected_keys = sorted(key for key in checkpoint_state if key not in model_state)
    unsafe_mismatches: list[dict[str, Any]] = []

    for key, expected in model_state.items():
        if key not in checkpoint_state:
            missing_keys.append(key)
            continue
        value = checkpoint_state[key]
        expected_shape = tuple(expected.shape)
        actual_shape = tuple(value.shape) if hasattr(value, "shape") else None
        if actual_shape != expected_shape:
            mismatch = {
                "key": key,
                "checkpoint_shape": list(actual_shape) if actual_shape is not None else None,
                "model_shape": list(expected_shape),
                "classification_head": _is_classification_head_key(key),
            }
            if _is_classification_head_key(key):
                skipped_shape_mismatches.append(mismatch)
            else:
                unsafe_mismatches.append(mismatch)
            continue
        load_dict[key] = value
        loaded_keys.append(key)

    unsafe_missing = [key for key in missing_keys if not _is_classification_head_key(key)]
    if unsafe_mismatches or unsafe_missing:
        details = {
            "shape_mismatches": unsafe_mismatches,
            "missing_keys": unsafe_missing,
        }
        raise ValueError(
            "checkpoint has unsafe non-classification model differences: "
            f"{json.dumps(details, sort_keys=True)}"
        )

    # strict=False is safe here because the only omitted model keys are the
    # deliberately reinitialized classification filters (and we record them).
    model.load_state_dict(load_dict, strict=False)
    audit = {
        "loaded_keys": sorted(loaded_keys),
        "loaded_count": len(loaded_keys),
        "skipped_shape_mismatches": skipped_shape_mismatches,
        "skipped_keys": sorted(item["key"] for item in skipped_shape_mismatches),
        "skipped_count": len(skipped_shape_mismatches),
        "missing_keys": sorted(missing_keys),
        "unexpected_keys": unexpected_keys,
        "unsafe_mismatches": unsafe_mismatches,
    }
    return model, audit


def _select_validation_metric(
    results: list[dict[str, Any]], minimum_precision: float
) -> tuple[dict[str, Any], str, str]:
    """Select a confidence result without importing torch or YOLOX."""
    _validate_range("minimum-precision", minimum_precision, 0.0, 1.0)
    if not results:
        raise ValueError("validation results must not be empty")
    eligible = [
        item for item in results
        if _metric_value(item, "precision") >= minimum_precision
    ]
    if eligible:
        selected = max(eligible, key=lambda item: _selection_key(item, minimum_precision))
        policy, metric = _selection_policy(minimum_precision)
        return selected, policy, metric
    selected = max(results, key=lambda item: _selection_key(item, minimum_precision))
    threshold = f"{minimum_precision:g}"
    return (
        selected,
        f"highest_f1_no_threshold_reached_{threshold}_precision",
        "f1_then_recall_when_no_precision_threshold_is_reached",
    )


def _is_better_validation_metric(
    candidate: dict[str, Any], current: dict[str, Any] | None, minimum_precision: float
) -> bool:
    """Compare epoch selections using the same ordering as threshold selection."""
    return current is None or _selection_key(candidate, minimum_precision) > _selection_key(
        current, minimum_precision
    )


def _balanced_validation_summary(
    validation: dict[str, Any],
    minimum_precision: float,
    classes: list[str] | tuple[str, ...],
) -> dict[str, Any]:
    """Summarize per-class precision-gated validation selections.

    The aggregate validation selection is dominated by whichever class has the
    most boxes.  For multiclass experiments, this summary evaluates each class
    at its own precision-gated threshold and exposes a class-balanced ordering
    for an additional checkpoint.  The existing aggregate selection remains
    unchanged for backward compatibility.
    """
    _validate_range("minimum-precision", minimum_precision, 0.0, 1.0)
    diagnostics = validation.get("per_class_thresholds")
    if not isinstance(diagnostics, dict):
        raise ValueError("validation must contain per_class_thresholds")
    class_diagnostics = diagnostics.get("classes")
    if not isinstance(class_diagnostics, dict):
        raise ValueError("validation per_class_thresholds must contain classes")
    if not classes:
        raise ValueError("classes must not be empty")

    per_class: dict[str, Any] = {}
    selected_confidence_by_class: dict[str, float] = {}
    recalls: list[float] = []
    f1s: list[float] = []
    eligible_class_count = 0
    for class_id, class_name in enumerate(classes):
        diagnostic = class_diagnostics.get(class_name)
        if not isinstance(diagnostic, dict):
            raise ValueError(f"missing per-class validation diagnostics for {class_name}")
        selected = diagnostic.get("selected")
        if not isinstance(selected, dict):
            raise ValueError(f"missing selected validation metric for {class_name}")
        confidence = selected.get("confidence")
        if (not isinstance(confidence, (int, float)) or isinstance(confidence, bool) or
                not math.isfinite(float(confidence))):
            raise ValueError(f"invalid selected confidence for {class_name}")
        precision = _metric_value(selected, "precision")
        recall = _metric_value(selected, "recall")
        f1 = _metric_value(selected, "f1")
        precision_eligible = precision >= minimum_precision
        if precision_eligible:
            eligible_class_count += 1
        selected_confidence_by_class[class_name] = float(confidence)
        recalls.append(recall)
        f1s.append(f1)
        per_class[class_name] = {
            "class_id": class_id,
            "confidence": float(confidence),
            "precision_eligible": precision_eligible,
            "minimum_precision": minimum_precision,
            "selected": selected,
        }

    minimum_recall = min(recalls)
    mean_recall = sum(recalls) / len(recalls)
    mean_f1 = sum(f1s) / len(f1s)
    return {
        "minimum_precision": minimum_precision,
        "selection_policy": (
            "maximize_precision_eligible_class_count_then_minimum_recall_"
            "then_mean_recall_then_mean_f1"
        ),
        "selection_metric": (
            "eligible_class_count_then_minimum_recall_then_mean_recall_then_mean_f1"
        ),
        "eligible_class_count": eligible_class_count,
        "class_count": len(classes),
        "minimum_recall": round(minimum_recall, 6),
        "mean_recall": round(mean_recall, 6),
        "mean_f1": round(mean_f1, 6),
        "selected_confidence_by_class": selected_confidence_by_class,
        "per_class": per_class,
    }


def _balanced_selection_key(summary: dict[str, Any]) -> tuple[float, ...]:
    """Return the multiclass checkpoint ordering for a balanced summary."""
    return (
        float(summary.get("eligible_class_count", 0)),
        float(summary.get("minimum_recall", 0.0)),
        float(summary.get("mean_recall", 0.0)),
        float(summary.get("mean_f1", 0.0)),
    )


def _is_better_balanced_selection(
    candidate: dict[str, Any], current: dict[str, Any] | None
) -> bool:
    """Compare multiclass balanced summaries, retaining the first exact tie."""
    return current is None or _balanced_selection_key(candidate) > _balanced_selection_key(current)


def _validate_range(name: str, value: float, lower: float, upper: float | None = None) -> None:
    if not math.isfinite(value) or value < lower or (upper is not None and value > upper):
        limit = f"{lower:g}..{upper:g}" if upper is not None else f">={lower:g}"
        raise ValueError(f"{name} must be finite and in {limit}")


def _validate_training_args(args: argparse.Namespace) -> None:
    """Validate all CLI ranges before importing the heavyweight training stack."""
    if (args.epochs < 1 or args.batch_size < 1 or args.eval_every < 1 or
            args.log_every < 1 or args.input_size < 64 or args.lr_scale <= 0 or
            args.input_size % 32 != 0 or args.early_stop_patience < 0 or
            args.early_stop_min_epoch < 0):
        raise ValueError("counts, input size, and lr-scale must be positive")
    if args.early_stop_min_epoch > args.epochs:
        raise ValueError("--early-stop-min-epoch cannot exceed --epochs")
    for name in ("mosaic_prob", "flip_prob", "hsv_prob"):
        _validate_range(name.replace("_", "-"), getattr(args, name), 0.0, 1.0)
    _validate_range("degrees", args.degrees, 0.0, 180.0)
    _validate_range("translate", args.translate, 0.0, 1.0)
    _validate_range("shear", args.shear, 0.0, 180.0)
    _validate_range("mosaic-scale-min", args.mosaic_scale_min, 0.0)
    _validate_range("mosaic-scale-max", args.mosaic_scale_max, 0.0)
    if args.mosaic_scale_min == 0 or args.mosaic_scale_max == 0:
        raise ValueError("mosaic scale values must be greater than zero")
    if args.mosaic_scale_min > args.mosaic_scale_max:
        raise ValueError("mosaic-scale-min cannot exceed mosaic-scale-max")
    _validate_range("nms-threshold", args.nms_threshold, 0.0, 1.0)
    _validate_range("minimum-precision", args.minimum_precision, 0.0, 1.0)
    if args.no_aug_epochs is not None and not 0 <= args.no_aug_epochs < args.epochs:
        raise ValueError("no-aug-epochs must be between 0 and epochs - 1")


def _assert_roi_boundaries_clear(data_dir: Path) -> None:
    """Block training when train/val targets may have been cut by the crop."""
    for split in ("train", "val"):
        annotation_path = data_dir / "annotations" / f"instances_{split}2017.json"
        if not annotation_path.is_file():
            raise ValueError(f"Missing {split} annotations: {annotation_path}")
        document = json.loads(annotation_path.read_text(encoding="utf-8"))
        scope = coco_dataset_scope(document, f"{split} COCO")
        if isinstance(scope, dict) and scope.get("training_truth") is False:
            raise ValueError(
                f"{split} COCO dataset_scope.training_truth=false; "
                "diagnostic-only data cannot be used for training"
            )
        assert_coco_roi_safe(document, split)
        assert_coco_boxes_within_images(document, split)


def _validate_canonical_split_classes(
    data_dir: Path,
    classes: list[str] | tuple[str, ...],
    splits: tuple[str, ...] = ("train", "val", "test"),
) -> None:
    """Reject split category orders that cannot share one model output mapping."""
    for split in splits:
        path = data_dir / "annotations" / f"instances_{split}2017.json"
        if not path.is_file():
            continue
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
            resolve_classes(classes=classes, coco=document)
        except (OSError, json.JSONDecodeError, ValueError) as error:
            raise ValueError(f"{split} COCO classes do not match canonical order: {error}") from error


def _device(torch: Any, value: str) -> Any:
    if value != "auto":
        return torch.device(value)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def _match_class_aware(predictions: list[tuple[float, list[float], int]],
                       truths: list[tuple[int, list[float]]],
                       threshold: float = 0.5) -> int:
    """Match only predictions and truths with the same canonical class."""
    return len(_match_class_aware_pairs(predictions, truths, threshold))


def _match_class_aware_pairs(
    predictions: list[tuple[float, list[float], int]],
    truths: list[tuple[int, list[float]]],
    threshold: float = 0.5,
) -> list[tuple[int, int, float]]:
    """Return global prediction/truth indices for same-class matches."""
    pairs: list[tuple[int, int, float]] = []
    for class_id in sorted({item[2] for item in predictions} |
                           {item[0] for item in truths}):
        prediction_indices = [index for index, item in enumerate(predictions)
                              if item[2] == class_id]
        truth_indices = [index for index, item in enumerate(truths)
                         if item[0] == class_id]
        local = _match_boxes(
            [predictions[index][1] for index in prediction_indices],
            [truths[index][1] for index in truth_indices], threshold,
        )
        pairs.extend(
            (prediction_indices[prediction], truth_indices[truth], overlap)
            for prediction, truth, overlap in local
        )
    return sorted(pairs)


def _split_predictions(model: Any, device: Any, data_dir: Path,
                       input_size: tuple[int, int], nms_threshold: float,
                       split: str = "val", pre_filter_confidence: float = 0.01,
                       classes: list[str] | tuple[str, ...] | None = None
                       ) -> tuple[dict[int, list[tuple[float, list[float], int]]],
                                  dict[int, list[tuple[int, list[float]]]]]:
    import cv2
    import torch
    from yolox.data import ValTransform
    from yolox.utils import postprocess

    if split not in {"train", "val", "test"}:
        raise ValueError(f"Unsupported COCO split: {split}")
    if not 0 <= pre_filter_confidence <= 1:
        raise ValueError("pre_filter_confidence must be between 0 and 1")
    split_name = f"{split}2017"
    annotation = json.loads(
        (data_dir / "annotations" / f"instances_{split_name}.json").read_text(
            encoding="utf-8"
        )
    )
    class_names = resolve_classes(classes=classes, coco=annotation)
    truths: dict[int, list[tuple[int, list[float]]]] = {
        image["id"]: [] for image in annotation["images"]
    }
    category_ids = {
        int(item["id"]): class_names.index(item["name"].strip())
        for item in annotation.get("categories", [])
    }
    for item in annotation["annotations"]:
        # Datasets written by older geometry-only fixtures have no categories;
        # they remain the historical single enemy class.
        category_index = category_ids.get(int(item.get("category_id", 1)), 0)
        truths[item["image_id"]].append(
            (category_index, [float(value) for value in item["bbox"]])
        )
    transform = ValTransform(legacy=False)
    predictions: dict[int, list[tuple[float, list[float], int]]] = {}
    model.eval()
    with torch.inference_mode():
        for image_info in annotation["images"]:
            image = cv2.imread(str(data_dir / split_name / image_info["file_name"]))
            if image is None:
                raise FileNotFoundError(image_info["file_name"])
            height, width = image.shape[:2]
            ratio = min(input_size[0] / height, input_size[1] / width)
            transformed, _ = transform(image, None, input_size)
            tensor = torch.from_numpy(transformed).unsqueeze(0).float().to(device)
            raw = model(tensor).detach().cpu()
            detected = postprocess(
                raw, len(class_names), conf_thre=pre_filter_confidence,
                nms_thre=nms_threshold, class_agnostic=False,
            )[0]
            found: list[tuple[float, list[float], int]] = []
            if detected is not None:
                for row in detected.tolist():
                    x0, y0, x1, y1, objectness, class_confidence, class_id = row
                    found.append((
                        float(objectness * class_confidence),
                        [x0 / ratio, y0 / ratio,
                         (x1 - x0) / ratio, (y1 - y0) / ratio],
                        int(class_id),
                    ))
            predictions[image_info["id"]] = found
    return predictions, truths


def _validation_predictions(model: Any, device: Any, data_dir: Path,
                            input_size: tuple[int, int], nms_threshold: float,
                            classes: list[str] | tuple[str, ...] | None = None
                            ) -> tuple[dict[int, list[tuple[float, list[float], int]]],
                                       dict[int, list[tuple[int, list[float]]]]]:
    return _split_predictions(model, device, data_dir, input_size, nms_threshold, "val",
                              classes=classes)


def _threshold_metric(
    predictions: dict[int, list[tuple[float, list[float], int]]],
    truths: dict[int, list[tuple[int, list[float]]]],
    threshold: float,
    class_id: int | None = None,
) -> dict[str, Any]:
    """Compute class-aware counts at one global or one-class threshold."""
    tp = fp = fn = 0
    for image_id, ground_truth in truths.items():
        candidates = [
            item for item in predictions[image_id]
            if item[0] >= threshold and (class_id is None or item[2] == class_id)
        ]
        expected = (ground_truth if class_id is None else
                    [item for item in ground_truth if item[0] == class_id])
        matched = _match_class_aware(candidates, expected)
        tp += matched
        fp += len(candidates) - matched
        fn += len(expected) - matched
    return _finish(tp, fp, fn)


def _per_class_threshold_diagnostics(
    predictions: dict[int, list[tuple[float, list[float], int]]],
    truths: dict[int, list[tuple[int, list[float]]]],
    classes: tuple[str, ...],
    thresholds: list[float],
    minimum_precision: float,
) -> dict[str, Any]:
    """Select and report independent fixed thresholds for each class.

    These are diagnostics over the same development validation split used for
    checkpoint selection.  They are intentionally recorded alongside the
    aggregate curve and are never presented as an independent test result.
    """
    per_class: dict[str, Any] = {}
    selected_by_class: dict[str, float] = {}
    for class_id, class_name in enumerate(classes):
        curve = [
            {"confidence": threshold,
             **_threshold_metric(predictions, truths, threshold, class_id)}
            for threshold in thresholds
        ]
        selected, policy, metric = _select_validation_metric(curve, minimum_precision)
        selected_by_class[class_name] = float(selected["confidence"])
        per_class[class_name] = {
            "class_id": class_id,
            "minimum_precision": minimum_precision,
            "selection_policy": policy,
            "selection_metric": metric,
            "selected": selected,
            "thresholds": curve,
        }
    return {
        "minimum_precision": minimum_precision,
        "selected_confidence_by_class": selected_by_class,
        "classes": per_class,
    }


def evaluate(model: Any, device: Any, data_dir: Path,
             input_size: tuple[int, int], nms_threshold: float,
             minimum_precision: float = 0.9,
             classes: list[str] | tuple[str, ...] | None = None) -> dict[str, Any]:
    predictions, truths = _validation_predictions(
        model, device, data_dir, input_size, nms_threshold, classes
    )
    thresholds = [round(index / 100, 2) for index in range(1, 96, 2)]
    results = []
    for threshold in thresholds:
        result = {"confidence": threshold,
                  **_threshold_metric(predictions, truths, threshold)}
        results.append(result)
    _validate_range("minimum-precision", minimum_precision, 0.0, 1.0)
    selected, policy, metric = _select_validation_metric(results, minimum_precision)
    class_names = tuple(classes) if classes is not None else tuple(
        resolve_classes(coco=json.loads(
            (data_dir / "annotations" / "instances_val2017.json").read_text(
                encoding="utf-8"
            )
        ))
    )
    per_class = _per_class_threshold_diagnostics(
        predictions, truths, class_names, thresholds, minimum_precision,
    )
    selected_confidence = float(selected["confidence"])
    return {
        "iou_threshold": 0.5,
        "minimum_precision": minimum_precision,
        "selection_policy": policy,
        "selection_metric": metric,
        "selected": selected,
        "thresholds": results,
        "selected_confidence_by_class": {
            name: selected_confidence for name in class_names
        },
        "selected_per_class": {
            name: _threshold_metric(predictions, truths, selected_confidence, class_id)
            for class_id, name in enumerate(class_names)
        },
        "per_class_thresholds": per_class,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pretrained", type=Path)
    parser.add_argument("--classes", nargs="+", metavar="CLASS",
                        help="Explicit class order; otherwise derive from COCO categories")
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--input-size", type=int, default=256)
    parser.add_argument("--mosaic-prob", type=float, default=0.5)
    parser.add_argument("--mosaic-scale-min", type=float, default=0.7)
    parser.add_argument("--mosaic-scale-max", type=float, default=1.3)
    parser.add_argument("--hsv-prob", type=float, default=0.8)
    parser.add_argument("--flip-prob", type=float, default=0.5)
    parser.add_argument("--degrees", type=float, default=5.0)
    parser.add_argument("--translate", type=float, default=0.08)
    parser.add_argument("--shear", type=float, default=1.0)
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument("--minimum-precision", type=float, default=0.9)
    parser.add_argument("--no-aug-epochs", type=int)
    parser.add_argument(
        "--lr-scale", type=float, default=1.0,
        help="Multiply YOLOX's batch-scaled learning rate; use values such as 0.1 for fine-tuning",
    )
    parser.add_argument("--eval-every", type=int, default=5)
    parser.add_argument("--log-every", type=int, default=5)
    parser.add_argument(
        "--early-stop-patience", type=int, default=0,
        help="Stop after this many validation checkpoints without a better selected metric; 0 disables",
    )
    parser.add_argument(
        "--early-stop-min-epoch", type=int, default=0,
        help="Do not early-stop before this epoch",
    )
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--seed", type=int, default=20260926)
    args = parser.parse_args()
    try:
        _validate_training_args(args)
    except ValueError as error:
        parser.error(str(error))

    root = Path(__file__).resolve().parents[1]
    yolox_root = args.yolox_root.resolve()
    data_dir = args.data_dir.resolve()
    output = args.output.resolve()
    try:
        _assert_roi_boundaries_clear(data_dir)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        parser.error(str(error))
    sys.path.insert(0, str(yolox_root))
    os.environ["MAPASSIST_COCO_DIR"] = str(data_dir)

    import numpy as np
    import torch
    from yolox.exp import get_exp
    from yolox.utils import ModelEMA

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    cuda_seeded = bool(torch.cuda.is_available())
    if cuda_seeded:
        torch.cuda.manual_seed_all(args.seed)
    device = _device(torch, args.device)
    mps_seeded = False
    if (device.type == "mps" and hasattr(torch, "mps") and
            hasattr(torch.mps, "manual_seed")):
        torch.mps.manual_seed(args.seed)
        mps_seeded = True
    exp_path = root / "training/yolox_nano_minimap.py"
    exp = get_exp(str(exp_path), None)
    try:
        train_annotation_path = data_dir / "annotations/instances_train2017.json"
        train_document = json.loads(train_annotation_path.read_text(encoding="utf-8"))
        class_names = resolve_classes(
            classes=args.classes, coco=train_document,
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    try:
        _validate_canonical_split_classes(data_dir, class_names)
    except ValueError as error:
        parser.error(str(error))
    exp.class_names = list(normalize_classes(class_names))
    exp.num_classes = len(exp.class_names)
    exp.max_epoch = args.epochs
    exp.input_size = (args.input_size, args.input_size)
    exp.test_size = exp.input_size
    exp.random_size = (args.input_size // 32, args.input_size // 32)
    exp.mosaic_prob = args.mosaic_prob
    exp.mosaic_scale = (args.mosaic_scale_min, args.mosaic_scale_max)
    exp.hsv_prob = args.hsv_prob
    exp.flip_prob = args.flip_prob
    exp.degrees = args.degrees
    exp.translate = args.translate
    exp.shear = args.shear
    exp.nmsthre = args.nms_threshold
    exp.seed = args.seed
    exp.basic_lr_per_img *= args.lr_scale
    exp.no_aug_epochs = (args.no_aug_epochs if args.no_aug_epochs is not None else
                         min(exp.no_aug_epochs, max(1, args.epochs // 5)))
    exp.warmup_epochs = min(exp.warmup_epochs, max(0, args.epochs // 10))

    model = exp.get_model()
    checkpoint_load: dict[str, Any] = {
        "source": None,
        "loaded_keys": [],
        "loaded_count": 0,
        "skipped_shape_mismatches": [],
        "skipped_keys": [],
        "skipped_count": 0,
        "missing_keys": [],
        "unexpected_keys": [],
        "unsafe_mismatches": [],
    }
    if args.pretrained is not None:
        checkpoint = torch.load(args.pretrained, map_location="cpu", weights_only=False)
        model, checkpoint_load = _load_checkpoint_audited(
            model, checkpoint.get("model", checkpoint)
        )
        checkpoint_load["source"] = str(args.pretrained.resolve())
    model.to(device)
    optimizer = exp.get_optimizer(args.batch_size)
    cache_type = None
    if not args.no_cache:
        exp.dataset = exp.get_dataset(cache=True, cache_type="ram")
        cache_type = "ram"
    train_loader = exp.get_data_loader(
        args.batch_size, is_distributed=False, no_aug=False, cache_img=cache_type
    )
    iterations_per_epoch = len(train_loader)
    scheduler = exp.get_lr_scheduler(
        exp.basic_lr_per_img * args.batch_size, iterations_per_epoch
    )
    ema = ModelEMA(model, 0.9998)
    output.mkdir(parents=True, exist_ok=True)
    tensor_contract = yolox_tensor_contract(args.input_size, exp.class_names)

    metadata = {
        "schema_version": 1,
        "design": "development_training",
        "warning": (
            "Development train/validation data were used for model and threshold selection; "
            "metrics are not independent holdout results."
        ),
        "device": str(device),
        "seed": args.seed,
        "seed_provenance": {
            "python_random_seed": args.seed,
            "numpy_random_seed": args.seed,
            "torch_manual_seed": args.seed,
            "torch_cuda_manual_seed_all": args.seed if cuda_seeded else None,
            "torch_mps_manual_seed": args.seed if mps_seeded else None,
        },
        "determinism": {
            "torch_deterministic_algorithms": bool(
                getattr(torch, "are_deterministic_algorithms_enabled", lambda: False)()
            ),
            "cudnn_deterministic": bool(torch.backends.cudnn.deterministic),
            "cudnn_benchmark": bool(torch.backends.cudnn.benchmark),
            "forced": False,
        },
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "input_size": list(exp.input_size),
        "input": tensor_contract["input"],
        "classes": list(exp.class_names),
        "output_width": 5 + exp.num_classes,
        "output": tensor_contract["output"],
        "mosaic_prob": exp.mosaic_prob,
        "augmentation": {
            "mosaic_prob": exp.mosaic_prob,
            "mosaic_scale": list(exp.mosaic_scale),
            "hsv_prob": exp.hsv_prob,
            "flip_prob": exp.flip_prob,
            "degrees": exp.degrees,
            "translate": exp.translate,
            "shear": exp.shear,
        },
        "nms_threshold": exp.nmsthre,
        "postprocess": {
            "confidence": None,
            "confidence_by_class": None,
            "nms_iou": exp.nmsthre,
            "strides": [8, 16, 32],
        },
        "minimum_precision": args.minimum_precision,
        "selection": {
            "minimum_precision": args.minimum_precision,
            "policy": (
                "precision-gated recall, then f1, then precision; "
                "otherwise f1, then recall"
            ),
            "metric": (
                "recall_then_f1_then_precision_if_precision_meets_minimum_else_f1_then_recall"
            ),
        },
        "balanced_selection": {
            "enabled": len(exp.class_names) > 1,
            "minimum_precision": args.minimum_precision,
            "policy": (
                "maximize_precision_eligible_class_count_then_minimum_recall_"
                "then_mean_recall_then_mean_f1"
            ),
            "metric": (
                "eligible_class_count_then_minimum_recall_then_mean_recall_then_mean_f1"
            ),
            "best_epoch": None,
            "best_key": None,
            "eligible_class_count": None,
            "class_count": len(exp.class_names),
            "minimum_recall": None,
            "mean_recall": None,
            "mean_f1": None,
            "selected_confidence_by_class": None,
            "per_class": None,
        },
        "lr_scale": args.lr_scale,
        "basic_lr_per_img": exp.basic_lr_per_img,
        "no_aug_epochs": exp.no_aug_epochs,
        "early_stopping": {
            "patience_checkpoints": args.early_stop_patience,
            "minimum_epoch": args.early_stop_min_epoch,
            "selection_metric": "validation selection policy metric",
        },
        "yolox_revision": _git_revision(yolox_root),
        "provenance": {
            "trainer": {
                "path": str(Path(__file__).resolve()),
                "sha256": _sha256(Path(__file__).resolve()),
            },
        },
        "experiment": {"path": str(exp_path), "sha256": _sha256(exp_path)},
        "train_annotations_sha256": _sha256(
            data_dir / "annotations/instances_train2017.json"
        ),
        "val_annotations_sha256": _sha256(
            data_dir / "annotations/instances_val2017.json"
        ),
        "pretrained": ({"path": str(args.pretrained.resolve()),
                        "sha256": _sha256(args.pretrained.resolve())}
                       if args.pretrained is not None else None),
        "checkpoint_load": checkpoint_load,
        "history": [],
    }
    best_selected: dict[str, Any] | None = None
    best_epoch = None
    best_balanced: dict[str, Any] | None = None
    best_balanced_epoch = None
    evaluations_without_improvement = 0
    stopped_early = False
    no_aug = False
    started = time.monotonic()
    for epoch in range(args.epochs):
        if not no_aug and epoch >= args.epochs - exp.no_aug_epochs:
            train_loader.close_mosaic()
            model.head.use_l1 = True
            no_aug = True
        model.train()
        sums: dict[str, float] = {}
        train_iterator = iter(train_loader)
        for iteration in range(iterations_per_epoch):
            inputs, targets, _, _ = next(train_iterator)
            iteration_started = time.monotonic()
            inputs = inputs.float().to(device)
            targets = targets.float().to(device)
            outputs = model(inputs, targets)
            optimizer.zero_grad(set_to_none=True)
            outputs["total_loss"].backward()
            optimizer.step()
            ema.update(model)
            learning_rate = scheduler.update_lr(
                epoch * iterations_per_epoch + iteration + 1
            )
            for group in optimizer.param_groups:
                group["lr"] = learning_rate
            for key, value in outputs.items():
                if torch.is_tensor(value):
                    sums[key] = sums.get(key, 0.0) + float(value.detach().cpu())
            if ((iteration + 1) % args.log_every == 0 or
                    iteration + 1 == iterations_per_epoch):
                print(
                    f"epoch {epoch + 1}/{args.epochs} iteration "
                    f"{iteration + 1}/{iterations_per_epoch} "
                    f"loss={float(outputs['total_loss'].detach().cpu()):.4f} "
                    f"step={time.monotonic() - iteration_started:.2f}s",
                    flush=True,
                )
        record: dict[str, Any] = {
            "epoch": epoch + 1,
            "loss": {key: round(value / iterations_per_epoch, 6)
                     for key, value in sums.items()},
            "learning_rate": learning_rate,
            "elapsed_seconds": round(time.monotonic() - started, 3),
        }
        should_evaluate = ((epoch + 1) % args.eval_every == 0 or
                           epoch + 1 == args.epochs or no_aug and epoch + 1 == args.epochs - exp.no_aug_epochs)
        if should_evaluate:
            validation = evaluate(
                ema.ema, device, data_dir, exp.test_size, exp.nmsthre,
                args.minimum_precision, exp.class_names,
            )
            record["validation"] = validation
            if len(exp.class_names) > 1:
                balanced = _balanced_validation_summary(
                    validation, args.minimum_precision, exp.class_names,
                )
                validation["balanced_selection"] = balanced
                if _is_better_balanced_selection(balanced, best_balanced):
                    best_balanced = balanced
                    best_balanced_epoch = epoch + 1
                    torch.save(
                        {
                            "model": ema.ema.state_dict(),
                            "epoch": epoch + 1,
                            "validation": validation,
                            "balanced_selection": balanced,
                        },
                        output / "best_balanced_ckpt.pth",
                    )
                    metadata["balanced_selection"].update({
                        "best_epoch": best_balanced_epoch,
                        "best_key": list(_balanced_selection_key(balanced)),
                        "eligible_class_count": balanced["eligible_class_count"],
                        "minimum_recall": balanced["minimum_recall"],
                        "mean_recall": balanced["mean_recall"],
                        "mean_f1": balanced["mean_f1"],
                        "selected_confidence_by_class": balanced[
                            "selected_confidence_by_class"
                        ],
                        "per_class": balanced["per_class"],
                    })
            selected = validation["selected"]
            if _is_better_validation_metric(
                selected, best_selected, args.minimum_precision
            ):
                best_selected = selected
                best_epoch = epoch + 1
                evaluations_without_improvement = 0
                torch.save({"model": ema.ema.state_dict(), "epoch": epoch + 1,
                            "validation": validation}, output / "best_ckpt.pth")
            else:
                evaluations_without_improvement += 1
        metadata["history"].append(record)
        torch.save({"model": ema.ema.state_dict(), "optimizer": optimizer.state_dict(),
                    "epoch": epoch + 1}, output / "latest_ckpt.pth")
        (output / "metrics.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        validation_text = (f" val={record['validation']['selected']}"
                           if "validation" in record else "")
        print(f"epoch {epoch + 1}/{args.epochs} loss={record['loss']['total_loss']:.4f}"
              f" elapsed={record['elapsed_seconds']:.1f}s{validation_text}", flush=True)
        if ("validation" in record and args.early_stop_patience and
                epoch + 1 >= args.early_stop_min_epoch and
                evaluations_without_improvement >= args.early_stop_patience):
            stopped_early = True
            print(
                f"early stop after epoch {epoch + 1}: no validation improvement for "
                f"{evaluations_without_improvement} checkpoints",
                flush=True,
            )
            break

    best_f1 = _metric_value(best_selected, "f1") if best_selected is not None else -1.0
    metadata["best_validation_f1"] = round(best_f1, 6)
    metadata["best_validation_metric"] = best_selected
    best_validation = next(
        (record.get("validation") for record in metadata["history"]
         if record.get("epoch") == best_epoch and "validation" in record),
        None,
    )
    metadata["best_validation_selected_per_class"] = (
        best_validation.get("selected_per_class")
        if isinstance(best_validation, dict) else None
    )
    metadata["best_validation_per_class_thresholds"] = (
        best_validation.get("per_class_thresholds")
        if isinstance(best_validation, dict) else None
    )
    metadata["best_balanced_epoch"] = best_balanced_epoch
    metadata["best_balanced_validation_metric"] = best_balanced
    if best_balanced is not None:
        metadata["balanced_selection"].update({
            "best_epoch": best_balanced_epoch,
            "best_key": list(_balanced_selection_key(best_balanced)),
            "eligible_class_count": best_balanced["eligible_class_count"],
            "minimum_recall": best_balanced["minimum_recall"],
            "mean_recall": best_balanced["mean_recall"],
            "mean_f1": best_balanced["mean_f1"],
            "selected_confidence_by_class": best_balanced[
                "selected_confidence_by_class"
            ],
            "per_class": best_balanced["per_class"],
        })
        metadata["best_balanced_validation_metric"] = metadata["balanced_selection"]
    if best_selected is not None:
        selected_confidence = float(best_selected["confidence"])
        metadata["postprocess"]["confidence"] = selected_confidence
        metadata["postprocess"]["confidence_by_class"] = {
            name: selected_confidence for name in exp.class_names
        }
    metadata["best_epoch"] = best_epoch
    metadata["completed_epochs"] = len(metadata["history"])
    metadata["stopped_early"] = stopped_early
    metadata["elapsed_seconds"] = round(time.monotonic() - started, 3)
    (output / "metrics.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({
        "output": str(output),
        "best_epoch": best_epoch,
        "best_validation_metric": best_selected,
        "best_balanced_epoch": best_balanced_epoch,
        "best_balanced_selection": best_balanced,
        "elapsed_seconds": metadata["elapsed_seconds"],
    }, indent=2))


if __name__ == "__main__":
    main()
