"""Replay a recording through the Android-equivalent locator, ncnn, and event path.

This is the desktop equivalent of the Android path in ``native_bridge.cpp``:
when configured, the native locator first acquires and tracks the minimap ROI;
minimap inference stays silent while searching. Ready frames are cropped with the resolved ROI,
passed through ncnn's Android resize and right/bottom padding path, decoded
with YOLOX's three strides, and then fed into the C++ event engine. The JSONL
output keeps the event-layer ``cues`` shape consumed by :mod:`mapassist.evaluate`.
"""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np

try:
    from .yolox_decode import (
        DEFAULT_CLASSES, confidence_from_metadata, confidence_thresholds, resolve_classes,
    )
except ImportError:  # pragma: no cover - direct command-line execution.
    from yolox_decode import (  # type: ignore
        DEFAULT_CLASSES, confidence_from_metadata, confidence_thresholds, resolve_classes,
    )

try:  # Works both as ``python training/...`` and as a package import in tests.
    from .verify_yolox_ncnn import (
        _build_ncnn_net,
        _decode_and_nms,
        _ncnn_output,
    )
except ImportError:  # pragma: no cover - exercised by the command-line path.
    from verify_yolox_ncnn import (  # type: ignore
        _build_ncnn_net,
        _decode_and_nms,
        _ncnn_output,
    )

from mapassist import native
from mapassist.minimap_locator_evaluate import parse_locator
from mapassist.replay import decoded_frames, video_dimensions


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _artifact(path: Path) -> dict[str, Any]:
    resolved = path.resolve()
    return {
        "path": str(resolved),
        "size_bytes": resolved.stat().st_size,
        "sha256": _sha256(resolved),
    }


def _write_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _profile_assets(profile_path: Path, profile_json: dict[str, Any]) -> list[dict[str, Any]]:
    templates = profile_json.get("templates", {})
    if not isinstance(templates, dict):
        raise ValueError("Profile templates must be an object")
    assets = []
    for role, value in sorted(templates.items()):
        if not value:
            continue
        if not isinstance(value, str):
            raise ValueError(f"Profile template {role} must be a path")
        path = (profile_path.parent / value).resolve()
        if not path.is_file():
            raise FileNotFoundError(f"Missing profile template: {path}")
        assets.append({"role": role, **_artifact(path)})
    return assets


def _validate_replay_profile(profile_json: dict[str, Any],
                             model_bin: Path | None = None) -> None:
    detectors = profile_json.get("detectors", {})
    if not isinstance(detectors, dict) or detectors.get("minimap_yolox") is not True:
        raise ValueError("Frozen ncnn replay requires detectors.minimap_yolox=true")
    thresholds = profile_json.get("thresholds", {})
    if not isinstance(thresholds, dict):
        raise ValueError("Profile thresholds must be an object")
    input_size = thresholds.get("minimap_yolox_input_size", 320)
    # Mirror native/include/yolox_contract.h: square 32-pixel multiples from
    # the production 320 graph up to 1024.
    if (isinstance(input_size, bool) or not isinstance(input_size, int) or
            not 320 <= input_size <= 1024 or input_size % 32):
        raise ValueError(
            "Android YOLOX replay requires minimap_yolox_input_size in 320..1024 "
            "and a multiple of 32"
        )
    models = profile_json.get("models")
    if not isinstance(models, dict):
        raise ValueError("YOLOX replay profile needs models.minimap_yolox_bin_sha256")
    bound_sha256 = models.get("minimap_yolox_bin_sha256")
    if not isinstance(bound_sha256, str) or re.fullmatch(r"[0-9a-f]{64}", bound_sha256) is None:
        raise ValueError(
            "YOLOX replay profile models.minimap_yolox_bin_sha256 must be "
            "64 lowercase hex characters"
        )
    if model_bin is not None:
        actual_sha256 = _sha256(model_bin)
        if actual_sha256 != bound_sha256:
            raise ValueError(
                "Profile models.minimap_yolox_bin_sha256 does not match "
                f"the supplied ncnn bin ({actual_sha256})"
            )


def _pixel_roi(rect: native.Rect, width: int, height: int) -> tuple[int, int, int, int]:
    x0 = max(0, min(width, math.floor(float(rect.x) * width)))
    y0 = max(0, min(height, math.floor(float(rect.y) * height)))
    x1 = max(x0, min(width, math.ceil(float(rect.x + rect.w) * width)))
    y1 = max(y0, min(height, math.ceil(float(rect.y + rect.h) * height)))
    return x0, y0, x1, y1


def _direction_reference(profile: dict[str, Any], width: int, height: int,
                         detector_area: tuple[int, int, int, int]
                         ) -> tuple[int, int, int, int]:
    """Resolve the optional full-frame direction reference for a minimap crop."""
    rois = profile.get("rois", {})
    value = rois.get("minimap_direction") if isinstance(rois, dict) else None
    if value is None:
        return detector_area
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                or not math.isfinite(item) for item in value)):
        raise ValueError("rois.minimap_direction must be a normalized rectangle")
    x, y, w, h = (float(item) for item in value)
    if (x < 0 or y < 0 or w <= 0 or h <= 0 or
            x + w > 1.001 or y + h > 1.001):
        raise ValueError("rois.minimap_direction is outside the normalized frame")
    return _reference_pixel_roi(native.Rect(x, y, w, h), width, height)


def _reference_pixel_roi(rect: native.Rect, width: int,
                         height: int) -> tuple[int, int, int, int]:
    """Round calibrated widget edges to pixels without growing them by 1 px."""
    x0 = max(0, min(width, math.floor(float(rect.x) * width + 0.5)))
    y0 = max(0, min(height, math.floor(float(rect.y) * height + 0.5)))
    x1 = max(x0, min(width, math.floor(float(rect.x + rect.w) * width + 0.5)))
    y1 = max(y0, min(height, math.floor(float(rect.y + rect.h) * height + 0.5)))
    return x0, y0, x1, y1


def _direction_for(center_x: float, center_y: float,
                   area: tuple[int, int, int, int]) -> int:
    x0, y0, x1, y1 = area
    reference_x = (x0 + x1) * 0.5
    reference_y = (y0 + y1) * 0.5
    dx = (center_x - reference_x) / max(1.0, (x1 - x0) * 0.5)
    dy = (center_y - reference_y) / max(1.0, (y1 - y0) * 0.5)
    if abs(dx) < 0.15 and abs(dy) < 0.15:
        return 0
    if abs(dx) >= abs(dy):
        return 1 if dx < 0 else 2
    return 3 if dy < 0 else 4


def _bbox_dict(box: Any, width: int, height: int,
               area: tuple[int, int, int, int],
               direction_reference: tuple[int, int, int, int],
               classes=DEFAULT_CLASSES) -> dict[str, Any]:
    values = [float(value) for value in box]
    x0, y0, x1, y1, score = values[:5]
    class_id = int(values[5]) if len(values) > 5 else 0
    class_names = tuple(classes)
    if class_id < 0 or class_id >= len(class_names):
        raise ValueError(f"detector returned unknown class index {class_id}")
    crop_x, crop_y = area[:2]
    full_x0, full_y0 = crop_x + x0, crop_y + y0
    full_x1, full_y1 = crop_x + x1, crop_y + y1
    return {
        "bbox_norm": [
            round(full_x0 / width, 6),
            round(full_y0 / height, 6),
            round((full_x1 - full_x0) / width, 6),
            round((full_y1 - full_y0) / height, 6),
        ],
        "confidence": round(score, 6),
        "class_id": class_id,
        "class_name": class_names[class_id],
        "direction": {0: None, 1: "left", 2: "right", 3: "up", 4: "down"}[
            _direction_for((full_x0 + full_x1) * 0.5,
                           (full_y0 + full_y1) * 0.5, direction_reference)
        ],
    }


class FrozenReplay:
    """One ncnn model/profile/native engine session for a complete recording."""

    def __init__(self, profile_path: Path, param: Path, model_bin: Path,
                 library: Path, threads: int, input_name: str, output_name: str,
                 ncnn: Any, classes=None, metadata: Path | dict | None = None):
        self.profile_path = profile_path.resolve()
        self.param = param.resolve()
        self.model_bin = model_bin.resolve()
        self.library_path = library.resolve()
        self.ncnn = ncnn
        self.np = np
        self.input_name = input_name
        self.output_name = output_name
        model_metadata = metadata
        if model_metadata is None:
            for candidate in (
                self.param.with_suffix(".metadata.json"),
                self.model_bin.with_suffix(".metadata.json"),
            ):
                if candidate.is_file():
                    model_metadata = candidate
                    break
        self.classes = resolve_classes(classes=classes, metadata=model_metadata)
        self.profile, self.profile_json = native.read_profile(self.profile_path)
        _validate_replay_profile(self.profile_json, self.model_bin)
        self.lib = native.load_library(self.library_path)
        thresholds = self.profile_json.get("thresholds", {})
        if not isinstance(thresholds, dict):  # validated above; keeps type narrowing explicit
            raise ValueError("Profile thresholds must be an object")
        self.input_size = int(thresholds.get("minimap_yolox_input_size", 320))
        self.confidence = float(thresholds.get("minimap_yolox_confidence", 0.29))
        class_thresholds = thresholds.get("minimap_yolox_confidence_by_class")
        profile_confidence_by_class = (
            confidence_thresholds(class_thresholds, self.classes)
            if class_thresholds is not None else
            confidence_thresholds(self.confidence, self.classes)
        )
        metadata_confidence_by_class = None
        if model_metadata is not None:
            if isinstance(model_metadata, (str, Path)):
                metadata_document = json.loads(Path(model_metadata).read_text(
                    encoding="utf-8"))
            elif isinstance(model_metadata, dict):
                metadata_document = model_metadata
            else:
                raise ValueError("model metadata must be an object or JSON path")
            postprocess = metadata_document.get("postprocess", {})
            if postprocess is None:
                postprocess = {}
            if not isinstance(postprocess, dict):
                raise ValueError("model metadata postprocess must be an object")
            has_threshold = any(key in postprocess for key in
                                ("confidence_by_class", "confidence"))
            has_threshold = has_threshold or any(key in metadata_document for key in
                                                 ("confidence_by_class", "confidence"))
            if has_threshold:
                metadata_confidence_by_class = confidence_from_metadata(
                    metadata_document, self.classes, self.confidence,
                )
        self.confidence_by_class = metadata_confidence_by_class or profile_confidence_by_class
        self.nms_threshold = float(thresholds.get("minimap_yolox_nms", 0.5))
        if (not math.isfinite(self.confidence) or not 0 <= self.confidence <= 1 or
                not math.isfinite(self.nms_threshold) or not 0 <= self.nms_threshold <= 1):
            raise ValueError("Profile YOLOX confidence and NMS must be between 0 and 1")
        config = self.profile_json.get("events")
        if not isinstance(config, dict):
            raise ValueError("Profile events must be an object")
        self.engine_config = native.EngineConfig(
            float(config["min_confidence"]),
            int(config["max_observation_age_ms"]),
            int(config["min_global_gap_ms"]),
            int(config.get("minimap_min_gap_ms", 5000)),
            int(config["min_hits_in_three_frames"]),
            int(config["reset_after_missing_frames"]),
        )
        self.engine = self.lib.ma_engine_create(C.byref(self.engine_config))
        if not self.engine:
            raise RuntimeError("Could not create native event engine")
        # The near-zone relation layer is the same C++ code the APK runs.
        self.relation = None
        self.relation_config = native.relation_config_from_profile(self.profile_json)
        self.last_near_zone = None
        if self.relation_config is not None:
            if "minimap_player" not in self.classes:
                self.close()
                raise ValueError("minimap_relation needs a model with the minimap_player class")
            try:
                self.relation = native.Relation(self.lib, self.relation_config)
            except Exception:
                self.close()
                raise
        self.locator = None
        self.locator_config = None
        layout = self.profile_json.get("layout")
        locator_json = layout.get("minimap_locator") if isinstance(layout, dict) else None
        try:
            if locator_json is not None:
                self.locator_config, descriptor_bytes, _ = parse_locator(locator_json)
                descriptor = (C.c_int8 * len(descriptor_bytes)).from_buffer_copy(
                    descriptor_bytes
                )
                self.locator = self.lib.ma_minimap_locator_create(
                    C.byref(self.locator_config), descriptor, len(descriptor_bytes)
                )
                if not self.locator:
                    raise RuntimeError("Native minimap locator rejected profile")
        except Exception:
            self.close()
            raise
        self.net = None
        self.live_layers = None
        try:
            assets = self.profile_json.get("templates", {})
            enemy_path = assets.get("minimap_enemy") if isinstance(assets, dict) else None
            ping_path = assets.get("danger_ping") if isinstance(assets, dict) else None
            self.enemy_template, self._enemy_memory = native.read_template(
                self.profile_path.parent / enemy_path if enemy_path else None
            )
            self.ping_template, self._ping_memory = native.read_template(
                self.profile_path.parent / ping_path if ping_path else None
            )
            self.net, self.live_layers = _build_ncnn_net(
                self.param, self.model_bin, threads, self.ncnn, self.np
            )
        except Exception:
            self.close()
            raise

    def close(self) -> None:
        if getattr(self, "net", None) is not None:
            self.net.clear()
            self.net = None
        if getattr(self, "live_layers", None) is not None:
            self.live_layers.clear()
            self.live_layers = None
        if getattr(self, "relation", None) is not None:
            self.relation.close()
            self.relation = None
        if getattr(self, "engine", None):
            self.lib.ma_engine_destroy(self.engine)
            self.engine = None
        if getattr(self, "locator", None):
            self.lib.ma_minimap_locator_destroy(self.locator)
            self.locator = None

    def __enter__(self) -> "FrozenReplay":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def step(self, rgba: bytes, width: int, height: int,
             timestamp_ms: int) -> tuple[list[dict[str, Any]], list[dict[str, Any]],
                                         list[dict[str, Any]], dict[str, Any]]:
        expected = width * height * 4
        if len(rgba) != expected:
            raise ValueError(f"Expected {expected} RGBA bytes, got {len(rgba)}")
        frame = (C.c_uint8 * expected).from_buffer_copy(rgba)
        frame_profile = native.Profile.from_buffer_copy(self.profile)
        minimap_ready = True
        layout = {
            "state": "fixed",
            "score": None,
            "roi": [round(float(getattr(frame_profile.minimap, key)), 8)
                    for key in ("x", "y", "w", "h")],
            "content": None,
        }
        if self.locator:
            located = native.Rect()
            content = native.Rect()
            score = C.c_float()
            locator_state = self.lib.ma_minimap_locator_update(
                self.locator, frame, width, height, width * 4,
                C.byref(located), C.byref(content), C.byref(score),
            )
            if locator_state not in (0, 1, 2):
                raise RuntimeError(f"Native locator returned invalid state {locator_state}")
            minimap_ready = locator_state != 0
            layout = {
                "state": {0: "searching", 1: "locked", 2: "held"}[locator_state],
                "score": round(float(score.value), 6),
                "roi": ([round(float(getattr(located, key)), 8)
                         for key in ("x", "y", "w", "h")]
                        if minimap_ready else None),
                "content": [round(float(getattr(content, key)), 8)
                            for key in ("x", "y", "w", "h")],
            }
            if minimap_ready:
                frame_profile.minimap = located
            else:
                frame_profile.enable_minimap_template = 0
                frame_profile.enable_minimap_red_ring = 0
        native_observations = (native.Observation * 64)()
        native_count = self.lib.ma_detect_rgba(
            frame, width, height, width * 4, timestamp_ms, C.byref(frame_profile),
            C.byref(self.enemy_template) if self.enemy_template else None,
            C.byref(self.ping_template) if self.ping_template else None,
            native_observations, len(native_observations),
        )
        if native_count < 0 or native_count > len(native_observations):
            raise RuntimeError(f"Native detector returned invalid count {native_count}")

        frame_array = np.frombuffer(rgba, dtype=np.uint8).reshape((height, width, 4))
        area = _pixel_roi(frame_profile.minimap, width, height)
        direction_reference = _direction_reference(
            self.profile_json, width, height, area)
        x0, y0, x1, y1 = area
        detections = []
        if minimap_ready:
            crop_rgba = frame_array[y0:y1, x0:x1]
            if crop_rgba.shape[0] < 2 or crop_rgba.shape[1] < 2:
                raise RuntimeError("Resolved minimap ROI is smaller than 2x2 pixels")
            crop = np.ascontiguousarray(crop_rgba)
            crop_height, crop_width = crop.shape[:2]
            scale = min(self.input_size / crop_width, self.input_size / crop_height)
            resized_width = max(1, int(crop_width * scale))
            resized_height = max(1, int(crop_height * scale))
            resized = self.ncnn.Mat.from_pixels_resize(
                crop, self.ncnn.Mat.PixelType.PIXEL_RGBA2BGR,
                crop_width, crop_height, resized_width, resized_height,
            )
            if resized.empty():
                raise RuntimeError("ncnn RGBA resize returned an empty tensor")
            runtime_input = self.ncnn.copy_make_border(
                resized, 0, self.input_size - resized_height,
                0, self.input_size - resized_width,
                self.ncnn.BorderType.BORDER_CONSTANT, 114.0,
            )
            if runtime_input.empty():
                raise RuntimeError("ncnn padding returned an empty tensor")
            raw = _ncnn_output(
                self.net, runtime_input, self.input_name, self.output_name,
                self.ncnn, self.np,
            )
            detections = _decode_and_nms(
                raw, self.input_size, x1 - x0, y1 - y0,
                self.confidence_by_class or self.confidence,
                self.nms_threshold, self.np,
                self.classes,
            )
        observations = [native_observations[index] for index in range(native_count)]
        detection_dicts = []
        for detection in detections:
            values = [float(value) for value in detection]
            x_min, y_min, x_max, y_max, score = values[:5]
            full_x0, full_y0 = x0 + x_min, y0 + y_min
            full_x1, full_y1 = x0 + x_max, y0 + y_max
            direction = _direction_for(
                (full_x0 + full_x1) * 0.5, (full_y0 + full_y1) * 0.5,
                direction_reference,
            )
            class_id = int(values[5]) if len(values) > 5 else 0
            class_name = self.classes[class_id]
            kind_by_class = {
                "minimap_enemy": native.MA_MINIMAP_ENEMY,
                "minimap_player": native.MA_MINIMAP_PLAYER,
            }
            if class_name not in kind_by_class:
                raise ValueError(f"unsupported minimap detector class {class_name!r}")
            observations.append(native.Observation(
                kind_by_class[class_name], direction,
                native.Rect(
                    full_x0 / width, full_y0 / height,
                    (full_x1 - full_x0) / width, (full_y1 - full_y0) / height,
                ),
                score, timestamp_ms,
            ))
            detection_dicts.append(
                _bbox_dict(detection, width, height, area,
                           direction_reference, self.classes))

        observations.sort(key=lambda item: float(item.confidence), reverse=True)
        observations = observations[:64]
        observation_buffer = (native.Observation * 64)()
        for index, observation in enumerate(observations):
            observation_buffer[index] = observation
        cues_buffer = (native.Cue * 1)()
        cue_count = self.lib.ma_engine_step(
            self.engine, observation_buffer, len(observations), timestamp_ms,
            cues_buffer, len(cues_buffer),
        )
        if cue_count < 0 or cue_count > 1:
            raise RuntimeError(f"Native event engine returned invalid count {cue_count}")
        self.last_near_zone = None
        if self.relation is not None:
            entities = (native.TrackedEntity * native.MA_MAX_TRACKED_ENTITIES)()
            entity_count = self.lib.ma_engine_read_tracked_entities(
                self.engine, entities, len(entities))
            rois = self.profile_json.get("rois", {})
            map_body = (native.Rect(*rois["minimap_direction"])
                        if "minimap_direction" in rois else frame_profile.minimap)
            self.last_near_zone = self.relation.update(
                entities, entity_count, map_body, minimap_ready, width, height, timestamp_ms)
        observations_json = [native.observation_dict(item) for item in observations]
        cues_json = [native.cue_dict(cues_buffer[index]) for index in range(cue_count)]
        return observations_json, detection_dicts, cues_json, layout


def run(video: Path, profile: Path, param: Path, model_bin: Path,
        output: Path, fps: int = 12, library: Path | None = None,
        metadata: Path | None = None, threads: int = 2,
        input_name: str = "in0", output_name: str = "out0",
        classes=None, model_metadata: Path | None = None) -> dict[str, Any]:
    if fps < 1 or fps > 30:
        raise ValueError("fps must be between 1 and 30")
    if threads < 1:
        raise ValueError("threads must be positive")
    video = video.resolve()
    profile = profile.resolve()
    param = param.resolve()
    model_bin = model_bin.resolve()
    library = (library or native.default_library_path()).resolve()
    for label, path in (("video", video), ("profile", profile),
                        ("ncnn param", param), ("ncnn bin", model_bin),
                        ("native library", library)):
        if not path.is_file():
            raise FileNotFoundError(f"Missing {label}: {path}")
    if output.resolve() in {video, profile, param, model_bin, library}:
        raise ValueError("Output must not overwrite a replay input")
    output = output.resolve()
    provenance_metadata = (metadata or Path(f"{output}.meta.json")).resolve()
    if provenance_metadata in {video, profile, param, model_bin, library, output}:
        raise ValueError("Metadata must not overwrite a replay input or predictions")
    if model_metadata is not None:
        model_metadata = model_metadata.expanduser().resolve()
        if not model_metadata.is_file():
            raise FileNotFoundError(f"Missing model metadata: {model_metadata}")
    else:
        for candidate in (
            param.with_suffix(".metadata.json"),
            model_bin.with_suffix(".metadata.json"),
        ):
            if candidate.is_file():
                model_metadata = candidate.resolve()
                break

    import ncnn

    width, height = video_dimensions(video)
    profile_json = json.loads(profile.read_text(encoding="utf-8"))
    source_records = {
        "video": _artifact(video),
        "profile": _artifact(profile),
        "profile_assets": _profile_assets(profile, profile_json),
        "model": {
            "param": _artifact(param),
            "bin": _artifact(model_bin),
            "metadata": (_artifact(model_metadata)
                         if model_metadata is not None else None),
        },
        "native_library": _artifact(library),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    processing_ms: list[float] = []
    total_observations = 0
    total_detections = 0
    total_cues = 0
    total_frames = 0
    layout_states = {"fixed": 0, "searching": 0, "locked": 0, "held": 0}
    near_states = {name: 0 for name in native.RELATION_STATE_NAMES.values()}
    near_events = {name: 0 for name in native.RELATION_EVENT_NAMES.values() if name}
    near_suppressions: dict[str, int] = {}
    near_zone_enabled = False
    started = time.perf_counter()
    try:
        with FrozenReplay(profile, param, model_bin, library, threads,
                          input_name, output_name, ncnn, classes, model_metadata) as replay, \
                temporary.open("w", encoding="utf-8") as stream:
            near_zone_enabled = replay.relation is not None
            for index, frame in enumerate(decoded_frames(video, width, height, fps)):
                timestamp_ms = round(index * 1000 / fps)
                frame_started = time.perf_counter()
                observations, detections, cues, layout = replay.step(
                    frame, width, height, timestamp_ms
                )
                processing_ms.append((time.perf_counter() - frame_started) * 1000)
                record = {
                    "frame_index": index,
                    "timestamp_ms": timestamp_ms,
                    "observations": observations,
                    "detections": detections,
                    "cues": cues,
                    "layout": layout,
                }
                near_zone = replay.last_near_zone
                if near_zone is not None:
                    record["near_zone"] = near_zone
                    near_states[near_zone["state"]] += 1
                    if near_zone["event"] is not None:
                        near_events[near_zone["event"]] += 1
                    if near_zone["suppression"] is not None:
                        near_suppressions[near_zone["suppression"]] = (
                            near_suppressions.get(near_zone["suppression"], 0) + 1)
                stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
                total_frames += 1
                total_observations += len(observations)
                total_detections += len(detections)
                total_cues += len(cues)
                layout_states[layout["state"]] += 1
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    ordered = sorted(processing_ms)
    elapsed = time.perf_counter() - started
    stats = {
        "frames": total_frames,
        "observations": total_observations,
        "yolox_detections": total_detections,
        "cues": total_cues,
        "fps": fps,
        "width": width,
        "height": height,
        "elapsed_seconds": round(elapsed, 3),
        "replay_fps": round(total_frames / elapsed, 3) if elapsed else None,
        "processing_ms_p50": round(statistics.median(ordered), 3) if ordered else None,
        "processing_ms_p95": round(
            ordered[max(0, (95 * len(ordered) + 99) // 100 - 1)], 3
        ) if ordered else None,
        "layout_states": layout_states,
    }
    if near_zone_enabled:
        minutes = total_frames / fps / 60.0 if total_frames else 0.0
        known = total_frames - near_states["UNKNOWN"]
        stats["near_zone"] = {
            # Evidence coverage: share of frames with a usable self marker
            # and map. Report it next to the cue rate; silence alone would
            # also give a low false-cue rate.
            "coverage": round(known / total_frames, 4) if total_frames else None,
            "state_frames": near_states,
            "events": near_events,
            "suppressions": near_suppressions,
            "near_enter_per_minute": (round(near_events["NEAR_ENTER"] / minutes, 3)
                                      if minutes else None),
            "radar_pauses_per_minute": (round(near_events["RADAR_PAUSED"] / minutes, 3)
                                        if minutes else None),
            "replay_minutes": round(minutes, 3),
        }
    provenance = {
        "schema_version": 1,
        "replay": "frozen_yolox_ncnn_native_event_replay",
        **source_records,
        "predictions": _artifact(output),
        "stats": stats,
        "sampling": {
            "method": "ffmpeg CFR fps filter",
            "fps": fps,
            "frame_count": total_frames,
        },
        "timeline": {
            "kind": "synthetic_media_time_ms",
            "timestamp_formula": "round(frame_index * 1000 / fps)",
            "source_pts_preserved": False,
        },
        "event_now_policy": "zero_queue_delay; ma_engine_step now_ms equals synthetic frame timestamp",
        "runtime": {
            "python": sys.version.split()[0],
            "numpy": np.__version__,
            "ncnn": getattr(ncnn, "__version__", None),
            "input_name": input_name,
            "output_name": output_name,
            "threads": threads,
            "preprocessing": (
                "native minimap locator when configured -> RGBA crop -> ncnn "
                "PIXEL_RGBA2BGR resize -> right/bottom 114 border"
            ),
            "determinism": "float32 ncnn arithmetic; Android packing may differ",
            "postprocess": {
                "classes": list(replay.classes) if 'replay' in locals() else list(
                    resolve_classes(classes=classes)
                ),
                "confidence": json.loads(profile.read_text(encoding="utf-8"))
                .get("thresholds", {}).get("minimap_yolox_confidence", 0.29),
                "confidence_by_class": replay.confidence_by_class,
                "nms_iou": json.loads(profile.read_text(encoding="utf-8"))
                .get("thresholds", {}).get("minimap_yolox_nms", 0.5),
                "strides": [8, 16, 32],
            },
        },
    }
    _write_json_atomic(provenance_metadata, provenance)
    return {**stats, "metadata": str(provenance_metadata)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--param", type=Path, required=True)
    parser.add_argument("--bin", dest="model_bin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fps", type=int, default=12)
    parser.add_argument("--library", type=Path)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument(
        "--model-metadata", type=Path,
        help="Model sidecar whose classes and per-class thresholds define decoding",
    )
    parser.add_argument("--classes", nargs="+", metavar="CLASS",
                        help="Explicit model class order when sidecar metadata is unavailable")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--input-name", default="in0")
    parser.add_argument("--output-name", default="out0")
    args = parser.parse_args()
    try:
        result = run(
            args.video, args.profile, args.param, args.model_bin, args.output,
            args.fps, args.library, args.metadata, args.threads,
            args.input_name, args.output_name, args.classes, args.model_metadata,
        )
    except (FileNotFoundError, OSError, RuntimeError, ValueError,
            subprocess.CalledProcessError, ImportError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
