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


def _parse_runtime_boxes(value: object, image: str) -> list[tuple[float, list[float]]]:
    if not isinstance(value, list):
        raise ValueError(f"runtime detections must be a list for {image}")
    result = []
    for item in value:
        if (
            not isinstance(item, list)
            or len(item) != 5
            or any(
                not isinstance(number, (int, float))
                or isinstance(number, bool)
                or not math.isfinite(number)
                for number in item
            )
        ):
            raise ValueError(f"invalid runtime detection in {image}: {item!r}")
        x0, y0, x1, y1, score = map(float, item)
        if x0 < 0 or y0 < 0 or x1 <= x0 or y1 <= y0 or not 0 <= score <= 1:
            raise ValueError(f"runtime detection is outside valid bounds in {image}: {item!r}")
        # The verifier stores XYXY in source-image pixels; COCO and the project
        # matcher use XYWH.
        result.append((score, [x0, y0, x1 - x0, y1 - y0]))
    return result


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
    nms_threshold = runtime_gate.get("nms_threshold")
    if not isinstance(confidence, (int, float)) or isinstance(confidence, bool):
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

    truths: dict[int, list[list[float]]] = {int(image["id"]): [] for image in images}
    for item in annotations:
        bbox = item.get("bbox")
        if not isinstance(bbox, list) or len(bbox) != 4:
            raise ValueError(f"invalid COCO bbox: {item!r}")
        x, y, width, height = map(float, bbox)
        if not all(math.isfinite(value) for value in (x, y, width, height)):
            raise ValueError(f"non-finite COCO bbox: {item!r}")
        if width <= 0 or height <= 0:
            raise ValueError(f"non-positive COCO bbox: {item!r}")
        truths[int(item["image_id"])].append([x, y, width, height])

    per_image = []
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
        detections = [entry for entry in detections if entry[0] >= float(confidence)]
        boxes = [box for _, box in detections]
        matches = _match_boxes(boxes, truths[image_id], args.iou_threshold)
        image_tp = len(matches)
        image_fp = len(boxes) - image_tp
        image_fn = len(truths[image_id]) - image_tp
        tp += image_tp
        fp += image_fp
        fn += image_fn
        matched_ious = [
            _iou(boxes[prediction_index], truths[image_id][truth_index])
            for prediction_index, truth_index, _ in matches
        ]
        per_image.append({
            "image": name,
            "image_id": image_id,
            "truth_boxes": len(truths[image_id]),
            "runtime_detections": len(boxes),
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
        "confidence": float(confidence),
        "confidence_source": "fixed value recorded in ncnn parity report; no threshold search",
        "nms_threshold": float(nms_threshold),
        "iou_threshold": args.iou_threshold,
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
