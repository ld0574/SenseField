"""Replay a recording through the frozen ncnn YOLOX model and native event engine.

This is the desktop equivalent of the Android path in ``native_bridge.cpp``:
frames are cropped with the profile ROI, passed through ncnn's Android resize
and right/bottom padding path, decoded with YOLOX's three strides, and then
fed into the existing C++ detector and event engine.  The JSONL output keeps
the event-layer ``cues`` shape consumed by :mod:`mapassist.evaluate`.
"""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import math
import os
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np

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


def _validate_replay_profile(profile_json: dict[str, Any]) -> None:
    detectors = profile_json.get("detectors", {})
    if not isinstance(detectors, dict) or detectors.get("minimap_yolox") is not True:
        raise ValueError("Frozen ncnn replay requires detectors.minimap_yolox=true")
    thresholds = profile_json.get("thresholds", {})
    if not isinstance(thresholds, dict):
        raise ValueError("Profile thresholds must be an object")
    if int(thresholds.get("minimap_yolox_input_size", 320)) != 320:
        raise ValueError("Android YOLOX replay requires minimap_yolox_input_size=320")


def _pixel_roi(rect: native.Rect, width: int, height: int) -> tuple[int, int, int, int]:
    x0 = max(0, min(width, math.floor(float(rect.x) * width)))
    y0 = max(0, min(height, math.floor(float(rect.y) * height)))
    x1 = max(x0, min(width, math.ceil(float(rect.x + rect.w) * width)))
    y1 = max(y0, min(height, math.ceil(float(rect.y + rect.h) * height)))
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


def _bbox_dict(box: Any, width: int, height: int, area: tuple[int, int, int, int]) -> dict[str, Any]:
    x0, y0, x1, y1, score = [float(value) for value in box]
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
        "direction": {0: None, 1: "left", 2: "right", 3: "up", 4: "down"}[
            _direction_for((full_x0 + full_x1) * 0.5, (full_y0 + full_y1) * 0.5, area)
        ],
    }


class FrozenReplay:
    """One ncnn model/profile/native engine session for a complete recording."""

    def __init__(self, profile_path: Path, param: Path, model_bin: Path,
                 library: Path, threads: int, input_name: str, output_name: str,
                 ncnn: Any):
        self.profile_path = profile_path.resolve()
        self.param = param.resolve()
        self.model_bin = model_bin.resolve()
        self.library_path = library.resolve()
        self.ncnn = ncnn
        self.np = np
        self.input_name = input_name
        self.output_name = output_name
        self.lib = native.load_library(self.library_path)
        self.profile, self.profile_json = native.read_profile(self.profile_path)
        _validate_replay_profile(self.profile_json)
        thresholds = self.profile_json.get("thresholds", {})
        if not isinstance(thresholds, dict):  # validated above; keeps type narrowing explicit
            raise ValueError("Profile thresholds must be an object")
        self.input_size = int(thresholds.get("minimap_yolox_input_size", 320))
        self.confidence = float(thresholds.get("minimap_yolox_confidence", 0.29))
        self.nms_threshold = float(thresholds.get("minimap_yolox_nms", 0.5))
        if self.input_size != 320:
            raise ValueError("Android YOLOX replay requires minimap_yolox_input_size=320")
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
        if getattr(self, "engine", None):
            self.lib.ma_engine_destroy(self.engine)
            self.engine = None

    def __enter__(self) -> "FrozenReplay":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def step(self, rgba: bytes, width: int, height: int,
             timestamp_ms: int) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        expected = width * height * 4
        if len(rgba) != expected:
            raise ValueError(f"Expected {expected} RGBA bytes, got {len(rgba)}")
        frame = (C.c_uint8 * expected).from_buffer_copy(rgba)
        native_observations = (native.Observation * 64)()
        native_count = self.lib.ma_detect_rgba(
            frame, width, height, width * 4, timestamp_ms, C.byref(self.profile),
            C.byref(self.enemy_template) if self.enemy_template else None,
            C.byref(self.ping_template) if self.ping_template else None,
            native_observations, len(native_observations),
        )
        if native_count < 0 or native_count > len(native_observations):
            raise RuntimeError(f"Native detector returned invalid count {native_count}")

        frame_array = np.frombuffer(rgba, dtype=np.uint8).reshape((height, width, 4))
        area = _pixel_roi(self.profile.minimap, width, height)
        x0, y0, x1, y1 = area
        crop_rgba = frame_array[y0:y1, x0:x1]
        if crop_rgba.shape[0] < 2 or crop_rgba.shape[1] < 2:
            raise RuntimeError("Profile minimap ROI is smaller than 2x2 pixels")
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
            self.confidence, self.nms_threshold, self.np,
        )
        observations = [native_observations[index] for index in range(native_count)]
        detection_dicts = []
        for detection in detections:
            x_min, y_min, x_max, y_max, score = [float(value) for value in detection]
            full_x0, full_y0 = x0 + x_min, y0 + y_min
            full_x1, full_y1 = x0 + x_max, y0 + y_max
            direction = _direction_for(
                (full_x0 + full_x1) * 0.5, (full_y0 + full_y1) * 0.5, area
            )
            observations.append(native.Observation(
                2, direction,
                native.Rect(
                    full_x0 / width, full_y0 / height,
                    (full_x1 - full_x0) / width, (full_y1 - full_y0) / height,
                ),
                score, timestamp_ms,
            ))
            detection_dicts.append(_bbox_dict(detection, width, height, area))

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
        observations_json = [native.observation_dict(item) for item in observations]
        cues_json = [native.cue_dict(cues_buffer[index]) for index in range(cue_count)]
        return observations_json, detection_dicts, cues_json


def run(video: Path, profile: Path, param: Path, model_bin: Path,
        output: Path, fps: int = 12, library: Path | None = None,
        metadata: Path | None = None, threads: int = 2,
        input_name: str = "in0", output_name: str = "out0") -> dict[str, Any]:
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
    metadata = (metadata or Path(f"{output}.meta.json")).resolve()
    if metadata in {video, profile, param, model_bin, library, output}:
        raise ValueError("Metadata must not overwrite a replay input or predictions")

    import ncnn

    width, height = video_dimensions(video)
    profile_json = json.loads(profile.read_text(encoding="utf-8"))
    source_records = {
        "video": _artifact(video),
        "profile": _artifact(profile),
        "profile_assets": _profile_assets(profile, profile_json),
        "model": {"param": _artifact(param), "bin": _artifact(model_bin)},
        "native_library": _artifact(library),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    processing_ms: list[float] = []
    total_observations = 0
    total_detections = 0
    total_cues = 0
    total_frames = 0
    started = time.perf_counter()
    try:
        with FrozenReplay(profile, param, model_bin, library, threads,
                          input_name, output_name, ncnn) as replay, \
                temporary.open("w", encoding="utf-8") as stream:
            for index, frame in enumerate(decoded_frames(video, width, height, fps)):
                timestamp_ms = round(index * 1000 / fps)
                frame_started = time.perf_counter()
                observations, detections, cues = replay.step(
                    frame, width, height, timestamp_ms
                )
                processing_ms.append((time.perf_counter() - frame_started) * 1000)
                stream.write(json.dumps({
                    "frame_index": index,
                    "timestamp_ms": timestamp_ms,
                    "observations": observations,
                    "detections": detections,
                    "cues": cues,
                }, ensure_ascii=False, allow_nan=False) + "\n")
                total_frames += 1
                total_observations += len(observations)
                total_detections += len(detections)
                total_cues += len(cues)
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
            "preprocessing": "RGBA crop -> ncnn PIXEL_RGBA2BGR resize -> right/bottom 114 border",
            "determinism": "float32 ncnn arithmetic; Android packing may differ",
            "postprocess": {
                "confidence": json.loads(profile.read_text(encoding="utf-8"))
                .get("thresholds", {}).get("minimap_yolox_confidence", 0.29),
                "nms_iou": json.loads(profile.read_text(encoding="utf-8"))
                .get("thresholds", {}).get("minimap_yolox_nms", 0.5),
                "strides": [8, 16, 32],
            },
        },
    }
    _write_json_atomic(metadata, provenance)
    return {**stats, "metadata": str(metadata)}


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
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--input-name", default="in0")
    parser.add_argument("--output-name", default="out0")
    args = parser.parse_args()
    try:
        result = run(
            args.video, args.profile, args.param, args.model_bin, args.output,
            args.fps, args.library, args.metadata, args.threads,
            args.input_name, args.output_name,
        )
    except (FileNotFoundError, OSError, RuntimeError, ValueError,
            subprocess.CalledProcessError, ImportError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
