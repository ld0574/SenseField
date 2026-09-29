"""Shared category-aware YOLOX decoding helpers.

YOLOX emits one row per grid cell with ``5 + C`` values:
``cx_offset, cy_offset, log_width, log_height, objectness, class_probs...``.
Historically this project assumed ``C == 1`` in each consumer.  The helpers in
this module keep that format working while making the class order explicit and
using per-class NMS when more than one class is present.

The model metadata ``classes`` array is the canonical class order.  COCO
categories are converted to the same zero-based model order by sorting their
numeric ids; category ids themselves do not need to be contiguous.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


DEFAULT_CLASSES: tuple[str, ...] = ("minimap_enemy",)
YOLOX_STRIDES: tuple[int, ...] = (8, 16, 32)


def normalize_classes(value: object, label: str = "classes") -> tuple[str, ...]:
    """Validate and normalize a model class list.

    Empty class lists, duplicate names, and non-string names are rejected.  A
    tuple is returned so callers cannot accidentally mutate the canonical
    order while constructing a model or report.
    """
    if not isinstance(value, (list, tuple)) or not value:
        raise ValueError(f"{label} must be a non-empty list of class names")
    names: list[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str) or not item.strip():
            raise ValueError(f"{label} must contain non-empty string names")
        name = item.strip()
        if name in seen:
            raise ValueError(f"{label} contains duplicate class name {name!r}")
        seen.add(name)
        names.append(name)
    return tuple(names)


def classes_from_coco(document: dict[str, Any],
                      default: Sequence[str] = DEFAULT_CLASSES) -> tuple[str, ...]:
    """Return canonical model classes from a COCO document.

    Existing geometry-only fixtures and old one-class datasets can omit the
    ``categories`` member; those continue to mean the historical enemy class.
    If categories are present, every annotation category is validated and the
    category names are ordered by category id for deterministic model indices.
    """
    categories = document.get("categories")
    if categories is None:
        return normalize_classes(default, "default classes")
    if not isinstance(categories, list) or not categories:
        raise ValueError("COCO categories must be a non-empty list")
    entries: list[tuple[int, str]] = []
    seen_ids: set[int] = set()
    for item in categories:
        if not isinstance(item, dict):
            raise ValueError("COCO categories must contain objects")
        identifier = item.get("id")
        name = item.get("name")
        if (not isinstance(identifier, int) or isinstance(identifier, bool) or
                not isinstance(name, str) or not name.strip()):
            raise ValueError("COCO categories need integer id and non-empty name")
        if identifier in seen_ids:
            raise ValueError(f"COCO categories contain duplicate id {identifier}")
        seen_ids.add(identifier)
        entries.append((identifier, name.strip()))
    entries.sort(key=lambda item: item[0])
    names = normalize_classes([name for _, name in entries], "COCO category names")
    known_ids = {identifier for identifier, _ in entries}
    for item in document.get("annotations", []):
        if not isinstance(item, dict):
            raise ValueError("COCO annotations must contain objects")
        category_id = item.get("category_id")
        if (not isinstance(category_id, int) or isinstance(category_id, bool) or
                category_id not in known_ids):
            raise ValueError(f"COCO annotation references unknown category {category_id!r}")
    return names


def classes_from_coco_path(path: Path,
                           default: Sequence[str] = DEFAULT_CLASSES) -> tuple[str, ...]:
    """Load a COCO annotation file and return its canonical class order."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read COCO annotations {path}: {error}") from error
    if not isinstance(document, dict):
        raise ValueError(f"COCO annotations must be a JSON object: {path}")
    return classes_from_coco(document, default)


def classes_from_metadata(metadata: object,
                          default: Sequence[str] = DEFAULT_CLASSES) -> tuple[str, ...]:
    """Read the canonical ``classes`` array from model metadata."""
    if metadata is None:
        return normalize_classes(default, "default classes")
    if isinstance(metadata, (str, Path)):
        path = Path(metadata)
        try:
            metadata = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError(f"cannot read model metadata {path}: {error}") from error
    if not isinstance(metadata, dict):
        raise ValueError("model metadata must be an object")
    value = metadata.get("classes")
    if value is None:
        # Older development metadata did not declare classes.  Keep those
        # artifacts usable while every newly written report includes classes.
        return normalize_classes(default, "default classes")
    return normalize_classes(value, "model metadata classes")


def resolve_classes(*, classes: object = None, metadata: object = None,
                    coco: dict[str, Any] | None = None,
                    default: Sequence[str] = DEFAULT_CLASSES) -> tuple[str, ...]:
    """Resolve one class order and reject conflicting declarations.

    Explicit classes take precedence, followed by metadata, then COCO.  When
    multiple sources are provided they must agree, preventing a model from
    silently interpreting an enemy score as a player score.
    """
    sources: list[tuple[str, tuple[str, ...]]] = []
    if classes is not None:
        sources.append(("explicit classes", normalize_classes(classes, "classes")))
    if metadata is not None:
        sources.append(("model metadata", classes_from_metadata(metadata, default)))
    coco_has_categories = (
        coco is not None and "categories" in coco and coco.get("categories") is not None
    )
    if coco_has_categories:
        sources.append(("COCO categories", classes_from_coco(coco, default)))
    elif coco is not None and not sources:
        # Geometry-only legacy annotations do not declare a class order.  An
        # explicit or metadata class list can still define the model contract.
        sources.append(("COCO default classes", normalize_classes(default, "default classes")))
    if not sources:
        return normalize_classes(default, "default classes")
    chosen = sources[0][1]
    if coco is not None and not coco_has_categories and len(chosen) > 1:
        raise ValueError(
            "COCO annotations must declare categories when the model has multiple classes"
        )
    for source, value in sources[1:]:
        if value != chosen:
            raise ValueError(
                f"class order mismatch: {sources[0][0]}={list(chosen)!r}, "
                f"{source}={list(value)!r}"
            )
    return chosen


def confidence_thresholds(value: object, classes: Sequence[str],
                          default: float = 0.0) -> dict[str, float]:
    """Normalize a scalar or per-class confidence configuration.

    New metadata should write ``postprocess.confidence_by_class``.  A scalar
    remains valid for old one-class artifacts and is expanded to every class.
    Mapping keys must be the complete canonical class list so a newly added
    player class cannot accidentally inherit an enemy threshold.
    """
    class_names = normalize_classes(classes)
    configured = default if value is None else value
    if isinstance(configured, Mapping):
        keys = set(configured)
        expected = set(class_names)
        if keys != expected:
            raise ValueError(
                f"confidence_by_class keys must equal classes: expected {sorted(expected)}, "
                f"got {sorted(keys)}"
            )
        result = {}
        for name in class_names:
            threshold = configured[name]
            if (not isinstance(threshold, (int, float)) or isinstance(threshold, bool) or
                    not math.isfinite(float(threshold)) or not 0 <= float(threshold) <= 1):
                raise ValueError(f"confidence threshold for {name!r} must be in [0,1]")
            result[name] = float(threshold)
        return result
    if (not isinstance(configured, (int, float)) or isinstance(configured, bool) or
            not math.isfinite(float(configured)) or not 0 <= float(configured) <= 1):
        raise ValueError("confidence must be finite and between 0 and 1")
    return {name: float(configured) for name in class_names}


def confidence_from_metadata(metadata: object,
                             classes: Sequence[str],
                             default: float = 0.0) -> dict[str, float]:
    """Read per-class thresholds from model metadata with legacy fallback."""
    if isinstance(metadata, (str, Path)):
        path = Path(metadata)
        try:
            metadata = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError(f"cannot read model metadata {path}: {error}") from error
    if metadata is None:
        return confidence_thresholds(default, classes)
    if not isinstance(metadata, dict):
        raise ValueError("model metadata must be an object")
    postprocess = metadata.get("postprocess", {})
    if postprocess is None:
        postprocess = {}
    if not isinstance(postprocess, dict):
        raise ValueError("model metadata postprocess must be an object")
    value = postprocess.get("confidence_by_class")
    if value is None:
        value = postprocess.get("confidence")
    # A few early sidecars wrote these fields at the metadata root.  Reading
    # them here keeps every consumer on the same precedence path while newly
    # written metadata continues to use postprocess.*.
    if value is None:
        value = metadata.get("confidence_by_class")
    if value is None:
        value = metadata.get("confidence", default)
    return confidence_thresholds(value, classes, default)


def decoded_output_width(classes: Sequence[str] | int = DEFAULT_CLASSES) -> int:
    """Return the expected YOLOX raw width ``5 + class_count``."""
    count = classes if isinstance(classes, int) else len(classes)
    if isinstance(count, bool) or count < 1:
        raise ValueError("class count must be a positive integer")
    return 5 + int(count)


def yolox_tensor_contract(input_size: int,
                          classes: Sequence[str] = DEFAULT_CLASSES
                          ) -> dict[str, list[int]]:
    """Return canonical model input/output tensor shapes.

    Android reads the top-level ``input`` and ``output`` arrays from model
    metadata.  Training and conversion reports retain their historical shape
    field names for compatibility, but newly written artifacts derive all
    shape values from this contract.
    """
    class_names = normalize_classes(classes)
    if (isinstance(input_size, bool) or not isinstance(input_size, int) or
            input_size < 32 or input_size % 32):
        raise ValueError("input_size must be an integer at least 32 and divisible by 32")
    anchors = sum((input_size // stride) ** 2 for stride in YOLOX_STRIDES)
    return {
        "input": [1, 3, input_size, input_size],
        "output": [1, anchors, decoded_output_width(class_names)],
    }


def _raw_rows(raw: Any, np: Any) -> tuple[Any, tuple[int, ...]]:
    values = np.asarray(raw)
    if values.ndim == 3:
        if values.shape[0] != 1:
            raise ValueError(f"YOLOX raw batch must contain one image, got {values.shape}")
        return values[0], values.shape
    if values.ndim == 2:
        return values, values.shape
    raise ValueError(f"YOLOX raw output must be [N,5+C] or [1,N,5+C], got {values.shape}")


def decode_yolox(raw: Any, input_size: int, np: Any = None,
                 strides: Iterable[int] = YOLOX_STRIDES) -> Any:
    """Decode YOLOX grid offsets while preserving all class score columns."""
    if np is None:
        import numpy as np_module
        np = np_module
    rows, original_shape = _raw_rows(raw, np)
    stride_values = tuple(int(stride) for stride in strides)
    if input_size < 1 or not stride_values:
        raise ValueError("input_size and strides must be positive")
    expected_rows = sum((input_size // stride) ** 2 for stride in stride_values)
    if rows.shape[0] != expected_rows or rows.shape[1] < 6:
        raise ValueError(
            f"YOLOX raw output shape must be ({expected_rows},5+C), got {tuple(rows.shape)}"
        )
    grids = []
    expanded_strides = []
    for stride in stride_values:
        side = input_size // stride
        y, x = np.meshgrid(np.arange(side), np.arange(side), indexing="ij")
        grids.append(np.stack((x, y), axis=2).reshape(-1, 2))
        expanded_strides.append(np.full((side * side, 1), stride))
    grid = np.concatenate(grids, axis=0).astype(rows.dtype, copy=False)
    expanded_stride = np.concatenate(expanded_strides, axis=0).astype(rows.dtype, copy=False)
    decoded = rows.copy()
    decoded[:, :2] = (decoded[:, :2] + grid) * expanded_stride
    # Match Android's bounded exp() exactly.  Besides preventing overflow on
    # malformed logits, this keeps Python/ONNX diagnostics and native replay
    # from producing different boxes for the same raw output.
    with np.errstate(over="ignore", invalid="ignore"):
        decoded[:, 2:4] = np.exp(np.clip(decoded[:, 2:4], -10.0, 10.0)) * expanded_stride
    if len(original_shape) == 3:
        return decoded[np.newaxis, ...]
    return decoded


def _iou(left: Any, right: Any) -> float:
    intersection_width = max(0.0, min(float(left[2]), float(right[2])) -
                            max(float(left[0]), float(right[0])))
    intersection_height = max(0.0, min(float(left[3]), float(right[3])) -
                             max(float(left[1]), float(right[1])))
    intersection = intersection_width * intersection_height
    left_area = max(0.0, float(left[2]) - float(left[0])) * max(
        0.0, float(left[3]) - float(left[1]))
    right_area = max(0.0, float(right[2]) - float(right[0])) * max(
        0.0, float(right[3]) - float(right[1]))
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def class_aware_nms(boxes: Any, scores: Any, class_ids: Any,
                    threshold: float, np: Any = None) -> Any:
    """Return indices kept by greedy NMS, independently for each class."""
    if np is None:
        import numpy as np_module
        np = np_module
    if not 0 <= threshold <= 1 or not math.isfinite(float(threshold)):
        raise ValueError("NMS threshold must be finite and between 0 and 1")
    boxes = np.asarray(boxes)
    scores = np.asarray(scores)
    class_ids = np.asarray(class_ids)
    if len(boxes) != len(scores) or len(boxes) != len(class_ids):
        raise ValueError("boxes, scores, and class_ids must have the same length")
    if not len(boxes):
        return np.empty((0,), dtype=np.int64)
    order = sorted(range(len(boxes)), key=lambda index: (-float(scores[index]), index))
    kept: list[int] = []
    for index in order:
        if all(int(class_ids[index]) != int(class_ids[other]) or
               _iou(boxes[index], boxes[other]) <= threshold for other in kept):
            kept.append(index)
    return np.asarray(kept, dtype=np.int64)


def candidates_from_raw(raw: Any, input_size: int, image_width: int,
                       image_height: int, confidence: object,
                       nms_threshold: float, classes: Sequence[str] = DEFAULT_CLASSES,
                       np: Any = None) -> Any:
    """Decode raw output and return ``[x0,y0,x1,y1,score,class_index]`` rows."""
    if np is None:
        import numpy as np_module
        np = np_module
    class_names = normalize_classes(classes)
    rows = decode_yolox(raw, input_size, np=np)
    rows = rows[0] if rows.ndim == 3 else rows
    if rows.shape[1] != decoded_output_width(class_names):
        raise ValueError(
            f"YOLOX raw output width {rows.shape[1]} does not match {len(class_names)} "
            f"classes (expected {decoded_output_width(class_names)})"
        )
    if image_width <= 0 or image_height <= 0:
        raise ValueError("image dimensions must be positive")
    scale = min(input_size / image_width, input_size / image_height)
    centers = rows[:, :2]
    sizes = rows[:, 2:4]
    objectness = rows[:, 4]
    thresholds = confidence_thresholds(confidence, class_names)
    class_scores = rows[:, 5:]
    # Match the native decoder: apply each class's threshold to its complete
    # objectness-weighted score first, then choose the highest surviving class.
    # This matters when the highest-scoring class is below its stricter
    # threshold while a lower-scoring class clears its own threshold.
    threshold_array = np.asarray(
        [thresholds[name] for name in class_names], dtype=np.float32
    )
    with np.errstate(over="ignore", invalid="ignore"):
        class_confidences = objectness[:, None] * class_scores
    eligible_classes = np.isfinite(class_confidences)
    eligible_classes &= class_confidences >= threshold_array[None, :]
    filtered_confidences = np.where(
        eligible_classes, class_confidences, -np.inf
    )
    class_ids = np.argmax(filtered_confidences, axis=1).astype(np.int64)
    scores = filtered_confidences[np.arange(len(rows)), class_ids]
    boxes = np.empty((len(rows), 4), dtype=np.float32)
    boxes[:, 0] = centers[:, 0] - sizes[:, 0] * 0.5
    boxes[:, 1] = centers[:, 1] - sizes[:, 1] * 0.5
    boxes[:, 2] = centers[:, 0] + sizes[:, 0] * 0.5
    boxes[:, 3] = centers[:, 1] + sizes[:, 1] * 0.5
    boxes[:, [0, 2]] /= scale
    boxes[:, [1, 3]] /= scale
    boxes[:, [0, 2]] = np.clip(boxes[:, [0, 2]], 0.0, float(image_width))
    boxes[:, [1, 3]] = np.clip(boxes[:, [1, 3]], 0.0, float(image_height))
    valid = eligible_classes.any(axis=1) & np.isfinite(scores)
    valid &= (boxes[:, 2] - boxes[:, 0] >= 1.0) & (boxes[:, 3] - boxes[:, 1] >= 1.0)
    candidate_boxes = boxes[valid]
    candidate_scores = scores[valid]
    candidate_classes = class_ids[valid]
    keep = class_aware_nms(candidate_boxes, candidate_scores, candidate_classes,
                           nms_threshold, np=np)
    if not len(keep):
        return np.empty((0, 6), dtype=np.float32)
    result = np.column_stack((candidate_boxes[keep], candidate_scores[keep],
                              candidate_classes[keep].astype(np.float32)))
    return result.astype(np.float32, copy=False)
