from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training"))

from evaluate_yolox_locator import locator_metrics


def test_locator_metrics_selects_deployment_box_and_reports_crop_quality() -> None:
    predictions = {
        1: [(0.8, [10.0, 10.0, 80.0, 80.0]), (0.7, [0.0, 0.0, 20.0, 20.0])],
        2: [(0.4, [10.0, 10.0, 80.0, 80.0])],
    }
    truths = {
        1: [[10.0, 10.0, 80.0, 80.0]],
        2: [[10.0, 10.0, 80.0, 80.0]],
    }
    images = {
        1: {"id": 1, "file_name": "one.png", "width": 100, "height": 100},
        2: {"id": 2, "file_name": "two.png", "width": 100, "height": 100},
    }

    metrics, per_image = locator_metrics(predictions, truths, 0.5, images)

    assert metrics["frames_with_prediction"] == 1
    assert metrics["missing_frames"] == 1
    assert metrics["extra_predictions"] == 1
    assert metrics["iou"]["median"] == 1.0
    assert metrics["truth_coverage"]["median"] == 1.0
    assert metrics["max_edge_error_px"]["maximum"] == 0.0
    assert per_image[0]["selected"]["confidence"] == 0.8
    assert per_image[0]["selected"]["crop_bbox"] == [10.0, 10.0, 80.0, 80.0]
    assert per_image[1]["selected"] is None


def test_locator_metrics_requires_one_truth_per_frame() -> None:
    with pytest.raises(ValueError, match="exactly one truth box"):
        locator_metrics(
            {1: []}, {1: []}, 0.5,
            {1: {"id": 1, "file_name": "one.png", "width": 100, "height": 100}},
        )


def test_locator_metrics_requires_identical_image_coverage() -> None:
    with pytest.raises(ValueError, match="identical IDs"):
        locator_metrics(
            {1: []}, {1: [[0.0, 0.0, 1.0, 1.0]]}, 0.5, {},
        )


def test_locator_metrics_expands_crop_by_short_edge_and_clips() -> None:
    metrics, per_image = locator_metrics(
        {1: [(0.9, [5.0, 0.0, 80.0, 80.0])]},
        {1: [[0.0, 0.0, 90.0, 90.0]]},
        0.5,
        {1: {"id": 1, "file_name": "one.png", "width": 200, "height": 100}},
        margin_short_edge=0.05,
    )

    assert per_image[0]["selected"]["crop_bbox"] == [0.0, 0.0, 90.0, 85.0]
    assert metrics["margin_short_edge"] == 0.05
