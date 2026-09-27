from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from mapassist.safe_roi_manifest import remap_manifest, remap_to_file


EXPECTED_ROIS = {
    "video2": [0.0203611, 0.0, 0.1773333, 0.3559877],
    "video3": [0.037375, 0.0, 0.1700417, 0.3498148],
    "video4": [0.0203611, 0.0, 0.1773333, 0.3559877],
    "video5": [0.0203611, 0.0, 0.1773333, 0.3559877],
    "video6": [0.0370278, 0.0, 0.1703889, 0.3529012],
    "video7": [0.0377212622, 0.0, 0.1679203755, 0.3456557],
}

LAYOUTS = {
    "video1": ([720, 324], [0.0361111, 0.0, 0.1458333, 0.3209877]),
    "video2": ([720, 324], [0.0361111, 0.0, 0.1458333, 0.3209877]),
    "video3": ([960, 432], [0.053125, 0.0, 0.1385417, 0.3148148]),
    "video4": ([720, 324], [0.0361111, 0.0, 0.1458333, 0.3209877]),
    "video5": ([720, 324], [0.0361111, 0.0, 0.1458333, 0.3209877]),
    "video6": ([720, 324], [0.0527778, 0.0, 0.1388889, 0.3179012]),
    "video7": ([2712, 1220], [0.0534661, 0.0, 0.1364307, 0.3106557]),
}


def _write_fixture(tmp_path: Path) -> tuple[Path, Path, dict[str, bytes]]:
    video_dir = tmp_path / "videos"
    video_dir.mkdir()
    source_matches = []
    layout_matches = []
    video_bytes = {}
    for index, (match_id, (display_size, widget)) in enumerate(LAYOUTS.items()):
        video_path = video_dir / f"{match_id}.mp4"
        video_bytes[match_id] = f"video data for {match_id}".encode()
        video_path.write_bytes(video_bytes[match_id])
        source_match = {
            "id": match_id,
            "video": str(video_path),
            "split": "val" if match_id == "video6" else "train",
            "roi": [0.0, 0.0, 1.0, 1.0],
            "frames": [{"at_ms": 100 + index, "boxes": [[0.1, 0.1, 0.02, 0.03]]}],
        }
        if match_id == "video6":
            source_match["orientation"] = {
                "display_size": display_size,
                "display_rotation_degrees": 0,
            }
        source_matches.append(source_match)
        layout_matches.append({
            "id": match_id,
            "video": str(video_path),
            "split": "train",
            "orientation": {
                "source_coded_size": display_size,
                "display_size": display_size,
                "display_rotation_degrees": 0,
                "queue_frames_must_be_display_oriented": True,
            },
            "frames": [
                {"at_ms": timestamp, "boxes": [widget]}
                for timestamp in (1000, 2000, 3000)
            ],
        })
    detection_path = tmp_path / "detection.json"
    layout_path = tmp_path / "layout.json"
    detection_path.write_text(json.dumps({
        "schema_version": 1,
        "category": "minimap_enemy",
        "matches": source_matches,
    }), encoding="utf-8")
    layout_path.write_text(json.dumps({
        "schema_version": 1,
        "category": "minimap_region",
        "matches": layout_matches,
    }), encoding="utf-8")
    return detection_path, layout_path, video_bytes


def test_remap_uses_reviewed_widget_bounds_expands_and_excludes_old_video1(
    tmp_path: Path,
) -> None:
    detection_path, layout_path, video_bytes = _write_fixture(tmp_path)
    output_path = tmp_path / "safe-detection.json"
    original_detection = detection_path.read_bytes()
    original_layout = layout_path.read_bytes()

    summary = remap_to_file(
        detection_path, layout_path, output_path, exclude_matches={"video1"}
    )
    remapped = json.loads(output_path.read_text(encoding="utf-8"))
    by_id = {match["id"]: match for match in remapped["matches"]}

    assert summary["match_ids"] == list(EXPECTED_ROIS)
    assert set(by_id) == set(EXPECTED_ROIS)
    assert "video1" not in by_id
    for match_id, expected in EXPECTED_ROIS.items():
        assert by_id[match_id]["roi"] == pytest.approx(expected, abs=1e-9)
        assert by_id[match_id]["widget_roi"] == LAYOUTS[match_id][1]
        assert by_id[match_id]["video_sha256"] == hashlib.sha256(
            video_bytes[match_id]
        ).hexdigest()
    assert by_id["video6"]["split"] == "val"
    assert by_id["video6"]["orientation"]["display_size"] == [720, 324]
    assert by_id["video6"]["frames"] == [{
        "at_ms": 105, "boxes": [[0.1, 0.1, 0.02, 0.03]],
    }]
    audit = remapped["roi_remap_audit"]
    assert audit["padding_short_side_fraction"] == 0.035
    assert audit["layout_frame_policy"] == "exactly one identical widget box in every frame"
    assert audit["matches"]["video2"]["layout_frame_count"] == 3
    assert detection_path.read_bytes() == original_detection
    assert layout_path.read_bytes() == original_layout


def test_remap_rejects_inconsistent_layout_frames(tmp_path: Path) -> None:
    detection_path, layout_path, _ = _write_fixture(tmp_path)
    layout = json.loads(layout_path.read_text(encoding="utf-8"))
    layout["matches"][1]["frames"][1]["boxes"] = [[0.03, 0.0, 0.15, 0.32]]
    layout_path.write_text(json.dumps(layout), encoding="utf-8")

    with pytest.raises(ValueError, match="widget boxes differ across frames"):
        remap_manifest(detection_path, layout_path, include_matches={"video2"})


def test_remap_rejects_id_that_maps_to_another_video(tmp_path: Path) -> None:
    detection_path, layout_path, _ = _write_fixture(tmp_path)
    layout = json.loads(layout_path.read_text(encoding="utf-8"))
    other_video = tmp_path / "videos/other.mp4"
    other_video.write_bytes(b"other recording")
    layout["matches"][1]["video"] = str(other_video)
    layout_path.write_text(json.dumps(layout), encoding="utf-8")

    with pytest.raises(ValueError, match="maps to different videos"):
        remap_manifest(detection_path, layout_path, include_matches={"video2"})


def test_remap_rejects_hash_mismatch_and_does_not_create_output(tmp_path: Path) -> None:
    detection_path, layout_path, _ = _write_fixture(tmp_path)
    detection = json.loads(detection_path.read_text(encoding="utf-8"))
    detection["matches"][1]["video_sha256"] = "0" * 64
    detection_path.write_text(json.dumps(detection), encoding="utf-8")
    output_path = tmp_path / "never-written.json"

    with pytest.raises(ValueError, match="video_sha256 does not match"):
        remap_to_file(
            detection_path, layout_path, output_path, include_matches={"video2"}
        )
    assert not output_path.exists()
    assert not list(tmp_path.glob(".never-written.json.*.tmp"))


def test_remap_records_box_center_within_four_pixel_widget_tolerance(
    tmp_path: Path,
) -> None:
    detection_path, layout_path, _ = _write_fixture(tmp_path)
    detection = json.loads(detection_path.read_text(encoding="utf-8"))
    video6 = next(match for match in detection["matches"] if match["id"] == "video6")
    widget_left = LAYOUTS["video6"][1][0]
    center_x = widget_left - 3.1 / LAYOUTS["video6"][0][0]
    video6["frames"][0]["boxes"] = [[center_x - 0.01, 0.1, 0.02, 0.03]]
    detection_path.write_text(json.dumps(detection), encoding="utf-8")
    output_path = tmp_path / "tolerated-edge.json"

    remap_to_file(
        detection_path, layout_path, output_path, include_matches={"video6"}
    )
    output = json.loads(output_path.read_text(encoding="utf-8"))
    exceptions = output["roi_remap_audit"]["widget_center_tolerance_exceptions"]
    assert exceptions == [{
        "match_id": "video6",
        "at_ms": 105,
        "box_index": 1,
        "outside_px": pytest.approx(3.1, abs=1e-6),
        "sides": ["left"],
    }]
    assert output["roi_remap_audit"]["widget_center_tolerance_px"] == 4.0


def test_remap_rejects_center_more_than_four_pixels_outside_without_output(
    tmp_path: Path,
) -> None:
    detection_path, layout_path, _ = _write_fixture(tmp_path)
    detection = json.loads(detection_path.read_text(encoding="utf-8"))
    video7 = next(match for match in detection["matches"] if match["id"] == "video7")
    widget = LAYOUTS["video7"][1]
    display_height = LAYOUTS["video7"][0][1]
    center_y = widget[1] + widget[3] + 18.4 / display_height
    video7["frames"][0]["boxes"] = [[0.1, center_y - 0.005, 0.02, 0.01]]
    detection_path.write_text(json.dumps(detection), encoding="utf-8")
    output_path = tmp_path / "rejected-edge.json"

    with pytest.raises(
        ValueError,
        match=r"video7@106 box 1 center is 18\.400 px outside widget \(bottom\); tolerance is 4 px",
    ):
        remap_to_file(
            detection_path, layout_path, output_path, include_matches={"video7"}
        )
    assert not output_path.exists()
    assert not list(tmp_path.glob(".rejected-edge.json.*.tmp"))


@pytest.mark.parametrize("tolerance", [-0.01, float("nan"), float("inf")])
def test_widget_center_tolerance_must_be_finite_and_nonnegative(
    tmp_path: Path, tolerance: float,
) -> None:
    with pytest.raises(ValueError, match="Widget center tolerance must be finite and nonnegative"):
        remap_manifest(
            tmp_path / "unused-detection.json",
            tmp_path / "unused-layout.json",
            widget_center_tolerance_px=tolerance,
        )


def test_output_cannot_overwrite_an_input_manifest(tmp_path: Path) -> None:
    detection_path, layout_path, _ = _write_fixture(tmp_path)
    before = detection_path.read_bytes()

    with pytest.raises(ValueError, match="must not overwrite either input"):
        remap_to_file(detection_path, layout_path, detection_path)
    assert detection_path.read_bytes() == before
