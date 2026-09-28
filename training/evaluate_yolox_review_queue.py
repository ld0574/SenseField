"""Evaluate a frozen YOLOX model against a completed manual review queue.

The report includes box-level metrics and a deterministic annotation-workload
estimate. A matched box needs no edit when each of its four edges is within the
configured full-frame pixel tolerance; unmatched predictions count as deletes,
unmatched truths as adds, and other matched boxes as one reframe operation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from mapassist.detection_evaluate import _match_boxes, _boxes


def _metrics(tp: int, fp: int, fn: int) -> dict[str, int | float | None]:
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = (2 * precision * recall / (precision + recall)
          if precision is not None and recall is not None and precision + recall else None)
    return {
        "tp": tp, "fp": fp, "fn": fn,
        "precision": round(precision, 6) if precision is not None else None,
        "recall": round(recall, 6) if recall is not None else None,
        "f1": round(f1, 6) if f1 is not None else None,
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _canonical_sha256(value: object) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                or not math.isfinite(float(item)) for item in value)):
        raise ValueError(f"{label} must be a finite normalized [x, y, width, height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def _reviewed_frames(document: dict) -> tuple[dict, list[dict]]:
    if document.get("schema_version") != 1:
        raise ValueError("review manifest must have schema_version 1")
    if document.get("review_mode") != "manual":
        raise ValueError("evaluation requires a manual-review queue")
    matches = document.get("matches")
    if not isinstance(matches, list) or len(matches) != 1:
        raise ValueError("evaluation currently requires exactly one match")
    match = matches[0]
    match_id = match.get("id")
    if not isinstance(match_id, str) or not match_id:
        raise ValueError("review match needs an id")
    normalized_id = match_id.casefold().replace("_", "-")
    if normalized_id.startswith(("video9", "video12")):
        raise ValueError(f"sealed match {match_id!r} is not allowed for evaluation")
    default_roi = _roi(document.get("roi"), "review roi")
    match_roi_value = match.get("roi")
    roi = _roi(default_roi if match_roi_value is None else match_roi_value,
               f"{match_id} roi")
    label_roi_value = match.get("label_roi")
    if label_roi_value is None:
        label_roi_value = document.get("label_roi")
    label_roi = (_roi(label_roi_value, f"{match_id} label roi")
                 if label_roi_value is not None else None)
    samples = match.get("samples")
    if not isinstance(samples, list) or not samples:
        raise ValueError("review match needs samples")

    seen: set[int] = set()
    frames = []
    for sample in samples:
        at_ms = sample.get("at_ms")
        status = sample.get("review_status", "pending")
        if (not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0 or
                at_ms in seen):
            raise ValueError(f"invalid or duplicate review timestamp: {at_ms}")
        seen.add(at_ms)
        if status == "pending":
            raise ValueError(f"review queue is incomplete at {match_id}@{at_ms}")
        if status == "accepted":
            raise ValueError("accepted suggestions are not human ground truth; use manual review")
        if status not in {"corrected", "negative", "excluded", "skip"}:
            raise ValueError(f"invalid review status at {match_id}@{at_ms}: {status}")
        if status == "corrected":
            truth = _boxes(sample.get("reviewed_boxes"),
                           f"{match_id}@{at_ms} reviewed_boxes")
            if not truth:
                raise ValueError(f"{match_id}@{at_ms} corrected sample has no boxes")
        else:
            truth = []
            if status == "negative" and sample.get("reviewed_boxes") not in (None, []):
                raise ValueError(f"{match_id}@{at_ms} negative sample has reviewed boxes")
        frame = sample.get("frame")
        if not isinstance(frame, str) or not frame:
            raise ValueError(f"{match_id}@{at_ms} has no frame path")
        frames.append({
            "match_id": match_id,
            "at_ms": at_ms,
            "status": status,
            "frame": frame,
            "truth": truth,
            "roi": roi,
            "label_roi": label_roi,
        })

    if document.get("sampling", {}).get("predictions_used_for_selection") is not False:
        raise ValueError("manual queue must declare prediction-independent frame selection")
    return match, frames


def summarize_predictions(
    document: dict,
    frame_sizes: dict[int, tuple[int, int]],
    predictions: dict[int, list[list[float]]],
    iou_threshold: float = 0.5,
    edit_tolerance_px: float = 8.0,
) -> dict:
    """Score full-frame normalized predictions and count edit operations."""
    if not 0 < iou_threshold <= 1:
        raise ValueError("iou_threshold must be in (0, 1]")
    if not math.isfinite(edit_tolerance_px) or edit_tolerance_px < 0:
        raise ValueError("edit_tolerance_px must be finite and non-negative")
    match, frames = _reviewed_frames(document)
    match_id = match["id"]
    status_counts: Counter[str] = Counter(frame["status"] for frame in frames)
    labeled = [frame for frame in frames if frame["status"] in {"corrected", "negative"}]
    expected = {frame["at_ms"] for frame in labeled}
    if set(predictions) != expected:
        missing = sorted(expected - set(predictions))
        extra = sorted(set(predictions) - expected)
        raise ValueError(f"prediction timestamps differ from labeled frames: missing={len(missing)}, extra={len(extra)}")

    totals = Counter()
    ground_truth_fingerprint = []
    frame_results = []
    truth_boxes_total = 0
    for frame in labeled:
        at_ms = frame["at_ms"]
        width, height = frame_sizes[at_ms]
        if width <= 0 or height <= 0:
            raise ValueError(f"invalid image dimensions at {match_id}@{at_ms}")
        truth = frame["truth"]
        boxes = _boxes(predictions[at_ms], f"predictions for {match_id}@{at_ms}")
        pairs = _match_boxes(boxes, truth, iou_threshold)
        deletions = len(boxes) - len(pairs)
        additions = len(truth) - len(pairs)
        adjustments = 0
        for prediction_index, truth_index, _overlap in pairs:
            prediction = boxes[prediction_index]
            target = truth[truth_index]
            edge_deltas = (
                abs(prediction[0] - target[0]) * width,
                abs(prediction[1] - target[1]) * height,
                abs((prediction[0] + prediction[2]) -
                    (target[0] + target[2])) * width,
                abs((prediction[1] + prediction[3]) -
                    (target[1] + target[3])) * height,
            )
            adjustments += int(max(edge_deltas, default=0.0) > edit_tolerance_px)
        operations = additions + deletions + adjustments
        frame_result = {
            "at_ms": at_ms,
            "review_status": frame["status"],
            "truth_boxes": len(truth),
            "predicted_boxes": len(boxes),
            "tp": len(pairs),
            "fp": deletions,
            "fn": additions,
            "exact_count_frame": deletions == 0 and additions == 0,
            "add_operations": additions,
            "delete_operations": deletions,
            "reframe_operations": adjustments,
            "total_operations": operations,
            "no_edit_frame": operations == 0,
        }
        frame_results.append(frame_result)
        totals.update({
            "tp": len(pairs), "fp": deletions, "fn": additions,
            "add_operations": additions,
            "delete_operations": deletions,
            "reframe_operations": adjustments,
            "total_operations": operations,
            "exact_count_frames": int(frame_result["exact_count_frame"]),
            "no_edit_frames": int(frame_result["no_edit_frame"]),
        })
        truth_boxes_total += len(truth)
        ground_truth_fingerprint.append({
            "match_id": match_id,
            "at_ms": at_ms,
            "review_status": frame["status"],
            "reviewed_boxes": truth,
        })

    metrics = _metrics(totals["tp"], totals["fp"], totals["fn"])
    return {
        "review": {
            "match_id": match_id,
            "video_sha256": match.get("video_sha256"),
            "tasks": len(frames),
            "labeled_frames": len(labeled),
            "status_counts": dict(sorted(status_counts.items())),
            "truth_boxes": truth_boxes_total,
            "labels_sha256": _canonical_sha256(ground_truth_fingerprint),
            "frame_timestamps_ms": [frame["at_ms"] for frame in labeled],
        },
        "iou_threshold": iou_threshold,
        "edit_tolerance_px": edit_tolerance_px,
        "metrics": metrics,
        "workload": {
            "unit": "one add, delete, or reframe per box",
            "matching": f"maximum one-to-one IoU matching at {iou_threshold:g}",
            "unchanged_box_rule": (
                f"all four full-frame box edges differ by at most {edit_tolerance_px:g} px"
            ),
            "exact_count_frames": totals["exact_count_frames"],
            "no_edit_frames": totals["no_edit_frames"],
            "add_operations": totals["add_operations"],
            "delete_operations": totals["delete_operations"],
            "reframe_operations": totals["reframe_operations"],
            "total_operations": totals["total_operations"],
        },
        "per_frame": frame_results,
    }


def _crop_box_to_full_frame(
    roi: list[float], crop_xy: tuple[int, int], full_size: tuple[int, int],
    box: list[float],
) -> list[float] | None:
    full_width, full_height = full_size
    crop_x, crop_y = crop_xy
    x0 = max(0.0, min(float(box[0]), full_width))
    y0 = max(0.0, min(float(box[1]), full_height))
    x1 = max(0.0, min(float(box[2]), full_width))
    y1 = max(0.0, min(float(box[3]), full_height))
    left = max(roi[0], (crop_x + x0) / full_width)
    top = max(roi[1], (crop_y + y0) / full_height)
    right = min(roi[0] + roi[2], (crop_x + x1) / full_width)
    bottom = min(roi[1] + roi[3], (crop_y + y1) / full_height)
    if right <= left or bottom <= top:
        return None
    return [left, top, right - left, bottom - top]


def _center_inside_roi(box: list[float], roi: list[float]) -> bool:
    center_x = box[0] + box[2] * 0.5
    center_y = box[1] + box[3] * 0.5
    return (roi[0] <= center_x <= roi[0] + roi[2] and
            roi[1] <= center_y <= roi[1] + roi[3])


def _filter_label_roi(boxes: list[list[float]], label_roi: list[float] | None
                      ) -> list[list[float]]:
    if label_roi is None:
        raise ValueError("label ROI filtering requires label_roi in the review manifest")
    return [box for box in boxes if _center_inside_roi(box, label_roi)]


def _verify_display_size(document: dict, width: int, height: int) -> None:
    orientation = document.get("orientation")
    if not isinstance(orientation, dict) or orientation.get("display_size") is None:
        return
    size = orientation["display_size"]
    if (not isinstance(size, list) or len(size) != 2 or
            any(not isinstance(value, int) or isinstance(value, bool) for value in size)):
        raise ValueError("orientation.display_size must be [width, height]")
    if size != [width, height]:
        raise ValueError(
            f"review frame size {width}x{height} does not match orientation.display_size "
            f"{size[0]}x{size[1]}"
        )


def _load_onnx(path: Path, input_size: int, confidence: float,
               nms_threshold: float) -> tuple[Any, Any]:
    import onnxruntime as ort

    from prelabel_review_queue import _decode, _nms

    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    def predict(crop: Any) -> tuple[list[list[float]], float]:
        import cv2
        import numpy as np

        height, width = crop.shape[:2]
        ratio = min(input_size / height, input_size / width)
        resized = cv2.resize(crop, (round(width * ratio), round(height * ratio)),
                             interpolation=cv2.INTER_LINEAR)
        canvas = np.full((input_size, input_size, 3), 114, dtype=np.uint8)
        canvas[:resized.shape[0], :resized.shape[1]] = resized
        batch = canvas.transpose(2, 0, 1)[None].astype(np.float32)
        raw = session.run(None, {input_name: batch})[0]
        decoded = _decode(raw, input_size)
        scores = decoded[:, 4] * decoded[:, 5]
        selected = scores >= confidence
        decoded, scores = decoded[selected], scores[selected]
        boxes = np.empty((len(decoded), 4), dtype=np.float32)
        boxes[:, 0] = decoded[:, 0] - decoded[:, 2] / 2
        boxes[:, 1] = decoded[:, 1] - decoded[:, 3] / 2
        boxes[:, 2] = decoded[:, 0] + decoded[:, 2] / 2
        boxes[:, 3] = decoded[:, 1] + decoded[:, 3] / 2
        keep = _nms(boxes, scores, nms_threshold)
        result = [[float(value) for value in boxes[index] / ratio] for index in keep]
        return result, ratio

    return predict, {"backend": "onnxruntime", "input_name": input_name}


def _load_torch(path: Path, yolox_root: Path, input_size: int, confidence: float,
                nms_threshold: float, device_name: str) -> tuple[Any, dict]:
    import cv2
    import numpy as np
    import torch

    sys.path.insert(0, str(yolox_root))
    from yolox.data import ValTransform
    from yolox.exp import get_exp
    from yolox.utils import load_ckpt, postprocess

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from train_yolox_minimap import _device, _git_revision

    repository_root = Path(__file__).resolve().parents[1]
    exp_path = repository_root / "training/yolox_nano_minimap.py"
    exp = get_exp(str(exp_path), None)
    exp.input_size = (input_size, input_size)
    exp.test_size = exp.input_size
    checkpoint = torch.load(path, map_location="cpu", weights_only=False)
    model = load_ckpt(exp.get_model(), checkpoint.get("model", checkpoint))
    device = _device(torch, device_name)
    model.to(device).eval()
    transform = ValTransform(legacy=False)

    def predict(crop: Any) -> tuple[list[list[float]], float]:
        height, width = crop.shape[:2]
        ratio = min(input_size / height, input_size / width)
        transformed, _ = transform(crop, None, (input_size, input_size))
        tensor = torch.from_numpy(transformed).unsqueeze(0).float().to(device)
        with torch.inference_mode():
            raw = model(tensor)
            detected = postprocess(
                raw, 1, conf_thre=min(confidence, 0.01),
                nms_thre=nms_threshold, class_agnostic=True,
            )[0]
        if detected is not None:
            detected = detected.detach().cpu()
        result = []
        if detected is not None:
            for row in detected.tolist():
                x0, y0, x1, y1, objectness, class_confidence, _ = row
                if objectness * class_confidence >= confidence:
                    result.append([x0 / ratio, y0 / ratio, x1 / ratio, y1 / ratio])
        return result, ratio

    metadata = {
        "backend": "pytorch",
        "device": str(device),
        "checkpoint_epoch": checkpoint.get("epoch"),
        "yolox_revision": _git_revision(yolox_root),
        "experiment_path": str(exp_path),
        "experiment_sha256": _sha256(exp_path),
    }
    return predict, metadata


def evaluate_checkpoint(
    review_manifest: Path,
    model_path: Path,
    output: Path,
    input_size: int = 320,
    confidence: float | None = None,
    nms_threshold: float = 0.5,
    iou_threshold: float = 0.5,
    edit_tolerance_px: float = 2.0,
    yolox_root: Path | None = None,
    device_name: str = "auto",
    filter_label_roi: bool = False,
) -> dict:
    import cv2

    review_manifest = review_manifest.resolve()
    model_path = model_path.resolve()
    output = output.resolve()
    if input_size < 64 or input_size % 32:
        raise ValueError("input_size must be a multiple of 32 and at least 64")
    if not 0 <= nms_threshold <= 1:
        raise ValueError("nms_threshold must be between 0 and 1")
    if not 0 < iou_threshold <= 1:
        raise ValueError("iou_threshold must be in (0, 1]")

    document = json.loads(review_manifest.read_text(encoding="utf-8"))
    match, frames = _reviewed_frames(document)
    if model_path.suffix.lower() == ".pth":
        if yolox_root is None:
            raise ValueError("--yolox-root is required for a .pth checkpoint")
        checkpoint = None
        if confidence is None:
            import torch

            checkpoint = torch.load(model_path, map_location="cpu", weights_only=False)
            confidence = float(checkpoint["validation"]["selected"]["confidence"])
            confidence_source = "checkpoint_validation"
        else:
            confidence_source = "command_line"
        if not math.isfinite(confidence) or not 0 <= confidence <= 1:
            raise ValueError("confidence must be finite and between 0 and 1")
        predict, backend_metadata = _load_torch(
            model_path, yolox_root.resolve(), input_size, confidence,
            nms_threshold, device_name,
        )
    elif model_path.suffix.lower() == ".onnx":
        if confidence is None:
            raise ValueError("--confidence is required for an ONNX model")
        if not math.isfinite(confidence) or not 0 <= confidence <= 1:
            raise ValueError("confidence must be finite and between 0 and 1")
        confidence_source = "command_line"
        predict, backend_metadata = _load_onnx(
            model_path, input_size, confidence, nms_threshold,
        )
    else:
        raise ValueError("model must be a YOLOX .pth checkpoint or .onnx model")

    if filter_label_roi and any(frame["label_roi"] is None for frame in frames):
        raise ValueError("--filter-label-roi requires label_roi in the review manifest")
    predictions: dict[int, list[list[float]]] = {}
    frame_sizes: dict[int, tuple[int, int]] = {}
    frame_hashes = []
    for frame in frames:
        if frame["status"] not in {"corrected", "negative"}:
            continue
        frame_path = (review_manifest.parent / frame["frame"]).resolve()
        if review_manifest.parent not in frame_path.parents or not frame_path.is_file():
            raise ValueError(f"review frame path is invalid: {frame['frame']}")
        image = cv2.imread(str(frame_path))
        if image is None:
            raise ValueError(f"cannot decode review frame: {frame['frame']}")
        height, width = image.shape[:2]
        _verify_display_size(document, width, height)
        frame_sizes[frame["at_ms"]] = (width, height)
        frame_hashes.append({"at_ms": frame["at_ms"], "sha256": _sha256(frame_path)})
        roi = frame["roi"]
        rx, ry, rw, rh = roi
        crop_x, crop_y = math.floor(rx * width), math.floor(ry * height)
        crop_right, crop_bottom = math.ceil((rx + rw) * width), math.ceil((ry + rh) * height)
        crop = image[crop_y:crop_bottom, crop_x:crop_right]
        crop_boxes, _ratio = predict(crop)
        normalized = []
        for box in crop_boxes:
            full_box = _crop_box_to_full_frame(
                roi, (crop_x, crop_y), (width, height), box,
            )
            if full_box is not None:
                normalized.append(full_box)
        if filter_label_roi:
            normalized = _filter_label_roi(normalized, frame["label_roi"])
        predictions[frame["at_ms"]] = normalized

    result = summarize_predictions(document, frame_sizes, predictions,
                                   iou_threshold, edit_tolerance_px)
    result.update({
        "schema_version": 1,
        "design": "fixed_checkpoint_manual_review_evaluation",
        "warning": (
            "Development-match diagnostic. If these frames or labels influenced training, "
            "model selection, threshold selection, or post-hoc tuning, this is not an "
            "independent validation result."
        ),
        "review_manifest": {
            "path": str(review_manifest),
            "sha256": _sha256(review_manifest),
            "frames_sha256": _canonical_sha256(frame_hashes),
        },
        "model": {
            "path": str(model_path),
            "sha256": _sha256(model_path),
            "format": model_path.suffix.lower().lstrip("."),
            "input_size": input_size,
            "confidence": confidence,
            "confidence_source": confidence_source,
            "nms_threshold": nms_threshold,
            **backend_metadata,
        },
        "matching": "mapassist.detection_evaluate._match_boxes",
        "postprocessing": {
            "center_inside_label_roi": filter_label_roi,
            "safe_roi_edge_contacts_removed": False,
        },
    })
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review_manifest", type=Path)
    parser.add_argument("--model", type=Path, required=True,
                        help="Frozen YOLOX .pth checkpoint or .onnx model")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--yolox-root", type=Path,
                        help="Required for a .pth checkpoint")
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--confidence", type=float,
                        help="Defaults to the checkpoint's selected confidence for .pth")
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    parser.add_argument("--edit-tolerance-px", type=float, default=8.0)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--filter-label-roi", action="store_true",
                        help="Keep only detections whose centers fall inside label_roi")
    args = parser.parse_args()
    try:
        report = evaluate_checkpoint(
            args.review_manifest, args.model, args.output,
            input_size=args.input_size,
            confidence=args.confidence,
            nms_threshold=args.nms_threshold,
            iou_threshold=args.iou_threshold,
            edit_tolerance_px=args.edit_tolerance_px,
            yolox_root=args.yolox_root,
            device_name=args.device,
            filter_label_roi=args.filter_label_roi,
        )
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps({
        "output": str(args.output.resolve()),
        "review": report["review"],
        "model": report["model"],
        "metrics": report["metrics"],
        "workload": report["workload"],
        "warning": report["warning"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
