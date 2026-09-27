"""ctypes ABI for native/include/mapassist.h."""

from __future__ import annotations

import ctypes as C
import json
import math
from pathlib import Path
from typing import Any

from PIL import Image


class Rect(C.Structure):
    _fields_ = [(name, C.c_float) for name in ("x", "y", "w", "h")]


class Profile(C.Structure):
    _fields_ = [
        ("minimap", Rect),
        ("ping_area", Rect),
        ("center_mask", Rect),
        ("enable_main_bar", C.c_int),
        ("enable_minimap_template", C.c_int),
        ("enable_minimap_red_ring", C.c_int),
        ("enable_ping_template", C.c_int),
        ("red_min", C.c_int),
        ("red_dominance", C.c_float),
        ("main_min_width_ratio", C.c_float),
        ("main_max_height_ratio", C.c_float),
        ("main_min_aspect", C.c_float),
        ("template_threshold", C.c_float),
    ]


class Template(C.Structure):
    _fields_ = [
        ("rgba", C.POINTER(C.c_uint8)),
        ("width", C.c_int),
        ("height", C.c_int),
        ("row_stride", C.c_int),
    ]


class Observation(C.Structure):
    _fields_ = [
        ("kind", C.c_int),
        ("direction", C.c_int),
        ("bbox", Rect),
        ("confidence", C.c_float),
        ("timestamp_ms", C.c_int64),
    ]


class Cue(C.Structure):
    _fields_ = [
        ("kind", C.c_int),
        ("direction", C.c_int),
        ("priority", C.c_int),
        ("emitted_at_ms", C.c_int64),
        ("expires_at_ms", C.c_int64),
    ]


class MinimapMarker(C.Structure):
    _fields_ = [
        ("state", C.c_int),
        ("movement_direction", C.c_int),
        ("bbox", Rect),
        ("age_ms", C.c_int),
        ("event", C.c_int),
    ]


class EngineConfig(C.Structure):
    _fields_ = [
        ("min_confidence", C.c_float),
        ("max_observation_age_ms", C.c_int64),
        ("min_global_gap_ms", C.c_int64),
        ("minimap_min_gap_ms", C.c_int64),
        ("min_hits_in_three_frames", C.c_int),
        ("reset_after_missing_frames", C.c_int),
    ]


class MinimapLocatorConfig(C.Structure):
    _fields_ = [
        ("base_short", Rect),
        ("search_radius_x_short", C.c_float),
        ("search_radius_y_short", C.c_float),
        ("position_step_short", C.c_float),
        ("min_scale", C.c_float),
        ("max_scale", C.c_float),
        ("scale_steps", C.c_int),
        ("min_aspect", C.c_float),
        ("max_aspect", C.c_float),
        ("aspect_steps", C.c_int),
        ("descriptor_width", C.c_int),
        ("descriptor_height", C.c_int),
        ("min_score", C.c_float),
        ("confirm_frames", C.c_int),
        ("hold_frames", C.c_int),
        ("refresh_frames", C.c_int),
        ("normalize_black_bars", C.c_int),
        ("black_threshold", C.c_int),
        ("preserve_base_roi", C.c_int),
    ]


def default_library_path() -> Path:
    root = Path(__file__).resolve().parents[2]
    for extension in ("dylib", "so"):
        candidate = root / "build" / "native" / f"libmapassist.{extension}"
        if candidate.exists():
            return candidate
    raise FileNotFoundError("Build the native core first: cmake -S native -B build/native && cmake --build build/native")


def load_library(path: Path | None = None) -> C.CDLL:
    lib = C.CDLL(str(path or default_library_path()))
    lib.ma_detect_rgba.argtypes = [
        C.POINTER(C.c_uint8), C.c_int, C.c_int, C.c_int, C.c_int64,
        C.POINTER(Profile), C.POINTER(Template), C.POINTER(Template),
        C.POINTER(Observation), C.c_int,
    ]
    lib.ma_detect_rgba.restype = C.c_int
    lib.ma_engine_create.argtypes = [C.POINTER(EngineConfig)]
    lib.ma_engine_create.restype = C.c_void_p
    lib.ma_engine_step.argtypes = [
        C.c_void_p, C.POINTER(Observation), C.c_int, C.c_int64,
        C.POINTER(Cue), C.c_int,
    ]
    lib.ma_engine_step.restype = C.c_int
    lib.ma_engine_read_minimap_markers.argtypes = [
        C.c_void_p, C.POINTER(MinimapMarker), C.c_int,
    ]
    lib.ma_engine_read_minimap_markers.restype = C.c_int
    lib.ma_engine_clear_minimap_tracks.argtypes = [C.c_void_p]
    lib.ma_engine_clear_minimap_tracks.restype = None
    lib.ma_engine_destroy.argtypes = [C.c_void_p]
    lib.ma_engine_reset.argtypes = [C.c_void_p]
    lib.ma_minimap_locator_create.argtypes = [
        C.POINTER(MinimapLocatorConfig), C.POINTER(C.c_int8), C.c_int,
    ]
    lib.ma_minimap_locator_create.restype = C.c_void_p
    lib.ma_minimap_locator_update.argtypes = [
        C.c_void_p, C.POINTER(C.c_uint8), C.c_int, C.c_int, C.c_int,
        C.POINTER(Rect), C.POINTER(Rect), C.POINTER(C.c_float),
    ]
    lib.ma_minimap_locator_update.restype = C.c_int
    lib.ma_minimap_locator_reset.argtypes = [C.c_void_p]
    lib.ma_minimap_locator_destroy.argtypes = [C.c_void_p]
    return lib


def _rect(values: list[float]) -> Rect:
    if len(values) != 4 or any(not math.isfinite(value) or value < 0 or value > 1
                               for value in values):
        raise ValueError(f"Invalid normalized rectangle: {values}")
    if values[0] + values[2] > 1.001 or values[1] + values[3] > 1.001:
        raise ValueError(f"Rectangle outside frame: {values}")
    return Rect(*values)


def _bounded(value: Any, minimum: float, maximum: float) -> float:
    number = float(value)
    if not math.isfinite(number) or not minimum <= number <= maximum:
        raise ValueError(f"Profile value {value} must be in [{minimum}, {maximum}]")
    return number


def read_profile(path: Path) -> tuple[Profile, dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected GameProfile schema_version 1")
    rois = data["rois"]
    detector = data["detectors"]
    thresholds = data["thresholds"]
    profile = Profile(
        _rect(rois["minimap"]),
        _rect(rois["ping_area"]),
        _rect(rois["center_mask"]),
        int(detector["main_red_bar"]),
        int(detector["minimap_template"]),
        int(detector.get("minimap_red_ring", False)),
        int(detector["danger_ping_template"]),
        int(_bounded(thresholds["red_min"], 0, 255)),
        _bounded(thresholds["red_dominance"], 1, 10),
        _bounded(thresholds["main_min_width_ratio"], 0, 1),
        _bounded(thresholds["main_max_height_ratio"], 0, 1),
        _bounded(thresholds["main_min_aspect"], 1, 100),
        _bounded(thresholds["template_match"], 0, 1),
    )
    return profile, data


def read_template(path: Path | None) -> tuple[Template | None, Any]:
    if path is None:
        return None, None
    with Image.open(path) as source:
        if source.width < 1 or source.height < 1 or source.width > 256 or source.height > 256:
            raise ValueError(f"Template must be within 1..256 pixels: {path}")
        image = source.convert("RGBA")
    raw = image.tobytes()
    memory = (C.c_uint8 * len(raw)).from_buffer_copy(raw)
    return Template(memory, image.width, image.height, image.width * 4), memory


def observation_dict(value: Observation) -> dict[str, Any]:
    return {
        "type": {1: "main_enemy", 2: "minimap_enemy", 3: "danger_ping"}[value.kind],
        "source": {1: "main", 2: "minimap", 3: "ping"}[value.kind],
        "direction": {0: None, 1: "left", 2: "right", 3: "up", 4: "down"}[value.direction],
        "bbox_norm": [round(getattr(value.bbox, name), 5) for name in ("x", "y", "w", "h")],
        "confidence": round(value.confidence, 4),
        "timestamp_ms": value.timestamp_ms,
    }


def cue_dict(value: Cue) -> dict[str, Any]:
    return {
        "kind": {1: "main_enemy", 2: "minimap_enemy", 3: "danger_ping"}[value.kind],
        "direction": {0: None, 1: "left", 2: "right", 3: "up", 4: "down"}[value.direction],
        "priority": value.priority,
        "emitted_at_ms": value.emitted_at_ms,
        "expires_at_ms": value.expires_at_ms,
    }


class Pipeline:
    def __init__(self, profile_path: Path, library_path: Path | None = None):
        self.lib = load_library(library_path)
        self.profile, self.profile_json = read_profile(profile_path)
        assets = self.profile_json.get("templates", {})
        enemy_path = assets.get("minimap_enemy")
        ping_path = assets.get("danger_ping")
        self.enemy_template, self._enemy_memory = read_template(
            profile_path.parent / enemy_path if enemy_path else None
        )
        self.ping_template, self._ping_memory = read_template(
            profile_path.parent / ping_path if ping_path else None
        )
        if self.profile.enable_minimap_template and self.enemy_template is None:
            raise ValueError("minimap_template is enabled but its PNG is missing")
        if self.profile.enable_ping_template and self.ping_template is None:
            raise ValueError("danger_ping_template is enabled but its PNG is missing")
        config = self.profile_json["events"]
        self.engine_config = EngineConfig(
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

    def close(self) -> None:
        if self.engine:
            self.lib.ma_engine_destroy(self.engine)
            self.engine = None

    def __enter__(self) -> "Pipeline":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def step(self, rgba: bytes, width: int, height: int, timestamp_ms: int) -> tuple[list[dict], list[dict]]:
        expected = width * height * 4
        if len(rgba) != expected:
            raise ValueError(f"Expected {expected} RGBA bytes, got {len(rgba)}")
        frame = (C.c_uint8 * expected).from_buffer_copy(rgba)
        observations = (Observation * 64)()
        count = self.lib.ma_detect_rgba(
            frame, width, height, width * 4, timestamp_ms, C.byref(self.profile),
            C.byref(self.enemy_template) if self.enemy_template else None,
            C.byref(self.ping_template) if self.ping_template else None,
            observations, len(observations),
        )
        cues = (Cue * 4)()
        cue_count = self.lib.ma_engine_step(
            self.engine, observations, count, timestamp_ms, cues, len(cues)
        )
        return ([observation_dict(observations[i]) for i in range(count)],
                [cue_dict(cues[i]) for i in range(cue_count)])
