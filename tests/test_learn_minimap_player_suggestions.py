from __future__ import annotations

import numpy as np
import pytest

from training.learn_minimap_player_suggestions import (
    DEFAULT_PENDING_BATCH_SIZE,
    _iter_batches,
    _prediction_for_record,
    _print_pending_progress,
    _score_pending_batch,
    _score_records,
    choose_threshold,
    final_boxes,
    frame_metrics,
    manifest_match_index,
)


def _task(status: str, boxes: object) -> dict:
    return {"id": 7, "review_status": status, "reviewed_boxes": boxes}


def test_final_boxes_accepts_one_corrected_box_and_negative_without_boxes() -> None:
    assert final_boxes(_task("corrected", [[0.1, 0.2, 0.03, 0.04]])) == [
        [0.1, 0.2, 0.03, 0.04]
    ]
    assert final_boxes(_task("negative", None)) == []


def test_final_boxes_rejects_more_than_one_box_per_frame() -> None:
    with pytest.raises(ValueError, match="at most one box"):
        final_boxes(_task("corrected", [
            [0.1, 0.2, 0.03, 0.04],
            [0.5, 0.6, 0.03, 0.04],
        ]))


def test_threshold_prefers_recall_after_precision_gate() -> None:
    selected = choose_threshold([
        {"threshold": 0.20, "precision": 0.96, "recall": 0.80, "f1": 0.87},
        {"threshold": 0.50, "precision": 0.99, "recall": 0.70, "f1": 0.82},
        {"threshold": 0.80, "precision": 0.90, "recall": 0.95, "f1": 0.92},
    ])
    assert selected["threshold"] == 0.20
    assert selected["selection"] == "minimum_precision_then_recall"


def test_frame_metrics_is_one_prediction_per_frame() -> None:
    scored = [
        {
            "ground_truth": [(10.0, 10.0, [0.1, 0.1, 0.02, 0.02])],
            "scored_items": [
                {"x": 11, "y": 10, "probability": 0.9},
                {"x": 10, "y": 10, "probability": 0.8},
            ],
        },
        {
            "ground_truth": [],
            "scored_items": [{"x": 30, "y": 30, "probability": 0.95}],
        },
    ]
    metrics = frame_metrics(scored, 0.5)
    assert metrics["tp"] == 1
    assert metrics["fp"] == 1
    assert metrics["fn"] == 0
    assert metrics["tn"] == 0


def test_manifest_index_rejects_sealed_source_before_work() -> None:
    with pytest.raises(ValueError, match="sealed match"):
        manifest_match_index({
            "kind": "minimap_player",
            "widget_roi": [0.0, 0.0, 1.0, 1.0],
            "matches": [{"id": "video12-hidden", "split": "val", "samples": []}],
        })


def test_pending_batches_are_bounded_and_keep_source_order() -> None:
    rows = [{"id": index} for index in range(17)]
    batches = list(_iter_batches(rows, DEFAULT_PENDING_BATCH_SIZE))
    assert [len(batch) for batch in batches] == [8, 8, 1]
    assert [row["id"] for batch in batches for row in batch] == list(range(17))
    with pytest.raises(ValueError, match="pending_batch_size must be positive"):
        list(_iter_batches(rows, 0))


def _candidate_record(task_id: int, logits: list[float]) -> dict:
    items = []
    for index, logit in enumerate(logits):
        items.append({
            "patch": np.full((6, 64, 64), logit, dtype=np.float32),
            "x": 100 + index * 100,
            "y": 200,
            "radius": 7,
            "ring_score": 0.2 + index / 10,
        })
    return {
        "task_id": task_id,
        "match_id": "video1-hd-player",
        "at_ms": task_id * 1000,
        "frame": f"train/frame-{task_id}.png",
        "split": "train",
        "review_status": "pending",
        "ground_truth": [],
        "items": items,
        "rect": (0, 0, 1000, 1000),
        "full_size": (1000, 1000),
        "nearest_truth_distances": [],
        "candidate_count": len(items),
    }


def test_pending_batch_scoring_matches_per_record_semantics() -> None:
    torch = pytest.importorskip("torch")

    class TrackingModel(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.max_batch = 0
            self.calls = 0

        def forward(self, inputs):
            self.calls += 1
            self.max_batch = max(self.max_batch, int(inputs.shape[0]))
            return inputs[:, 0, 0, 0]

    records = [_candidate_record(20, [2.0, -2.0]),
               _candidate_record(10, [-2.0, 1.0])]
    expected_model = TrackingModel()
    expected = [
        _prediction_for_record(scored, 0.5)
        for scored in _score_records(expected_model, records, "cpu", 2)
    ]
    actual_model = TrackingModel()
    actual = _score_pending_batch(
        actual_model, records, "cpu", batch_size=2, threshold=0.5,
    )

    assert actual == expected
    assert [item["id"] for item in actual] == [20, 10]
    assert actual_model.calls == 2
    assert actual_model.max_batch == 2


def test_pending_progress_stays_on_stderr(capsys) -> None:
    _print_pending_progress(0, 8, 0.0)
    _print_pending_progress(8, 8, 0.0)
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "pending suggestions: 0/8" in captured.err
    assert "pending suggestions: 8/8" in captured.err
