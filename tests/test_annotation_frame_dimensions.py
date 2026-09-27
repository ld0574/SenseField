from __future__ import annotations

import json
from pathlib import Path

import pytest
from PIL import Image

from mapassist.annotation_server import AnnotationStore


def _write_oriented_review_queue(dataset: Path, frame_size: tuple[int, int]) -> None:
    media = dataset / "test/match-01"
    media.mkdir(parents=True)
    for name in ("frame.png", "overlay.jpg"):
        Image.new("RGB", frame_size).save(media / name)

    manifest = {
        "schema_version": 1,
        "kind": "minimap_enemy",
        "review_mode": "blind",
        "roi": [0.03324915825, 0.0, 0.16919191919, 0.34351851852],
        "matches": [{
            "id": "match-01",
            "split": "test",
            "orientation": {
                "source_coded_size": [1080, 2376],
                "display_size": [2376, 1080],
                "display_rotation_degrees": 90,
                "queue_frames_must_be_display_oriented": True,
            },
            "samples": [{
                "at_ms": 1000,
                "selection": "systematic_blind",
                "suggested_boxes": [],
                "directions": [],
                "review_status": "pending",
                "reviewed_boxes": None,
                "frame": "test/match-01/frame.png",
                "overlay": "test/match-01/overlay.jpg",
            }],
        }],
    }
    (dataset / "review-manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )


def test_annotation_store_rejects_cropped_frame_with_full_display_roi(
    tmp_path: Path,
) -> None:
    _write_oriented_review_queue(tmp_path, (348, 344))

    with pytest.raises(ValueError, match=r"display_size \[2376, 1080\]"):
        AnnotationStore(tmp_path)


def test_annotation_store_accepts_declared_full_display_frame(
    tmp_path: Path,
) -> None:
    _write_oriented_review_queue(tmp_path, (2376, 1080))

    assert AnnotationStore(tmp_path).stats()["counts"]["pending"] == 1


def test_manual_review_keeps_seeded_legacy_boxes_as_editable_suggestions(
    tmp_path: Path,
) -> None:
    _write_oriented_review_queue(tmp_path, (2376, 1080))
    manifest_path = tmp_path / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["review_mode"] = "manual"
    manifest["label_assistance"] = {
        "source": "prior_human_review",
        "notice": "Prior boxes are editable suggestions only.",
    }
    suggestion = [0.1683502, 0.1, 0.0185185, 0.04]
    manifest["matches"][0]["samples"][0]["suggested_boxes"] = [suggestion]
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    store = AnnotationStore(tmp_path, lease_seconds=60)
    bootstrap = store.bootstrap()
    assert bootstrap["review_mode"] == "manual"
    assert bootstrap["suggestions_available"] is True
    assert bootstrap["label_assistance"] == manifest["label_assistance"]
    task = store.claim_next("Reviewer")
    assert task is not None
    assert task["review_status"] == "pending"
    assert task["reviewed_boxes"] is None
    assert task["suggested_boxes"] == [suggestion]

    edited_box = [0.1683502, 0.1, 0.021, 0.04]
    saved = store.save(task["id"], "Reviewer", task["version"], "corrected",
                       [edited_box])
    assert saved["review_status"] == "corrected"
    assert saved["reviewed_boxes"] == [edited_box]
    assert saved["suggested_boxes"] == [suggestion]


def test_widget_roi_is_validated_and_exposed_to_annotation_clients(
    tmp_path: Path,
) -> None:
    _write_oriented_review_queue(tmp_path, (2376, 1080))
    manifest_path = tmp_path / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    widget_roi = [106 / 2376, 0.0, 348 / 2376, 344 / 1080]
    manifest["widget_roi"] = widget_roi
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    store = AnnotationStore(tmp_path)

    assert store.bootstrap()["widget_roi"] == widget_roi
    assert store.list_tasks()[0]["widget_roi"] == widget_roi


def test_widget_roi_must_be_normalized(tmp_path: Path) -> None:
    _write_oriented_review_queue(tmp_path, (2376, 1080))
    manifest_path = tmp_path / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["widget_roi"] = [0.9, 0.0, 0.2, 0.3]
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ValueError, match="widget_roi is outside"):
        AnnotationStore(tmp_path)
