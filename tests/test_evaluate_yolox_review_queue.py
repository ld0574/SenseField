from __future__ import annotations

import pytest

from training.evaluate_yolox_review_queue import (
    _center_inside_roi,
    _crop_box_to_full_frame,
    _filter_label_roi,
    _reviewed_frames,
    _verify_display_size,
)


def _manual_queue(status: str = "corrected") -> dict:
    return {
        "schema_version": 1,
        "review_mode": "manual",
        "roi": [0.0, 0.0, 1.0, 1.0],
        "sampling": {"predictions_used_for_selection": False},
        "matches": [{
            "id": "video2-hd",
            "split": "train",
            "roi": [0.0, 0.0, 1.0, 1.0],
            "samples": [{
                "at_ms": 1,
                "frame": "frame.png",
                "review_status": status,
                "reviewed_boxes": [[0.1, 0.1, 0.1, 0.1]] if status == "corrected" else [],
            }],
        }],
    }


def test_crop_boxes_are_translated_after_resize_is_already_inverted() -> None:
    roi = [55 / 2400, 0.0, 455 / 2400, 420 / 1080]
    converted = _crop_box_to_full_frame(
        roi, (54, 0), (2400, 1080), [98.59, 1.66, 156.32, 57.32],
    )
    assert converted is not None
    assert converted[0] == pytest.approx((54 + 98.59) / 2400)
    assert converted[1] == pytest.approx(1.66 / 1080)
    assert converted[2] == pytest.approx((156.32 - 98.59) / 2400)
    assert converted[3] == pytest.approx((57.32 - 1.66) / 1080)


def test_center_filter_uses_the_full_frame_label_roi() -> None:
    label_roi = [0.1, 0.2, 0.2, 0.3]
    inside = [0.18, 0.32, 0.04, 0.04]
    outside = [0.01, 0.32, 0.04, 0.04]
    assert _center_inside_roi(inside, label_roi)
    assert not _center_inside_roi(outside, label_roi)
    assert _filter_label_roi([inside, outside], label_roi) == [inside]


def test_review_queue_roi_falls_back_to_root_values() -> None:
    document = _manual_queue()
    document["roi"] = [0.05, 0.0, 0.2, 0.4]
    document["label_roi"] = [0.08, 0.0, 0.17, 0.35]
    document["matches"][0]["roi"] = None
    document["matches"][0]["label_roi"] = None
    _, frames = _reviewed_frames(document)
    assert frames[0]["roi"] == document["roi"]
    assert frames[0]["label_roi"] == document["label_roi"]


@pytest.mark.parametrize("match_id", ["video9", "video9-hd", "video12", "video12-hd"])
def test_sealed_matches_are_rejected_before_evaluation(match_id: str) -> None:
    document = _manual_queue()
    document["matches"][0]["id"] = match_id
    with pytest.raises(ValueError, match="sealed match"):
        _reviewed_frames(document)


def test_orientation_display_size_is_checked() -> None:
    _verify_display_size({"orientation": {"display_size": [2400, 1080]}}, 2400, 1080)
    with pytest.raises(ValueError, match="does not match"):
        _verify_display_size({"orientation": {"display_size": [1080, 2400]}}, 2400, 1080)


@pytest.mark.parametrize("status", ["pending", "accepted"])
def test_incomplete_or_accepted_queues_are_rejected(status: str) -> None:
    with pytest.raises(ValueError):
        _reviewed_frames(_manual_queue(status))


def test_multiclass_review_requires_explicit_reviewed_categories() -> None:
    document = _manual_queue()
    document["classes"] = ["minimap_enemy", "minimap_player"]
    document["matches"][0]["samples"][0]["suggested_categories"] = [
        "minimap_player"
    ]

    with pytest.raises(ValueError, match="categories are required"):
        _reviewed_frames(document)
