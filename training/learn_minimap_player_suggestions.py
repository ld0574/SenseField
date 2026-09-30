#!/usr/bin/env python3
"""Learn review-only player-box suggestions from a manual review queue.

The model in this file is a candidate ranker.  It scores hand-built green
annulus candidates so that a reviewer can reach useful frames sooner.  Its
checkpoint and predictions are review aids only: they are not labels, metric
evidence, or an Android release model.

The source queue is opened through a read-only SQLite URI.  This is deliberate
because running a learning job must never update the review database.  Only
``corrected`` and ``negative`` tasks are used as labels; pending suggestions
and other non-training states are never promoted to training truth.

Example::

    .venv/bin/python training/learn_minimap_player_suggestions.py \
        --source data/private/minimap-player-review-queue-v1 \
        --output build/player-label-audit/candidate-ranker-v1

The output directory contains ``checkpoint.pt``, ``report.json`` and
``pending-predictions.json``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sqlite3
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterator, Mapping, Sequence
from urllib.parse import quote


TRAINING_STATUSES = frozenset({"corrected", "negative"})
PENDING_STATUS = "pending"
SEALED_VIDEO_PREFIXES = ("video9", "video12")
DEFAULT_SEED = 20260930
DEFAULT_EPOCHS = 25
DEFAULT_BATCH_SIZE = 96
DEFAULT_PENDING_BATCH_SIZE = 8
DEFAULT_THRESHOLD_PRECISION = 0.95
FRAME_CENTER_TOLERANCE_PX = 20.0
PATCH_SIZE = 64
INPUT_CHANNELS = ("r", "g", "b", "h", "s", "v")
ARTIFACT_WARNING = (
    "Review aid only. This checkpoint/prediction is not ground truth, "
    "training truth, evaluation evidence, or a release capability metric."
)


def _json_load(value: Any, label: str) -> Any:
    """Decode a JSON column or return an already decoded value."""
    if value is None:
        return None
    if isinstance(value, str) and not value.strip():
        return None
    if isinstance(value, (list, dict)):
        return value
    try:
        return json.loads(value)
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is not valid JSON") from error


def validate_box(box: Any, label: str = "box") -> list[float]:
    """Validate and normalize one full-frame ``[x, y, w, h]`` box.

    This is intentionally dependency-free so it can be tested without loading
    NumPy, OpenCV, or PyTorch.
    """
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        raise ValueError(f"{label} must be [x, y, width, height]")
    values: list[float] = []
    for value in box:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{label} values must be finite numbers")
        number = float(value)
        if not math.isfinite(number):
            raise ValueError(f"{label} values must be finite numbers")
        values.append(number)
    x, y, width, height = values
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return [round(value, 7) for value in values]


def final_boxes(row: Mapping[str, Any]) -> list[list[float]]:
    """Return the manually reviewed boxes for a training-eligible task.

    The player queue has a strict one-player invariant.  A corrected task must
    therefore have exactly one final box, while a negative task must have no
    final boxes.  Suggestions are deliberately ignored here.
    """
    status = row.get("review_status")
    raw = _json_load(row.get("reviewed_boxes"), "reviewed_boxes")
    boxes = [] if raw is None else raw
    if not isinstance(boxes, list):
        raise ValueError(f"task {row.get('id')} reviewed_boxes must be a list")
    if status == "negative":
        if boxes:
            raise ValueError(
                f"task {row.get('id')} is negative but has reviewed boxes"
            )
        return []
    if status != "corrected":
        raise ValueError(
            f"task {row.get('id')} is not a training final status: {status!r}"
        )
    if len(boxes) != 1:
        raise ValueError(
            f"task {row.get('id')} has {len(boxes)} reviewed boxes; "
            "minimap_player requires at most one box per frame"
        )
    return [validate_box(boxes[0], f"task {row.get('id')} reviewed_boxes[0]")]


def is_sealed_match(match_id: str) -> bool:
    """Return whether a match belongs to a source that must stay sealed."""
    lowered = str(match_id).lower()
    return lowered.startswith(SEALED_VIDEO_PREFIXES)


def validate_roi(value: Any, label: str = "widget_roi") -> list[float]:
    """Validate a normalized ROI without importing project runtime helpers."""
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        raise ValueError(f"{label} must be [x, y, width, height]")
    values: list[float] = []
    for item in value:
        if isinstance(item, bool) or not isinstance(item, (int, float)):
            raise ValueError(f"{label} values must be finite numbers")
        number = float(item)
        if not math.isfinite(number):
            raise ValueError(f"{label} values must be finite numbers")
        values.append(number)
    x, y, width, height = values
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return values


def resolve_widget_roi(document: Mapping[str, Any],
                       match: Mapping[str, Any]) -> list[float]:
    """Get a match-specific widget ROI, falling back to the manifest ROI."""
    value = match.get("widget_roi", document.get("widget_roi"))
    return validate_roi(value, f"{match.get('id', 'match')} widget_roi")


def manifest_match_index(document: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    """Validate the manifest shape and return its matches keyed by id."""
    if document.get("kind") != "minimap_player":
        raise ValueError("source queue must have kind=minimap_player")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("source queue manifest must contain matches")
    indexed: dict[str, dict[str, Any]] = {}
    for match in matches:
        if not isinstance(match, dict) or not isinstance(match.get("id"), str):
            raise ValueError("each manifest match needs a string id")
        match_id = match["id"]
        if match_id in indexed:
            raise ValueError(f"duplicate manifest match {match_id}")
        if is_sealed_match(match_id):
            raise ValueError(
                f"sealed match {match_id} must not be read or scored"
            )
        split = match.get("split")
        if not isinstance(split, str) or not split:
            raise ValueError(f"match {match_id} is missing manifest split")
        resolve_widget_roi(document, match)
        samples = match.get("samples", [])
        if not isinstance(samples, list):
            raise ValueError(f"match {match_id} samples must be a list")
        seen_times: set[int] = set()
        for sample in samples:
            if not isinstance(sample, dict) or "at_ms" not in sample:
                raise ValueError(f"match {match_id} has an invalid sample")
            at_ms = sample["at_ms"]
            if isinstance(at_ms, bool) or not isinstance(at_ms, int):
                raise ValueError(f"match {match_id} sample at_ms must be an integer")
            if at_ms in seen_times:
                raise ValueError(f"duplicate sample {match_id}@{at_ms}")
            seen_times.add(at_ms)
        indexed[match_id] = match
    return indexed


def choose_threshold(metrics: Sequence[Mapping[str, Any]],
                     min_precision: float = DEFAULT_THRESHOLD_PRECISION
                     ) -> dict[str, Any]:
    """Choose the highest-recall threshold meeting the precision target.

    Ties prefer higher precision and then the higher threshold.  If no
    threshold reaches the requested precision, the best F1 threshold is used
    and the report records that the constraint was unmet.
    """
    if not metrics:
        raise ValueError("cannot choose a threshold without metrics")
    if not 0 <= min_precision <= 1:
        raise ValueError("min_precision must be between 0 and 1")
    eligible = [item for item in metrics
                if float(item.get("precision", 0.0)) >= min_precision]
    if eligible:
        best = max(eligible, key=lambda item: (
            float(item.get("recall", 0.0)),
            float(item.get("precision", 0.0)),
            float(item.get("threshold", 0.0)),
        ))
        selected_by = "minimum_precision_then_recall"
    else:
        best = max(metrics, key=lambda item: (
            float(item.get("f1", 0.0)),
            float(item.get("precision", 0.0)),
            float(item.get("recall", 0.0)),
            float(item.get("threshold", 0.0)),
        ))
        selected_by = "best_f1_precision_constraint_unmet"
    result = dict(best)
    result["selection"] = selected_by
    result["minimum_precision"] = min_precision
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_only_connection(path: Path) -> sqlite3.Connection:
    """Open SQLite in read-only mode, including when the path has spaces."""
    uri = f"file:{quote(str(path.resolve()), safe='/')}?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def _safe_source_path(source: Path, relative: str, label: str) -> Path:
    if not isinstance(relative, str) or not relative:
        raise ValueError(f"{label} must be a non-empty relative path")
    path = (source / relative).resolve()
    try:
        path.relative_to(source.resolve())
    except ValueError as error:
        raise ValueError(f"{label} escapes source queue: {relative!r}") from error
    if not path.is_file():
        raise FileNotFoundError(f"missing {label}: {path}")
    return path


def _load_queue(source: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]],
                                list[dict[str, Any]]]:
    """Read and validate manifest metadata and all task rows without writes."""
    source = source.resolve()
    manifest_path = source / "review-manifest.json"
    database_path = source / "annotations.sqlite3"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"missing review-manifest.json: {manifest_path}")
    if not database_path.is_file():
        raise FileNotFoundError(f"missing annotations.sqlite3: {database_path}")
    document = json.loads(manifest_path.read_text(encoding="utf-8"))
    matches = manifest_match_index(document)
    with _read_only_connection(database_path) as connection:
        try:
            rows = [dict(row) for row in connection.execute(
                "SELECT * FROM tasks ORDER BY id"
            )]
        except sqlite3.Error as error:
            raise ValueError(f"could not read tasks from source database: {error}") from error
    if not rows:
        raise ValueError("source queue contains no tasks")
    seen_keys: set[tuple[str, int]] = set()
    for row in rows:
        match_id = row.get("match_id")
        at_ms = row.get("at_ms")
        if match_id not in matches:
            raise ValueError(f"task {row.get('id')} references unknown match {match_id!r}")
        if isinstance(at_ms, bool) or not isinstance(at_ms, int):
            raise ValueError(f"task {row.get('id')} has invalid at_ms")
        key = (match_id, at_ms)
        if key in seen_keys:
            raise ValueError(f"duplicate task frame {match_id}@{at_ms}")
        seen_keys.add(key)
        status = row.get("review_status")
        if status not in TRAINING_STATUSES and status != PENDING_STATUS and status not in {
            "accepted", "skip", "excluded"
        }:
            raise ValueError(f"task {row.get('id')} has unknown review_status {status!r}")
        # Validate final rows before any image work.  This keeps an accidental
        # multi-box frame from silently entering either training or reporting.
        if status in TRAINING_STATUSES:
            final_boxes(row)
        _safe_source_path(source, row.get("frame"), f"task {row.get('id')} frame")
    return document, matches, rows


def _import_image_dependencies() -> tuple[Any, Any, Any]:
    try:
        import cv2  # type: ignore
        import numpy as np  # type: ignore
        from PIL import Image  # type: ignore
    except ImportError as error:  # pragma: no cover - environment dependent
        raise RuntimeError(
            "training requires numpy, opencv-python and Pillow; "
            "use the project .venv"
        ) from error
    return cv2, np, Image


def _green_annulus_proposals(image: Any, top_k: int = 50) -> list[tuple[float, int, int, int]]:
    """Generate deterministic broad green annulus candidate centers."""
    cv2, np, _ = _import_image_dependencies()
    rgb = np.asarray(image)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    mask = cv2.inRange(
        hsv, np.array((35, 40, 40), np.uint8), np.array((90, 255, 255), np.uint8)
    ).astype(np.float32) / 255
    maps = []
    radii = np.arange(7, 24, dtype=np.int16)
    for radius in radii:
        edge = int(radius) + 2
        yy, xx = np.ogrid[-edge:edge + 1, -edge:edge + 1]
        distance = np.sqrt(xx * xx + yy * yy)
        kernel = ((distance >= radius - 1.5) &
                  (distance <= radius + 1.5)).astype(np.float32)
        maps.append(cv2.filter2D(
            mask, cv2.CV_32F, kernel, borderType=cv2.BORDER_CONSTANT
        ) / kernel.sum())
    stack = np.stack(maps)
    scores = stack.max(0)
    best_radius = radii[stack.argmax(0)]
    peaks = cv2.dilate(scores, np.ones((11, 11), np.uint8))
    ys, xs = np.where((scores >= 0.025) & (scores >= peaks - 1e-7))
    ordered = sorted(
        ((float(scores[y, x]), int(x), int(y), int(best_radius[y, x]))
         for y, x in zip(ys, xs)),
        reverse=True,
    )
    selected: list[tuple[float, int, int, int]] = []
    for candidate in ordered:
        _, x, y, _ = candidate
        if all((x - previous[1]) ** 2 + (y - previous[2]) ** 2 >= 14 ** 2
               for previous in selected):
            selected.append(candidate)
        if len(selected) >= top_k:
            break
    return selected


def _tensor_patch(image: Any, x: int, y: int, radius: int) -> Any:
    """Build the six-channel RGB/HSV patch used by the candidate ranker."""
    cv2, np, _ = _import_image_dependencies()
    side = max(52, min(84, int(round(radius * 3.4))))
    array = np.asarray(image)
    pad = side // 2 + 2
    array = np.pad(array, ((pad, pad), (pad, pad), (0, 0)), mode="reflect")
    xx, yy = x + pad, y + pad
    patch = array[yy - side // 2:yy - side // 2 + side,
                  xx - side // 2:xx - side // 2 + side]
    patch = cv2.resize(
        patch, (PATCH_SIZE, PATCH_SIZE),
        interpolation=cv2.INTER_AREA if side >= PATCH_SIZE else cv2.INTER_CUBIC,
    )
    hsv = cv2.cvtColor(patch, cv2.COLOR_RGB2HSV)
    rgb = patch.astype(np.float32) / 255
    channels = np.concatenate([
        rgb,
        (hsv[..., 0].astype(np.float32) / 179)[..., None],
        (hsv[..., 1].astype(np.float32) / 255)[..., None],
        (hsv[..., 2].astype(np.float32) / 255)[..., None],
    ], axis=2)
    return channels.transpose(2, 0, 1).astype(np.float32)


def _crop_widget(source: Path, row: Mapping[str, Any],
                 match: Mapping[str, Any], document: Mapping[str, Any]
                 ) -> tuple[Any, tuple[int, int, int, int], tuple[int, int]]:
    _, _, Image = _import_image_dependencies()
    image_path = _safe_source_path(source, row["frame"], f"task {row['id']} frame")
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    x, y, roi_width, roi_height = resolve_widget_roi(document, match)
    rect = (
        round(x * width), round(y * height),
        round((x + roi_width) * width), round((y + roi_height) * height),
    )
    if rect[2] <= rect[0] or rect[3] <= rect[1]:
        raise ValueError(f"match {match['id']} has an empty widget ROI")
    return image.crop(rect), rect, (width, height)


def _record_with_candidates(source: Path, row: Mapping[str, Any],
                            match: Mapping[str, Any],
                            document: Mapping[str, Any]) -> dict[str, Any]:
    """Load one frame and attach broad proposals plus optional final truth."""
    _, np, _ = _import_image_dependencies()
    crop, rect, full_size = _crop_widget(source, row, match, document)
    candidates = _green_annulus_proposals(crop)
    boxes = final_boxes(row) if row["review_status"] in TRAINING_STATUSES else []
    full_width, full_height = full_size
    ground_truth: list[tuple[float, float, list[float]]] = []
    for box in boxes:
        gx = (box[0] + box[2] / 2) * full_width - rect[0]
        gy = (box[1] + box[3] / 2) * full_height - rect[1]
        ground_truth.append((gx, gy, box))
    positive_indices: set[int] = set()
    nearest_distances: list[float] = []
    for gx, gy, _ in ground_truth:
        distances = [math.hypot(item[1] - gx, item[2] - gy)
                     for item in candidates]
        if not distances:
            nearest_distances.append(math.inf)
            continue
        nearest = min(range(len(distances)), key=distances.__getitem__)
        nearest_distances.append(distances[nearest])
        if distances[nearest] <= FRAME_CENTER_TOLERANCE_PX:
            positive_indices.add(nearest)
    items: list[dict[str, Any]] = []
    negative_count = 0
    for index, (ring_score, x, y, radius) in enumerate(candidates):
        near = min((math.hypot(x - gx, y - gy) for gx, gy, _ in ground_truth),
                   default=math.inf)
        if index in positive_indices:
            label = 1
        elif near <= 28:
            continue
        else:
            label = 0
        if label == 0 and negative_count >= 30:
            continue
        if label == 0:
            negative_count += 1
        items.append({
            "patch": _tensor_patch(crop, x, y, radius),
            "label": label,
            "x": x,
            "y": y,
            "radius": radius,
            "ring_score": ring_score,
        })
    return {
        "task_id": row["id"],
        "match_id": row["match_id"],
        "at_ms": row["at_ms"],
        "frame": row["frame"],
        "split": match["split"],
        "review_status": row["review_status"],
        "ground_truth": ground_truth,
        "items": items,
        "rect": rect,
        "full_size": full_size,
        "nearest_truth_distances": nearest_distances,
        "candidate_count": len(candidates),
    }


def _set_deterministic_seed(seed: int) -> Any:
    """Seed Python, NumPy and PyTorch and return the imported torch module."""
    try:
        import numpy as np  # type: ignore
        import torch  # type: ignore
    except ImportError as error:  # pragma: no cover - environment dependent
        raise RuntimeError(
            "training requires numpy and torch; use the project .venv"
        ) from error
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if hasattr(torch, "use_deterministic_algorithms"):
        torch.use_deterministic_algorithms(True, warn_only=True)
    if hasattr(torch.backends, "cudnn"):
        torch.backends.cudnn.deterministic = True
        torch.backends.cudnn.benchmark = False
    return torch


def _device(requested: str, torch: Any) -> str:
    if requested == "cpu":
        return "cpu"
    available = bool(getattr(torch.backends, "mps", None) and
                     torch.backends.mps.is_available())
    if requested == "mps" and not available:
        raise RuntimeError("--device mps requested but MPS is unavailable")
    return "mps" if requested == "auto" and available else "cpu"


def _new_model(torch: Any) -> Any:
    from torch import nn

    def block(channels_in: int, channels_out: int) -> Any:
        return nn.Sequential(
            nn.Conv2d(channels_in, channels_out, 3, padding=1),
            nn.BatchNorm2d(channels_out),
            nn.SiLU(),
            nn.MaxPool2d(2),
        )

    class CandidateRanker(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.features = nn.Sequential(
                block(6, 24), block(24, 48), block(48, 96),
                nn.Conv2d(96, 128, 3, padding=1),
                nn.BatchNorm2d(128), nn.SiLU(), nn.AdaptiveAvgPool2d(1),
            )
            self.head = nn.Sequential(
                nn.Flatten(), nn.Dropout(0.2), nn.Linear(128, 1),
            )

        def forward(self, inputs: Any) -> Any:
            return self.head(self.features(inputs)).squeeze(1)

    return CandidateRanker()


def _train_model(train_records: Sequence[Mapping[str, Any]], seed: int,
                 epochs: int, batch_size: int, requested_device: str
                 ) -> tuple[Any, str, dict[str, Any]]:
    """Train the candidate ranker with deterministic balanced sampling."""
    if epochs <= 0:
        raise ValueError("epochs must be positive")
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    torch = _set_deterministic_seed(seed)
    _, np, _ = _import_image_dependencies()
    flattened = [item for record in train_records for item in record["items"]]
    if not flattened:
        raise ValueError("train split produced no candidate patches")
    labels = np.asarray([item["label"] for item in flattened], dtype=np.int64)
    counts = Counter(labels.tolist())
    if not counts.get(1) or not counts.get(0):
        raise ValueError("train split needs both positive and negative candidates")
    patches = np.stack([item["patch"] for item in flattened]).astype(np.float32)
    probabilities = np.where(labels == 1, 1 / counts[1], 1 / counts[0])
    probabilities = probabilities / probabilities.sum()
    device = _device(requested_device, torch)
    model = _new_model(torch).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    loss_function = torch.nn.BCEWithLogitsLoss()
    rng = np.random.default_rng(seed)
    steps = max(1, math.ceil(len(flattened) / batch_size))
    losses: list[float] = []
    for _epoch in range(epochs):
        model.train()
        epoch_losses: list[float] = []
        sampled = rng.choice(len(flattened), size=steps * batch_size,
                             replace=True, p=probabilities)
        for start in range(0, len(sampled), batch_size):
            indices = sampled[start:start + batch_size]
            inputs = patches[indices].copy()
            targets = labels[indices].astype(np.float32)
            # Deterministic lightweight augmentation.  Mirroring a minimap
            # icon is safe for the ranker and keeps the prototype behavior.
            for index in range(len(inputs)):
                if rng.random() < 0.5:
                    inputs[index] = inputs[index, :, :, ::-1]
                if rng.random() < 0.5:
                    inputs[index] = inputs[index, :, ::-1, :]
                gain = rng.uniform(0.88, 1.12)
                bias = rng.uniform(-0.04, 0.04)
                inputs[index, :3] = np.clip(inputs[index, :3] * gain + bias, 0, 1)
                inputs[index, 5] = np.clip(inputs[index, 5] * gain + bias, 0, 1)
            tensor_inputs = torch.from_numpy(inputs).to(device)
            tensor_targets = torch.from_numpy(targets).to(device)
            optimizer.zero_grad(set_to_none=True)
            loss = loss_function(model(tensor_inputs), tensor_targets)
            loss.backward()
            optimizer.step()
            epoch_losses.append(float(loss.detach().cpu()))
        losses.append(sum(epoch_losses) / len(epoch_losses))
    return model, device, {
        "candidate_count": len(flattened),
        "positive_candidates": int(counts[1]),
        "negative_candidates": int(counts[0]),
        "epochs": epochs,
        "batch_size": batch_size,
        "loss_first": losses[0],
        "loss_last": losses[-1],
        "device": device,
    }


def _score_records(model: Any, records: Sequence[Mapping[str, Any]],
                   device: str, batch_size: int = DEFAULT_BATCH_SIZE
                   ) -> list[dict[str, Any]]:
    """Return records with candidate probabilities, preserving source order."""
    _, np, _ = _import_image_dependencies()
    import torch  # type: ignore

    model.eval()
    scored: list[dict[str, Any]] = []
    with torch.inference_mode():
        for record in records:
            items = record["items"]
            if items:
                patches = np.stack([item["patch"] for item in items]).astype(np.float32)
                probabilities: list[float] = []
                for start in range(0, len(items), batch_size):
                    tensor = torch.from_numpy(patches[start:start + batch_size]).to(device)
                    probabilities.extend(torch.sigmoid(model(tensor)).cpu().numpy().tolist())
            else:
                probabilities = []
            copied = dict(record)
            copied["scored_items"] = [
                {**item, "probability": float(probability)}
                for item, probability in zip(items, probabilities)
            ]
            scored.append(copied)
    return scored


def _iter_batches(rows: Sequence[Mapping[str, Any]], batch_size: int
                  ) -> Iterator[Sequence[Mapping[str, Any]]]:
    """Yield bounded row batches without changing their source order."""
    if batch_size <= 0:
        raise ValueError("pending_batch_size must be positive")
    return (rows[start:start + batch_size]
            for start in range(0, len(rows), batch_size))


def _score_pending_batch(model: Any, records: Sequence[Mapping[str, Any]],
                         device: str, batch_size: int,
                         threshold: float) -> list[dict[str, Any]]:
    """Score a bounded group of frames with shared candidate inference.

    Candidate patches are flattened only as references.  The NumPy stack and
    model tensor are still limited to ``batch_size`` patches, while a single
    model call can serve candidates from several frames.  The prediction
    helper needs only candidate geometry, so patch arrays are not copied into
    the temporary scored records.
    """
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    if not records:
        return []
    _, np, _ = _import_image_dependencies()
    import torch  # type: ignore

    owners: list[tuple[int, Mapping[str, Any]]] = []
    for record_index, record in enumerate(records):
        owners.extend((record_index, item) for item in record["items"])
    probabilities: list[list[float]] = [[] for _ in records]
    model.eval()
    with torch.inference_mode():
        for start in range(0, len(owners), batch_size):
            chunk = owners[start:start + batch_size]
            patches = np.stack([item["patch"] for _, item in chunk]).astype(
                np.float32
            )
            values = torch.sigmoid(
                model(torch.from_numpy(patches).to(device))
            ).cpu().numpy().tolist()
            for (record_index, _), probability in zip(chunk, values):
                probabilities[record_index].append(float(probability))

    predictions: list[dict[str, Any]] = []
    for record, record_probabilities in zip(records, probabilities):
        scored_items = [
            {
                "x": item["x"],
                "y": item["y"],
                "radius": item["radius"],
                "ring_score": item["ring_score"],
                "probability": probability,
            }
            for item, probability in zip(record["items"], record_probabilities)
        ]
        scored = dict(record)
        scored["scored_items"] = scored_items
        predictions.append(_prediction_for_record(scored, threshold))
    return predictions


def _print_pending_progress(done: int, total: int, started: float) -> None:
    """Report pending progress on stderr without changing stdout JSON output."""
    percent = 100.0 if total == 0 else done * 100.0 / total
    elapsed = max(0.0, time.monotonic() - started)
    print(
        f"pending suggestions: {done}/{total} ({percent:.1f}%) "
        f"elapsed={elapsed:.1f}s",
        file=sys.stderr,
        flush=True,
    )


def frame_metrics(scored_records: Sequence[Mapping[str, Any]],
                  threshold: float,
                  center_tolerance_px: float = FRAME_CENTER_TOLERANCE_PX
                  ) -> dict[str, Any]:
    """Compute one-prediction-per-frame metrics from scored records."""
    if not 0 <= threshold <= 1:
        raise ValueError("threshold must be between 0 and 1")
    tp = fp = fn = tn = 0
    errors: list[float] = []
    for record in scored_records:
        ranked = sorted(record.get("scored_items", []),
                        key=lambda item: item["probability"], reverse=True)
        prediction = ranked[0] if ranked and ranked[0]["probability"] >= threshold else None
        truth = record.get("ground_truth", [])
        if prediction is None:
            if truth:
                fn += 1
            else:
                tn += 1
            continue
        distance = min(
            (math.hypot(prediction["x"] - gx, prediction["y"] - gy)
             for gx, gy, _ in truth),
            default=math.inf,
        )
        if distance <= center_tolerance_px:
            tp += 1
            errors.append(float(distance))
        else:
            fp += 1
            if truth:
                fn += 1
    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    # Avoid a NumPy dependency in this pure metric function.
    sorted_errors = sorted(errors)
    if sorted_errors:
        p95_index = min(len(sorted_errors) - 1,
                        max(0, math.ceil(0.95 * len(sorted_errors)) - 1))
        center_mean: float | None = sum(sorted_errors) / len(sorted_errors)
        center_p95: float | None = float(sorted_errors[p95_index])
    else:
        center_mean = center_p95 = None
    return {
        "threshold": float(threshold),
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
        "precision": precision, "recall": recall, "f1": f1,
        "center_mean": center_mean, "center_p95": center_p95,
        "center_tolerance_px": center_tolerance_px,
    }


def _threshold_metrics(scored: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    return [frame_metrics(scored, threshold)
            for threshold in (index / 100 for index in range(5, 100))]


def _prediction_for_record(record: Mapping[str, Any], threshold: float) -> dict[str, Any]:
    ranked = sorted(record.get("scored_items", []),
                    key=lambda item: item["probability"], reverse=True)
    if not ranked:
        probability = 0.0
        candidate: Mapping[str, Any] | None = None
    else:
        candidate = ranked[0]
        probability = float(candidate["probability"])
    suggested_box: list[float] | None = None
    center: list[int] | None = None
    radius = 0
    ring_score = 0.0
    if candidate is not None:
        center = [int(candidate["x"]), int(candidate["y"])]
        radius = int(candidate["radius"])
        ring_score = float(candidate["ring_score"])
        if probability >= threshold:
            width, height = record["full_size"]
            left, top, _, _ = record["rect"]
            gx = left + candidate["x"]
            gy = top + candidate["y"]
            side = 2 * (radius + 2)
            x0 = max(0.0, gx - side / 2)
            y0 = max(0.0, gy - side / 2)
            x1 = min(float(width), gx + side / 2)
            y1 = min(float(height), gy + side / 2)
            if x1 > x0 and y1 > y0:
                suggested_box = [
                    round(x0 / width, 7), round(y0 / height, 7),
                    round((x1 - x0) / width, 7), round((y1 - y0) / height, 7),
                ]
                # A generated prediction is still subjected to the same one
                # box validator as a human label.
                suggested_box = validate_box(suggested_box, "suggested_box")
    return {
        "id": record["task_id"],
        "match_id": record["match_id"],
        "at_ms": record["at_ms"],
        "frame": record["frame"],
        "probability": round(probability, 6),
        "threshold": round(float(threshold), 6),
        "candidate_ring_score": round(ring_score, 6),
        "candidate_radius_px": radius,
        "candidate_center_widget_px": center,
        "suggested_box": suggested_box,
        "is_ground_truth": False,
    }


def _relative_output(path: Path, output: Path) -> str:
    return str(path.relative_to(output))


def run(source: Path, output: Path, *, seed: int = DEFAULT_SEED,
        epochs: int = DEFAULT_EPOCHS, batch_size: int = DEFAULT_BATCH_SIZE,
        requested_device: str = "auto",
        pending_batch_size: int = DEFAULT_PENDING_BATCH_SIZE
        ) -> dict[str, Any]:
    """Train, score pending tasks, and write review-only artifacts."""
    if pending_batch_size <= 0:
        raise ValueError("pending_batch_size must be positive")
    source = source.resolve()
    output = output.resolve()
    if source == output or output.is_relative_to(source):
        raise ValueError("output directory must be outside the source queue")
    if output.exists():
        if not output.is_dir() or any(output.iterdir()):
            raise ValueError(f"output directory is not empty: {output}")
    document, matches, rows = _load_queue(source)
    output.mkdir(parents=True, exist_ok=True)

    # Read final human status once.  No manifest suggestions participate in
    # this list; the database's reviewed_boxes column is the sole label source.
    final_rows = [row for row in rows if row["review_status"] in TRAINING_STATUSES]
    pending_rows = [row for row in rows if row["review_status"] == PENDING_STATUS]
    final_records: list[dict[str, Any]] = []
    for row in final_rows:
        final_records.append(_record_with_candidates(
            source, row, matches[row["match_id"]], document,
        ))
    train_records = [record for record in final_records if record["split"] == "train"]
    val_records = [record for record in final_records if record["split"] == "val"]
    if not train_records:
        raise ValueError("manifest has no final human labels in split=train")
    if not val_records:
        raise ValueError("manifest has no final human labels in split=val")

    model, device, train_summary = _train_model(
        train_records, seed, epochs, batch_size, requested_device,
    )
    scored_train = _score_records(model, train_records, device, batch_size)
    scored_val = _score_records(model, val_records, device, batch_size)
    threshold_candidates = _threshold_metrics(scored_val)
    best = choose_threshold(threshold_candidates)
    train_at_threshold = frame_metrics(scored_train, best["threshold"])
    training_task_count = len(train_records)
    training_positive_frames = sum(
        bool(record["ground_truth"]) for record in train_records
    )
    training_negative_frames = sum(
        not record["ground_truth"] for record in train_records
    )
    training_frames_without_near_candidate = sum(
        any(distance > FRAME_CENTER_TOLERANCE_PX
            for distance in record["nearest_truth_distances"])
        for record in train_records
    )
    validation_task_count = len(val_records)
    validation_status_counts = dict(
        Counter(record["review_status"] for record in val_records)
    )

    # The model no longer needs the feature records after validation.  Release
    # their patch arrays before processing the much larger pending queue.
    del scored_train, scored_val, train_records, val_records, final_records

    # Keep only a small group of pending records in memory.  The flattened
    # scorer batches candidates across frames, reducing model-call overhead
    # without retaining patches for the full 1,239-frame working set.
    predictions: list[dict[str, Any]] = []
    started = time.monotonic()
    _print_pending_progress(0, len(pending_rows), started)
    processed = 0
    for row_batch in _iter_batches(pending_rows, pending_batch_size):
        records = [
            _record_with_candidates(
                source, row, matches[row["match_id"]], document,
            )
            for row in row_batch
        ]
        predictions.extend(_score_pending_batch(
            model, records, device, batch_size, best["threshold"],
        ))
        processed += len(row_batch)
        del records
        _print_pending_progress(processed, len(pending_rows), started)
    predictions.sort(key=lambda item: int(item["id"]))

    # Keep the checkpoint on CPU so it can be reviewed on a machine without
    # MPS, while still recording the actual training backend in metadata.
    import torch  # type: ignore
    model.cpu()
    checkpoint = {
        "schema": "mapassist.minimap_player_candidate_ranker_checkpoint",
        "schema_version": 1,
        "artifact_type": "review_aid_non_release",
        "warning": ARTIFACT_WARNING,
        "is_ground_truth": False,
        "release_eligible": False,
        "seed": seed,
        "input_channels": list(INPUT_CHANNELS),
        "input_size": PATCH_SIZE,
        "candidate_radii": [7, 23],
        "candidate_top_k": 50,
        "center_tolerance_px": FRAME_CENTER_TOLERANCE_PX,
        "threshold": best["threshold"],
        "validation": best,
        "source_reviewed_task_count": len(final_rows),
        "manifest_split_policy": "train and val fields from review-manifest.json",
        "state_dict": model.state_dict(),
    }
    checkpoint_path = output / "checkpoint.pt"
    torch.save(checkpoint, checkpoint_path)

    status_counts = Counter(row["review_status"] for row in rows)
    split_counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for row in rows:
        split_counts[matches[row["match_id"]]["split"]][row["review_status"]] += 1
    report = {
        "schema": "mapassist.minimap_player_learning_report",
        "schema_version": 1,
        "artifact_type": "review_aid_non_release",
        "warning": ARTIFACT_WARNING,
        "is_ground_truth": False,
        "release_eligible": False,
        "source_queue": str(source),
        "source_manifest_sha256": _sha256(source / "review-manifest.json"),
        "source_database_open_mode": "read_only_uri",
        "source_database_modified": False,
        "seed": seed,
        "statuses": dict(sorted(status_counts.items())),
        "manifest_splits": {
            split: dict(sorted(values.items()))
            for split, values in sorted(split_counts.items())
        },
        "training": {
            **train_summary,
            "final_statuses_used": sorted(TRAINING_STATUSES),
            "final_tasks": training_task_count,
            "positive_frames": training_positive_frames,
            "negative_frames": training_negative_frames,
            "frames_without_near_candidate": training_frames_without_near_candidate,
        },
        "development_validation": {
            "split": "val",
            "task_count": validation_task_count,
            "status_counts": validation_status_counts,
            "threshold_selection": best,
            "metrics_at_selected_threshold": best,
        },
        "train_at_selected_threshold": train_at_threshold,
        "pending": {
            "task_count": len(predictions),
            "suggestions": sum(item["suggested_box"] is not None for item in predictions),
            "empty_suggestions": sum(item["suggested_box"] is None for item in predictions),
            "by_match": dict(Counter(item["match_id"] for item in predictions)),
        },
        "artifacts": {
            "checkpoint": _relative_output(checkpoint_path, output),
            "report": "report.json",
            "pending_predictions": "pending-predictions.json",
        },
    }
    prediction_document = {
        "schema": "mapassist.minimap_player_learned_suggestions",
        "schema_version": 1,
        "artifact_type": "review_aid_non_release",
        "warning": ARTIFACT_WARNING,
        "is_ground_truth": False,
        "release_eligible": False,
        "source_queue": str(source),
        "source_reviewed_tasks": len(final_rows),
        "development_validation": best,
        "threshold_selected_on_development_validation": True,
        "pending_scored": len(predictions),
        "suggestions": sum(item["suggested_box"] is not None for item in predictions),
        "results": predictions,
    }
    (output / "pending-predictions.json").write_text(
        json.dumps(prediction_document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (output / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True,
                        help="review queue directory containing manifest and SQLite")
    parser.add_argument("--output", type=Path, required=True,
                        help="new directory for review-only artifacts")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--pending-batch-size", type=int,
                        default=DEFAULT_PENDING_BATCH_SIZE)
    parser.add_argument("--device", choices=("auto", "cpu", "mps"), default="auto")
    args = parser.parse_args(argv)
    report = run(
        args.source, args.output, seed=args.seed, epochs=args.epochs,
        batch_size=args.batch_size, requested_device=args.device,
        pending_batch_size=args.pending_batch_size,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
