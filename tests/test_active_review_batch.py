from __future__ import annotations

import json
import os
import sqlite3
from pathlib import Path

import pytest
from PIL import Image

from mapassist.annotation_server import AnnotationStore
from training.build_minimap_player_active_batch import (
    build,
    database_content_sha256,
    evenly_spaced,
    select_match,
)


def test_evenly_spaced_and_forced_selection_keep_a_small_diverse_batch() -> None:
    rows = [
        {
            "id": index,
            "match_id": "video3-player",
            "at_ms": index * 1000,
            "probability": probability,
            "suggested_box": ([0.1, 0.1, 0.02, 0.02]
                              if probability >= 0.4 else None),
        }
        for index, probability in enumerate(
            [0.01, 0.1, 0.3, 0.5, 0.7, 0.91, 0.95, 0.99], start=1
        )
    ]
    assert [row["id"] for row in evenly_spaced(rows, 3)] == [1, 5, 8]
    selected = select_match(rows, 6, {7})
    assert len(selected) == 6
    assert len({row["id"] for row in selected}) == 6
    assert next(row for row in selected if row["id"] == 7)[
        "selection_reason"
    ] == "diagnostic_hard_case"
    assert {row["selection_reason"] for row in selected} >= {
        "learned_high", "learned_boundary", "learned_low",
        "diagnostic_hard_case",
    }


def _source_queue(root: Path, match_id: str = "video3-player") -> Path:
    source = root / "source"
    image_dir = source / "train" / match_id
    image_dir.mkdir(parents=True)
    samples = []
    for index in range(6):
        relative = f"train/{match_id}/frame-{index}.png"
        Image.new("RGB", (100, 60), (20 + index, 40, 60)).save(source / relative)
        samples.append({
            "at_ms": index * 1000,
            "selection": "green_ring_suggestion",
            "frame": relative,
            "overlay": relative,
            "suggested_boxes": [],
            "suggested_categories": [],
            "directions": [],
            "review_status": "pending",
            "reviewed_boxes": None,
            "reviewed_categories": None,
        })
    manifest = {
        "schema_version": 1,
        "kind": "minimap_player",
        "classes": ["minimap_player"],
        "review_mode": "manual",
        "roi": [0.0, 0.0, 0.5, 0.8],
        "widget_roi": [0.0, 0.0, 0.4, 0.7],
        "matches": [{
            "id": match_id,
            "split": "train",
            "samples": samples,
            "provenance_samples": [
                {
                    "at_ms": sample["at_ms"],
                    "queue_frame": sample["frame"],
                    "queue_overlay": sample["overlay"],
                    "suggestion": {
                        "source": "green_annulus_weak_heuristic",
                    },
                    "suggested_box_count": 0,
                    "review_status": "pending",
                }
                for sample in samples
            ],
        }],
    }
    (source / "review-manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    AnnotationStore(source)
    return source


def _predictions(source: Path) -> Path:
    with sqlite3.connect(source / "annotations.sqlite3") as connection:
        connection.row_factory = sqlite3.Row
        tasks = [dict(row) for row in connection.execute(
            "SELECT id, match_id, at_ms, frame FROM tasks ORDER BY id"
        )]
    rows = []
    scores = [0.05, 0.15, 0.35, 0.6, 0.92, 0.99]
    for task, score in zip(tasks, scores):
        box = [0.1, 0.1, 0.04, 0.06] if score >= 0.4 else None
        rows.append({
            **task,
            "probability": score,
            "threshold": 0.4,
            "candidate_ring_score": score / 2,
            "candidate_radius_px": 10,
            "candidate_center_widget_px": [10, 10],
            "suggested_box": box,
        })
    path = source.parent / "predictions.json"
    path.write_text(json.dumps({
        "schema": "mapassist.minimap_player_learned_suggestions",
        "schema_version": 1,
        "results": rows,
    }), encoding="utf-8")
    return path


def test_build_active_batch_keeps_machine_results_pending(tmp_path: Path) -> None:
    source = _source_queue(tmp_path)
    predictions = _predictions(source)
    output = tmp_path / "focused"
    audit = build(source, predictions, output, per_match=3, forced_ids={6})

    assert audit["selected"] == 3
    assert audit["all_pending"] is True
    store = AnnotationStore(output)
    assert store.stats()["counts"]["pending"] == 3
    assert store.stats()["completed"] == 0
    exported = json.loads((output / "review-manifest.json").read_text())
    samples = exported["matches"][0]["samples"]
    assert all(sample["review_status"] == "pending" for sample in samples)
    assert all(sample["reviewed_boxes"] is None for sample in samples)
    assert any(sample["active_learning_source"]["source_task_id"] == 6
               for sample in samples)
    assert exported["suggestion_provenance"]["method"] == (
        "learned_candidate_ranker_over_green_annulus_proposals"
    )
    assert all(sample["suggestion_metadata"]["source"] ==
               "learned_candidate_ranker" for sample in samples)
    assert all(sample["suggestion_metadata"]["is_ground_truth"] is False
               for sample in samples)
    assert all(
        sample["suggestion_metadata"]["decision"] ==
        ("learned_suggestion" if sample["suggested_boxes"]
         else "empty_below_learned_threshold")
        for sample in samples
    )
    provenance = exported["matches"][0]["provenance_samples"]
    assert len(provenance) == len(samples)
    assert {entry["at_ms"] for entry in provenance} == {
        sample["at_ms"] for sample in samples
    }
    assert all(entry["suggestion"]["source"] == "learned_candidate_ranker"
               for entry in provenance)
    assert all(entry["review_status"] == "pending" for entry in provenance)
    copied = samples[0]["frame"]
    assert (source / copied).read_bytes() == (output / copied).read_bytes()
    assert os.stat(source / copied).st_ino != os.stat(output / copied).st_ino
    audit_document = json.loads((output / "audit.json").read_text())
    content_hash = audit_document["database_content_sha256"]
    AnnotationStore(output)
    assert database_content_sha256(output / "annotations.sqlite3") == content_hash


def test_build_active_batch_rejects_incomplete_or_duplicate_predictions(
    tmp_path: Path,
) -> None:
    source = _source_queue(tmp_path)
    predictions = _predictions(source)
    document = json.loads(predictions.read_text())
    document["results"][-1] = document["results"][0]
    predictions.write_text(json.dumps(document), encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate task ids"):
        build(source, predictions, tmp_path / "focused", 3, set())


def test_build_active_batch_never_replaces_source_queue(tmp_path: Path) -> None:
    source = _source_queue(tmp_path)
    predictions = _predictions(source)
    with pytest.raises(ValueError, match="must differ"):
        build(source, predictions, source, 3, set())


def test_build_active_batch_rejects_sealed_source(tmp_path: Path) -> None:
    source = _source_queue(tmp_path, match_id="video9-player")
    predictions = _predictions(source)
    with pytest.raises(ValueError, match="Sealed video9/video12"):
        build(source, predictions, tmp_path / "focused", 3, set())
