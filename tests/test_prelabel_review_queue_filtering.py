from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

from training.prelabel_review_queue import (
    _filter_suggestions,
    _label_roi,
    _roi,
    build,
)


def _suggestion(box: list[float]) -> dict:
    return {"bbox": box, "confidence": 0.9}


def test_label_roi_filter_uses_box_center_and_counts_removed_candidates() -> None:
    candidates = [
        _suggestion([0.20, 0.20, 0.04, 0.04]),  # Center inside.
        _suggestion([0.10, 0.20, 0.04, 0.04]),  # Center outside.
    ]

    kept, counts = _filter_suggestions(
        candidates, [0.0, 0.0, 1.0, 1.0], 1000, 1000,
        label_roi=[0.19, 0.19, 0.1, 0.1], filter_label_roi=True,
    )

    assert kept == [candidates[0]]
    assert counts == {
        "candidate_count": 2,
        "removed_by_label_roi": 1,
        "removed_by_safe_roi_edge": 0,
        "kept": 1,
    }


def test_safe_roi_filter_uses_one_pixel_and_keeps_physical_frame_contacts() -> None:
    expandable_roi = [0.1, 0.1, 0.4, 0.4]
    candidates = [
        _suggestion([0.101, 0.15, 0.05, 0.05]),  # One pixel from the crop edge.
        _suggestion([0.2, 0.2, 0.05, 0.05]),
    ]
    kept, counts = _filter_suggestions(
        candidates, expandable_roi, 1000, 1000,
        remove_safe_roi_edge_contacts=True,
    )

    assert kept == [candidates[1]]
    assert counts["removed_by_safe_roi_edge"] == 1

    # The left side is the physical frame edge, so it cannot be expanded.
    frame_edge = _suggestion([0.0, 0.15, 0.05, 0.05])
    kept, counts = _filter_suggestions(
        [frame_edge], [0.0, 0.1, 0.5, 0.5], 1000, 1000,
        remove_safe_roi_edge_contacts=True,
    )

    assert kept == [frame_edge]
    assert counts["removed_by_safe_roi_edge"] == 0


def test_label_roi_uses_match_override_then_manifest_fallback() -> None:
    document = {"label_roi": [0.1, 0.1, 0.5, 0.5]}

    assert _label_roi(document, {"label_roi": [0.2, 0.2, 0.2, 0.2]}) == (
        [0.2, 0.2, 0.2, 0.2], "match.label_roi",
    )
    assert _label_roi(document, {}) == (
        [0.1, 0.1, 0.5, 0.5], "manifest.label_roi",
    )


def test_label_roi_filter_requires_a_manifest_label_roi() -> None:
    with pytest.raises(ValueError, match="requires label_roi"):
        _filter_suggestions(
            [_suggestion([0.2, 0.2, 0.05, 0.05])],
            [0.0, 0.0, 1.0, 1.0], 1000, 1000,
            filter_label_roi=True,
        )


@pytest.mark.parametrize("value", [
    [True, 0.1, 0.2, 0.2],
    [0.1, 0.1, float("nan"), 0.2],
    [0.1, 0.1, float("inf"), 0.2],
])
def test_roi_rejects_boolean_and_nonfinite_values(value) -> None:
    with pytest.raises(ValueError, match="roi"):
        _roi(value)


def test_build_rejects_sealed_match_before_loading_model_or_frame(
    tmp_path: Path, monkeypatch,
) -> None:
    import onnxruntime

    def unexpected_model_load(*_args, **_kwargs):
        pytest.fail("sealed match must be rejected before model loading")

    monkeypatch.setattr(onnxruntime, "InferenceSession", unexpected_model_load)
    manifest = tmp_path / "sealed.json"
    manifest.write_text(json.dumps({
        "roi": [0.1, 0.1, 0.5, 0.5],
        "matches": [{"id": "Video12_hidden", "samples": []}],
    }), encoding="utf-8")

    with pytest.raises(ValueError, match="sealed match"):
        build(manifest, tmp_path / "model.onnx", tmp_path / "out")


def test_build_records_filter_options_and_removed_kept_counts(
    tmp_path: Path, monkeypatch,
) -> None:
    import cv2
    import onnxruntime
    import training.prelabel_review_queue as prelabel

    # At input size 64, rows 0 and 1 produce adjacent 8x8 boxes in the
    # 40x40 crop. The first center falls outside label_roi; the second inside.
    raw = np.zeros((1, 84, 6), dtype=np.float32)
    raw[0, 0, 4:6] = 1.0
    raw[0, 1, 4:6] = 1.0

    class FakeSession:
        def get_inputs(self):
            return [SimpleNamespace(name="images")]

        def run(self, _outputs, _inputs):
            return [raw]

    monkeypatch.setattr(onnxruntime, "InferenceSession", lambda *_a, **_k: FakeSession())
    monkeypatch.setattr(cv2, "imread", lambda _path: np.zeros((100, 100, 3), np.uint8))
    monkeypatch.setattr(prelabel, "_page", lambda *_args, **_kwargs: None)

    manifest = tmp_path / "review.json"
    manifest.write_text(json.dumps({
        "roi": [0.2, 0.2, 0.4, 0.4],
        "label_roi": [0.24, 0.2, 0.2, 0.2],
        "matches": [{
            "id": "match-1",
            "roi": None,
            "samples": [{"at_ms": 1000, "frame": "frame.png"}],
        }],
    }), encoding="utf-8")

    build(manifest, tmp_path / "model.onnx", tmp_path / "default-out",
          input_size=64, confidence=0.03)
    default_result = json.loads(
        (tmp_path / "default-out" / "prelabels.json").read_text()
    )
    default_provenance = default_result["filtering_provenance"]
    assert len(default_result["matches"][0]["samples"][0]["suggestions"]) == 2
    assert default_provenance["counts"] == {
        "candidate_count": 2,
        "removed_by_label_roi": 0,
        "removed_by_safe_roi_edge": 0,
        "kept": 2,
    }

    summary = build(manifest, tmp_path / "model.onnx", tmp_path / "out",
                    input_size=64, confidence=0.03, filter_label_roi=True)

    result = json.loads((tmp_path / "out" / "prelabels.json").read_text())
    sample = result["matches"][0]["samples"][0]
    assert len(sample["suggestions"]) == 1
    provenance = result["filtering_provenance"]
    assert provenance["filters"]["label_roi_center"]["enabled"] is True
    assert provenance["counts"] == {
        "candidate_count": 2,
        "removed_by_label_roi": 1,
        "removed_by_safe_roi_edge": 0,
        "kept": 1,
    }
    assert provenance["per_match"][0]["label_roi_source"] == "manifest.label_roi"
    assert provenance["per_match"][0]["label_roi"] == [0.24, 0.2, 0.2, 0.2]
    assert provenance["per_match"][0]["safe_roi"] == [0.2, 0.2, 0.4, 0.4]
    assert summary["filtering_counts"] == provenance["counts"]
