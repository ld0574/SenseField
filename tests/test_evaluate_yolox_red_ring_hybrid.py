from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "training"))

from evaluate_yolox_red_ring_hybrid import (
    HybridConfig,
    _build_parser,
    _config_from_args,
    _evaluate_metrics,
    _hybrid_accept,
    _strict_red_ring_features,
    _validate_config,
)


def _mark_red(image: np.ndarray, y: int, x: int) -> None:
    # Equality at both thresholds is accepted by the native strict-red rule.
    image[y, x] = (100, 80, 80)


def test_strict_red_ring_score_counts_supported_bands_and_opposite_pair() -> None:
    image = np.zeros((20, 20, 3), dtype=np.uint8)
    for offset in range(3):
        _mark_red(image, 4 + offset, 0)
        _mark_red(image, 4 + offset, 19)
        _mark_red(image, 0, 8 + offset)
        _mark_red(image, 19, 8 + offset)

    result = _strict_red_ring_features(image, [0, 0, 20, 20], 100, HybridConfig())

    assert result == {
        "ring_score": 36,
        "ring_valid": True,
        "supported_sides": 4,
        "side_counts": [3, 3, 3, 3],
        "red_pixels": 12,
        "band_width": 3,
    }


def test_gate_requires_three_sides_and_an_opposite_pair() -> None:
    three_with_pair = np.zeros((20, 20, 3), dtype=np.uint8)
    three_without_pair = np.zeros((20, 20, 3), dtype=np.uint8)
    for offset in range(3):
        _mark_red(three_with_pair, 4 + offset, 0)
        _mark_red(three_with_pair, 4 + offset, 19)
        _mark_red(three_with_pair, 0, 8 + offset)
        _mark_red(three_without_pair, 0, 8 + offset)
        _mark_red(three_without_pair, 4 + offset, 0)

    valid = _strict_red_ring_features(
        three_with_pair, [0, 0, 20, 20], 100, HybridConfig(),
    )
    invalid = _strict_red_ring_features(
        three_without_pair, [0, 0, 20, 20], 100,
        HybridConfig(min_sides=2),
    )

    assert valid["supported_sides"] == 3
    assert valid["ring_valid"] is True
    assert invalid["supported_sides"] == 2
    assert invalid["ring_valid"] is False


def test_red_thresholds_are_inclusive_and_bbox_is_clipped() -> None:
    image = np.zeros((8, 8, 3), dtype=np.uint8)
    image[1:4, 0] = (100, 80, 80)
    image[1:4, 7] = (100, 80, 80)
    image[0, 3:6] = (100, 80, 80)
    # These are below red_min even though their color ratios are otherwise red.
    image[4:7, 0] = (89, 0, 0)

    result = _strict_red_ring_features(image, [-3, 0, 14, 8], 8, HybridConfig())

    assert result["red_pixels"] == 9
    assert result["side_counts"] == [3, 3, 5, 0]
    assert result["ring_valid"] is True


def test_hybrid_acceptance_has_direct_high_pass_and_fixed_ring_gate() -> None:
    config = HybridConfig()
    no_ring = {"ring_valid": False, "ring_score": 0}
    passing_ring = {"ring_valid": True, "ring_score": 188}
    weak_ring = {"ring_valid": True, "ring_score": 187}

    assert _hybrid_accept(0.807, no_ring, config)
    assert _hybrid_accept(0.45, passing_ring, config)
    assert not _hybrid_accept(0.449, passing_ring, config)
    assert not _hybrid_accept(0.45, weak_ring, config)
    assert not _hybrid_accept(0.70, no_ring, config)


def test_metrics_compare_fixed_baseline_and_hybrid_in_dense_and_right_groups() -> None:
    config = HybridConfig()
    truths = {
        1: [[70.0, y, 10.0, 10.0] for y in (35.0, 45.0, 55.0)],
    }
    predictions = {
        1: [
            {"confidence": 0.9, "bbox": [70.0, 35.0, 10.0, 10.0],
             "ring_valid": False, "ring_score": 0},
            {"confidence": 0.5, "bbox": [70.0, 45.0, 10.0, 10.0],
             "ring_valid": True, "ring_score": 200},
            {"confidence": 0.5, "bbox": [70.0, 55.0, 10.0, 10.0],
             "ring_valid": False, "ring_score": 200},
        ],
    }
    images = {1: {"id": 1, "width": 100, "height": 100}}

    result = _evaluate_metrics(
        predictions, truths, images, {}, config, 0.5,
    )

    assert result["baseline"]["overall"]["tp"] == 1
    assert result["baseline"]["overall"]["fn"] == 2
    assert result["hybrid"]["overall"]["tp"] == 2
    assert result["hybrid"]["overall"]["fp"] == 0
    assert result["hybrid"]["overall"]["fn"] == 1
    assert result["hybrid"]["dense_3plus"] == result["hybrid"]["overall"]
    assert result["hybrid"]["right_direction"] == result["hybrid"]["overall"]


def _cli_config(*extra: str) -> tuple[HybridConfig, int, float, float]:
    args = _build_parser().parse_args([
        "--yolox-root", "YOLOX",
        "--data-dir", "dataset",
        "--checkpoint", "frozen.pth",
        "--output", "report.json",
        "--input-size", "320",
        *extra,
    ])
    return (_config_from_args(args), args.input_size,
            args.iou_threshold, args.nms_threshold)


def test_cli_defaults_match_documented_fixed_rule() -> None:
    config, input_size, iou, nms = _cli_config()

    _validate_config(config, input_size=input_size,
                     iou_threshold=iou, nms_threshold=nms)

    assert config == HybridConfig()


@pytest.mark.parametrize("overrides", [
    ("--input-size", "65"),
    ("--high-confidence", "nan"),
    ("--low-confidence", "0.9"),
    ("--ring-score-min", "100001"),
    ("--red-min", "256"),
    ("--red-dominance", "0.9"),
    ("--band-ratio", "0.251"),
    ("--min-band", "0"),
    ("--min-sides", "2"),
    ("--pixels-per-side", "129"),
    ("--iou-threshold", "0"),
    ("--nms-threshold", "inf"),
])
def test_cli_rejects_values_outside_documented_ranges(overrides: tuple[str, str]) -> None:
    args = _build_parser().parse_args([
        "--yolox-root", "YOLOX",
        "--data-dir", "dataset",
        "--checkpoint", "frozen.pth",
        "--output", "report.json",
        "--input-size", "320",
        *overrides,
    ])
    config = _config_from_args(args)

    with pytest.raises(ValueError):
        _validate_config(config, input_size=args.input_size,
                         iou_threshold=args.iou_threshold,
                         nms_threshold=args.nms_threshold)
