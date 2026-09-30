from __future__ import annotations

import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training"))

from train_yolox_minimap import (
    _assert_roi_boundaries_clear,
    _is_better_validation_metric,
    _load_checkpoint_audited,
    _per_class_threshold_diagnostics,
    _select_validation_metric,
    _validate_range,
    _validate_training_args,
)


class _FakeTensor:
    def __init__(self, shape: tuple[int, ...], marker: str) -> None:
        self.shape = shape
        self.marker = marker


class _FakeModel:
    def __init__(self, state: dict[str, _FakeTensor]) -> None:
        self._state = state
        self.loaded: dict[str, _FakeTensor] = {}

    def state_dict(self) -> dict[str, _FakeTensor]:
        return self._state

    def load_state_dict(self, values: dict[str, _FakeTensor], strict: bool) -> None:
        assert strict is False
        self.loaded = values


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


def test_selection_prefers_recall_then_f1_then_precision_after_gate() -> None:
    results = [
        {"confidence": 0.1, "precision": 0.95, "recall": 0.70, "f1": 0.80},
        {"confidence": 0.2, "precision": 0.91, "recall": 0.70, "f1": 0.82},
        {"confidence": 0.3, "precision": 0.89, "recall": 0.99, "f1": 0.93},
    ]

    selected, policy, metric = _select_validation_metric(results, 0.90)

    assert selected["confidence"] == 0.2
    assert policy == "highest_recall_with_precision_at_least_0.9"
    assert metric.startswith("recall_then_f1")


def test_selection_falls_back_to_f1_then_recall() -> None:
    results = [
        {"confidence": 0.1, "precision": 0.80, "recall": 0.90, "f1": 0.70},
        {"confidence": 0.2, "precision": 0.85, "recall": 0.60, "f1": 0.80},
    ]

    selected, policy, _ = _select_validation_metric(results, 0.90)

    assert selected["confidence"] == 0.2
    assert policy == "highest_f1_no_threshold_reached_0.9_precision"


def test_checkpoint_comparison_keeps_precision_eligible_metric_first() -> None:
    eligible = {"precision": 0.90, "recall": 0.70, "f1": 0.80}
    ineligible = {"precision": 0.89, "recall": 0.99, "f1": 0.95}

    assert _is_better_validation_metric(eligible, ineligible, 0.90)
    assert not _is_better_validation_metric(ineligible, eligible, 0.90)


def test_checkpoint_loader_only_skips_classification_shape_mismatches() -> None:
    model = _FakeModel({
        "backbone.weight": _FakeTensor((2, 2), "model-backbone"),
        "head.reg_preds.0.weight": _FakeTensor((4, 2, 1, 1), "model-reg"),
        "head.obj_preds.0.weight": _FakeTensor((1, 2, 1, 1), "model-obj"),
        "head.cls_preds.0.weight": _FakeTensor((2, 2, 1, 1), "model-cls"),
    })
    checkpoint = {
        "backbone.weight": _FakeTensor((2, 2), "checkpoint-backbone"),
        "head.reg_preds.0.weight": _FakeTensor((4, 2, 1, 1), "checkpoint-reg"),
        "head.obj_preds.0.weight": _FakeTensor((1, 2, 1, 1), "checkpoint-obj"),
        "head.cls_preds.0.weight": _FakeTensor((1, 2, 1, 1), "checkpoint-cls"),
    }

    _, audit = _load_checkpoint_audited(model, checkpoint)

    assert set(audit["loaded_keys"]) == {
        "backbone.weight", "head.reg_preds.0.weight", "head.obj_preds.0.weight",
    }
    assert audit["skipped_keys"] == ["head.cls_preds.0.weight"]
    assert model.loaded["backbone.weight"].marker == "checkpoint-backbone"
    assert model.loaded["head.reg_preds.0.weight"].marker == "checkpoint-reg"
    assert model.loaded["head.obj_preds.0.weight"].marker == "checkpoint-obj"
    assert audit["unsafe_mismatches"] == []


def test_checkpoint_loader_rejects_non_classification_shape_mismatch() -> None:
    model = _FakeModel({"backbone.weight": _FakeTensor((2, 2), "model")})
    checkpoint = {"backbone.weight": _FakeTensor((3, 2), "checkpoint")}

    with pytest.raises(ValueError, match="unsafe non-classification"):
        _load_checkpoint_audited(model, checkpoint)


def test_per_class_threshold_diagnostics_records_precision_gate() -> None:
    predictions = {
        1: [(0.8, [0.0, 0.0, 10.0, 10.0], 0),
            (0.7, [20.0, 20.0, 10.0, 10.0], 1)],
    }
    truths = {
        1: [(0, [0.0, 0.0, 10.0, 10.0]),
            (1, [20.0, 20.0, 10.0, 10.0])],
    }

    diagnostics = _per_class_threshold_diagnostics(
        predictions, truths, ("enemy", "player"), [0.5, 0.9], 0.9,
    )

    assert diagnostics["selected_confidence_by_class"] == {
        "enemy": 0.5, "player": 0.5,
    }
    assert diagnostics["classes"]["enemy"]["selected"]["precision"] == 1.0
    assert diagnostics["classes"]["player"]["selected"]["recall"] == 1.0


@pytest.mark.parametrize("value", [-0.01, 1.01, float("nan"), float("inf")])
def test_range_validation_rejects_out_of_range_and_non_finite(value: float) -> None:
    with pytest.raises(ValueError):
        _validate_range("minimum-precision", value, 0.0, 1.0)


def test_training_args_reject_zero_mosaic_scale() -> None:
    args = SimpleNamespace(
        epochs=60,
        batch_size=16,
        eval_every=5,
        log_every=5,
        input_size=320,
        lr_scale=0.1,
        early_stop_patience=4,
        early_stop_min_epoch=20,
        mosaic_prob=0.5,
        flip_prob=0.5,
        hsv_prob=0.8,
        degrees=5.0,
        translate=0.08,
        shear=1.0,
        mosaic_scale_min=0.0,
        mosaic_scale_max=1.3,
        nms_threshold=0.5,
        minimum_precision=0.9,
        no_aug_epochs=20,
    )

    with pytest.raises(ValueError, match="greater than zero"):
        _validate_training_args(args)


def test_training_gate_rejects_exporter_marked_crop_edge_contacts(tmp_path: Path) -> None:
    annotation_dir = tmp_path / "annotations"
    annotation_dir.mkdir()
    clean = {
        "images": [{"id": 1, "width": 100, "height": 100}],
        "annotations": [{"id": 1, "image_id": 1, "bbox": [20, 20, 10, 10]}],
        "info": {"roi_boundary_audit": _roi_audit()},
    }
    (annotation_dir / "instances_train2017.json").write_text(
        json.dumps(clean), encoding="utf-8"
    )
    (annotation_dir / "instances_val2017.json").write_text(
        json.dumps(clean), encoding="utf-8"
    )

    _assert_roi_boundaries_clear(tmp_path)

    dirty = dict(clean)
    dirty["info"] = {"roi_boundary_audit": _roi_audit([{
        "match_id": "m1", "at_ms": 1000, "box_index": 1, "sides": ["right"],
    }])}
    (annotation_dir / "instances_val2017.json").write_text(
        json.dumps(dirty), encoding="utf-8"
    )
    with pytest.raises(ValueError, match="expandable crop edge"):
        _assert_roi_boundaries_clear(tmp_path)


def test_training_gate_allows_physical_screen_edge_contacts(tmp_path: Path) -> None:
    annotation_dir = tmp_path / "annotations"
    annotation_dir.mkdir()
    document = {
        "images": [{"id": 1, "width": 100, "height": 100}],
        "annotations": [{"id": 1, "image_id": 1, "bbox": [0, 20, 10, 10]}],
        "info": {"roi_boundary_audit": _roi_audit(physical_contacts=[{
            "match_id": "m1", "at_ms": 1000, "box_index": 1, "sides": ["left"],
        }])},
    }
    for split in ("train", "val"):
        (annotation_dir / f"instances_{split}2017.json").write_text(
            json.dumps(document), encoding="utf-8"
        )

    _assert_roi_boundaries_clear(tmp_path)


def test_training_gate_rejects_bbox_outside_image(tmp_path: Path) -> None:
    annotation_dir = tmp_path / "annotations"
    annotation_dir.mkdir()
    document = {
        "images": [{"id": 1, "width": 100, "height": 100}],
        "annotations": [{"id": 1, "image_id": 1, "bbox": [95, 20, 10, 10]}],
    }
    for split in ("train", "val"):
        (annotation_dir / f"instances_{split}2017.json").write_text(
            json.dumps(document), encoding="utf-8"
        )

    with pytest.raises(ValueError, match="outside or invalid"):
        _assert_roi_boundaries_clear(tmp_path)
