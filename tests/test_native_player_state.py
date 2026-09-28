from __future__ import annotations

import ctypes as C
import subprocess
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.native import (
    Cue, EngineConfig, Observation, PlayerStateMatcherConfig,
    MinimapMarker, PlayerStateSignature, Rect, load_library,
)
from mapassist.player_state_calibrate import extract_features


@pytest.fixture(scope="session")
def native_library(tmp_path_factory: pytest.TempPathFactory) -> Path:
    root = Path(__file__).resolve().parents[1]
    build = tmp_path_factory.mktemp("native-player-state")
    subprocess.run(["cmake", "-S", str(root / "native"), "-B", str(build)],
                   check=True, capture_output=True)
    subprocess.run(["cmake", "--build", str(build)], check=True, capture_output=True)
    return next(build.glob("libmapassist.*"))


def test_player_life_state_machine_emits_only_transitions(native_library: Path) -> None:
    lib = load_library(native_library)
    config = EngineConfig(0.1, 500, 0, 0, 2, 3)
    engine = lib.ma_engine_create(C.byref(config))
    assert engine

    def step(kind: int | None, now: int) -> list[int]:
        observations = (Observation * 1)()
        count = 0
        if kind is not None:
            observations[0] = Observation(kind, 0, Rect(0, 0, 1, 1), 0.99, now)
            count = 1
        cues = (Cue * 1)()
        cue_count = lib.ma_engine_step(engine, observations, count, now, cues, 1)
        return [cues[index].kind for index in range(cue_count)]

    try:
        assert step(5, 100) == []
        assert step(5, 200) == []  # Initial ALIVE establishment is silent.
        assert step(4, 300) == []
        assert step(4, 400) == [4]
        assert step(4, 500) == []
        assert step(None, 600) == []
        assert step(5, 700) == []
        assert step(5, 800) == [5]
        assert step(5, 900) == []
        lib.ma_engine_reset(engine)
        assert step(5, 1000) == []
        assert step(5, 1100) == []
    finally:
        lib.ma_engine_destroy(engine)


def test_minimap_track_id_is_not_reused_after_engine_reset(
    native_library: Path,
) -> None:
    lib = load_library(native_library)
    config = EngineConfig(0.1, 500, 0, 0, 2, 3)
    engine = lib.ma_engine_create(C.byref(config))
    assert engine
    observations = (Observation * 1)()
    cues = (Cue * 1)()
    marker_buffer = (MinimapMarker * 1)()

    def confirm(at_ms: int) -> int:
        observations[0] = Observation(
            2, 1, Rect(0.10, 0.10, 0.02, 0.04), 0.99, at_ms
        )
        lib.ma_engine_step(engine, observations, 1, at_ms, cues, 1)
        return lib.ma_engine_read_minimap_markers(engine, marker_buffer, 1)

    try:
        assert confirm(100) == 0
        assert confirm(200) == 1
        first_id = marker_buffer[0].track_id
        assert first_id > 0
        lib.ma_engine_reset(engine)
        assert confirm(300) == 0
        assert confirm(400) == 1
        assert marker_buffer[0].track_id > first_id
    finally:
        lib.ma_engine_destroy(engine)


def test_python_calibration_features_match_native_classifier(
    native_library: Path, tmp_path: Path,
) -> None:
    samples: list[tuple[int, Path]] = []
    for state, color in ((1, (180, 25, 30)), (2, (20, 150, 45))):
        for index in range(3):
            image = Image.new("RGB", (64, 36), color)
            draw = ImageDraw.Draw(image)
            draw.rectangle((8 + index, 7, 28 + index, 29), fill=(255, 255, 255))
            path = tmp_path / f"{state}-{index}.png"
            image.save(path)
            samples.append((state, path))
    signatures = (PlayerStateSignature * len(samples))()
    for index, (state, path) in enumerate(samples):
        features = extract_features(path, (0, 0, 1, 1))
        signatures[index].state = state
        signatures[index].dhash = features.dhash
        signatures[index].luma[:] = features.luma
        signatures[index].chroma[:] = features.chroma

    lib = load_library(native_library)
    config = PlayerStateMatcherConfig(Rect(0, 0, 1, 1), 12, 0.2, 0.2, 0.01)
    matcher = lib.ma_player_state_matcher_create(
        C.byref(config), signatures, len(signatures)
    )
    assert matcher
    try:
        with Image.open(samples[0][1]) as source:
            rgba = source.convert("RGBA")
        frame = (C.c_uint8 * len(rgba.tobytes())).from_buffer_copy(rgba.tobytes())
        confidence = C.c_float()
        state = lib.ma_player_state_match_rgba(
            matcher, frame, rgba.width, rgba.height, rgba.width * 4,
            C.byref(confidence),
        )
        assert state == 1
        assert confidence.value > 0.8
    finally:
        lib.ma_player_state_matcher_destroy(matcher)


def test_matcher_ignores_signatures_that_fail_any_gate(
    native_library: Path, tmp_path: Path,
) -> None:
    image = Image.new("RGB", (64, 36), (120, 80, 40))
    path = tmp_path / "query.png"
    image.save(path)
    features = extract_features(path, (0, 0, 1, 1))
    signatures = (PlayerStateSignature * 6)()
    for index in range(3):
        signatures[index].state = 1
        signatures[index].dhash = features.dhash
        signatures[index].luma[:] = bytes(64)
        signatures[index].chroma[:] = features.chroma
    for index in range(3, 6):
        signatures[index].state = 2
        signatures[index].dhash = features.dhash
        signatures[index].luma[:] = features.luma
        signatures[index].chroma[:] = features.chroma

    lib = load_library(native_library)
    config = PlayerStateMatcherConfig(Rect(0, 0, 1, 1), 0, 0.01, 0.01, 0.01)
    matcher = lib.ma_player_state_matcher_create(
        C.byref(config), signatures, len(signatures)
    )
    assert matcher
    try:
        rgba = image.convert("RGBA")
        frame = (C.c_uint8 * len(rgba.tobytes())).from_buffer_copy(rgba.tobytes())
        confidence = C.c_float()
        state = lib.ma_player_state_match_rgba(
            matcher, frame, rgba.width, rgba.height, rgba.width * 4,
            C.byref(confidence),
        )
        assert state == 2
        assert confidence.value > 0.9
    finally:
        lib.ma_player_state_matcher_destroy(matcher)
