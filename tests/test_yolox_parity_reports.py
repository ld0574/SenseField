from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training"))

from verify_yolox_ncnn import _paired_detection_metrics  # noqa: E402
from verify_yolox_onnx import _raw_difference_detail  # noqa: E402


def test_onnx_raw_error_report_locates_cell_and_marks_low_confidence() -> None:
    torch_raw = np.zeros((1, 2100, 6), dtype=np.float32)
    onnx_raw = np.zeros_like(torch_raw)
    torch_raw[0, 1968] = [-1.1085927, -0.1327409, 1.9591352, 1.2179699,
                          4.5886857e-8, 0.010274521]
    onnx_raw[0, 1968] = [-1.1092238, -0.13282165, 1.9594166, 1.2180145,
                         2.9802322e-8, 0.010273993]

    detail = _raw_difference_detail(torch_raw, onnx_raw, 320, 0.21, np)

    assert detail["row"] == 1968
    assert detail["channel_name"] == "center_x_offset"
    assert detail["stride"] == 16
    assert (detail["grid_x"], detail["grid_y"]) == (8, 18)
    assert detail["absolute_error"] == pytest.approx(0.000631094, abs=1e-8)
    assert detail["decoded_coordinate_error_pixels"] == pytest.approx(0.0100975)
    assert detail["torch_confidence"] < 1e-8
    assert detail["onnx_confidence"] < 1e-8
    assert not detail["torch_above_confidence_threshold"]
    assert not detail["onnx_above_confidence_threshold"]


def test_ncnn_semantic_detection_metrics_do_not_replace_strict_coordinate_gate() -> None:
    reference = np.asarray([[10.0, 10.0, 30.0, 30.0, 0.8]], dtype=np.float32)
    runtime = np.asarray([[10.1, 10.0, 30.1, 30.0, 0.79]], dtype=np.float32)

    metrics = _paired_detection_metrics(reference, runtime, [(0, 0)])

    assert len(metrics) == 1
    assert metrics[0]["iou"] == pytest.approx(0.99005, rel=1e-5)
    assert metrics[0]["score_abs_error"] == pytest.approx(0.01, abs=1e-6)
    assert metrics[0]["max_box_coordinate_abs_error"] == pytest.approx(0.1, abs=1e-6)
