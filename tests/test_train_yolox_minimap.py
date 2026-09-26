from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training"))

from train_yolox_minimap import (
    _is_better_validation_metric,
    _select_validation_metric,
    _validate_range,
    _validate_training_args,
)


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
