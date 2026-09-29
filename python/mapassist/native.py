"""ctypes ABI for native/include/mapassist.h."""

from __future__ import annotations

import ctypes as C
import base64
import json
import math
import re
from pathlib import Path
from typing import Any

from PIL import Image


# Values shared with the C header. Keeping these names in the ctypes module
# avoids making tests and offline replayers depend on magic integers.
MA_MINIMAP_ENEMY = 2
MA_MINIMAP_PLAYER = 6
MA_TRACK_STATE_CANDIDATE = 0
MA_TRACK_STATE_VISIBLE = 1
MA_TRACK_STATE_LOST = 2
MA_TRACK_STATE_EXPIRED = 3
MA_VISION_EVENT_NONE = 0
MA_VISION_EVENT_APPEAR = 1
MA_VISION_EVENT_DISAPPEAR = 2
MA_PLAYER_RELEVANCE_MAX_AGE_MS = 500


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
        ("minimap_direction", Rect),
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
        ("track_id", C.c_int),
    ]


class TrackedEntity(C.Structure):
    """Category-aware visual-memory snapshot from ``ma_engine``.

    The native struct is append-only relative to the legacy marker ABI.  Keep
    this layout in lockstep with ``native/include/mapassist.h`` so callers can
    inspect player freshness without decoding the packed Android result.
    """

    _fields_ = [
        ("entity_kind", C.c_int),
        ("track_id", C.c_int),
        ("state", C.c_int),
        ("bbox", Rect),
        ("confidence", C.c_float),
        ("last_seen_ms", C.c_int64),
        ("freshness_ms", C.c_int),
        ("velocity_x", C.c_float),
        ("velocity_y", C.c_float),
        ("transition", C.c_int),
    ]


class PlayerStateSignature(C.Structure):
    _fields_ = [
        ("state", C.c_int),
        ("dhash", C.c_uint64),
        ("luma", C.c_uint8 * 64),
        ("chroma", C.c_uint8 * 32),
    ]


class PlayerStateMatcherConfig(C.Structure):
    _fields_ = [
        ("roi", Rect),
        ("max_dhash_distance", C.c_int),
        ("max_luma_mae", C.c_float),
        ("max_chroma_mae", C.c_float),
        ("min_state_margin", C.c_float),
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
    lib.ma_engine_read_tracked_entities.argtypes = [
        C.c_void_p, C.POINTER(TrackedEntity), C.c_int,
    ]
    lib.ma_engine_read_tracked_entities.restype = C.c_int
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
    lib.ma_player_state_matcher_create.argtypes = [
        C.POINTER(PlayerStateMatcherConfig), C.POINTER(PlayerStateSignature), C.c_int,
    ]
    lib.ma_player_state_matcher_create.restype = C.c_void_p
    lib.ma_player_state_match_rgba.argtypes = [
        C.c_void_p, C.POINTER(C.c_uint8), C.c_int, C.c_int, C.c_int,
        C.POINTER(C.c_float),
    ]
    lib.ma_player_state_match_rgba.restype = C.c_int
    lib.ma_player_state_matcher_destroy.argtypes = [C.c_void_p]
    return lib


def _rect(values: list[float]) -> Rect:
    valid = isinstance(values, list) and len(values) == 4
    if valid:
        for value in values:
            try:
                if (isinstance(value, bool) or not isinstance(value, (int, float)) or
                        not math.isfinite(value) or value < 0 or value > 1):
                    valid = False
                    break
            except (OverflowError, TypeError):
                valid = False
                break
    if not valid:
        raise ValueError(f"Invalid normalized rectangle: {values}")
    if (values[2] <= 0 or values[3] <= 0 or
            values[0] + values[2] > 1.001 or values[1] + values[3] > 1.001):
        raise ValueError(f"Rectangle outside frame: {values}")
    return Rect(*values)


def _bounded(value: Any, minimum: float, maximum: float) -> float:
    try:
        number = float(value)
    except (OverflowError, TypeError, ValueError) as error:
        raise ValueError(f"Profile value {value} must be in [{minimum}, {maximum}]") from error
    if not math.isfinite(number) or not minimum <= number <= maximum:
        raise ValueError(f"Profile value {value} must be in [{minimum}, {maximum}]")
    return number


def _profile_integer(value: Any, field: str, minimum: int, maximum: int) -> int:
    """Parse an integer JSON number using the Android getDouble/rint rule."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be an integer in [{minimum}, {maximum}]")
    try:
        number = float(value)
    except (OverflowError, TypeError, ValueError) as error:
        raise ValueError(f"{field} must be an integer in [{minimum}, {maximum}]") from error
    if not math.isfinite(number) or number != math.floor(number) or \
            number < minimum or number > maximum:
        raise ValueError(f"{field} must be an integer in [{minimum}, {maximum}]")
    return int(number)


def _profile_float(value: Any, field: str, minimum: float, maximum: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be in [{minimum}, {maximum}]")
    try:
        number = float(value)
    except (OverflowError, TypeError, ValueError) as error:
        raise ValueError(f"{field} must be in [{minimum}, {maximum}]") from error
    if not math.isfinite(number) or not minimum <= number <= maximum:
        raise ValueError(f"{field} must be in [{minimum}, {maximum}]")
    return number


def _decode_feature(value: Any, field: str, expected_length: int) -> bytes:
    if not isinstance(value, str):
        raise ValueError(f"player_life.{field} must be base64 text")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError(f"player_life.{field} is not valid base64") from error
    if len(decoded) != expected_length:
        raise ValueError(
            f"player_life.{field} must decode to {expected_length} bytes"
        )
    return decoded


def _validate_player_life(player: Any) -> None:
    """Validate the enabled player-life schema shared with Android.

    Keep the disabled form intentionally lightweight: the checked-in public
    profiles carry only ``enabled: false`` until real signatures are available.
    Once enabled, every field required by the native ABI is checked before a
    matcher can be constructed, so malformed data cannot silently disable the
    recognizer.
    """
    if player is None:
        return
    if not isinstance(player, dict):
        raise ValueError("state_recognition.player_life must be an object")
    enabled = player.get("enabled", False)
    if not isinstance(enabled, bool):
        raise ValueError("player_life.enabled must be true or false")
    if not enabled:
        return
    if player.get("schema") != "mapassist.player_life_signatures":
        raise ValueError("Unsupported player-life signature schema")
    if _profile_integer(player.get("schema_version"),
                        "player_life.schema_version", 1, 1) != 1:
        raise ValueError("Unsupported player-life signature schema version")
    roi = player.get("roi")
    if not isinstance(roi, list) or len(roi) != 4:
        raise ValueError("player_life.roi needs four numbers")
    for index, value in enumerate(roi):
        _profile_float(value, f"player_life.roi[{index}]", 0.0, 1.0)
    if roi[2] <= 0 or roi[3] <= 0 or roi[0] + roi[2] > 1.001 or \
            roi[1] + roi[3] > 1.001:
        raise ValueError("Invalid player-life ROI")

    thresholds = player.get("thresholds")
    if not isinstance(thresholds, dict):
        raise ValueError("player_life.thresholds must be an object")
    _profile_integer(thresholds.get("max_dhash_distance"),
                     "player_life.thresholds.max_dhash_distance", 0, 64)
    for field in ("max_luma_mae", "max_chroma_mae", "min_state_margin"):
        _profile_float(thresholds.get(field), f"player_life.thresholds.{field}",
                       0.0, 1.0)

    entries_by_state = {}
    total = 0
    crop_hashes: set[str] = set()
    for state in ("dead", "alive"):
        entries = player.get(state)
        if not isinstance(entries, list):
            raise ValueError(f"player_life.{state} must be an array")
        if len(entries) < 3:
            raise ValueError(
                f"player_life.{state} requires at least 3 signatures"
            )
        total += len(entries)
        entries_by_state[state] = entries
    if total < 6 or total > 64:
        raise ValueError("player_life requires 6 to 64 total signatures")

    for state, entries in entries_by_state.items():
        for index, signature in enumerate(entries):
            prefix = f"player_life.{state}[{index}]"
            if not isinstance(signature, dict):
                raise ValueError(f"{prefix} must be an object")
            dhash = signature.get("dhash64")
            if (not isinstance(dhash, str) or
                    re.fullmatch(r"[0-9a-f]{16}", dhash) is None):
                raise ValueError(f"{prefix}.dhash64 must be 16 lowercase hexadecimal characters")
            _decode_feature(signature.get("luma8x8_b64"),
                            f"{state}[{index}].luma8x8_b64", 64)
            _decode_feature(signature.get("chroma4x4_b64"),
                            f"{state}[{index}].chroma4x4_b64", 32)
            crop_sha = signature.get("crop_sha256")
            if (not isinstance(crop_sha, str) or
                    re.fullmatch(r"[0-9a-f]{64}", crop_sha) is None):
                raise ValueError(f"{prefix}.crop_sha256 must be lowercase SHA-256")
            if crop_sha in crop_hashes:
                raise ValueError("player_life signatures must have unique crop_sha256 values")
            crop_hashes.add(crop_sha)


def read_profile(path: Path) -> tuple[Profile, dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("GameProfile must be a JSON object")
    try:
        schema_version = _profile_integer(
            data.get("schema_version"), "GameProfile.schema_version", 1, 1
        )
    except ValueError as error:
        raise ValueError("Expected GameProfile schema_version 1") from error
    if schema_version != 1:
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
        (_rect(rois["minimap_direction"])
         if "minimap_direction" in rois else Rect()),
    )
    state_recognition = data.get("state_recognition")
    if state_recognition is not None and not isinstance(state_recognition, dict):
        raise ValueError("state_recognition must be an object")
    _validate_player_life(
        state_recognition.get("player_life") if state_recognition else None
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
        "type": {1: "main_enemy", 2: "minimap_enemy", 3: "danger_ping",
                 4: "player_dead", 5: "player_alive"}[value.kind],
        "source": {1: "main", 2: "minimap", 3: "ping",
                   4: "player_state", 5: "player_state"}[value.kind],
        "direction": {0: None, 1: "left", 2: "right", 3: "up", 4: "down"}[value.direction],
        "bbox_norm": [round(getattr(value.bbox, name), 5) for name in ("x", "y", "w", "h")],
        "confidence": round(value.confidence, 4),
        "timestamp_ms": value.timestamp_ms,
    }


def cue_dict(value: Cue) -> dict[str, Any]:
    return {
        "kind": {1: "main_enemy", 2: "minimap_enemy", 3: "danger_ping",
                 4: "player_dead", 5: "player_alive"}[value.kind],
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
        self.player_matcher = None
        self._player_signatures = None
        state_recognition = self.profile_json.get("state_recognition") or {}
        player = state_recognition.get("player_life")
        if player and player.get("enabled"):
            entries = [(1, value) for value in player.get("dead", [])]
            entries += [(2, value) for value in player.get("alive", [])]
            signatures = (PlayerStateSignature * len(entries))()
            for index, (state, value) in enumerate(entries):
                luma = _decode_feature(value["luma8x8_b64"],
                                       "player_life.luma8x8_b64", 64)
                chroma = _decode_feature(value["chroma4x4_b64"],
                                         "player_life.chroma4x4_b64", 32)
                signatures[index].state = state
                signatures[index].dhash = int(value["dhash64"], 16)
                signatures[index].luma[:] = luma
                signatures[index].chroma[:] = chroma
            thresholds = player["thresholds"]
            config = PlayerStateMatcherConfig(
                _rect(player["roi"]),
                _profile_integer(thresholds["max_dhash_distance"],
                                 "player_life.thresholds.max_dhash_distance", 0, 64),
                _profile_float(thresholds["max_luma_mae"],
                               "player_life.thresholds.max_luma_mae", 0.0, 1.0),
                _profile_float(thresholds["max_chroma_mae"],
                               "player_life.thresholds.max_chroma_mae", 0.0, 1.0),
                _profile_float(thresholds["min_state_margin"],
                               "player_life.thresholds.min_state_margin", 0.0, 1.0),
            )
            self.player_matcher = self.lib.ma_player_state_matcher_create(
                C.byref(config), signatures, len(entries)
            )
            if not self.player_matcher:
                raise ValueError("Native player-life matcher rejected profile")
            self._player_signatures = signatures

    def close(self) -> None:
        if self.player_matcher:
            self.lib.ma_player_state_matcher_destroy(self.player_matcher)
            self.player_matcher = None
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
        if self.player_matcher and count < len(observations):
            confidence = C.c_float()
            state = self.lib.ma_player_state_match_rgba(
                self.player_matcher, frame, width, height, width * 4,
                C.byref(confidence),
            )
            if state in (1, 2):
                observations[count] = Observation(
                    4 if state == 1 else 5, 0, _rect(
                        self.profile_json["state_recognition"]["player_life"]["roi"]
                    ), confidence.value, timestamp_ms,
                )
                count += 1
        cues = (Cue * 4)()
        cue_count = self.lib.ma_engine_step(
            self.engine, observations, count, timestamp_ms, cues, len(cues)
        )
        return ([observation_dict(observations[i]) for i in range(count)],
                [cue_dict(cues[i]) for i in range(cue_count)])
