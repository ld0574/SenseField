"""Compare YOLOX PyTorch and ONNX Runtime outputs on real validation crops."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

try:
    from .yolox_decode import (
        DEFAULT_CLASSES,
        confidence_from_metadata,
        confidence_thresholds,
        candidates_from_raw,
        decode_yolox,
        normalize_classes,
        resolve_classes,
    )
except ImportError:  # pragma: no cover - direct command-line execution.
    from yolox_decode import (  # type: ignore
        DEFAULT_CLASSES,
        confidence_from_metadata,
        confidence_thresholds,
        candidates_from_raw,
        decode_yolox,
        normalize_classes,
        resolve_classes,
    )


def _decode(raw, input_size: int, classes=DEFAULT_CLASSES):
    import numpy as np

    normalize_classes(classes)
    return decode_yolox(raw, input_size, np=np)


def _raw_difference_detail(torch_raw, candidate_raw, input_size: int,
                           confidence: object, np, candidate_name: str = "onnx",
                           classes=DEFAULT_CLASSES):
    """Describe the single worst raw cell without hiding a failed raw gate."""
    class_names = normalize_classes(classes)
    confidence_by_class = confidence_thresholds(confidence, class_names)
    output_width = 5 + len(class_names)
    torch_values = np.asarray(torch_raw, dtype=np.float32).reshape(-1, output_width)
    candidate_values = np.asarray(candidate_raw, dtype=np.float32).reshape(-1, output_width)
    if torch_values.shape != candidate_values.shape or not len(torch_values):
        return None

    delta = np.abs(torch_values - candidate_values)
    row, channel = np.unravel_index(int(np.argmax(delta)), delta.shape)
    row = int(row)
    channel = int(channel)
    offset = 0
    stride = None
    grid_x = None
    grid_y = None
    for candidate_stride in (8, 16, 32):
        grid_size = input_size // candidate_stride
        end = offset + grid_size * grid_size
        if row < end:
            local_row = row - offset
            stride = candidate_stride
            grid_x = local_row % grid_size
            grid_y = local_row // grid_size
            break
        offset = end

    torch_value = float(torch_values[row, channel])
    candidate_value = float(candidate_values[row, channel])
    class_channel_names = (
        ("class_probability",) if len(class_names) == 1 else
        tuple(f"class_probability:{name}" for name in class_names)
    )
    channel_names = (
        "center_x_offset", "center_y_offset", "log_width", "log_height",
        "objectness_probability", *class_channel_names,
    )
    torch_class_id = int(np.argmax(torch_values[row, 5:]))
    candidate_class_id = int(np.argmax(candidate_values[row, 5:]))
    item = {
        "row": row,
        "channel": channel,
        "channel_name": channel_names[channel],
        "torch_value": torch_value,
        f"{candidate_name}_value": candidate_value,
        "absolute_error": float(delta[row, channel]),
        "torch_class": class_names[torch_class_id],
        f"{candidate_name}_class": class_names[candidate_class_id],
        "torch_confidence": float(torch_values[row, 4] *
                                   torch_values[row, 5 + torch_class_id]),
        f"{candidate_name}_confidence": float(
            candidate_values[row, 4] * candidate_values[row, 5 + candidate_class_id]
        ),
        "confidence_threshold": confidence_by_class[class_names[torch_class_id]],
        "confidence_by_class": confidence_by_class,
    }
    if stride is not None:
        item["stride"] = stride
        item["grid_x"] = int(grid_x)
        item["grid_y"] = int(grid_y)
        if channel == 0 or channel == 1:
            item["decoded_coordinate_error_pixels"] = float(delta[row, channel] * stride)
        elif channel == 2 or channel == 3:
            with np.errstate(over="ignore", invalid="ignore"):
                decoded_delta = stride * abs(
                    np.exp(torch_value) - np.exp(candidate_value)
                )
            item["decoded_size_error_pixels"] = (
                float(decoded_delta) if np.isfinite(decoded_delta) else None
            )
    item["torch_above_confidence_threshold"] = (
        item["torch_confidence"] >= confidence_by_class[class_names[torch_class_id]]
    )
    item[f"{candidate_name}_above_confidence_threshold"] = (
        item[f"{candidate_name}_confidence"] >=
        confidence_by_class[class_names[candidate_class_id]]
    )
    item["raw_max_error_by_channel"] = {
        name: float(delta[:, index].max())
        for index, name in enumerate(channel_names)
    }
    return item


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--confidence", type=float,
                        help="Override metadata per-class confidence thresholds")
    parser.add_argument("--images", type=int, default=12)
    parser.add_argument("--max-raw-error", type=float, default=5e-4)
    parser.add_argument("--metadata", type=Path,
                        help="Model metadata JSON; its classes array is canonical")
    parser.add_argument("--classes", nargs="+", metavar="CLASS",
                        help="Explicit class order when metadata is unavailable")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    data_dir = args.data_dir.resolve()
    sys.path.insert(0, str(args.yolox_root.resolve()))
    os.environ["MAPASSIST_COCO_DIR"] = str(data_dir)

    import cv2
    import numpy as np
    import onnxruntime as ort
    import torch
    from yolox.data import ValTransform
    from yolox.exp import get_exp

    annotation = json.loads(
        (data_dir / "annotations/instances_val2017.json").read_text(encoding="utf-8")
    )
    metadata_path = args.metadata
    if metadata_path is None:
        candidate = args.checkpoint.with_suffix(".metadata.json")
        metadata_path = candidate if candidate.is_file() else None
    class_names = resolve_classes(
        classes=args.classes, metadata=metadata_path, coco=annotation,
    )
    confidence = (
        confidence_thresholds(args.confidence, class_names)
        if args.confidence is not None else
        confidence_from_metadata(metadata_path, class_names, 0.21)
    )
    exp = get_exp(str(root / "training/yolox_nano_minimap.py"), None)
    exp.class_names = list(class_names)
    exp.num_classes = len(class_names)
    exp.input_size = (args.input_size, args.input_size)
    exp.test_size = exp.input_size
    model = exp.get_model()
    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    model.load_state_dict(checkpoint["model"])
    model.head.decode_in_inference = False
    model.eval()
    session = ort.InferenceSession(
        str(args.onnx), providers=["CPUExecutionProvider"]
    )
    selected = annotation["images"][:args.images]
    transform = ValTransform(legacy=False)
    results = []
    for image_info in selected:
        image = cv2.imread(str(data_dir / "val2017" / image_info["file_name"]))
        if image is None:
            raise FileNotFoundError(image_info["file_name"])
        transformed, _ = transform(image, None, exp.test_size)
        batch = transformed[np.newaxis].astype(np.float32)
        with torch.inference_mode():
            torch_raw = model(torch.from_numpy(batch)).numpy()
        onnx_raw = session.run(None, {session.get_inputs()[0].name: batch})[0]
        raw_error = np.abs(torch_raw - onnx_raw)
        # Use the same threshold-before-class-selection rule as Android and
        # ncnn parity. YOLOX's generic postprocess chooses the raw argmax class
        # first, which drops a valid lower-scoring class when the argmax class
        # has a stricter per-class threshold.
        torch_array = candidates_from_raw(
            torch_raw, args.input_size, image.shape[1], image.shape[0],
            confidence, 0.5, class_names, np=np,
        )
        onnx_array = candidates_from_raw(
            onnx_raw, args.input_size, image.shape[1], image.shape[0],
            confidence, 0.5, class_names, np=np,
        )
        same_detections = (torch_array.shape == onnx_array.shape and
                           np.allclose(torch_array, onnx_array, rtol=1e-4, atol=1e-4))
        results.append({
            "image": image_info["file_name"],
            "raw_max_abs_error": float(raw_error.max()),
            "raw_mean_abs_error": float(raw_error.mean()),
            "raw_max_detail": _raw_difference_detail(
                torch_raw, onnx_raw, args.input_size, confidence, np,
                classes=class_names,
            ),
            "torch_detections": int(len(torch_array)),
            "onnx_detections": int(len(onnx_array)),
            "detections_match": bool(same_detections),
        })
    passed = bool(results) and all(
        item["raw_max_abs_error"] <= args.max_raw_error and item["detections_match"]
        for item in results
    )
    report = {
        "schema_version": 2,
        "images": len(results),
        "input_size": args.input_size,
        "confidence": args.confidence,
        "confidence_by_class": confidence,
        "classes": list(class_names),
        "output_width": 5 + len(class_names),
        "max_raw_error_allowed": args.max_raw_error,
        "passed": passed,
        "maximum_raw_error": max(item["raw_max_abs_error"] for item in results),
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
