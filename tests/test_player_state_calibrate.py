from __future__ import annotations

import base64
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.player_state_calibrate import calibrate, extract_features


def _images(root: Path, prefix: str, base: tuple[int, int, int]) -> list[Path]:
    paths = []
    for index in range(3):
        image = Image.new("RGB", (64, 36), base)
        draw = ImageDraw.Draw(image)
        draw.rectangle((8 + index, 7, 28 + index, 29), fill=(255, 255, 255))
        draw.line((5, 5 + index, 55, 30 - index), fill=(20, 20, 20), width=2)
        path = root / f"{prefix}-{index}.png"
        image.save(path)
        paths.append(path)
    return paths


def test_calibration_emits_runtime_compatible_signatures(tmp_path: Path) -> None:
    dead = _images(tmp_path, "dead", (180, 25, 30))
    alive = _images(tmp_path, "alive", (20, 150, 45))

    result = calibrate(dead, alive, (0.0, 0.0, 1.0, 1.0))

    assert result["schema"] == "mapassist.player_life_signatures"
    assert result["development_leave_one_out"]["precision"] >= 0.95
    assert result["development_leave_one_out"]["recall"] >= 0.90
    assert len(result["dead"]) == len(result["alive"]) == 3
    assert len(base64.b64decode(result["dead"][0]["luma8x8_b64"])) == 64
    assert len(base64.b64decode(result["dead"][0]["chroma4x4_b64"])) == 32
    assert len(result["dead"][0]["crop_sha256"]) == 64


def test_feature_extraction_is_deterministic(tmp_path: Path) -> None:
    path = _images(tmp_path, "sample", (80, 90, 100))[0]
    first = extract_features(path, (0.1, 0.1, 0.8, 0.8))
    second = extract_features(path, (0.1, 0.1, 0.8, 0.8))
    assert first == second


def test_crop_hash_ignores_pixels_outside_roi_and_tracks_pixels_inside(tmp_path: Path) -> None:
    roi = (0.25, 0.25, 0.5, 0.5)
    original = Image.new("RGB", (64, 36), (30, 50, 70))
    outside_changed = original.copy()
    outside_changed.putpixel((0, 0), (255, 0, 0))
    inside_changed = original.copy()
    inside_changed.putpixel((24, 18), (255, 0, 0))

    paths = [tmp_path / name for name in ("original.png", "outside.png", "inside.png")]
    for image, path in zip((original, outside_changed, inside_changed), paths):
        image.save(path)

    baseline_hash = extract_features(paths[0], roi).crop_sha256
    assert extract_features(paths[1], roi).crop_sha256 == baseline_hash
    assert extract_features(paths[2], roi).crop_sha256 != baseline_hash


def test_calibration_requires_both_classes(tmp_path: Path) -> None:
    dead = _images(tmp_path, "dead", (180, 25, 30))
    with pytest.raises(ValueError, match="three dead and three alive"):
        calibrate(dead, dead[:2], (0.0, 0.0, 1.0, 1.0))


def test_calibration_rejects_duplicate_crop_hashes(tmp_path: Path) -> None:
    dead = _images(tmp_path, "dead", (180, 25, 30))
    alive = _images(tmp_path, "alive", (20, 150, 45))

    with pytest.raises(ValueError, match="three unique dead"):
        calibrate([dead[0], dead[0], dead[1]], alive, (0.0, 0.0, 1.0, 1.0))
    with pytest.raises(ValueError, match="unique crop_sha256"):
        calibrate(dead, [alive[0], alive[1], dead[0]], (0.0, 0.0, 1.0, 1.0))


def test_calibration_rejects_nonfinite_roi(tmp_path: Path) -> None:
    dead = _images(tmp_path, "dead", (180, 25, 30))
    alive = _images(tmp_path, "alive", (20, 150, 45))

    with pytest.raises(ValueError, match="normalized rectangle"):
        calibrate(dead, alive, (0.0, 0.0, float("nan"), 1.0))
