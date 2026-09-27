from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "training"))

from evaluate_yolox_minimap import (
    _confidence_curve,
    _confidence_sweep_summary,
    _direction_event_metrics,
    _read_evaluation_annotations,
    _read_image_direction_rois,
    _resolve_sweep_thresholds,
)


def _roi_audit(crop_contacts: list[dict] | None = None,
               physical_contacts: list[dict] | None = None) -> dict:
    crop_contacts = crop_contacts or []
    eligible = not crop_contacts
    return {
        "schema_version": 1,
        "edge_tolerance_px": 1.0,
        "crop_edge_contacts": crop_contacts,
        "physical_edge_contacts": physical_contacts or [],
        "edge_contacts": crop_contacts,
        "training_eligible": eligible,
        "usable_for_training_or_evaluation": eligible,
        "policy": "Targets at expandable crop edges require review.",
    }


def _metrics(predictions: dict, truths: dict,
             direction_rois: dict[int, list[float]] | None = None,
             image_size: tuple[int, int] = (100, 100)) -> dict:
    image_sizes = {image_id: image_size for image_id in truths}
    return _direction_event_metrics(
        predictions, truths, confidence=0.5, iou_threshold=0.5,
        image_sizes=image_sizes, direction_rois=direction_rois,
    )


def test_confidence_range_expands_decimal_steps_without_drift() -> None:
    thresholds = _resolve_sweep_thresholds([0.87, 0.89], 0.001, None)

    assert thresholds == pytest.approx([0.87, 0.871, 0.872, 0.873, 0.874,
                                        0.875, 0.876, 0.877, 0.878, 0.879,
                                        0.88, 0.881, 0.882, 0.883, 0.884,
                                        0.885, 0.886, 0.887, 0.888, 0.889,
                                        0.89])


def test_confidence_curve_reports_box_metrics_at_each_threshold() -> None:
    predictions = {1: [
        (0.89, [10.0, 10.0, 10.0, 10.0]),
        (0.88, [50.0, 50.0, 10.0, 10.0]),
    ]}
    truths = {1: [[10.0, 10.0, 10.0, 10.0]]}

    curve = _confidence_curve(predictions, truths, [0.87, 0.88, 0.89], 0.5)

    assert [item["confidence"] for item in curve] == [0.87, 0.88, 0.89]
    assert [(item["precision"], item["recall"], item["f1"]) for item in curve] == [
        (0.5, 1.0, 0.666667),
        (0.5, 1.0, 0.666667),
        (1.0, 1.0, 1.0),
    ]


def test_confidence_sweep_selects_recall_gate_and_global_max_f1() -> None:
    curve = [
        {"confidence": 0.87, "tp": 92, "fp": 10, "fn": 8,
         "precision": 0.9, "recall": 0.92, "f1": 0.91},
        {"confidence": 0.88, "tp": 91, "fp": 0, "fn": 9,
         "precision": 1.0, "recall": 0.91, "f1": 0.95288},
        {"confidence": 0.89, "tp": 99, "fp": 12, "fn": 1,
         "precision": 0.891892, "recall": 0.99, "f1": 0.938389},
    ]

    summary = _confidence_sweep_summary(curve)

    assert summary["precision_eligible_thresholds"] == 2
    assert summary["max_recall_at_minimum_precision"]["confidence"] == 0.87
    assert summary["max_f1"]["confidence"] == 0.88


def test_confidence_sweep_reports_no_precision_eligible_point() -> None:
    curve = [{"confidence": 0.87, "tp": 8, "fp": 2, "fn": 2,
              "precision": 0.8, "recall": 0.8, "f1": 0.8}]

    summary = _confidence_sweep_summary(curve)

    assert summary["precision_eligible_thresholds"] == 0
    assert summary["max_recall_at_minimum_precision"] is None
    assert summary["max_f1"]["confidence"] == 0.87


@pytest.mark.parametrize("arguments", [
    ([0.8, 0.9], None, None),
    (None, 0.01, None),
    (None, None, [0.8, 0.8]),
    (None, None, [float("nan")]),
])
def test_confidence_sweep_rejects_invalid_cli_combinations(arguments) -> None:
    with pytest.raises(ValueError):
        _resolve_sweep_thresholds(*arguments)


def test_same_direction_ground_truth_boxes_fold_into_one_event() -> None:
    truths = {
        1: [
            [10.0, 20.0, 10.0, 10.0],
            [20.0, 40.0, 10.0, 10.0],
        ]
    }
    predictions = {
        1: [
            (0.9, [10.0, 20.0, 10.0, 10.0]),
            (0.8, [20.0, 40.0, 10.0, 10.0]),
        ]
    }

    result = _metrics(predictions, truths)

    assert result["truth_boxes"] == 2
    assert result["usable_truth_boxes"] == 2
    assert result["truth_events"] == 1
    assert result["set"]["tp"] == 1
    assert result["set"]["fp"] == 0
    assert result["set"]["fn"] == 0
    assert result["iou_gated"]["tp"] == 1
    assert result["iou_gated"]["fp"] == 0
    assert result["iou_gated"]["fn"] == 0


def test_direction_set_hit_without_iou_match_is_gated_as_fp_and_fn() -> None:
    truths = {1: [[5.0, 40.0, 10.0, 10.0]]}
    # Both boxes map to left, but they do not overlap.
    predictions = {1: [(0.9, [30.0, 30.0, 10.0, 10.0])]}

    result = _metrics(predictions, truths)

    assert result["set"]["tp"] == 1
    assert result["set"]["fp"] == 0
    assert result["set"]["fn"] == 0
    assert result["iou_gated"]["tp"] == 0
    assert result["iou_gated"]["fp"] == 1
    assert result["iou_gated"]["fn"] == 1


def test_ambiguous_and_center_ground_truth_are_excluded() -> None:
    truths = {
        1: [
            [65.0, 65.0, 10.0, 10.0],  # Diagonal boundary: ambiguous.
            [46.0, 46.0, 8.0, 8.0],  # Center: no directional event.
        ]
    }

    result = _metrics(
        {1: [(0.9, [65.0, 65.0, 10.0, 10.0]), (0.9, [46.0, 46.0, 8.0, 8.0])]},
        truths,
    )

    assert result["truth_boxes"] == 2
    assert result["usable_truth_boxes"] == 0
    assert result["ambiguous_truth_boxes"] == 1
    assert result["center_truth_boxes"] == 1
    assert result["truth_events"] == 0
    assert result["set"]["tp"] == 0
    assert result["set"]["fp"] == 1
    assert result["set"]["fn"] == 0
    assert result["iou_gated"]["tp"] == 0
    assert result["iou_gated"]["fp"] == 1
    assert result["iou_gated"]["fn"] == 0


def test_empty_frame_has_no_direction_events_and_false_event_is_fp() -> None:
    empty = _metrics({1: []}, {1: []})

    assert empty["truth_boxes"] == 0
    assert empty["truth_events"] == 0
    assert empty["set"] == {
        "tp": 0, "fp": 0, "fn": 0,
        "precision": None, "recall": None, "f1": None,
    }
    assert empty["iou_gated"] == empty["set"]

    false_event = _metrics({1: [(0.9, [10.0, 40.0, 10.0, 10.0])]}, {1: []})
    assert false_event["set"]["tp"] == 0
    assert false_event["set"]["fp"] == 1
    assert false_event["set"]["fn"] == 0
    assert false_event["iou_gated"]["fp"] == 1


def test_direction_events_use_widget_reference_inside_safe_crop() -> None:
    # The safe crop is 402x371 from [79,0,481,371]. The actual widget is
    # [106,0,454,344], whose local centre differs vertically from crop centre.
    widget = {1: [27 / 402, 0, 348 / 402, 344 / 371]}
    truth = [146.0, 107.0, 30.0, 30.0]       # center (161,122): up of widget centre
    prediction = [140.0, 112.0, 30.0, 30.0] # center (155,127): left of widget centre

    crop_center = _metrics({1: [(0.9, prediction)]}, {1: [truth]},
                            image_size=(402, 371))
    widget_center = _metrics({1: [(0.9, prediction)]}, {1: [truth]}, widget,
                              image_size=(402, 371))

    assert crop_center["set"]["tp"] == 1
    assert widget_center["direction_reference_images"] == 1
    assert widget_center["set"]["tp"] == 0
    assert widget_center["set"]["fp"] == 1
    assert widget_center["set"]["fn"] == 1


@pytest.mark.parametrize("value", [
    [float("nan"), 0.1, 0.2, 0.2],
    [float("inf"), 0.1, 0.2, 0.2],
    [0.1, 0.1, 0.0, 0.2],
    [0.9, 0.1, 0.2, 0.2],
])
def test_direction_roi_reader_rejects_invalid_widget_rectangles(value) -> None:
    with pytest.raises(ValueError, match="direction_roi"):
        _read_image_direction_rois({"images": [
            {"id": 1, "direction_roi": value},
        ]})


def test_evaluation_gate_rejects_exporter_marked_crop_edge_contacts(tmp_path: Path) -> None:
    annotation_dir = tmp_path / "annotations"
    annotation_dir.mkdir()
    annotation = {
        "images": [{"id": 1, "width": 100, "height": 100}],
        "annotations": [{"id": 1, "image_id": 1, "bbox": [20, 20, 10, 10]}],
        "info": {"roi_boundary_audit": _roi_audit([{
            "match_id": "m1", "at_ms": 1000, "box_index": 1, "sides": ["right"],
        }])},
    }
    (annotation_dir / "instances_test2017.json").write_text(
        json.dumps(annotation), encoding="utf-8"
    )

    with pytest.raises(ValueError, match="expandable crop edge"):
        _read_evaluation_annotations(tmp_path, "test")


def test_evaluation_gate_allows_physical_edge_and_unknown_coco_provenance(
    tmp_path: Path,
) -> None:
    annotation_dir = tmp_path / "annotations"
    annotation_dir.mkdir()
    annotation = {
        "images": [{"id": 1, "width": 100, "height": 100}],
        "annotations": [{"id": 1, "image_id": 1, "bbox": [0, 20, 10, 10]}],
        "info": {"roi_boundary_audit": _roi_audit(physical_contacts=[{
            "match_id": "m1", "at_ms": 1000, "box_index": 1, "sides": ["left"],
        }])},
    }
    path = annotation_dir / "instances_test2017.json"
    path.write_text(json.dumps(annotation), encoding="utf-8")

    loaded_path, _ = _read_evaluation_annotations(tmp_path, "test")
    assert loaded_path == path

    del annotation["info"]
    path.write_text(json.dumps(annotation), encoding="utf-8")
    assert _read_evaluation_annotations(tmp_path, "test")[0] == path

    annotation["info"] = {"roi_boundary_audit": {"schema_version": 9}}
    path.write_text(json.dumps(annotation), encoding="utf-8")
    assert _read_evaluation_annotations(tmp_path, "test")[0] == path


def test_evaluation_geometry_gate_matches_training_for_out_of_image_bbox(
    tmp_path: Path,
) -> None:
    annotation_dir = tmp_path / "annotations"
    annotation_dir.mkdir()
    annotation = {
        "images": [{"id": 1, "width": 100, "height": 100}],
        "annotations": [{"id": 1, "image_id": 1, "bbox": [95, 20, 10, 10]}],
    }
    (annotation_dir / "instances_test2017.json").write_text(
        json.dumps(annotation), encoding="utf-8"
    )

    with pytest.raises(ValueError, match="outside or invalid"):
        _read_evaluation_annotations(tmp_path, "test")
