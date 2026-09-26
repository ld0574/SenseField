from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from training.attach_review_suggestions import attach
from training.prelabel_review_queue import _normalized_box


def _write_fixture(tmp_path: Path) -> tuple[Path, Path, Path]:
    model = tmp_path / "model.onnx"
    model.write_bytes(b"model fixture")
    manifest = tmp_path / "review-manifest.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "review_mode": "manual",
        "roi": [0.0, 0.0, 0.25, 0.5],
        "sampling": {"predictions_used_for_selection": False},
        "matches": [{
            "id": "match-1", "split": "train", "samples": [{
                "at_ms": 1000, "frame": "train/frame.png",
                "suggested_boxes": [], "review_status": "pending",
            }],
        }],
    }), encoding="utf-8")
    prelabels = tmp_path / "prelabels.json"
    prelabels.write_text(json.dumps({
        "schema_version": 1,
        "model": str(model), "input_size": 320,
        "confidence": 0.1, "nms_threshold": 0.5,
        "matches": [{"id": "match-1", "samples": [{
            "at_ms": 1000, "frame": "train/frame.png",
            "suggestions": [{
                "bbox": [0.05, 0.1, 0.02, 0.04], "confidence": 0.7,
            }],
        }]}],
    }), encoding="utf-8")
    return manifest, prelabels, model


def test_attach_suggestions_preserves_manual_review_contract(tmp_path: Path) -> None:
    manifest, prelabels, model = _write_fixture(tmp_path)

    result = attach(manifest, prelabels)

    assert result["images"] == result["images_with_suggestions"] == 1
    assert result["suggestions"] == 1
    data = json.loads(manifest.read_text())
    sample = data["matches"][0]["samples"][0]
    assert sample["suggested_boxes"] == [[0.05, 0.1, 0.02, 0.04]]
    assert sample["suggestion_confidences"] == [0.7]
    assert sample["review_status"] == "pending"
    assert data["sampling"]["predictions_used_for_selection"] is False
    provenance = data["suggestion_provenance"]
    assert provenance["manual_confirmation_required"] is True
    assert provenance["predictions_used_for_selection"] is False
    assert provenance["model_sha256"] == hashlib.sha256(model.read_bytes()).hexdigest()


def test_attach_suggestions_requires_exact_prediction_coverage(tmp_path: Path) -> None:
    manifest, prelabels, _ = _write_fixture(tmp_path)
    predictions = json.loads(prelabels.read_text())
    predictions["matches"][0]["samples"] = []
    prelabels.write_text(json.dumps(predictions), encoding="utf-8")

    with pytest.raises(ValueError, match="missing 1 review samples"):
        attach(manifest, prelabels)


def test_attach_suggestions_rejects_boxes_outside_roi(tmp_path: Path) -> None:
    manifest, prelabels, _ = _write_fixture(tmp_path)
    predictions = json.loads(prelabels.read_text())
    predictions["matches"][0]["samples"][0]["suggestions"][0]["bbox"] = [0.24, 0.1, 0.02, 0.04]
    prelabels.write_text(json.dumps(predictions), encoding="utf-8")

    with pytest.raises(ValueError, match="outside the roi"):
        attach(manifest, prelabels)


def test_prelabel_box_clips_pixel_rounding_to_declared_roi() -> None:
    roi = [0.0375, 0.0, 0.1444444444, 0.3333333333]

    box = _normalized_box(
        roi, crop_x=101, crop_y=0, full_width=2712, full_height=1220,
        x0=350.0, y0=100.0, x1=393.0, y1=407.0,
    )

    assert box is not None
    assert box[0] >= roi[0]
    assert box[1] >= roi[1]
    assert box[0] + box[2] <= roi[0] + roi[2]
    assert box[1] + box[3] <= roi[1] + roi[3]
