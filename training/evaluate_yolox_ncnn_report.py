"""Score detections recorded by verify_yolox_ncnn.py against the fixed COCO val set.

This evaluates the Android-equivalent ncnn detections at the confidence and NMS
threshold already recorded in the parity report. It never runs a model or
searches thresholds, and it is intended only for development validation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

try:
    from .yolox_decode import classes_from_coco, confidence_thresholds
except ImportError:  # pragma: no cover - direct command-line execution.
    from yolox_decode import classes_from_coco, confidence_thresholds  # type: ignore


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _finish(tp: int, fp: int, fn: int) -> dict[str, int | float | None]:
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall
        else None
    )
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(precision, 6) if precision is not None else None,
        "recall": round(recall, 6) if recall is not None else None,
        "f1": round(f1, 6) if f1 is not None else None,
    }


def _parse_runtime_boxes(value: object, image: str) -> list[tuple[float, list[float], int]]:
    if not isinstance(value, list):
        raise ValueError(f"runtime detections must be a list for {image}")
    result = []
    for item in value:
        if (
            not isinstance(item, list)
            or len(item) not in {5, 6}
            or any(
                not isinstance(number, (int, float))
                or isinstance(number, bool)
                or not math.isfinite(number)
                for number in item
            )
        ):
            raise ValueError(f"invalid runtime detection in {image}: {item!r}")
        x0, y0, x1, y1, score = map(float, item[:5])
        class_id = int(item[5]) if len(item) == 6 else 0
        if len(item) == 6 and (float(item[5]) != class_id or class_id < 0):
            raise ValueError(f"runtime detection has invalid class index in {image}: {item!r}")
        if x0 < 0 or y0 < 0 or x1 <= x0 or y1 <= y0 or not 0 <= score <= 1:
            raise ValueError(f"runtime detection is outside valid bounds in {image}: {item!r}")
        # The verifier stores XYXY in source-image pixels; COCO and the project
        # matcher use XYWH.
        result.append((score, [x0, y0, x1 - x0, y1 - y0], class_id))
    return result


def _match_class_aware(predictions, truths, threshold: float, matcher):
    pairs = []
    for class_id in sorted({entry[2] for entry in predictions} |
                            {entry[0] for entry in truths}):
        prediction_indices = [index for index, entry in enumerate(predictions)
                              if entry[2] == class_id]
        truth_indices = [index for index, entry in enumerate(truths)
                         if entry[0] == class_id]
        local = matcher(
            [predictions[index][1] for index in prediction_indices],
            [truths[index][1] for index in truth_indices], threshold,
        )
        pairs.extend((prediction_indices[prediction], truth_indices[truth], overlap)
                     for prediction, truth, overlap in local)
    return pairs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--parity-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    args = parser.parse_args()
    if not 0 < args.iou_threshold <= 1:
        parser.error("--iou-threshold must be between 0 and 1")

    repo_root = Path(__file__).resolve().parents[1]
    sys.path.insert(0, str(repo_root / "python"))
    from mapassist.detection_evaluate import _match_boxes
    from mapassist.roi_safety import assert_coco_boxes_within_images, assert_coco_roi_safe

    data_dir = args.data_dir.expanduser().resolve()
    annotation_path = data_dir / "annotations" / "instances_val2017.json"
    report_path = args.parity_report.expanduser().resolve()
    annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert_coco_roi_safe(annotation, "val")
    assert_coco_boxes_within_images(annotation, "val")

    if report.get("comparison") != "torchscript_vs_ncnn_conversion_and_runtime_preprocess":
        raise ValueError("parity report is not a TorchScript/ncnn runtime-preprocess report")
    gates = report.get("gates", {})
    runtime_gate = gates.get("runtime_preprocess_and_detections", {})
    confidence = runtime_gate.get("confidence")
    confidence_by_class = runtime_gate.get("confidence_by_class")
    nms_threshold = runtime_gate.get("nms_threshold")
    if confidence_by_class is None and (
        not isinstance(confidence, (int, float)) or isinstance(confidence, bool)
    ):
        raise ValueError("parity report has no valid fixed confidence")
    if not isinstance(nms_threshold, (int, float)) or isinstance(nms_threshold, bool):
        raise ValueError("parity report has no valid fixed NMS threshold")

    images = annotation.get("images")
    annotations = annotation.get("annotations")
    results = report.get("results")
    if not isinstance(images, list) or not images or not isinstance(annotations, list):
        raise ValueError("COCO validation annotations are empty or invalid")
    if not isinstance(results, list) or len(results) != len(images):
        raise ValueError("parity report must contain one result for every COCO val image")

    class_names = classes_from_coco(annotation)
    confidence_by_class = confidence_thresholds(
        confidence_by_class if confidence_by_class is not None else confidence,
        class_names,
    )
    category_ids = {
        int(item["id"]): index
        for index, item in enumerate(sorted(annotation.get("categories", []),
                                            key=lambda value: int(value["id"])))
    }
    truths: dict[int, list[tuple[int, list[float]]]] = {
        int(image["id"]): [] for image in images
    }
    for item in annotations:
        bbox = item.get("bbox")
        if not isinstance(bbox, list) or len(bbox) != 4:
            raise ValueError(f"invalid COCO bbox: {item!r}")
        x, y, width, height = map(float, bbox)
        if not all(math.isfinite(value) for value in (x, y, width, height)):
            raise ValueError(f"non-finite COCO bbox: {item!r}")
        if width <= 0 or height <= 0:
            raise ValueError(f"non-positive COCO bbox: {item!r}")
        truths[int(item["image_id"])].append((
            category_ids.get(int(item.get("category_id", 1)), 0),
            [x, y, width, height],
        ))

    per_image = []
    per_class_totals = {
        name: {"tp": 0, "fp": 0, "fn": 0} for name in class_names
    }
    result_by_name = {item.get("image"): item for item in results}
    if len(result_by_name) != len(results):
        raise ValueError("parity report contains duplicate or missing image names")
    expected_names = [image.get("file_name") for image in images]
    if any(not isinstance(name, str) or not name for name in expected_names):
        raise ValueError("COCO val images contain an invalid file_name")
    if set(result_by_name) != set(expected_names):
        raise ValueError("parity report images do not match the complete COCO val split")

    from mapassist.detection_evaluate import _iou

    tp = fp = fn = 0
    for image in images:
        name = image["file_name"]
        image_id = int(image["id"])
        item = result_by_name[name]
        image_path = data_dir / "val2017" / name
        if not image_path.is_file() or _sha256(image_path) != item.get("sha256"):
            raise ValueError(f"validation image hash does not match parity report: {name}")
        runtime = item.get("runtime_preprocess", {})
        detections = _parse_runtime_boxes(
            runtime.get("runtime_detections_xyxy_confidence"), name
        )
        if any(entry[2] >= len(class_names) for entry in detections):
            raise ValueError(f"runtime detection references an unknown class for {name}")
        detections = [entry for entry in detections
                      if entry[0] >= confidence_by_class[class_names[entry[2]]]]
        matches = _match_class_aware(
            detections, truths[image_id], args.iou_threshold, _match_boxes,
        )
        image_tp = len(matches)
        image_fp = len(detections) - image_tp
        image_fn = len(truths[image_id]) - image_tp
        tp += image_tp
        fp += image_fp
        fn += image_fn
        matched_prediction_indices = {prediction for prediction, _truth, _ in matches}
        matched_truth_indices = {truth for _prediction, truth, _ in matches}
        image_per_class = {}
        for class_id, class_name in enumerate(class_names):
            class_prediction_indices = {
                index for index, entry in enumerate(detections)
                if entry[2] == class_id
            }
            class_truth_indices = {
                index for index, entry in enumerate(truths[image_id])
                if entry[0] == class_id
            }
            class_tp = sum(
                1 for prediction, truth, _overlap in matches
                if detections[prediction][2] == class_id
                and truths[image_id][truth][0] == class_id
            )
            class_fp = len(class_prediction_indices - matched_prediction_indices)
            class_fn = len(class_truth_indices - matched_truth_indices)
            per_class_totals[class_name]["tp"] += class_tp
            per_class_totals[class_name]["fp"] += class_fp
            per_class_totals[class_name]["fn"] += class_fn
            image_per_class[class_name] = _finish(class_tp, class_fp, class_fn)
        matched_ious = [
            _iou(detections[prediction_index][1], truths[image_id][truth_index][1])
            for prediction_index, truth_index, _ in matches
        ]
        per_image.append({
            "image": name,
            "image_id": image_id,
            "truth_boxes": len(truths[image_id]),
            "runtime_detections": len(detections),
            "runtime_classes": [class_names[item[2]] for item in detections
                                 if item[2] < len(class_names)],
            "per_class": image_per_class,
            "tp": image_tp,
            "fp": image_fp,
            "fn": image_fn,
            "matched_iou_min": min(matched_ious) if matched_ious else None,
        })

    metric = _finish(tp, fp, fn)
    quality_gate = {
        "minimum_precision": 0.90,
        "minimum_recall": 0.80,
        "passed": (
            metric["precision"] is not None
            and metric["recall"] is not None
            and tp / (tp + fp) >= 0.90
            and tp / (tp + fn) >= 0.80
        ),
    }
    report_artifacts = report.get("artifacts", {})
    result = {
        "schema_version": 1,
        "design": "fixed_confidence_ncnn_runtime_coco_validation",
        "warning": (
            "This COCO val split was used for model and/or confidence selection; these "
            "metrics are a development diagnostic, not an independent evaluation. Strict "
            "parity failure is reported separately and is not waived by these detection metrics."
        ),
        "split": "COCO HD development val",
        "confidence": (float(confidence) if isinstance(confidence, (int, float))
                       and not isinstance(confidence, bool) else None),
        "confidence_by_class": confidence_by_class,
        "classes": list(class_names),
        "output_width": 5 + len(class_names),
        "confidence_source": "fixed value recorded in ncnn parity report; no threshold search",
        "nms_threshold": float(nms_threshold),
        "iou_threshold": args.iou_threshold,
        "per_class": {
            name: _finish(values["tp"], values["fp"], values["fn"])
            for name, values in per_class_totals.items()
        },
        "metrics": metric,
        "development_quality_gate": quality_gate,
        "parity": {
            "passed": bool(report.get("passed")),
            "gates": gates,
            "parity_report_sha256": _sha256(report_path),
            "ncnn_param": report_artifacts.get("ncnn_param"),
            "ncnn_bin": report_artifacts.get("ncnn_bin"),
            "torchscript": report_artifacts.get("torchscript"),
        },
        "dataset": {
            "path": str(data_dir),
            "annotations": str(annotation_path),
            "annotations_sha256": _sha256(annotation_path),
            "images": len(images),
            "truth_boxes": sum(len(value) for value in truths.values()),
        },
        "prediction_source": str(report_path),
        "prediction_images_sha256_verified": len(images),
        "evaluator": {
            "path": str(Path(__file__).resolve()),
            "sha256": _sha256(Path(__file__).resolve()),
        },
        "per_image": per_image,
    }
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "metrics": metric,
                      "development_quality_gate": quality_gate,
                      "parity_passed": result["parity"]["passed"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
