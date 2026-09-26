from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.calibrate_minimap_anchor import calibrate
from mapassist.minimap_locator_evaluate import _load_locator


def _manifest(root: Path, frames: list[str], roi=None, statuses=None) -> Path:
    payload = {
        "schema_version": 1,
        "kind": "minimap_enemy",
        "roi": roi if roi is not None else [0.1, 0.2, 0.4, 0.4],
        "matches": [{
            "id": "match-1",
            "split": "train",
            "samples": [{
                "frame": frame,
                "review_status": (statuses[index] if statuses else "negative"),
            } for index, frame in enumerate(frames)],
        }],
    }
    path = root / "review-manifest.json"
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return path


def test_median_suppresses_moving_object_and_output_is_reproducible(tmp_path: Path) -> None:
    frame_paths = []
    # The moving object is present in only two of five frames.  A per-pixel
    # median therefore keeps the stable background even where it moves.
    for index, x in enumerate((None, 2, None, 10, None)):
        image = Image.new("RGB", (40, 20), (30, 30, 30))
        # A stable asymmetric map-like structure remains after the moving
        # object is suppressed and yields a usable locator descriptor.
        stable = ImageDraw.Draw(image)
        stable.rectangle((0, 0, 4, 19), fill=(80, 80, 80))
        stable.rectangle((5, 12, 19, 18), fill=(120, 120, 120))
        if x is not None:
            ImageDraw.Draw(image).rectangle((x, 4, x + 2, 8), fill=(255, 255, 255))
        path = tmp_path / f"frame-{index}.png"
        image.save(path)
        frame_paths.append(path.name)
    manifest = _manifest(tmp_path, frame_paths, roi=[0, 0, 0.5, 1])
    first = tmp_path / "first.json"
    second = tmp_path / "second.json"
    result_one = calibrate(manifest, first, 4, 4)
    result_two = calibrate(manifest, second, 4, 4)
    assert first.read_bytes() == second.read_bytes()
    assert result_one["training_frame_count"] == 5
    values = list(base64.b64decode(result_one["descriptor_b64"]))
    assert len(values) == 16
    assert len(set(values)) > 1
    assert result_one["normalization"]["stddev"] > 0
    assert result_one["descriptor_sha256"]
    assert result_one["confirm_frames"] == 2


def test_short_edge_coordinates_use_actual_frame_dimensions(tmp_path: Path) -> None:
    image = Image.new("RGB", (200, 100), (10, 20, 30))
    ImageDraw.Draw(image).rectangle((20, 20, 50, 45), fill=(180, 190, 200))
    image.save(tmp_path / "frame.png")
    manifest = _manifest(tmp_path, ["frame.png"], roi=[0.1, 0.2, 0.4, 0.5])
    output = tmp_path / "locator.json"
    locator = calibrate(manifest, output, 4, 4)
    # The short edge is 100: normalized x/y/w/h become pixel units / 100.
    assert locator["coordinate_space"] == "short_edge"
    assert locator["base_rect_short"] == [0.2, 0.2, 0.8, 0.5]


def test_short_edge_coordinates_are_relative_to_detected_black_bars(tmp_path: Path) -> None:
    image = Image.new("RGB", (240, 100), (0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rectangle((20, 0, 219, 99), fill=(30, 40, 50))
    draw.rectangle((40, 10, 99, 69), fill=(180, 100, 60))
    draw.rectangle((40, 10, 59, 29), fill=(70, 80, 90))
    image.save(tmp_path / "frame.png")
    manifest = _manifest(
        tmp_path, ["frame.png"], roi=[40 / 240, 0.1, 60 / 240, 0.6]
    )
    locator = calibrate(manifest, tmp_path / "locator.json", 4, 4)
    # Native runtime anchors the base at active-content x=20. The calibrator
    # must subtract the same bar instead of adding that offset a second time.
    assert locator["base_rect_short"] == [0.2, 0.1, 0.6, 0.6]


def test_descriptor_grid_matches_android_schema_minimum(tmp_path: Path) -> None:
    image = Image.new("RGB", (20, 20), (10, 10, 10))
    ImageDraw.Draw(image).rectangle((0, 0, 5, 19), fill=(100, 100, 100))
    image.save(tmp_path / "frame.png")
    manifest = _manifest(tmp_path, ["frame.png"], roi=[0, 0, 1, 1])
    with pytest.raises(ValueError, match="at least 4"):
        calibrate(manifest, tmp_path / "locator.json", 3, 4)


def test_ignored_samples_do_not_count_but_empty_review_fails(tmp_path: Path) -> None:
    image = Image.new("RGB", (20, 20), (10, 10, 10))
    image.save(tmp_path / "frame.png")
    manifest = _manifest(tmp_path, ["frame.png"], statuses=["pending"])
    with pytest.raises(ValueError, match="no valid reviewed frames"):
        calibrate(manifest, tmp_path / "locator.json")


def test_uniform_frames_cannot_create_a_locator(tmp_path: Path) -> None:
    image = Image.new("RGB", (20, 20), (20, 20, 20))
    image.save(tmp_path / "frame.png")
    manifest = _manifest(tmp_path, ["frame.png"], roi=[0, 0, 1, 1])
    with pytest.raises(ValueError, match="no visual variation"):
        calibrate(manifest, tmp_path / "locator.json", 4, 4)


def test_invalid_roi_and_inconsistent_dimensions_are_rejected(tmp_path: Path) -> None:
    Image.new("RGB", (20, 20), (0, 0, 0)).save(tmp_path / "a.png")
    Image.new("RGB", (30, 20), (0, 0, 0)).save(tmp_path / "b.png")
    manifest = _manifest(tmp_path, ["a.png", "b.png"])
    with pytest.raises(ValueError, match="Inconsistent frame dimensions"):
        calibrate(manifest, tmp_path / "locator.json")

    invalid = _manifest(tmp_path, ["a.png"], roi=[0.8, 0, 0.4, 0.4])
    with pytest.raises(ValueError, match="outside the normalized frame"):
        calibrate(invalid, tmp_path / "locator.json")


def test_locator_evaluator_rejects_corrupted_descriptor_provenance(tmp_path: Path) -> None:
    image = Image.new("RGB", (20, 20), (20, 20, 20))
    ImageDraw.Draw(image).rectangle((0, 0, 5, 19), fill=(100, 100, 100))
    image.save(tmp_path / "frame.png")
    manifest = _manifest(tmp_path, ["frame.png"], roi=[0, 0, 1, 1])
    output = tmp_path / "locator.json"
    calibrate(manifest, output, 4, 4)

    config, descriptor, _ = _load_locator(output)
    assert config.descriptor_width == 4
    assert len(descriptor) == 16

    payload = json.loads(output.read_text(encoding="utf-8"))
    payload["descriptor_sha256"] = "0" * 64
    output.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        _load_locator(output)
