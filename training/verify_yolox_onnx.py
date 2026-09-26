"""Compare YOLOX PyTorch and ONNX Runtime outputs on real validation crops."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path


def _decode(raw, input_size: int):
    import numpy as np

    grids = []
    strides = []
    for stride in (8, 16, 32):
        height = input_size // stride
        width = input_size // stride
        y, x = np.meshgrid(np.arange(height), np.arange(width), indexing="ij")
        grid = np.stack((x, y), axis=2).reshape(1, -1, 2)
        grids.append(grid)
        strides.append(np.full((*grid.shape[:2], 1), stride))
    grid = np.concatenate(grids, axis=1).astype(raw.dtype)
    expanded_stride = np.concatenate(strides, axis=1).astype(raw.dtype)
    decoded = raw.copy()
    decoded[..., :2] = (decoded[..., :2] + grid) * expanded_stride
    decoded[..., 2:4] = np.exp(decoded[..., 2:4]) * expanded_stride
    return decoded


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--confidence", type=float, default=0.21)
    parser.add_argument("--images", type=int, default=12)
    parser.add_argument("--max-raw-error", type=float, default=5e-4)
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
    from yolox.utils import postprocess

    exp = get_exp(str(root / "training/yolox_nano_minimap.py"), None)
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
    annotation = json.loads(
        (data_dir / "annotations/instances_val2017.json").read_text(encoding="utf-8")
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
        torch_detections = postprocess(
            torch.from_numpy(_decode(torch_raw, args.input_size)),
            1, args.confidence, 0.5, class_agnostic=True,
        )[0]
        onnx_detections = postprocess(
            torch.from_numpy(_decode(onnx_raw, args.input_size)),
            1, args.confidence, 0.5, class_agnostic=True,
        )[0]
        torch_array = (torch_detections.numpy() if torch_detections is not None else
                       np.empty((0, 7), dtype=np.float32))
        onnx_array = (onnx_detections.numpy() if onnx_detections is not None else
                      np.empty((0, 7), dtype=np.float32))
        same_detections = (torch_array.shape == onnx_array.shape and
                           np.allclose(torch_array, onnx_array, rtol=1e-4, atol=1e-4))
        results.append({
            "image": image_info["file_name"],
            "raw_max_abs_error": float(raw_error.max()),
            "raw_mean_abs_error": float(raw_error.mean()),
            "torch_detections": int(len(torch_array)),
            "onnx_detections": int(len(onnx_array)),
            "detections_match": bool(same_detections),
        })
    passed = bool(results) and all(
        item["raw_max_abs_error"] <= args.max_raw_error and item["detections_match"]
        for item in results
    )
    report = {
        "schema_version": 1,
        "images": len(results),
        "input_size": args.input_size,
        "confidence": args.confidence,
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
