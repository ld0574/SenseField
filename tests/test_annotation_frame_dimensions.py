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
