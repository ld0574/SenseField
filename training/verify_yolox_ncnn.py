"""Check YOLOX ncnn raw parity and Android-equivalent preprocessing on images."""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import math
import os
import sys
from pathlib import Path
from typing import Any


def _require(module: str, package: str | None = None) -> Any:
    try:
        return importlib.import_module(module)
    except ImportError as error:
        install = package or module
        raise RuntimeError(
            f"missing Python dependency {module!r}; install {install!r} into "
            f"the active interpreter ({sys.executable})"
        ) from error


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _artifact(path: Path) -> dict[str, object]:
    return {
        "path": str(path),
        "size_bytes": path.stat().st_size,
        "sha256": _sha256(path),
    }


def _write_report(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def _select_images(
    data_dir: Path | None,
    split: str,
    limit: int,
    explicit: list[Path] | None,
) -> tuple[list[tuple[str, Path]], Path | None]:
    if explicit:
        selected = []
        for path in explicit:
            resolved = path.expanduser().resolve()
            if not resolved.is_file():
                raise FileNotFoundError(f"image does not exist: {resolved}")
            selected.append((resolved.name, resolved))
        return selected, None
    if data_dir is None:
        raise ValueError("pass --data-dir for COCO validation images or pass --images")
    root = data_dir.expanduser().resolve()
    annotation_path = root / "annotations" / f"instances_{split}2017.json"
    if not annotation_path.is_file():
        raise FileNotFoundError(f"COCO annotation does not exist: {annotation_path}")
    annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
    images = annotation.get("images")
    if not isinstance(images, list) or not images:
        raise ValueError(f"COCO annotation has no images: {annotation_path}")
    selected = []
    for item in images[:limit]:
        filename = item.get("file_name") if isinstance(item, dict) else None
        if not isinstance(filename, str) or not filename:
            raise ValueError(f"invalid COCO image entry in {annotation_path}")
        path = root / f"{split}2017" / filename
        if not path.is_file():
            raise FileNotFoundError(f"validation image does not exist: {path}")
        selected.append((filename, path))
    return selected, annotation_path


def _preprocess(image: Any, input_size: int, cv2: Any, np: Any) -> Any:
    height, width = image.shape[:2]
    ratio = min(input_size / height, input_size / width)
    resized = cv2.resize(
        image,
        (int(width * ratio), int(height * ratio)),
        interpolation=cv2.INTER_LINEAR,
    ).astype(np.uint8)
    padded = np.full((input_size, input_size, 3), 114, dtype=np.uint8)
    padded[: resized.shape[0], : resized.shape[1]] = resized
    return np.ascontiguousarray(padded.transpose(2, 0, 1), dtype=np.float32)


def _runtime_preprocess(image: Any, input_size: int, ncnn: Any, np: Any):
    """Reproduce the Android ncnn resize and right/bottom padding path."""
    height, width = image.shape[:2]
    scale = min(input_size / width, input_size / height)
    resized_width = max(1, int(width * scale))
    resized_height = max(1, int(height * scale))
    contiguous = np.ascontiguousarray(image)
    resized = ncnn.Mat.from_pixels_resize(
        contiguous,
        ncnn.Mat.PixelType.PIXEL_BGR,
        width,
        height,
        resized_width,
        resized_height,
    )
    if resized.empty():
        raise RuntimeError("ncnn Mat.from_pixels_resize returned an empty tensor")
    padded = ncnn.copy_make_border(
        resized,
        0,
        input_size - resized_height,
        0,
        input_size - resized_width,
        ncnn.BorderType.BORDER_CONSTANT,
        114.0,
    )
    if padded.empty():
        raise RuntimeError("ncnn copy_make_border returned an empty tensor")
    return padded, scale


def _ncnn_output(net: Any, value: Any, input_name: str, output_name: str,
                 ncnn: Any, np: Any) -> Any:
    extractor = net.create_extractor()
    input_value = value if isinstance(value, ncnn.Mat) else ncnn.Mat(value).clone()
    input_status = extractor.input(input_name, input_value)
    if input_status != 0:
        raise RuntimeError(f"ncnn rejected input {input_name!r} (status {input_status})")
    output_status, output = extractor.extract(output_name)
    if output_status != 0:
        raise RuntimeError(
            f"ncnn failed to extract {output_name!r} (status {output_status})"
        )
    result = np.array(output, dtype=np.float32, copy=True)
    del output, extractor
    return result


def _intersection_over_union(left: Any, right: Any) -> float:
    intersection_width = max(0.0, min(left[2], right[2]) - max(left[0], right[0]))
    intersection_height = max(0.0, min(left[3], right[3]) - max(left[1], right[1]))
    intersection = intersection_width * intersection_height
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0.0 else 0.0


def _decode_and_nms(
    raw: Any,
    input_size: int,
    image_width: int,
    image_height: int,
    confidence: float,
    nms_threshold: float,
    np: Any,
) -> Any:
    expected_rows = sum((input_size // stride) ** 2 for stride in (8, 16, 32))
    if raw.shape != (expected_rows, 6):
        raise RuntimeError(
            f"YOLOX raw output shape must be {(expected_rows, 6)}, got {raw.shape}"
        )
    scale = min(input_size / image_width, input_size / image_height)
    proposals = []
    anchor = 0
    for stride in (8, 16, 32):
        grid = input_size // stride
        for grid_y in range(grid):
            for grid_x in range(grid):
                row = raw[anchor]
                anchor += 1
                score = float(np.float32(row[4] * row[5]))
                if not math.isfinite(score) or score < confidence:
                    continue
                center_x = float(np.float32((row[0] + grid_x) * stride))
                center_y = float(np.float32((row[1] + grid_y) * stride))
                box_width = float(np.float32(np.exp(np.clip(row[2], -10.0, 10.0)) * stride))
                box_height = float(np.float32(np.exp(np.clip(row[3], -10.0, 10.0)) * stride))
                detection = np.array(
                    [
                        center_x - box_width * 0.5,
                        center_y - box_height * 0.5,
                        center_x + box_width * 0.5,
                        center_y + box_height * 0.5,
                        score,
                    ],
                    dtype=np.float32,
                )
                detection[[0, 2]] = np.clip(
                    detection[[0, 2]] / scale, 0.0, float(image_width)
                )
                detection[[1, 3]] = np.clip(
                    detection[[1, 3]] / scale, 0.0, float(image_height)
                )
                if (detection[2] - detection[0] >= 1.0
                        and detection[3] - detection[1] >= 1.0):
                    proposals.append(detection)
    proposals.sort(key=lambda item: float(item[4]), reverse=True)
    kept = []
    for candidate in proposals:
        if all(_intersection_over_union(candidate, existing) <= nms_threshold
               for existing in kept):
            kept.append(candidate)
    if not kept:
        return np.empty((0, 5), dtype=np.float32)
    return np.stack(kept).astype(np.float32, copy=False)


def _pair_detections(reference: Any, candidate: Any, np: Any) -> list[tuple[int, int]]:
    """Pair equivalent unordered detections by highest IoU, then score distance."""
    remaining = set(range(len(candidate)))
    pairs = []
    for reference_index, detection in enumerate(reference):
        if not remaining:
            break
        candidate_index = max(
            remaining,
            key=lambda index: (
                _intersection_over_union(detection, candidate[index]),
                -abs(float(detection[4] - candidate[index][4])),
                -index,
            ),
        )
        remaining.remove(candidate_index)
        pairs.append((reference_index, candidate_index))
    return pairs


def _build_ncnn_net(param: Path, model_bin: Path, threads: int, ncnn: Any, np: Any):
    live_layers: list[Any] = []

    class YoloV5Focus(ncnn.Layer):
        def __init__(self) -> None:
            super().__init__()
            self.one_blob_only = True

        def forward(self, bottom_blob: Any, top_blob: Any, opt: Any) -> int:
            value = np.asarray(bottom_blob)
            focused = np.concatenate(
                (
                    value[..., ::2, ::2],
                    value[..., 1::2, ::2],
                    value[..., ::2, 1::2],
                    value[..., 1::2, 1::2],
                ),
                axis=0,
            )
            top_blob.clone_from(
                ncnn.Mat(np.ascontiguousarray(focused)), opt.blob_allocator
            )
            return -100 if top_blob.empty() else 0

    def create_focus() -> Any:
        layer = YoloV5Focus()
        live_layers.append(layer)
        return layer

    def destroy_focus(layer: Any) -> None:
        try:
            live_layers.remove(layer)
        except ValueError:
            pass

    net = ncnn.Net()
    net.opt.num_threads = threads
    # Exact parity requires float32 arithmetic.  ncnn enables fp16 paths by
    # default on capable ARM CPUs, which is appropriate for deployment but can
    # hide conversion errors behind much larger rounding noise here.
    net.opt.use_fp16_packed = False
    net.opt.use_fp16_storage = False
    net.opt.use_fp16_arithmetic = False
    net.opt.use_bf16_storage = False
    net.opt.use_packing_layout = False
    registration = net.register_custom_layer(
        "YoloV5Focus", create_focus, destroy_focus
    )
    if registration != 0:
        raise RuntimeError(f"failed to register YoloV5Focus (ncnn status {registration})")
    param_status = net.load_param(str(param))
    if param_status != 0:
        raise RuntimeError(f"failed to load ncnn param (status {param_status}): {param}")
    model_status = net.load_model(str(model_bin))
    if model_status != 0:
        raise RuntimeError(
            f"failed to load ncnn weights (status {model_status}): {model_bin}"
        )
    # The callbacks must outlive the net, so return the list with it.
    return net, live_layers


def _torch_output(model: Any, batch: Any, torch: Any, np: Any) -> Any:
    with torch.inference_mode():
        output = model(torch.from_numpy(batch))
    if isinstance(output, (tuple, list)):
        if len(output) != 1:
            raise RuntimeError(f"TorchScript returned {len(output)} outputs; expected one")
        output = output[0]
    if not hasattr(output, "detach"):
        raise RuntimeError(f"TorchScript returned unsupported output type {type(output)!r}")
    array = output.detach().cpu().numpy().astype(np.float32, copy=False)
    if array.ndim >= 1 and array.shape[0] == 1:
        array = array[0]
    return array


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--torchscript", type=Path, required=True)
    parser.add_argument("--param", type=Path, required=True)
    parser.add_argument("--bin", dest="model_bin", type=Path, required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--data-dir", type=Path)
    source.add_argument("--images", type=Path, nargs="+")
    parser.add_argument("--split", choices=("train", "val", "test"), default="val")
    parser.add_argument("--image-count", type=int, default=12)
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--input-name", default="in0")
    parser.add_argument("--output-name", default="out0")
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--max-raw-error", type=float, default=5e-4)
    parser.add_argument(
        "--runtime-preprocess-check", action=argparse.BooleanOptionalAction,
        default=True,
        help="also verify Android-equivalent ncnn resize/padding and final detections",
    )
    parser.add_argument("--confidence", type=float, default=0.29)
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument("--max-runtime-input-error", type=float, default=1.0)
    parser.add_argument("--max-detection-error", type=float, default=0.01)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.image_count < 1 or args.input_size < 32 or args.input_size % 32:
        parser.error("image count must be positive; input size must be divisible by 32")
    finite_non_negative = (
        args.max_raw_error,
        args.max_runtime_input_error,
        args.max_detection_error,
    )
    if args.threads < 1 or any(
        not math.isfinite(value) or value < 0 for value in finite_non_negative
    ):
        parser.error("threads must be positive and error limits must be finite and non-negative")
    if (not math.isfinite(args.confidence) or not 0 <= args.confidence <= 1
            or not math.isfinite(args.nms_threshold)
            or not 0 <= args.nms_threshold <= 1):
        parser.error("confidence and NMS thresholds must be finite and between 0 and 1")

    torchscript = args.torchscript.expanduser().resolve()
    param = args.param.expanduser().resolve()
    model_bin = args.model_bin.expanduser().resolve()
    output = args.output.expanduser().resolve()
    for description, path in (
        ("TorchScript model", torchscript),
        ("ncnn param", param),
        ("ncnn weights", model_bin),
    ):
        if not path.is_file():
            parser.error(f"{description} does not exist: {path}")
    if output in {torchscript, param, model_bin}:
        parser.error("--output must not overwrite a model input")

    np = _require("numpy")
    cv2 = _require("cv2", "opencv-python")
    torch = _require("torch")
    ncnn = _require("ncnn")
    selected, annotation_path = _select_images(
        args.data_dir, args.split, args.image_count, args.images
    )
    if output in {path for _, path in selected}:
        parser.error("--output must not overwrite an input image")
    if annotation_path is not None and output == annotation_path.resolve():
        parser.error("--output must not overwrite the COCO annotation input")
    model = torch.jit.load(str(torchscript), map_location="cpu").eval()
    net, live_layers = _build_ncnn_net(param, model_bin, args.threads, ncnn, np)

    results = []
    try:
        for name, image_path in selected:
            image = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
            if image is None:
                raise RuntimeError(f"OpenCV could not decode image: {image_path}")
            chw = _preprocess(image, args.input_size, cv2, np)
            torch_raw = _torch_output(model, chw[np.newaxis], torch, np)
            ncnn_raw = _ncnn_output(
                net, chw, args.input_name, args.output_name, ncnn, np
            )
            shapes_match = torch_raw.shape == ncnn_raw.shape
            torch_finite = bool(np.isfinite(torch_raw).all())
            ncnn_finite = bool(np.isfinite(ncnn_raw).all())
            if shapes_match and torch_finite and ncnn_finite:
                absolute_error = np.abs(torch_raw - ncnn_raw)
                maximum = float(absolute_error.max())
                mean = float(absolute_error.mean())
            else:
                maximum = None
                mean = None
            raw_passed = bool(
                shapes_match
                and torch_finite
                and ncnn_finite
                and maximum is not None
                and maximum <= args.max_raw_error
            )

            runtime_result = None
            runtime_passed = True
            if args.runtime_preprocess_check:
                runtime_input, runtime_scale = _runtime_preprocess(
                    image, args.input_size, ncnn, np
                )
                runtime_array = np.array(runtime_input, dtype=np.float32, copy=True)
                input_shapes_match = runtime_array.shape == chw.shape
                input_finite = bool(np.isfinite(runtime_array).all())
                if input_shapes_match and input_finite:
                    input_error = np.abs(chw - runtime_array)
                    max_input_error = float(input_error.max())
                    mean_input_error = float(input_error.mean())
                else:
                    max_input_error = None
                    mean_input_error = None
                runtime_raw = _ncnn_output(
                    net, runtime_input, args.input_name, args.output_name, ncnn, np
                )
                runtime_raw_finite = bool(np.isfinite(runtime_raw).all())
                runtime_shape_match = runtime_raw.shape == torch_raw.shape
                runtime_raw_error = (
                    float(np.abs(torch_raw - runtime_raw).max())
                    if runtime_shape_match and torch_finite and runtime_raw_finite
                    else None
                )
                expected_rows = sum(
                    (args.input_size // stride) ** 2 for stride in (8, 16, 32)
                )
                decodable = (
                    torch_raw.shape == (expected_rows, 6)
                    and runtime_raw.shape == (expected_rows, 6)
                    and torch_finite
                    and runtime_raw_finite
                )
                if decodable:
                    height, width = image.shape[:2]
                    reference_detections = _decode_and_nms(
                        torch_raw, args.input_size, width, height,
                        args.confidence, args.nms_threshold, np,
                    )
                    runtime_detections = _decode_and_nms(
                        runtime_raw, args.input_size, width, height,
                        args.confidence, args.nms_threshold, np,
                    )
                    count_match = len(reference_detections) == len(runtime_detections)
                    if count_match:
                        pairs = _pair_detections(
                            reference_detections, runtime_detections, np
                        )
                        detection_error = (
                            max(
                                float(np.abs(
                                    reference_detections[left]
                                    - runtime_detections[right]
                                ).max())
                                for left, right in pairs
                            )
                            if pairs else 0.0
                        )
                    else:
                        pairs = []
                        detection_error = None
                else:
                    reference_detections = np.empty((0, 5), dtype=np.float32)
                    runtime_detections = np.empty((0, 5), dtype=np.float32)
                    count_match = False
                    pairs = []
                    detection_error = None
                detections_match = bool(
                    count_match
                    and detection_error is not None
                    and detection_error <= args.max_detection_error
                )
                runtime_passed = bool(
                    input_shapes_match
                    and input_finite
                    and max_input_error is not None
                    and max_input_error <= args.max_runtime_input_error
                    and runtime_shape_match
                    and runtime_raw_finite
                    and detections_match
                )
                runtime_result = {
                    "scale": runtime_scale,
                    "input_shape": list(runtime_array.shape),
                    "reference_input_shape": list(chw.shape),
                    "input_shapes_match": input_shapes_match,
                    "input_finite": input_finite,
                    "max_input_pixel_error": max_input_error,
                    "mean_input_pixel_error": mean_input_error,
                    "raw_shape": list(runtime_raw.shape),
                    "raw_shape_matches_reference": runtime_shape_match,
                    "raw_output_finite": runtime_raw_finite,
                    "raw_max_abs_error_from_reference": runtime_raw_error,
                    "reference_detection_count": len(reference_detections),
                    "runtime_detection_count": len(runtime_detections),
                    "detection_counts_match": count_match,
                    "detection_pairs": [list(pair) for pair in pairs],
                    "max_detection_value_error": detection_error,
                    "detections_match": detections_match,
                    "reference_detections_xyxy_confidence": reference_detections.tolist(),
                    "runtime_detections_xyxy_confidence": runtime_detections.tolist(),
                    "passed": runtime_passed,
                }
                del runtime_input
            results.append(
                {
                    "image": name,
                    "path": str(image_path),
                    "sha256": _sha256(image_path),
                    "raw_parity": {
                        "torch_shape": list(torch_raw.shape),
                        "ncnn_shape": list(ncnn_raw.shape),
                        "shapes_match": shapes_match,
                        "torch_output_finite": torch_finite,
                        "ncnn_output_finite": ncnn_finite,
                        "max_abs_error": maximum,
                        "mean_abs_error": mean,
                        "passed": raw_passed,
                    },
                    "runtime_preprocess": runtime_result,
                    "passed": raw_passed and runtime_passed,
                }
            )
    finally:
        net.clear()
        live_layers.clear()

    raw_maxima = [item["raw_parity"]["max_abs_error"] for item in results
                  if item["raw_parity"]["max_abs_error"] is not None]
    raw_passed = bool(results) and all(
        item["raw_parity"]["passed"] for item in results
    )
    runtime_items = [item["runtime_preprocess"] for item in results
                     if item["runtime_preprocess"] is not None]
    runtime_passed = (
        bool(runtime_items) and all(item["passed"] for item in runtime_items)
        if args.runtime_preprocess_check else None
    )
    runtime_input_maxima = [item["max_input_pixel_error"] for item in runtime_items
                            if item["max_input_pixel_error"] is not None]
    detection_maxima = [item["max_detection_value_error"] for item in runtime_items
                        if item["max_detection_value_error"] is not None]
    passed = raw_passed and (runtime_passed is not False)
    report = {
        "schema_version": 1,
        "comparison": "torchscript_vs_ncnn_conversion_and_runtime_preprocess",
        "passed": passed,
        "images": len(results),
        "input_size": args.input_size,
        "input_name": args.input_name,
        "output_name": args.output_name,
        "gates": {
            "raw_output_parity": {
                "passed": raw_passed,
                "max_error_allowed": args.max_raw_error,
                "maximum_error": max(raw_maxima) if raw_maxima else None,
            },
            "runtime_preprocess_and_detections": {
                "enabled": args.runtime_preprocess_check,
                "passed": runtime_passed,
                "max_input_pixel_error_allowed": args.max_runtime_input_error,
                "maximum_input_pixel_error": (
                    max(runtime_input_maxima) if runtime_input_maxima else None
                ),
                "max_detection_value_error_allowed": args.max_detection_error,
                "maximum_detection_value_error": (
                    max(detection_maxima) if detection_maxima else None
                ),
                "all_detection_counts_match": (
                    all(item["detection_counts_match"] for item in runtime_items)
                    if runtime_items else None
                ),
                "confidence": args.confidence,
                "nms_threshold": args.nms_threshold,
                "strides": [8, 16, 32],
            },
        },
        "preprocessing": {
            "color_order": "BGR",
            "resize": "aspect_fit_top_left",
            "padding_value": 114,
            "normalization": "none",
            "tensor_layout": "CHW_float32",
            "runtime_path": "ncnn Mat.from_pixels_resize PIXEL_BGR then right/bottom 114 border",
        },
        "float32_parity_options": {
            "use_fp16_packed": False,
            "use_fp16_storage": False,
            "use_fp16_arithmetic": False,
            "use_bf16_storage": False,
            "use_packing_layout": False,
            "threads": args.threads,
        },
        "artifacts": {
            "torchscript": _artifact(torchscript),
            "ncnn_param": _artifact(param),
            "ncnn_bin": _artifact(model_bin),
        },
        "input_data": {
            "mode": "coco_split" if annotation_path is not None else "explicit_images",
            "split": args.split if annotation_path is not None else None,
            "annotation": (
                _artifact(annotation_path) if annotation_path is not None else None
            ),
        },
        "runtime_versions": {
            "python": sys.version.split()[0],
            "numpy": np.__version__,
            "opencv": cv2.__version__,
            "torch": torch.__version__,
            "ncnn": getattr(ncnn, "__version__", None),
        },
        "results": results,
    }
    _write_report(output, report)
    print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except (FileNotFoundError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"error: {error}") from error
