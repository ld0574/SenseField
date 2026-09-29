from __future__ import annotations

import base64
import copy
import ctypes
import hashlib
import json
import shutil
import subprocess
from types import SimpleNamespace
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.evaluate import evaluate, read_labels, read_predictions
from mapassist.bundle_profile import bundle
from mapassist.annotation_server import (
    AnnotationHTTPServer,
    AnnotationStore,
    ConflictError,
    _dataset_specs,
)
from mapassist import blind_review_dataset as blind_review_module
from mapassist import uniform_review_dataset as uniform_review_module
from mapassist.apply_label_shards import apply as apply_label_shards
from mapassist.blind_review_dataset import _sample_indices
from mapassist.calibrate_minimap_anchor import calibrate as calibrate_minimap_anchor
from mapassist.combine_detection_manifests import combine as combine_detection_manifests
from mapassist.detection_dataset import export as export_detection_dataset
from mapassist.detection_evaluate import evaluate_review as evaluate_detection_review
from mapassist.detection_evaluate import _direction_reference_roi, _match_boxes
from mapassist import detection_replay_evaluate
from mapassist.extract_frame import extract
from mapassist.finalize_review import finalize as finalize_review
from mapassist.measure_latency import measure
from mapassist.merge_detection_manifests import merge as merge_detection_manifests
from mapassist.minimap_layout_review_dataset import build as build_minimap_layout_review
from mapassist.native import (
    Cue,
    EngineConfig,
    MA_MINIMAP_ENEMY,
    MA_MINIMAP_PLAYER,
    MA_PLAYER_RELEVANCE_MAX_AGE_MS,
    MA_TRACK_STATE_LOST,
    MA_TRACK_STATE_VISIBLE,
    MA_VISION_EVENT_APPEAR,
    MA_VISION_EVENT_DISAPPEAR,
    MA_VISION_EVENT_NONE,
    MinimapMarker,
    Observation,
    Pipeline,
    Rect,
    TrackedEntity,
    load_library,
)
from mapassist.replay import run
from mapassist.roi_safety import inspect_box_roi
from mapassist.review_dataset import build as build_review_dataset
from mapassist.synthetic import create
from mapassist.uniform_review_dataset import exclude_timestamp_windows, sample_timestamps


@pytest.fixture
def annotation_dataset(tmp_path: Path) -> Path:
    dataset = tmp_path / "review"
    image_dir = dataset / "train/match-01"
    image_dir.mkdir(parents=True)
    for index in range(3):
        Image.new("RGB", (320, 180), (20 + index, 30, 40)).save(
            image_dir / f"frame-{index}.png"
        )
        Image.new("RGB", (320, 180), (40, 30, 20 + index)).save(
            image_dir / f"frame-{index}-overlay.jpg"
        )
    manifest = {
        "schema_version": 1,
        "kind": "minimap_enemy",
        "roi": [0.0, 0.0, 0.25, 0.4],
        "matches": [{
            "id": "match-01",
            "split": "train",
            "video": "private.mp4",
            "samples": [{
                "at_ms": index * 1000,
                "selection": "cue" if index < 2 else "background",
                "suggested_boxes": [[0.05, 0.1, 0.04, 0.06]] if index < 2 else [],
                "directions": ["left"] if index < 2 else [],
                "review_status": "pending",
                "reviewed_boxes": None,
                "frame": f"train/match-01/frame-{index}.png",
                "overlay": f"train/match-01/frame-{index}-overlay.jpg",
            } for index in range(3)],
        }],
    }
    (dataset / "review-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return dataset


def test_annotation_server_accepts_multiple_named_datasets() -> None:
    assert _dataset_specs(None) == [
        ("video1-5", Path("data/private/minimap-review-v3")),
    ]
    assert _dataset_specs(["dev=/tmp/dev-review", "video6=/tmp/blind-review"]) == [
        ("dev", Path("/tmp/dev-review")),
        ("video6", Path("/tmp/blind-review")),
    ]
    with pytest.raises(ValueError, match="duplicate dataset name"):
        _dataset_specs(["dev=/tmp/a", "dev=/tmp/b"])


def test_annotation_server_bootstrap_can_recover_stale_dataset(
    annotation_dataset: Path,
) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    server = AnnotationHTTPServer(
        ("127.0.0.1", 0), {"current": store}, annotation_dataset
    )
    try:
        assert server.resolve_store("old-name", fallback_unknown=True) is store
        with pytest.raises(ValueError, match="unknown dataset"):
            server.resolve_store("old-name")
    finally:
        server.server_close()


def test_annotation_store_coordinates_collaborators_and_exports(
    annotation_dataset: Path,
) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    alice = store.claim_next("Alice")
    bob = store.claim_next("Bob")
    assert alice is not None and bob is not None
    assert alice["id"] != bob["id"]
    with pytest.raises(ConflictError, match="Alice"):
        store.claim(alice["id"], "Bob")

    saved = store.save(alice["id"], "Alice", alice["version"], "accepted")
    assert saved["review_status"] == "accepted"
    assert saved["reviewed_by"] == "Alice"
    assert saved["reviewed_at"].endswith("+00:00")
    assert saved["version"] == alice["version"] + 1
    exported = json.loads((annotation_dataset / "review-manifest.json").read_text())
    sample = exported["matches"][0]["samples"][0]
    assert sample["review_status"] == "accepted"
    assert sample["reviewed_by"] == "Alice"
    assert sample["reviewed_at"] == saved["reviewed_at"]

    with pytest.raises(ConflictError, match="changed"):
        store.save(alice["id"], "Alice", alice["version"], "negative")
    stats = store.stats()
    assert stats["total"] == 3
    assert stats["completed"] == 1
    assert stats["counts"] == {
        "pending": 2, "accepted": 1, "corrected": 0, "negative": 0,
        "skip": 0, "excluded": 0,
    }
    assert stats["contributors"] == [{"name": "Alice", "count": 1}]


def test_annotation_store_releases_unfinished_task(annotation_dataset: Path) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Alice")
    assert task is not None
    with pytest.raises(ConflictError, match="Alice"):
        store.release(task["id"], "Bob")
    released = store.release(task["id"], "Alice")
    assert released["lease_owner"] is None
    assert released["review_status"] == "pending"
    reclaimed = store.claim(task["id"], "Bob")
    assert reclaimed["lease_owner"] == "Bob"


def test_annotation_store_validates_edits_and_media(annotation_dataset: Path) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None
    with pytest.raises(ValueError, match="at least one box"):
        store.save(task["id"], "Reviewer", task["version"], "corrected", [])
    with pytest.raises(ValueError, match="inside the safe roi"):
        store.save(task["id"], "Reviewer", task["version"], "corrected",
                   [[0.8, 0.1, 0.1, 0.1]])
    with pytest.raises(ValueError, match="positive integer"):
        store.save(task["id"], "Reviewer", True, "accepted")
    with pytest.raises(ValueError, match="not part"):
        store.media_path("../review/review-manifest.json")
    with pytest.raises(ValueError, match="not part"):
        store.media_path("review-manifest.json")
    assert store.media_path(task["frame"]).is_file()

    corrected = store.save(task["id"], "Reviewer", task["version"], "corrected",
                           [[0.06, 0.12, 0.05, 0.07]])
    assert corrected["reviewed_boxes"] == [[0.06, 0.12, 0.05, 0.07]]
    with pytest.raises(ConflictError, match="claim it again"):
        store.save(corrected["id"], "Reviewer", corrected["version"], "skip")


def test_annotation_store_uses_match_specific_roi(annotation_dataset: Path) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["matches"][0]["roi"] = [0.2, 0.0, 0.3, 0.5]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Layout reviewer")
    assert task is not None
    assert task["roi"] == [0.2, 0.0, 0.3, 0.5]
    with pytest.raises(ValueError, match="inside the safe roi"):
        store.save(task["id"], "Layout reviewer", task["version"], "corrected",
                   [[0.06, 0.12, 0.05, 0.07]])
    saved = store.save(task["id"], "Layout reviewer", task["version"], "corrected",
                       [[0.22, 0.12, 0.05, 0.07]])
    assert saved["reviewed_boxes"] == [[0.22, 0.12, 0.05, 0.07]]


def test_annotation_store_blocks_ground_truth_touching_expandable_crop_edge(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["matches"][0]["samples"][0]["suggested_boxes"] = [[0.20, 0.1, 0.05, 0.1]]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    with pytest.raises(ValueError, match="expandable safe roi boundary"):
        store.save(task["id"], "Reviewer", task["version"], "corrected",
                   [[0.20, 0.1, 0.05, 0.1]])
    with pytest.raises(ValueError, match="expandable safe roi boundary"):
        store.save(task["id"], "Reviewer", task["version"], "accepted")

    # Negative review rejects a suggestion; it does not turn that suggestion
    # into a ground-truth annotation and must remain savable.
    saved = store.save(task["id"], "Reviewer", task["version"], "negative")
    assert saved["review_status"] == "negative"


def test_minimap_region_annotations_may_touch_full_screen_edge(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["kind"] = "minimap_region"
    data["label_roi"] = [0.5, 0.5, 0.2, 0.2]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Layout reviewer")
    assert task is not None

    saved = store.save(task["id"], "Layout reviewer", task["version"], "corrected",
                       [[0.0, 0.05, 0.2, 0.3]])

    assert saved["reviewed_boxes"] == [[0.0, 0.05, 0.2, 0.3]]


def test_annotation_store_allows_target_touching_physical_frame_edge(
    annotation_dataset: Path,
) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    saved = store.save(task["id"], "Reviewer", task["version"], "corrected",
                       [[0.05, 0.0, 0.05, 0.1]])

    assert saved["reviewed_boxes"] == [[0.05, 0.0, 0.05, 0.1]]


def test_annotation_store_uses_rounded_crop_bounds_at_physical_frame_edge(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["matches"][0]["roi"] = [0.0005, 0.0, 0.2495, 0.4]
    data["matches"][0]["samples"][0]["suggested_boxes"] = [[0.0, 0.1, 0.04, 0.06]]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    saved = store.save(task["id"], "Reviewer", task["version"], "corrected",
                       [[0.0, 0.1, 0.04, 0.06]])

    assert saved["reviewed_boxes"] == [[0.0, 0.1, 0.04, 0.06]]


def test_corrected_centers_use_label_roi_even_outside_widget(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["widget_roi"] = [0.05, 0.05, 0.1, 0.2]
    data["label_roi"] = [0.08, 0.05, 0.08, 0.25]
    data["matches"][0]["label_roi"] = [0.02, 0.02, 0.2, 0.3]
    manifest.write_text(json.dumps(data), encoding="utf-8")

    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    bootstrap = store.bootstrap()
    task = store.claim_next("Reviewer")
    assert task is not None
    assert bootstrap["label_roi"] == data["label_roi"]
    assert bootstrap["matches"][0]["label_roi"] == data["matches"][0]["label_roi"]
    assert task["label_roi"] == data["matches"][0]["label_roi"]
    assert task["widget_roi"] == data["widget_roi"]

    box = [0.18, 0.1, 0.04, 0.06]
    assert box[0] + box[2] / 2 > (
        data["widget_roi"][0] + data["widget_roi"][2]
    )
    saved = store.save(task["id"], "Reviewer", task["version"], "corrected", [box])
    assert saved["reviewed_boxes"] == [box]


def test_corrected_center_outside_label_roi_is_rejected(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["widget_roi"] = [0.05, 0.05, 0.1, 0.2]
    data["label_roi"] = [0.05, 0.05, 0.1, 0.2]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    with pytest.raises(ValueError, match="centers must stay inside label_roi"):
        store.save(task["id"], "Reviewer", task["version"], "corrected",
                   [[0.18, 0.1, 0.04, 0.06]])


def test_corrected_box_crossing_safe_roi_is_rejected(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["label_roi"] = [0.0, 0.0, 0.4, 0.4]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    with pytest.raises(ValueError, match="inside the safe roi"):
        store.save(task["id"], "Reviewer", task["version"], "corrected",
                   [[0.24, 0.1, 0.03, 0.06]])


def test_legacy_widget_roi_remains_the_center_boundary(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    widget_roi = [0.05, 0.05, 0.1, 0.2]
    data["widget_roi"] = widget_roi
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    assert store.bootstrap()["label_roi"] == widget_roi
    task = store.claim_next("Reviewer")
    assert task is not None

    with pytest.raises(ValueError, match="centers must stay inside label_roi"):
        store.save(task["id"], "Reviewer", task["version"], "corrected",
                   [[0.18, 0.1, 0.04, 0.06]])


def test_accept_rejects_suggested_box_center_outside_label_roi(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["label_roi"] = [0.1, 0.05, 0.1, 0.2]
    data["matches"][0]["samples"][0]["suggested_boxes"] = [
        [0.05, 0.1, 0.04, 0.06],
    ]
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    with pytest.raises(ValueError, match="target box centers must stay inside label_roi"):
        store.save(task["id"], "Reviewer", task["version"], "accepted")


@pytest.mark.parametrize("bad_value", [float("nan"), float("inf"), float("-inf")])
def test_minimap_region_rejects_nonfinite_box_coordinates(
    annotation_dataset: Path, bad_value: float,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["kind"] = "minimap_region"
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Reviewer")
    assert task is not None

    with pytest.raises(ValueError, match="box values must be finite"):
        store.save(task["id"], "Reviewer", task["version"], "corrected",
                   [[bad_value, 0.1, 0.05, 0.1]])


def test_roi_safety_distinguishes_physical_edge_from_expandable_crop_edge() -> None:
    roi = [0.0, 0.0, 0.25, 0.4]

    ordinary = inspect_box_roi([0.05, 0.1, 0.05, 0.1], roi, 320, 180)
    assert ordinary["outside"] == []
    assert ordinary["crop_touches"] == []
    assert ordinary["frame_touches"] == []

    physical_left = inspect_box_roi([0.0, 0.1, 0.05, 0.1], roi, 320, 180)
    assert physical_left["touches"] == ["left"]
    assert physical_left["crop_touches"] == []
    assert physical_left["frame_touches"] == ["left"]

    crop_right = inspect_box_roi([0.20, 0.1, 0.05, 0.1], roi, 320, 180)
    assert crop_right["crop_touches"] == ["right"]
    clipped = inspect_box_roi([0.24, 0.1, 0.02, 0.1], roi, 320, 180)
    assert clipped["outside"] == ["right"]


def test_annotation_store_can_reopen_completed_task(annotation_dataset: Path) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Alice")
    assert task is not None
    saved = store.save(task["id"], "Alice", task["version"], "negative")
    reopened = store.claim(saved["id"], "Bob")
    revised = store.save(reopened["id"], "Bob", reopened["version"], "accepted")
    assert revised["review_status"] == "accepted"
    assert revised["reviewed_by"] == "Bob"


def test_annotation_store_excludes_non_gameplay_frame(annotation_dataset: Path) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    task = store.claim_next("Context reviewer")
    assert task is not None
    saved = store.save(task["id"], "Context reviewer", task["version"], "excluded")
    assert saved["review_status"] == "excluded"
    assert saved["reviewed_boxes"] is None
    exported = json.loads((annotation_dataset / "review-manifest.json").read_text())
    assert exported["matches"][0]["samples"][0]["review_status"] == "excluded"
    assert store.stats()["counts"]["excluded"] == 1


@pytest.mark.parametrize("review_mode", ["blind", "manual"])
def test_manual_review_rejects_accepting_empty_suggestions(
    annotation_dataset: Path, review_mode: str,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["review_mode"] = review_mode
    manifest.write_text(json.dumps(data), encoding="utf-8")
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    assert store.bootstrap()["review_mode"] == review_mode
    task = store.claim_next("Blind reviewer")
    assert task is not None
    with pytest.raises(ValueError, match="human confirmation"):
        store.save(task["id"], "Blind reviewer", task["version"], "accepted")


def test_annotation_bootstrap_reports_seeded_suggestions(annotation_dataset: Path) -> None:
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    assert store.bootstrap()["suggestions_available"] is True


def test_blind_review_sampling_is_uniform_and_prediction_independent() -> None:
    assert _sample_indices(120, 6) == [10, 30, 50, 70, 90, 110]
    assert _sample_indices(5, 5) == [0, 1, 2, 3, 4]
    with pytest.raises(ValueError, match="between 1 and frame_count"):
        _sample_indices(5, 6)


def test_uniform_review_sampling_spans_multiple_gameplay_intervals() -> None:
    timestamps = sample_timestamps([(1000, 2000), (5000, 7000)], 6)

    assert timestamps == [1250, 1750, 5250, 5750, 6250, 6750]
    assert all(1000 <= value < 2000 or 5000 <= value < 7000
               for value in timestamps)


def test_uniform_review_can_exclude_windows_around_prior_samples() -> None:
    remaining = exclude_timestamp_windows(
        [(1000, 3000), (5000, 7000)], [1500, 6000], gap_ms=250
    )

    assert remaining == [(1000, 1250), (1751, 3000), (5000, 5750), (6251, 7000)]
    timestamps = sample_timestamps(remaining, 8)
    assert len(timestamps) == len(set(timestamps)) == 8
    assert all(abs(value - 1500) > 250 and abs(value - 6000) > 250
               for value in timestamps)
    with pytest.raises(ValueError, match="nonnegative"):
        exclude_timestamp_windows([(0, 100)], [50], gap_ms=-1)


def test_apply_label_shards_updates_manifest_and_annotation_database(
    annotation_dataset: Path,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["review_mode"] = "manual"
    manifest.write_text(json.dumps(data), encoding="utf-8")
    AnnotationStore(annotation_dataset, lease_seconds=60)
    samples = data["matches"][0]["samples"]
    shard = annotation_dataset / "labels.json"
    shard.write_text(json.dumps({
        "schema_version": 1,
        "annotator": "test-agent",
        "matches": [{
            "id": "match-01",
            "samples": [
                {"at_ms": samples[0]["at_ms"], "review_status": "corrected",
                 "reviewed_boxes": [[0.1, 0.1, 0.1, 0.1]]},
                {"at_ms": samples[1]["at_ms"], "review_status": "negative",
                 "reviewed_boxes": None},
                {"at_ms": samples[2]["at_ms"], "review_status": "excluded",
                 "reviewed_boxes": None},
            ],
        }],
    }), encoding="utf-8")

    result = apply_label_shards(manifest, [shard])

    assert result["samples"] == 3
    assert result["boxes"] == 1
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    assert store.stats()["completed"] == 3
    statuses = [sample["review_status"]
                for sample in json.loads(manifest.read_text())["matches"][0]["samples"]]
    assert statuses == ["corrected", "negative", "excluded"]


def test_apply_label_shards_rolls_back_database_when_manifest_replace_fails(
    annotation_dataset: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    manifest = annotation_dataset / "review-manifest.json"
    data = json.loads(manifest.read_text())
    data["review_mode"] = "manual"
    manifest.write_text(json.dumps(data), encoding="utf-8")
    original = manifest.read_text()
    AnnotationStore(annotation_dataset, lease_seconds=60)
    samples = data["matches"][0]["samples"]
    shard = annotation_dataset / "labels.json"
    shard.write_text(json.dumps({
        "schema_version": 1,
        "annotator": "test-agent",
        "matches": [{
            "id": "match-01",
            "samples": [
                {"at_ms": sample["at_ms"], "review_status": "negative",
                 "reviewed_boxes": None}
                for sample in samples
            ],
        }],
    }), encoding="utf-8")

    def fail_replace(_source: object, _destination: object) -> None:
        raise OSError("simulated manifest replace failure")

    monkeypatch.setattr("mapassist.apply_label_shards.os.replace", fail_replace)
    with pytest.raises(OSError, match="simulated manifest replace failure"):
        apply_label_shards(manifest, [shard])

    assert manifest.read_text() == original
    assert AnnotationStore(annotation_dataset, lease_seconds=60).stats()["completed"] == 0


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="ffmpeg is needed for context frames")
def test_annotation_store_extracts_cached_temporal_context(annotation_dataset: Path) -> None:
    video = annotation_dataset / "private.mp4"
    subprocess.run([
        "ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
        "color=c=0x224466:s=320x180:r=4:d=2", "-c:v", "mpeg4", "-y", str(video),
    ], check=True)
    store = AnnotationStore(annotation_dataset, lease_seconds=60)
    bootstrap = store.bootstrap()
    assert bootstrap["context_offsets_ms"] == [-500, 500]
    assert bootstrap["context_matches"] == ["match-01"]
    task = store.list_tasks(limit=1)[0]
    before = store.context_frame(task["id"], -500)
    after = store.context_frame(task["id"], 500)
    assert before.is_file() and after.is_file()
    assert store.context_frame(task["id"], 500) == after
    with Image.open(after) as extracted:
        assert extracted.size == (320, 180)
    with pytest.raises(ValueError, match="offset_ms"):
        store.context_frame(task["id"], 250)


def test_detection_review_evaluates_boxes_directions_and_failures(tmp_path: Path) -> None:
    manifest = tmp_path / "review.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "roi": [0.0, 0.0, 1.0, 1.0],
        "matches": [
            {"id": "train-match", "split": "train", "samples": [
                {"at_ms": 1000, "selection": "cue", "review_status": "accepted",
                 "suggested_boxes": [[0.1, 0.4, 0.1, 0.1]], "directions": ["left"]},
                {"at_ms": 2000, "selection": "cue", "review_status": "corrected",
                 "suggested_boxes": [[0.1, 0.1, 0.1, 0.1]],
                 "reviewed_boxes": [[0.7, 0.7, 0.1, 0.1]], "directions": ["up"]},
                {"at_ms": 3000, "selection": "background", "review_status": "negative",
                 "suggested_boxes": [[0.2, 0.2, 0.1, 0.1]], "directions": ["up"]},
                {"at_ms": 4000, "selection": "cue", "review_status": "skip",
                 "suggested_boxes": [], "directions": []},
                {"at_ms": 5000, "selection": "background", "review_status": "excluded",
                 "suggested_boxes": [], "directions": []},
            ]},
            {"id": "val-match", "split": "val", "samples": [
                {"at_ms": 1000, "selection": "cue", "review_status": "corrected",
                 "suggested_boxes": [[0.7, 0.4, 0.1, 0.1]],
                 "reviewed_boxes": [[0.72, 0.4, 0.1, 0.1]], "directions": ["left"]},
                {"at_ms": 2000, "selection": "background", "review_status": "negative",
                 "suggested_boxes": [], "directions": []},
            ]},
        ],
    }), encoding="utf-8")
    report = evaluate_detection_review(manifest)
    assert report["skipped_frames"] == 1
    assert report["excluded_frames"] == 1
    assert report["overall"] == {
        "frames": 5, "positive_frames": 3, "negative_frames": 2,
        "exact_frames": 3, "exact_frame_accuracy": 0.6,
        "tp": 2, "fp": 2, "fn": 1,
        "precision": 0.5, "recall": 0.6667,
        "mean_matched_iou": 0.8333,
        "directed_matches": 2, "ambiguous_direction_matches": 0,
        "direction_accuracy": 0.5,
    }
    assert report["splits"]["val"]["precision"] == 1.0
    assert report["splits"]["val"]["recall"] == 1.0
    assert report["sampled_frame_targets_met"] is False
    assert [(item["at_ms"], item["fp"], item["fn"])
            for item in report["failures"]] == [(2000, 1, 1), (3000, 1, 0)]
    assert [(item["match_id"], item["at_ms"], item["predicted_direction"],
             item["ground_truth_direction"])
            for item in report["direction_failures"]] == [
                ("val-match", 1000, "left", "right")
            ]

    data = json.loads(manifest.read_text())
    data["matches"][0]["samples"][0]["review_status"] = "pending"
    manifest.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="pending samples"):
        evaluate_detection_review(manifest)
    partial = evaluate_detection_review(manifest, allow_pending=True)
    assert partial["review_complete"] is False
    assert partial["total_samples"] == 7
    assert partial["reviewed_samples"] == 6
    assert partial["pending_samples"] == 1
    assert partial["completion_ratio"] == pytest.approx(6 / 7, abs=0.0001)
    assert partial["sampled_frame_targets_met"] is False
    assert partial["warning"].startswith("PARTIAL REPORT:")


def test_detection_review_joins_committed_blind_predictions(tmp_path: Path) -> None:
    predictions = tmp_path / "frozen.jsonl"
    predictions.write_text(json.dumps({
        "timestamp_ms": 1000,
        "observations": [{
            "type": "minimap_enemy",
            "bbox_norm": [0.1, 0.2, 0.1, 0.1],
            "direction": "left",
        }],
        "cues": [],
    }) + "\n", encoding="utf-8")

    def sha(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    video_hash = "1" * 64
    profile_hash = "2" * 64
    library_hash = "3" * 64
    metadata = Path(f"{predictions}.meta.json")
    metadata.write_text(json.dumps({
        "schema_version": 1,
        "video": {"sha256": video_hash},
        "profile": {"sha256": profile_hash},
        "native_library": {"sha256": library_hash},
        "predictions": {"sha256": sha(predictions)},
        "stats": {"fps": 12, "frames": 1},
    }), encoding="utf-8")
    manifest = tmp_path / "review.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "review_mode": "blind",
        "roi": [0, 0, 1, 1],
        "sampling": {"predictions_used_for_selection": False},
        "prediction_commitment": {
            "metadata_sha256": sha(metadata),
            "predictions_sha256": sha(predictions),
            "video_sha256": video_hash,
            "profile_sha256": profile_hash,
            "native_library_sha256": library_hash,
        },
        "matches": [{
            "id": "holdout", "split": "test",
            "samples": [{
                "at_ms": 1000,
                "selection": "systematic_blind",
                "suggested_boxes": [],
                "directions": [],
                "review_status": "corrected",
                "reviewed_boxes": [[0.1, 0.2, 0.1, 0.1]],
            }],
        }],
    }), encoding="utf-8")
    report = evaluate_detection_review(manifest, predictions=predictions)
    assert report["evaluation_design"] == "independent_blind_frame_holdout"
    assert report["overall"]["precision"] == 1.0
    assert report["overall"]["recall"] == 1.0
    assert report["prediction_source"]["predictions_sha256"] == sha(predictions)

    predictions.write_text(predictions.read_text() + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="frozen commitment"):
        evaluate_detection_review(manifest, predictions=predictions)


def test_detection_review_uses_match_roi_for_direction_metrics(tmp_path: Path) -> None:
    """Per-match ROI overrides must also control ground-truth direction labels."""
    manifest = tmp_path / "review.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        # The top-level ROI is intentionally different from the match ROI.
        "roi": [0.0, 0.0, 1.0, 1.0],
        "matches": [{
            "id": "phone-portrait-crop", "split": "val",
            "roi": [0.5, 0.0, 0.5, 1.0],
            "samples": [{
                "at_ms": 1000, "selection": "cue", "review_status": "corrected",
                "suggested_boxes": [[0.6, 0.45, 0.1, 0.1]],
                "reviewed_boxes": [[0.6, 0.45, 0.1, 0.1]],
                # Relative to the match ROI center (x=.75), this is left.
                "directions": ["left"],
            }],
        }],
    }), encoding="utf-8")

    report = evaluate_detection_review(manifest)

    assert report["overall"]["direction_accuracy"] == 1.0
    assert report["direction_failures"] == []


def test_detection_review_uses_widget_roi_separate_from_safe_crop(
    tmp_path: Path,
) -> None:
    width, height = 2376, 1080
    safe_roi = [79 / width, 0, 402 / width, 371 / height]
    widget_roi = [106 / width, 0, 348 / width, 344 / height]
    # At full-frame center (312,155), the safe-crop center classifies "up";
    # the measured widget center classifies "right".
    box = [302 / width, 145 / height, 20 / width, 20 / height]
    manifest = tmp_path / "safe-roi-review.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "roi": safe_roi,
        "widget_roi": widget_roi,
        "matches": [{"id": "video8-safe-roi", "split": "test", "samples": [{
            "at_ms": 1000, "selection": "cue", "review_status": "corrected",
            "suggested_boxes": [box], "reviewed_boxes": [box],
            "directions": ["up"],
        }]}],
    }), encoding="utf-8")

    report = evaluate_detection_review(manifest)

    assert report["overall"]["directed_matches"] == 1
    assert report["overall"]["direction_accuracy"] == 0.0
    assert [(item["predicted_direction"], item["ground_truth_direction"])
            for item in report["direction_failures"]] == [("up", "right")]


@pytest.mark.parametrize("widget_roi", [
    [float("nan"), 0.1, 0.2, 0.2],
    [float("inf"), 0.1, 0.2, 0.2],
    [0.1, 0.1, 0.0, 0.2],
    [0.9, 0.1, 0.2, 0.2],
])
def test_detection_review_direction_reference_rejects_invalid_widget_roi(
    widget_roi: list[float],
) -> None:
    with pytest.raises(ValueError):
        _direction_reference_roi({}, {"widget_roi": widget_roi},
                                 [0.0, 0.0, 1.0, 1.0])


@pytest.mark.parametrize("widget_roi", [
    [float("nan"), 0.1, 0.2, 0.2],
    [float("inf"), 0.1, 0.2, 0.2],
    [0.1, 0.1, 0.0, 0.2],
    [0.9, 0.1, 0.2, 0.2],
])
def test_review_dataset_rejects_invalid_widget_roi_before_sampling(
    tmp_path: Path, widget_roi: list[float],
) -> None:
    manifest = tmp_path / "source.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "widget_roi": widget_roi,
    }), encoding="utf-8")

    with pytest.raises(ValueError, match="widget_roi"):
        build_review_dataset(manifest, tmp_path / "review")


def test_replay_evaluator_scores_directions_against_profile_reference(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    profile_roi = [0.05, 0.05, 0.28, 0.44]
    manifest = tmp_path / "review.json"
    (tmp_path / "frame.png").parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", (320, 180), (0, 0, 0, 255)).save(tmp_path / "frame.png")
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "roi": [0.0, 0.0, 0.4, 0.6],
        "matches": [{
            "id": "legacy-queue", "split": "val",
            "samples": [{
                "at_ms": 1000, "review_status": "corrected",
                "frame": "frame.png", "suggested_boxes": [],
                "reviewed_boxes": [[0.1, 0.1, 0.02, 0.02]],
            }],
        }],
    }), encoding="utf-8")
    profile = tmp_path / "profile.json"
    profile.write_text("profile", encoding="utf-8")
    library = tmp_path / "native.so"
    library.write_bytes(b"native")

    class FakePipeline:
        def __init__(self, *_args):
            self.profile = SimpleNamespace(
                minimap_direction=Rect(*profile_roi),
                minimap=Rect(),
            )

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def step(self, *_args):
            return ([{"type": "minimap_enemy", "bbox_norm": [
                0.1, 0.1, 0.02, 0.02,
            ], "direction": "right"}], [])

    captured = {}

    def capture_evaluation_manifest(path: Path) -> dict:
        replayed = json.loads(path.read_text(encoding="utf-8"))
        captured["widget_roi"] = replayed["matches"][0].get("widget_roi")
        return {"overall": {}}

    monkeypatch.setattr(detection_replay_evaluate, "Pipeline", FakePipeline)
    monkeypatch.setattr(detection_replay_evaluate, "evaluate_review",
                        capture_evaluation_manifest)

    detection_replay_evaluate.evaluate_current_detector(manifest, profile, library)

    assert captured["widget_roi"] == pytest.approx(profile_roi)


def test_detection_box_matching_finds_maximum_cardinality() -> None:
    predictions = [[0.0, 0.0, 0.6, 1.0], [0.0, 0.0, 0.3, 1.0]]
    truth = [[0.0, 0.0, 0.3, 1.0], [0.3, 0.0, 0.3, 1.0]]
    matches = _match_boxes(predictions, truth, 0.5)
    assert {(prediction, target) for prediction, target, _ in matches} == {(0, 1), (1, 0)}


def test_finalize_review_omits_non_gameplay_frames(tmp_path: Path) -> None:
    manifest = tmp_path / "review.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "widget_roi": [0.1, 0.15, 0.35, 0.3],
        "label_roi": [0.11, 0.15, 0.34, 0.3],
        "matches": [{
            "id": "match-01", "video": "match.mp4", "split": "train",
            "label_roi": [0.12, 0.15, 0.32, 0.3],
            "samples": [
                {"at_ms": 0, "review_status": "excluded", "suggested_boxes": []},
                {"at_ms": 1000, "review_status": "negative", "suggested_boxes": []},
            ],
        }],
    }), encoding="utf-8")
    output = tmp_path / "detections.json"
    summary = finalize_review(manifest, output)
    assert summary["frames"] == 1
    assert summary["statuses"]["excluded"] == 1
    exported = json.loads(output.read_text())
    assert exported["matches"][0]["frames"] == [{"at_ms": 1000, "boxes": []}]
    assert exported["widget_roi"] == [0.1, 0.15, 0.35, 0.3]
    assert exported["label_roi"] == [0.11, 0.15, 0.34, 0.3]
    assert exported["matches"][0]["label_roi"] == [0.12, 0.15, 0.32, 0.3]


@pytest.fixture(scope="session")
def native_library(tmp_path_factory: pytest.TempPathFactory) -> Path:
    build_dir = tmp_path_factory.mktemp("native-build")
    root = Path(__file__).resolve().parents[1]
    subprocess.run(["cmake", "-S", str(root / "native"), "-B", str(build_dir)],
                   check=True, capture_output=True)
    subprocess.run(["cmake", "--build", str(build_dir)], check=True, capture_output=True)
    return next(build_dir.glob("libmapassist.*"))


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed for replay")
def test_visible_events_emit_once_and_single_frame_noise_is_rejected(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    predictions = tmp_path / "predictions.jsonl"
    stats = run(fixture["video"], fixture["profile"], predictions, 12, native_library)
    assert stats["frames"] == 84
    assert stats["profile_verified"] is False
    assert stats["desktop_processing_ms_p95"] is not None
    metadata = json.loads(Path(stats["metadata"]).read_text())
    assert metadata["schema_version"] == 1
    assert metadata["video"]["sha256"] == hashlib.sha256(
        fixture["video"].read_bytes()).hexdigest()
    assert metadata["profile"]["sha256"] == hashlib.sha256(
        fixture["profile"].read_bytes()).hexdigest()
    assert metadata["predictions"]["sha256"] == hashlib.sha256(
        predictions.read_bytes()).hexdigest()
    assert {asset["role"] for asset in metadata["profile_assets"]} == {
        "danger_ping", "minimap_enemy",
    }
    cues = read_predictions(predictions)
    assert [(cue["kind"], cue["direction"]) for cue in cues] == [
        ("main_enemy", "left"),
        ("minimap_enemy", "right"),
        ("danger_ping", None),
    ]
    result = evaluate(cues, read_labels(fixture["labels"]))
    assert result["overall"]["tp"] == 3
    assert result["overall"]["fp"] == 0
    assert result["overall"]["fn"] == 0
    assert result["overall"]["direction_accuracy"] == 1.0
    assert result["overall"]["onset_to_cue_p95_ms"] is not None


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed for replay")
def test_unverified_game_profile_fails_silent(tmp_path: Path, native_library: Path) -> None:
    fixture = create(tmp_path)
    root = Path(__file__).resolve().parents[1]
    output = tmp_path / "silent.jsonl"
    stats = run(fixture["video"], root / "profiles/hok_android_unverified.json",
                output, 12, native_library)
    assert stats["cues"] == 0
    assert stats["observations"] == 0


def test_evaluation_counts_wrong_or_duplicate_events() -> None:
    labels = [{"kind": "main_enemy", "start_ms": 1000, "end_ms": 1200,
               "direction": "left"}]
    predictions = [
        {"kind": "main_enemy", "emitted_at_ms": 1050, "direction": "right"},
        {"kind": "main_enemy", "emitted_at_ms": 1100, "direction": "right"},
    ]
    result = evaluate(predictions, labels)
    assert result["main_enemy"]["tp"] == 1
    assert result["main_enemy"]["fp"] == 1
    assert result["main_enemy"]["direction_accuracy"] == 0.0


def test_cue_before_visible_evidence_is_false_positive() -> None:
    labels = [{"kind": "main_enemy", "start_ms": 1000, "end_ms": 1200,
               "direction": "left"}]
    predictions = [{"kind": "main_enemy", "emitted_at_ms": 900, "direction": "left"}]
    result = evaluate(predictions, labels)
    assert result["main_enemy"]["fp"] == 1
    assert result["main_enemy"]["fn"] == 1


def test_overlapping_events_use_maximum_one_to_one_matching() -> None:
    labels = [
        {"kind": "main_enemy", "start_ms": 1000, "end_ms": 1200, "direction": "left"},
        {"kind": "main_enemy", "start_ms": 1100, "end_ms": 2000, "direction": "right"},
    ]
    predictions = [
        {"kind": "main_enemy", "emitted_at_ms": 1120, "direction": "left"},
        {"kind": "main_enemy", "emitted_at_ms": 1900, "direction": "right"},
    ]
    result = evaluate(predictions, labels)
    assert result["main_enemy"]["tp"] == 2
    assert result["main_enemy"]["fp"] == 0
    assert result["main_enemy"]["fn"] == 0
    assert result["main_enemy"]["direction_accuracy"] == 1.0


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed to extract frames")
def test_detection_dataset_exports_boxes_and_negative_frames(tmp_path: Path) -> None:
    fixture = create(tmp_path)
    val_fixture = create(tmp_path / "other_match")
    manifest = tmp_path / "detections.json"
    manifest.write_text(json.dumps({"schema_version": 1,
                                    "roi": [0.05, 0.1, 0.5, 0.4],
                                    "widget_roi": [0.1, 0.15, 0.35, 0.3],
                                    "label_roi": [0.15, 0.2, 0.1, 0.1],
                                    "matches": [
        {"id": "match-01", "video": fixture["video"].name, "split": "train",
         "frames": [
             {"at_ms": 1000, "boxes": [[0.1, 0.2, 0.3, 0.1]]},
             {"at_ms": 2000, "boxes": []},
         ]},
        {"id": "match-02", "video": str(val_fixture["video"].relative_to(tmp_path)),
         "split": "val", "frames": [{"at_ms": 1500, "boxes": []}]},
    ]}), encoding="utf-8")
    output = tmp_path / "dataset"
    summary = export_detection_dataset(manifest, output)
    coco = json.loads((output / "annotations/instances_train2017.json").read_text())
    assert summary["train"] == {"images": 2, "boxes": 1, "negative_images": 1}
    assert len(coco["images"]) == 2
    assert coco["annotations"][0]["bbox"] == [32.0, 36.0, 96.0, 18.0]
    assert all((output / "train2017" / image["file_name"]).is_file()
               for image in coco["images"])
    assert (output / "train2017/match-01_000001000.png").is_file()
    assert (output / "train2017/match-01_000002000.png").is_file()
    val_coco = json.loads((output / "annotations/instances_val2017.json").read_text())
    assert summary["val"] == {"images": 1, "boxes": 0, "negative_images": 1}
    assert (output / "val2017" / val_coco["images"][0]["file_name"]).is_file()

    cropped_output = tmp_path / "cropped-dataset"
    export_detection_dataset(manifest, cropped_output, crop_roi=True)
    cropped = json.loads(
        (cropped_output / "annotations/instances_train2017.json").read_text()
    )
    assert cropped["images"][0]["width"] == 160
    assert cropped["images"][0]["height"] == 72
    assert cropped["images"][0]["direction_roi"] == pytest.approx(
        [0.1, 0.125, 0.7, 0.75])
    assert cropped["annotations"][0]["bbox"] == [16.0, 18.0, 96.0, 18.0]
    with Image.open(cropped_output / "train2017/match-01_000001000.png") as image:
        assert image.size == (160, 72)

    manifest.write_text(json.dumps({"schema_version": 1, "matches": [
        {"id": "train", "video": fixture["video"].name, "split": "train",
         "frames": [{"at_ms": 1000, "boxes": []}]},
        {"id": "test", "video": fixture["video"].name, "split": "test",
         "frames": [{"at_ms": 2000, "boxes": []}]},
    ]}), encoding="utf-8")
    with pytest.raises(ValueError, match="cannot cross splits"):
        export_detection_dataset(manifest, tmp_path / "leaked")
    assert not (tmp_path / "leaked").exists()

    manifest.write_text(json.dumps({"schema_version": 1, "matches": [{
        "id": "replaced", "video": fixture["video"].name, "split": "train",
        "video_sha256": "0" * 64,
        "frames": [{"at_ms": 1000, "boxes": []}],
    }]}), encoding="utf-8")
    with pytest.raises(ValueError, match="Source video hash mismatch.*new match id"):
        export_detection_dataset(manifest, tmp_path / "hash-mismatch")
    assert not (tmp_path / "hash-mismatch").exists()


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed to extract frames")
def test_detection_export_audits_crop_edge_and_rejects_silent_clipping(
    tmp_path: Path,
) -> None:
    fixture = create(tmp_path)
    manifest = tmp_path / "edge-detections.json"
    roi = [0.05, 0.1, 0.5, 0.4]
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "roi": roi,
        "matches": [{
            "id": "edge-match", "video": fixture["video"].name, "split": "train",
            "frames": [{"at_ms": 1000, "boxes": [[0.5, 0.2, 0.05, 0.1]]}],
        }],
    }), encoding="utf-8")

    output = tmp_path / "edge-coco"
    export_detection_dataset(manifest, output, crop_roi=True)

    annotation = json.loads(
        (output / "annotations/instances_train2017.json").read_text(encoding="utf-8")
    )
    audit = annotation["info"]["roi_boundary_audit"]
    assert audit["training_eligible"] is False
    assert audit["usable_for_training_or_evaluation"] is False
    assert audit["crop_edge_contacts"] == [{
        "match_id": "edge-match", "at_ms": 1000, "box_index": 1,
        "sides": ["right"],
    }]

    clipped_manifest = tmp_path / "clipped-detections.json"
    clipped_manifest.write_text(json.dumps({
        "schema_version": 1,
        "roi": roi,
        "matches": [{
            "id": "clipped-match", "video": fixture["video"].name, "split": "train",
            "frames": [{"at_ms": 1000, "boxes": [[0.54, 0.2, 0.03, 0.1]]}],
        }],
    }), encoding="utf-8")
    with pytest.raises(ValueError, match="would be silently clipped"):
        export_detection_dataset(clipped_manifest, tmp_path / "clipped-coco", crop_roi=True)


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed for rotated video")
def test_detection_dataset_exports_display_oriented_rotated_video(tmp_path: Path) -> None:
    """Normalized labels are measured on display frames, including Display Matrix media."""
    coded = tmp_path / "coded.mp4"
    rotated = tmp_path / "rotated.mp4"
    subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=red:s=8x12:d=1:r=1",
        "-frames:v", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(coded),
    ], check=True)
    subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-display_rotation:v:0", "90", "-i", str(coded), "-c", "copy", str(rotated),
    ], check=True)
    manifest = tmp_path / "detections.json"
    # Deliberately omit orientation: the exporter must resolve it from the video,
    # which also repairs legacy manifests produced before orientation metadata.
    manifest.write_text(json.dumps({"schema_version": 1, "roi": [0, 0, 1, 1],
                                    "matches": [{
        "id": "rotated", "video": rotated.name, "split": "test",
        "frames": [{"at_ms": 0, "boxes": [[0.25, 0.25, 0.25, 0.25]]}],
    }]}), encoding="utf-8")
    output = tmp_path / "dataset"
    export_detection_dataset(manifest, output, crop_roi=True)
    coco = json.loads(
        (output / "annotations/instances_test2017.json").read_text()
    )
    assert coco["images"][0]["width"] == 12
    assert coco["images"][0]["height"] == 8
    assert coco["annotations"][0]["bbox"] == [3.0, 2.0, 3.0, 2.0]
    with Image.open(output / "test2017/rotated_000000000.png") as image:
        assert image.size == (12, 8)

    # A full-frame extraction must also clear the container rotation metadata.
    # Otherwise a browser applies EXIF after pixels were already rotated.
    frame = tmp_path / "display-frame.png"
    assert extract(rotated, 0, frame, display_rotation=90) == (12, 8)
    with Image.open(frame) as image:
        assert image.size == (12, 8)
        assert image.getexif().get(274) is None


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed to extract frames")
def test_detection_dataset_exports_adaptive_locator_crops(
    tmp_path: Path, native_library: Path,
) -> None:
    frame = Image.new("RGB", (320, 180), (25, 29, 33))
    draw = ImageDraw.Draw(frame)
    roi = [0.05, 0.1, 0.5, 0.4]
    left, top, width, height = 16, 18, 160, 72
    for gy in range(8):
        for gx in range(8):
            value = 30 + ((gx * 31 + gy * 47 + gx * gy * 7) % 190)
            draw.rectangle(
                (left + gx * width / 8, top + gy * height / 8,
                 left + (gx + 1) * width / 8 - 1,
                 top + (gy + 1) * height / 8 - 1),
                fill=(value, value, value),
            )
    source_frame = tmp_path / "source.png"
    frame.save(source_frame)
    video = tmp_path / "source.mp4"
    subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-loop", "1", "-i", str(source_frame), "-t", "3", "-r", "12",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", str(video),
    ], check=True)

    review_manifest = tmp_path / "review.json"
    review_manifest.write_text(json.dumps({
        "schema_version": 1,
        "roi": roi,
        "matches": [{
            "id": "calibration", "split": "train", "video": str(video),
            "samples": [{
                "at_ms": 1000, "review_status": "corrected",
                "reviewed_boxes": [[0.1, 0.2, 0.1, 0.1]],
                "frame": source_frame.name,
            }],
        }],
    }), encoding="utf-8")
    locator = tmp_path / "locator.json"
    calibrate_minimap_anchor(review_manifest, locator, 8, 8)

    detection_manifest = tmp_path / "detections.json"
    detection_manifest.write_text(json.dumps({
        "schema_version": 1,
        "category": "minimap_enemy",
        "matches": [{
            "id": "adaptive", "video": video.name, "split": "train",
            "frames": [
                {"at_ms": 1000, "boxes": [[0.1, 0.2, 0.1, 0.1]]},
                {"at_ms": 2000, "boxes": []},
            ],
        }],
    }), encoding="utf-8")
    output = tmp_path / "adaptive-dataset"

    summary = export_detection_dataset(
        detection_manifest, output, locator=locator, library=native_library,
    )

    assert summary["train"]["images"] == 2
    assert summary["train"]["boxes"] == 1
    assert summary["train"]["negative_images"] == 1
    assert summary["train"]["locator_states"] == {
        "searching": 0, "locked": 2, "held": 0,
    }
    assert summary["train"]["skipped_searching"] == 0
    assert summary["train"]["skipped_positive_images"] == 0
    assert summary["train"]["skipped_boxes"] == 0
    coco = json.loads(
        (output / "annotations/instances_train2017.json").read_text()
    )
    assert coco["info"]["adaptive_crop"]["locator_sha256"] == hashlib.sha256(
        locator.read_bytes()
    ).hexdigest()
    assert coco["info"]["adaptive_crop"]["native_library_sha256"] == hashlib.sha256(
        native_library.read_bytes()
    ).hexdigest()
    assert coco["info"]["skipped_searching_samples"] == []
    assert coco["images"][0]["width"] >= 160
    assert coco["images"][0]["height"] >= 72
    box = coco["annotations"][0]["bbox"]
    # The located union expands left of the coarse 160-pixel ROI. The label
    # must move by the same crop offset while keeping its full-frame size.
    assert box[0] == pytest.approx(26.0, abs=3.0)
    assert box[1] == pytest.approx(18.0, abs=2.0)
    assert box[2] == pytest.approx(32.0, abs=1.0)
    assert box[3] == pytest.approx(18.0, abs=1.0)


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed to extract frames")
def test_minimap_layout_review_dataset_uses_full_frame_annotation_bounds(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source.png"
    Image.new("RGB", (320, 180), (20, 30, 40)).save(source)
    video = tmp_path / "source.mp4"
    subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-loop", "1", "-i", str(source), "-t", "4", "-r", "12",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", str(video),
    ], check=True)
    manifest = tmp_path / "source-manifest.json"
    coarse = [0.04, 0.0, 0.15, 0.34]
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "roi": coarse,
        "matches": [{
            "id": "match1", "split": "train", "video": video.name,
            "active_intervals_ms": [[500, 3500]],
        }],
    }), encoding="utf-8")
    output = tmp_path / "layout-review"

    summary = build_minimap_layout_review(manifest, output, samples_per_match=2)

    assert summary["train"] == {"matches": 1, "samples": 2}
    review = json.loads((output / "review-manifest.json").read_text())
    assert review["kind"] == "minimap_region"
    assert review["review_mode"] == "manual"
    assert review["roi"] == [0.0, 0.0, 1.0, 1.0]
    assert review["matches"][0]["coarse_minimap_roi"] == coarse
    assert len(review["matches"][0]["samples"]) == 2
    for sample in review["matches"][0]["samples"]:
        assert sample["suggested_boxes"] == [coarse]
        assert (output / sample["frame"]).is_file()
        assert (output / sample["overlay"]).is_file()

    store = AnnotationStore(output, lease_seconds=60)
    task = store.claim_next("Layout reviewer")
    assert task is not None
    with pytest.raises(ValueError, match="exactly one"):
        store.save(task["id"], "Layout reviewer", task["version"], "corrected", [
            coarse, [0.3, 0.1, 0.1, 0.1],
        ])
    with pytest.raises(ValueError, match="use excluded"):
        store.save(task["id"], "Layout reviewer", task["version"], "negative")
    saved = store.save(
        task["id"], "Layout reviewer", task["version"], "corrected", [coarse]
    )
    assert saved["reviewed_boxes"] == [coarse]


def test_merge_detection_manifests_requires_explicit_unique_recordings(
    tmp_path: Path,
) -> None:
    first = tmp_path / "first.json"
    second_dir = tmp_path / "second"
    second_dir.mkdir()
    second = second_dir / "second.json"
    first.write_text(json.dumps({
        "schema_version": 1,
        "category": "minimap_enemy",
        "roi": [0.0, 0.0, 0.25, 0.5],
        "widget_roi": [0.02, 0.01, 0.2, 0.3],
        "matches": [{"id": "video1", "video": "video1.mp4", "split": "train",
                     "frames": [{"at_ms": 10, "boxes": []}]},
                    {"id": "video2", "video": "video2.mp4", "split": "val",
                     "roi": [0.0, 0.0, 0.3, 0.5],
                     "frames": [{"at_ms": 20, "boxes": [[0.1, 0.1, 0.1, 0.1]]}]}],
    }), encoding="utf-8")
    second.write_text(json.dumps({
        "schema_version": 1,
        "category": "minimap_enemy",
        "roi": [0.0, 0.0, 0.25, 0.5],
        "matches": [{"id": "video3", "video": "video3.mp4", "split": "test",
                     "frames": [{"at_ms": 30, "boxes": [[0.1, 0.1, 0.1, 0.1]]}]}],
    }), encoding="utf-8")
    output = tmp_path / "merged.json"
    summary = merge_detection_manifests(
        [first, second], output, {"video2": "train", "video3": "val"}
    )
    assert summary == {
        "test": {"matches": 0, "frames": 0, "boxes": 0},
        "train": {"matches": 2, "frames": 2, "boxes": 1},
        "val": {"matches": 1, "frames": 1, "boxes": 1},
    }
    merged = json.loads(output.read_text())
    assert [match["split"] for match in merged["matches"]] == ["train", "train", "val"]
    assert merged["matches"][1]["roi"] == [0.0, 0.0, 0.3, 0.5]
    assert [match.get("widget_roi") for match in merged["matches"]] == [
        [0.02, 0.01, 0.2, 0.3], [0.02, 0.01, 0.2, 0.3], None,
    ]
    assert [match.get("label_roi") for match in merged["matches"]] == [
        [0.02, 0.01, 0.2, 0.3], [0.02, 0.01, 0.2, 0.3], None,
    ]
    assert all(Path(match["video"]).is_absolute() for match in merged["matches"])

    with pytest.raises(ValueError, match="did not match"):
        merge_detection_manifests([first], tmp_path / "bad.json", {"missing": "train"})


def test_combine_detection_manifests_adds_frames_for_same_recording(
    tmp_path: Path,
) -> None:
    video = tmp_path / "video.mp4"
    video.write_bytes(b"private fixture")
    paths = [tmp_path / "first.json", tmp_path / "second.json"]
    video_sha256 = hashlib.sha256(video.read_bytes()).hexdigest()
    for path, timestamp in zip(paths, (100, 200)):
        path.write_text(json.dumps({
            "schema_version": 1,
            "category": "minimap_enemy",
            "roi": [0.0, 0.0, 0.25, 0.5],
            "widget_roi": [0.02, 0.01, 0.2, 0.3],
            "matches": [{
                "id": "video1", "video": video.name, "split": "train",
                "video_sha256": video_sha256,
                "frames": [{"at_ms": timestamp, "boxes": []}],
            }],
        }), encoding="utf-8")

    output = tmp_path / "combined.json"
    summary = combine_detection_manifests(paths, output)

    assert summary == {
        "matches": 1, "frames": 2, "boxes": 0,
        "splits": {"test": 0, "train": 2, "val": 0},
    }
    match = json.loads(output.read_text())["matches"][0]
    assert [frame["at_ms"] for frame in match["frames"]] == [100, 200]
    assert match["widget_roi"] == [0.02, 0.01, 0.2, 0.3]
    assert match["label_roi"] == [0.02, 0.01, 0.2, 0.3]
    assert match["video_sha256"] == video_sha256

    changed = json.loads(paths[1].read_text())
    changed["label_roi"] = [0.03, 0.01, 0.2, 0.3]
    paths[1].write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(ValueError, match="Metadata differs"):
        combine_detection_manifests(paths, tmp_path / "mismatched-label-rois.json")

    changed = json.loads(paths[1].read_text())
    changed.pop("label_roi")
    changed["matches"][0]["video_sha256"] = "0" * 64
    paths[1].write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(ValueError, match="Metadata differs"):
        combine_detection_manifests(paths, tmp_path / "mismatched-hashes.json")


def test_combine_detection_manifests_normalizes_missing_category(
    tmp_path: Path,
) -> None:
    video = tmp_path / "video.mp4"
    video.write_bytes(b"private fixture")
    paths = [tmp_path / "implicit.json", tmp_path / "explicit.json"]
    for index, path in enumerate(paths):
        document = {
            "schema_version": 1,
            "matches": [{
                "id": "video1", "video": video.name, "split": "train",
                "roi": [0.0, 0.0, 0.25, 0.5],
                "frames": [{"at_ms": 100 + index, "boxes": []}],
            }],
        }
        if index:
            document["category"] = "main_enemy"
        path.write_text(json.dumps(document), encoding="utf-8")

    output = tmp_path / "combined.json"
    combine_detection_manifests(paths, output)

    assert json.loads(output.read_text())["category"] == "main_enemy"


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are needed for review samples")
def test_review_dataset_keeps_suggestions_pending(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path / "fixture")
    predictions = tmp_path / "predictions.jsonl"
    run(fixture["video"], fixture["profile"], predictions, 12, native_library)
    manifest = tmp_path / "review-source.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "roi": [0.0, 0.0, 0.25, 0.34],
        "label_roi": [0.02, 0.0, 0.2, 0.34],
        "orientation": {"display_rotation_degrees": 0},
        "matches": [{
            "id": "match-01", "split": "train",
            "label_roi": [0.025, 0.01, 0.19, 0.32],
            "video": str(fixture["video"].relative_to(tmp_path)),
            "predictions": predictions.name,
            "active_intervals_ms": [[2000, 6000]],
        }],
    }), encoding="utf-8")
    output = tmp_path / "review"
    result = build_review_dataset(manifest, output, positives_per_match=1,
                                  negatives_per_match=1)
    assert result["train"] == {"matches": 1, "cue_samples": 1,
                               "background_samples": 1}
    exported = json.loads((output / "review-manifest.json").read_text())
    assert exported["label_roi"] == [0.02, 0.0, 0.2, 0.34]
    assert exported["matches"][0]["label_roi"] == [0.025, 0.01, 0.19, 0.32]
    assert exported["orientation"]["display_rotation_degrees"] == 0
    assert exported["matches"][0]["orientation"]["display_rotation_degrees"] == 0
    expected_video_sha256 = hashlib.sha256(fixture["video"].read_bytes()).hexdigest()
    assert exported["matches"][0]["video_sha256"] == expected_video_sha256
    samples = exported["matches"][0]["samples"]
    assert exported["matches"][0]["active_intervals_ms"] == [[2000, 6000]]
    assert all(2000 <= sample["at_ms"] <= 6000 for sample in samples)
    assert {sample["selection"] for sample in samples} == {"cue", "background"}
    assert all(sample["review_status"] == "pending" for sample in samples)
    assert all(sample["reviewed_boxes"] is None for sample in samples)
    assert all((output / sample["frame"]).is_file() for sample in samples)
    assert (output / "contact-sheets/match-01.jpg").is_file()
    assert (output / "contact-sheets/match-01-minimap.jpg").is_file()

    with pytest.raises(ValueError, match="pending samples"):
        finalize_review(output / "review-manifest.json", output / "detections.json")
    samples[0]["review_status"] = "negative"
    samples[1]["review_status"] = "accepted"
    (output / "review-manifest.json").write_text(
        json.dumps(exported, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    finalized = finalize_review(output / "review-manifest.json",
                                output / "detections.json")
    assert finalized["frames"] == 2
    detections = json.loads((output / "detections.json").read_text())
    assert detections["roi"] == [0.0, 0.0, 0.25, 0.34]
    assert detections["label_roi"] == [0.02, 0.0, 0.2, 0.34]
    assert detections["matches"][0]["label_roi"] == [0.025, 0.01, 0.19, 0.32]
    assert detections["orientation"]["display_rotation_degrees"] == 0
    assert detections["matches"][0]["orientation"]["display_rotation_degrees"] == 0
    assert detections["matches"][0]["video_sha256"] == expected_video_sha256
    coco_dir = output / "coco"
    export_detection_dataset(output / "detections.json", coco_dir)
    coco = json.loads((coco_dir / "annotations/instances_train2017.json").read_text())
    assert coco["categories"][0]["name"] == "minimap_enemy"


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="ffmpeg is needed to extract frames")
def test_extract_missing_frame_does_not_reuse_existing_image(tmp_path: Path) -> None:
    fixture = create(tmp_path)
    output = tmp_path / "frame.png"
    output.write_bytes(b"keep existing image")
    with pytest.raises(ValueError, match="No frame"):
        extract(fixture["video"], 999999, output)
    assert output.read_bytes() == b"keep existing image"


def test_external_latency_report_counts_missing_and_uses_p95() -> None:
    rows = [
        {"event_id": f"event-{index}", "cue_id": f"session:cue-{index}",
         "kind": "main_enemy", "evidence_ms": "1000",
         "audio_ms": str(1000 + delay), "source_note": "frame checked"}
        for index, delay in enumerate(range(0, 400, 20))
    ]
    rows.append({"event_id": "event-missing", "cue_id": "session:cue-missing",
                 "kind": "danger_ping", "evidence_ms": "5000", "audio_ms": "",
                 "source_note": "no audible onset"})
    result = measure(rows)
    assert result["by_kind"]["main_enemy"]["p95_ms"] == 360.0
    assert result["overall"] == {"paired_events": 20, "missing_audio": 1,
                                 "p95_ms": 360.0}
    with pytest.raises(ValueError, match="precedes evidence"):
        measure([{"event_id": "event-1", "cue_id": "session:1",
                  "kind": "main_enemy", "evidence_ms": "1000", "audio_ms": "900",
                  "source_note": "frame checked"}])


def test_profile_bundle_contains_android_templates(tmp_path: Path) -> None:
    fixture = create(tmp_path)
    output = tmp_path / "android-profile.json"
    result = bundle(fixture["profile"], output)
    imported = json.loads(output.read_text(encoding="utf-8"))
    assert result["embedded_templates"] == ["danger_ping", "minimap_enemy"]
    assert imported["schema_version"] == 1
    assert all(imported["templates_b64"][key] for key in result["embedded_templates"])
    assert base64.b64decode(imported["templates_b64"]["minimap_enemy"]) == (
        tmp_path / "enemy.png").read_bytes()


def test_hd_bootstrap_android_profile_matches_candidate_metadata() -> None:
    root = Path(__file__).resolve().parents[1]
    desktop = json.loads((root / "profiles/hok_minimap_hd_bootstrap.json").read_text())
    bundled = json.loads(
        (root / "profiles/hok_minimap_hd_bootstrap.android.json").read_text()
    )
    for key in ("schema_version", "name", "profile_version", "game", "verified", "rois",
                "detectors", "models", "thresholds", "events"):
        assert bundled[key] == desktop[key]
    assert bundled["verified"] is False
    assert bundled["detectors"]["minimap_yolox"] is True
    assert bundled["rois"]["minimap"] == pytest.approx(
        [44 / 1920, 0, (408 - 44) / 1920, 334 / 860]
    )
    assert bundled["rois"]["minimap_direction"] == pytest.approx(
        [96 / 1920, 0, (372 - 96) / 1920, 277 / 860]
    )
    assert bundled["profile_version"] == (
        "0.8.1-yolox-nano-hd-bootstrap-video10-video11-v2-c067"
    )
    assert bundled["thresholds"]["minimap_yolox_confidence"] == pytest.approx(0.67)
    assert bundled["thresholds"]["minimap_yolox_nms"] == pytest.approx(0.5)
    assert bundled["models"]["minimap_yolox_bin_sha256"] == (
        "d5b4b5dcee290122ae823750d247dd7336f656ab2430f86f68ba87f6ad1e4bd3"
    )
    android_profile = root / "profiles/hok_minimap_hd_bootstrap.android.json"
    profile_sha256 = hashlib.sha256(android_profile.read_bytes()).hexdigest()
    metadata_path = root / "android/app/src/main/assets/minimap-yolox-nano-320.metadata.json"
    metadata = json.loads(
        metadata_path.read_text(encoding="utf-8")
    )
    assert metadata["candidate"]["development_profile_android_sha256"] == profile_sha256
    assert metadata["candidate"]["id"] == (
        "yolox-nano-hd-bootstrap-video10-video11-v2-c067-dev-candidate"
    )
    assert metadata["candidate"]["profile_version"] == bundled["profile_version"]
    assert metadata["source"]["checkpoint_sha256"] == (
        "a11b560c6507f51f3e239b358445fb2acc95694edf80bc86c5cc207cc2a12f72"
    )
    assert metadata["source"]["onnx_sha256"] == (
        "517f296a99c79fe57b44746f9bdc33fb1cb564cffe0456e8f4fcaee8b0c58dea"
    )
    assert metadata["runtime"]["bin_sha256"] == bundled["models"][
        "minimap_yolox_bin_sha256"
    ]
    assert metadata["verified"] is False
    assert metadata["candidate"]["release_ready"] is False
    assert metadata["development_validation"]["split"].startswith(
        "video2-HD + video11 HD development val"
    )
    assert metadata["development_validation"]["images"] == 230
    assert metadata["development_validation"]["ncnn_runtime_box_detection_metrics"] == {
        "tp": 353,
        "fp": 15,
        "fn": 47,
        "precision": 0.959239,
        "recall": 0.8825,
        "f1": 0.919271,
    }
    assert metadata["parity"]["pytorch_vs_onnx"]["passed"] is False
    assert metadata["parity"]["pytorch_vs_onnx"]["maximum_raw_error"] == pytest.approx(
        0.000595390796661377
    )
    assert metadata["parity"]["pytorch_vs_onnx"]["raw_failed_images"] == 1
    assert metadata["parity"]["pytorch_vs_onnx"]["all_final_detection_arrays_match"] is True
    assert metadata["parity"]["torchscript_vs_ncnn_and_android_preprocess"]["overall_passed"] is False
    ncnn_parity = metadata["parity"]["torchscript_vs_ncnn_and_android_preprocess"]
    assert ncnn_parity["raw_max_error"] == pytest.approx(0.0010589361190795898)
    assert ncnn_parity["raw_failed_images"] == 12
    assert ncnn_parity["images_with_matching_final_detection_counts"] == 230
    assert ncnn_parity["reference_detections"] == 368
    assert ncnn_parity["runtime_detections"] == 368
    assert ncnn_parity["all_final_detection_counts_match"] is True
    assert ncnn_parity["maximum_detection_value_error"] == pytest.approx(0.4334869384765625)
    bundled_default = json.loads(
        (root / "android/app/src/main/assets/profile.json").read_text(encoding="utf-8")
    )
    assert bundled_default["verified"] is False
    assert bundled_default == bundled
    assert bundled_default["detectors"]["minimap_yolox"] is True


def test_android_metadata_records_video7_development_replay_smokes() -> None:
    root = Path(__file__).resolve().parents[1]
    metadata = json.loads(
        (root / "android/app/src/main/assets/minimap-yolox-nano-320.metadata.json")
        .read_text(encoding="utf-8")
    )
    smokes = metadata["historical_candidate"]["historical_replay_smokes"]
    assert "video7 development data chain" in smokes["scope"]
    assert "not independent quality evaluations" in smokes["scope"]
    assert smokes["runtime_artifacts"] == {
        "candidate_profile_sha256": "ac04bdeee6fd56b3fe83a32b66ed5e743821370d59cab49508d7ba1faecc2bd1",
        "ncnn_param_sha256": "4649269cae16fef3b64cc366f123ba58259a756b79f7f55d6be20cd3903cae14",
        "ncnn_bin_sha256": "34b2cc80e47bd197e52a40ff69e39d60aea363de2071c8a89510c6398bbfbc56",
    }

    negative = smokes["negative_segment"]
    assert (negative["frames"], negative["yolox_detections"], negative["cues"]) == (
        122, 0, 0
    )
    assert negative["human_sampled_point_detection_counts"] == [0, 0, 0, 0, 0]
    assert negative["same_clip_comparison_detection_counts"] == {
        "old_fixed_profile": 6,
        "old_adaptive_profile": 31,
    }
    assert {
        name: negative[name]["sha256"]
        for name in ("video", "predictions", "replay_metadata")
    } == {
        "video": "7e9ef922c19f52200946eecd281d95cee2a5c5bd7eec941b9ba640acf4941746",
        "predictions": "457840c948751f8cbcf42d278bde3add9b46cf537814e36cf6c37f0d3a690745",
        "replay_metadata": "52e57c6b163ff5da95fc86e253efe27d95932f043b398d82f28ef16d484eea70",
    }

    positive = smokes["positive_segment"]
    assert positive["original_seconds"] == [74.0, 92.0]
    assert (positive["frames"], positive["yolox_detections"], positive["cues"]) == (
        216, 501, 4
    )
    assert positive["processing_ms_p95"] == pytest.approx(29.938)
    assert positive["human_positive_point_box_counts"] == [1, 1, 2, 3, 3, 3, 3]
    assert positive["predicted_detection_counts_at_points"] == [1, 1, 2, 3, 3, 3, 3]
    assert positive["all_seven_point_counts_match"] is True
    assert {
        name: positive[name]["sha256"]
        for name in ("source_video", "clip", "predictions", "replay_metadata")
    } == {
        "source_video": "026a98209b5d9a426c18087f1af032def27f7347b6e5824392d503b94797a186",
        "clip": "98f6bb7abcb688afe02e221c39abfe5f9312fef4f92be603d69da74915b80f4e",
        "predictions": "c29bb9f17a67650342b689de6fc3ca2f3ad4c48ba050b489ed86dc8472d4e787",
        "replay_metadata": "43e08e146518ad3f716204a657c2d1a0fa46952084c31c63cfd4e1570c89e029",
    }


def test_bundled_android_default_enables_only_hd_minimap_recognizer() -> None:
    root = Path(__file__).resolve().parents[1]
    profile = json.loads((root / "android/app/src/main/assets/profile.json").read_text())
    assert profile["verified"] is False
    assert profile["detectors"] == {
        "main_red_bar": False,
        "minimap_template": False,
        "minimap_red_ring": False,
        "danger_ping_template": False,
        "minimap_yolox": True,
    }
    assert profile["thresholds"]["minimap_yolox_input_size"] == 320
    assert profile["thresholds"]["minimap_yolox_confidence"] == pytest.approx(0.67)
    assert profile["thresholds"]["minimap_yolox_nms"] == pytest.approx(0.5)
    assert profile["events"]["min_confidence"] == pytest.approx(0.67)
    assert profile["models"]["minimap_yolox_bin_sha256"] == (
        "d5b4b5dcee290122ae823750d247dd7336f656ab2430f86f68ba87f6ad1e4bd3"
    )


def test_schema1_yolox_profiles_bind_their_matching_ncnn_bin() -> None:
    root = Path(__file__).resolve().parents[1]
    expected_hashes = {
        "hok_minimap_development.json": (
            "34b2cc80e47bd197e52a40ff69e39d60aea363de2071c8a89510c6398bbfbc56"
        ),
        "hok_minimap_development.android.json": (
            "34b2cc80e47bd197e52a40ff69e39d60aea363de2071c8a89510c6398bbfbc56"
        ),
        "hok_minimap_adaptive.experimental.json": (
            "b3dbc844cc148aaa1a794e1bf03cb02a847b9c185215045c4b19b9ba4982236a"
        ),
        "hok_minimap_adaptive.experimental.android.json": (
            "b3dbc844cc148aaa1a794e1bf03cb02a847b9c185215045c4b19b9ba4982236a"
        ),
    }
    for name, expected_hash in expected_hashes.items():
        profile = json.loads((root / "profiles" / name).read_text(encoding="utf-8"))
        assert profile["schema_version"] == 1
        assert profile["detectors"]["minimap_yolox"] is True
        assert profile["models"]["minimap_yolox_bin_sha256"] == expected_hash
        assert len(expected_hash) == 64 and expected_hash == expected_hash.lower()


def test_adaptive_minimap_profile_is_importable_and_keeps_frozen_default() -> None:
    root = Path(__file__).resolve().parents[1]
    source = json.loads(
        (root / "profiles/hok_minimap_adaptive.experimental.json").read_text()
    )
    bundled = json.loads(
        (root / "profiles/hok_minimap_adaptive.experimental.android.json").read_text()
    )
    expected_default = copy.deepcopy(json.loads(
        (root / "profiles/hok_minimap_hd_bootstrap.android.json").read_text()
    ))
    frozen_default = json.loads(
        (root / "android/app/src/main/assets/profile.json").read_text()
    )

    assert bundled["templates_b64"] == {}
    assert bundled["layout"] == source["layout"]
    assert frozen_default == expected_default
    locator = bundled["layout"]["minimap_locator"]
    descriptor = base64.b64decode(locator["descriptor_b64"], validate=True)
    assert len(descriptor) == locator["grid_width"] * locator["grid_height"]
    assert hashlib.sha256(descriptor).hexdigest() == locator["descriptor_sha256"]
    assert locator["training_match_count"] == 6
    assert locator["training_frame_count"] == 573
    # The adaptive locator remains import-only; the reviewed fixed HD profile
    # is the APK default until an adaptive-crop detector passes evaluation.
    assert "layout" not in frozen_default
    assert frozen_default["profile_version"] == (
        "0.8.1-yolox-nano-hd-bootstrap-video10-video11-v2-c067"
    )


def test_android_ncnn_assets_match_metadata_and_patched_focus() -> None:
    root = Path(__file__).resolve().parents[1]
    assets = root / "android/app/src/main/assets"
    param = assets / "minimap-yolox-nano-320.param"
    weights = assets / "minimap-yolox-nano-320.bin"
    if not param.is_file() and not weights.is_file():
        pytest.skip(
            "private local YOLOX model assets are absent; frozen model consistency is unverified"
        )
    assert param.is_file() and weights.is_file(), (
        "private local model is incomplete; frozen model consistency is unverified"
    )
    metadata = json.loads(
        (assets / "minimap-yolox-nano-320.metadata.json").read_text(encoding="utf-8")
    )

    assert hashlib.sha256(param.read_bytes()).hexdigest() == (
        metadata["runtime"]["param_sha256"]
    )
    assert hashlib.sha256(weights.read_bytes()).hexdigest() == (
        metadata["runtime"]["bin_sha256"]
    )
    lines = param.read_text(encoding="utf-8").splitlines()
    assert lines[:4] == [
        "7767517",
        "280 310",
        "Input                    in0                      0 1 in0",
        "YoloV5Focus              focus                    1 1 in0 9",
    ]
    assert metadata["release_status"].startswith("experimental")
    notices = (assets / "THIRD_PARTY_NOTICES.txt").read_text(encoding="utf-8")
    assert "Terms of the BSD 3-Clause License" in notices
    assert "Apache License\n                           Version 2.0" in notices
    assert "Copyright (c) 2021-2022 Megvii Inc." in notices


def test_long_frame_gap_requires_fresh_confirmation(tmp_path: Path, native_library: Path) -> None:
    fixture = create(tmp_path)
    frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    ImageDraw.Draw(frame).rectangle((15, 85, 58, 90), fill=(220, 24, 26, 255))
    with Pipeline(fixture["profile"], native_library) as pipeline:
        _, first = pipeline.step(frame.tobytes(), 320, 180, 1000)
        _, confirmed = pipeline.step(frame.tobytes(), 320, 180, 1083)
        _, after_gap = pipeline.step(frame.tobytes(), 320, 180, 5000)
        _, reconfirmed = pipeline.step(frame.tobytes(), 320, 180, 5083)
    assert first == []
    assert len(confirmed) == 1
    assert after_gap == []
    assert len(reconfirmed) == 1


def test_center_mask_does_not_announce_red_bar(tmp_path: Path, native_library: Path) -> None:
    fixture = create(tmp_path)
    frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    ImageDraw.Draw(frame).rectangle((135, 85, 180, 90), fill=(220, 24, 26, 255))
    with Pipeline(fixture["profile"], native_library) as pipeline:
        observations, first = pipeline.step(frame.tobytes(), 320, 180, 1000)
        _, second = pipeline.step(frame.tobytes(), 320, 180, 1083)
    assert observations == []
    assert first == []
    assert second == []


def test_alpha_masked_templates_match_at_odd_pixel_offsets(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    for filename, position, expected in (
        ("enemy.png", (53, 25), "minimap_enemy"),
        ("danger.png", (271, 21), "danger_ping"),
    ):
        frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
        with Image.open(tmp_path / filename) as template:
            frame.alpha_composite(template.convert("RGBA"), position)
        with Pipeline(fixture["profile"], native_library) as pipeline:
            observations, first = pipeline.step(frame.tobytes(), 320, 180, 1000)
            _, second = pipeline.step(frame.tobytes(), 320, 180, 1083)
        assert any(item["type"] == expected for item in observations)
        assert first == []
        assert [item["kind"] for item in second] == [expected]


def test_centered_minimap_enemy_has_no_spurious_direction(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    with Image.open(tmp_path / "enemy.png") as template:
        frame.alpha_composite(template.convert("RGBA"), (34, 25))
    with Pipeline(fixture["profile"], native_library) as pipeline:
        observations, first = pipeline.step(frame.tobytes(), 320, 180, 1000)
        _, second = pipeline.step(frame.tobytes(), 320, 180, 1083)
    assert first == []
    assert [item["direction"] for item in observations if item["type"] == "minimap_enemy"] == [None]
    assert [(item["kind"], item["direction"]) for item in second] == [
        ("minimap_enemy", None)
    ]


def test_explicit_minimap_direction_reference_is_used_by_native_events(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    profile = json.loads(fixture["profile"].read_text(encoding="utf-8"))
    profile["rois"]["minimap"] = [0, 0, 0.4, 0.6]
    profile["rois"]["minimap_direction"] = [0.05, 0.05, 0.28, 0.44]
    profile["detectors"].update({"main_red_bar": False, "danger_ping_template": False})
    fixture["profile"].write_text(json.dumps(profile), encoding="utf-8")
    frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    with Image.open(tmp_path / "enemy.png") as template:
        frame.alpha_composite(template.convert("RGBA"), (64, 44))

    with Pipeline(fixture["profile"], native_library) as pipeline:
        observations, first = pipeline.step(frame.tobytes(), 320, 180, 1000)
        _, second = pipeline.step(frame.tobytes(), 320, 180, 1083)

    minimap = [item for item in observations if item["type"] == "minimap_enemy"]
    assert [item["direction"] for item in minimap] == ["right"]
    assert first == []
    assert [(item["kind"], item["direction"]) for item in second] == [
        ("minimap_enemy", "right")
    ]


def test_minimap_red_ring_rejects_narrow_static_red_symbols(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    profile = json.loads(fixture["profile"].read_text(encoding="utf-8"))
    profile["detectors"] = {
        "main_red_bar": False,
        "minimap_template": False,
        "minimap_red_ring": True,
        "danger_ping_template": False,
    }
    profile["thresholds"]["red_min"] = 90
    profile["thresholds"]["red_dominance"] = 1.25
    fixture["profile"].write_text(
        json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    draw = ImageDraw.Draw(frame)
    draw.rectangle((0, 0, 79, 60), fill=(32, 40, 52, 255))
    for y in (7, 22, 37, 52):
        for x in (7, 27, 47, 67):
            draw.rectangle((x, y, x + 2, y + 2), fill=(20, 120, 135, 255))
    draw.ellipse((10, 20, 24, 34), outline=(180, 35, 50, 255), width=3)
    draw.rectangle((67, 8, 70, 17), fill=(180, 35, 50, 255))
    draw.rectangle((72, 35, 75, 44), fill=(180, 35, 50, 255))
    with Pipeline(fixture["profile"], native_library) as pipeline:
        observations, first = pipeline.step(frame.tobytes(), 320, 180, 1000)
        _, second = pipeline.step(frame.tobytes(), 320, 180, 1083)
    minimap = [item for item in observations if item["type"] == "minimap_enemy"]
    assert len(minimap) == 1
    assert minimap[0]["direction"] == "left"
    assert first == []
    assert [(item["kind"], item["direction"]) for item in second] == [
        ("minimap_enemy", "left")
    ]

    covered = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    covered_draw = ImageDraw.Draw(covered)
    covered_draw.rectangle((0, 0, 79, 60), fill=(20, 24, 31, 255))
    covered_draw.rectangle((20, 18, 38, 38), fill=(180, 35, 50, 255))
    with Pipeline(fixture["profile"], native_library) as pipeline:
        covered_observations, _ = pipeline.step(covered.tobytes(), 320, 180, 2000)
    assert covered_observations == []


def test_minimap_red_ring_splits_adjacent_portraits(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    profile = json.loads(fixture["profile"].read_text(encoding="utf-8"))
    profile["rois"]["minimap"] = [0.0, 0.0, 0.325, 0.6]
    profile["detectors"] = {
        "main_red_bar": False,
        "minimap_template": False,
        "minimap_red_ring": True,
        "danger_ping_template": False,
    }
    profile["thresholds"]["red_min"] = 90
    profile["thresholds"]["red_dominance"] = 1.25
    fixture["profile"].write_text(
        json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    frame = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    draw = ImageDraw.Draw(frame)
    draw.rectangle((0, 0, 103, 107), fill=(32, 40, 52, 255))
    for y in (6, 33, 60, 87):
        for x in (6, 32, 58, 84):
            draw.rectangle((x, y, x + 2, y + 2), fill=(20, 120, 135, 255))
    # The expanded masks touch, but each portrait still has its own red ring.
    draw.ellipse((34, 38, 50, 55), outline=(190, 32, 45, 255), width=3)
    draw.ellipse((46, 48, 62, 65), outline=(190, 32, 45, 255), width=3)

    with Pipeline(fixture["profile"], native_library) as pipeline:
        observations, _ = pipeline.step(frame.tobytes(), 320, 180, 1000)
    minimap = [item for item in observations if item["type"] == "minimap_enemy"]
    assert len(minimap) == 2


def test_minimap_red_ring_rejects_hero_loading_cards(
    tmp_path: Path, native_library: Path
) -> None:
    fixture = create(tmp_path)
    profile = json.loads(fixture["profile"].read_text(encoding="utf-8"))
    profile["detectors"] = {
        "main_red_bar": False,
        "minimap_template": False,
        "minimap_red_ring": True,
        "danger_ping_template": False,
    }
    profile["thresholds"]["red_min"] = 90
    profile["thresholds"]["red_dominance"] = 1.25
    fixture["profile"].write_text(
        json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    dark_cards = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    dark_draw = ImageDraw.Draw(dark_cards)
    dark_draw.rectangle((0, 0, 79, 60), fill=(24, 31, 45, 255))
    dark_draw.rectangle((25, 2, 39, 57), outline=(25, 155, 175, 255), width=2)
    dark_draw.ellipse((26, 20, 38, 32), outline=(190, 32, 45, 255), width=3)

    bright_cards = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    bright_draw = ImageDraw.Draw(bright_cards)
    bright_draw.rectangle((0, 0, 79, 60), fill=(95, 120, 145, 255))
    for y in (7, 22, 37, 52):
        for x in (7, 27, 47, 67):
            bright_draw.rectangle((x, y, x + 2, y + 2), fill=(20, 150, 175, 255))
    bright_draw.ellipse((10, 20, 24, 34), outline=(190, 32, 45, 255), width=3)

    distributed_cards = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    distributed_draw = ImageDraw.Draw(distributed_cards)
    distributed_draw.rectangle((0, 0, 79, 60), fill=(24, 31, 45, 255))
    for cell in range(11):
        x = (cell % 4) * 20 + 5
        y = (cell // 4) * 15 + 5
        distributed_draw.rectangle((x, y, x + 2, y + 2),
                                   fill=(20, 150, 175, 255))
    distributed_draw.ellipse((30, 25, 43, 38), outline=(190, 32, 45, 255), width=3)

    split_cards = Image.new("RGBA", (320, 180), (13, 19, 29, 255))
    split_draw = ImageDraw.Draw(split_cards)
    split_draw.rectangle((0, 0, 79, 60), fill=(95, 120, 145, 255))
    split_draw.rectangle((0, 0, 38, 60), fill=(24, 31, 45, 255))
    for y in (7, 22, 37, 52):
        for x in (7, 27, 47, 67):
            split_draw.rectangle((x, y, x + 2, y + 2), fill=(20, 150, 175, 255))
    split_draw.ellipse((48, 25, 61, 38), outline=(190, 32, 45, 255), width=3)

    for timestamp, frame in ((1000, dark_cards), (2000, bright_cards),
                             (3000, distributed_cards), (4000, split_cards)):
        with Pipeline(fixture["profile"], native_library) as pipeline:
            observations, _ = pipeline.step(frame.tobytes(), 320, 180, timestamp)
        assert observations == []


def test_stale_frame_observation_cannot_trigger_audio(native_library: Path) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 1000, 5000, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    old = (Observation * 1)(Observation(1, 1, Rect(0.1, 0.4, 0.1, 0.05), 0.95, 1000))
    cue = (Cue * 1)()
    try:
        assert library.ma_engine_step(engine, old, 1, 1400, cue, 1) == 0
        assert library.ma_engine_step(engine, old, 1, 1483, cue, 1) == 0
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_spatial_track_does_not_repeat_when_direction_changes(
    native_library: Path
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 1000, 5000, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()

    def step(at_ms: int, x: float | None, direction: int = 1) -> int:
        if x is None:
            return library.ma_engine_step(engine, None, 0, at_ms, cue, 1)
        observations = (Observation * 1)(
            Observation(2, direction, Rect(x, 0.1, 0.02, 0.04), 0.95, at_ms)
        )
        return library.ma_engine_step(engine, observations, 1, at_ms, cue, 1)

    try:
        assert step(0, 0.10, 1) == 0
        assert step(83, 0.11, 1) == 1
        assert step(166, 0.14, 2) == 0
        for index in range(12):
            assert step(249 + index * 83, None) == 0
        assert step(1300, 0.14, 2) == 0
        assert step(1383, 0.14, 2) == 0
        assert step(5300, 0.14, 2) == 0
        assert step(5383, 0.14, 2) == 1
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_track_exposes_confirmed_visible_then_lost_marker(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 1000, 5000, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    markers = (MinimapMarker * 8)()

    def observe(at_ms: int, x: float | None) -> None:
        if x is None:
            library.ma_engine_step(engine, None, 0, at_ms, cue, 1)
            return
        observation = (Observation * 1)(
            Observation(2, 2, Rect(x, 0.10, 0.02, 0.04), 0.95, at_ms)
        )
        library.ma_engine_step(engine, observation, 1, at_ms, cue, 1)

    try:
        observe(0, 0.10)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        observe(83, 0.12)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].state == 1
        assert markers[0].movement_direction == 2
        assert markers[0].age_ms == 0
        assert markers[0].event == 1
        assert markers[0].bbox.x == pytest.approx(0.107, abs=1e-6)
        assert markers[0].bbox.y == pytest.approx(0.10, abs=1e-6)
        assert markers[0].bbox.w == pytest.approx(0.02, abs=1e-6)
        assert markers[0].bbox.h == pytest.approx(0.04, abs=1e-6)

        observe(166, None)
        observe(249, None)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].state == 1
        # A short dropout followed by a sighting keeps the confirmed track
        # alive and does not produce another APPEAR event.
        observe(260, 0.12)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].state == 1
        assert markers[0].event == 0
        observe(343, None)
        observe(426, None)
        observe(509, None)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].state == 2
        assert markers[0].movement_direction == 2
        assert markers[0].age_ms == 0
        assert markers[0].event == 2

        observe(592, None)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].age_ms == 83
        assert markers[0].event == 0
        for index in range(7, 53):
            observe(83 + index * 83, None)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        observe(509 + 3999, None)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].age_ms == 3999
        observe(509 + 4000, None)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
    finally:
        library.ma_engine_destroy(engine)


@pytest.mark.parametrize("min_hits", [2, 3])
def test_minimap_confirmation_threshold_accepts_two_or_three_hits_in_window(
    native_library: Path, min_hits: int
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 0, 0, min_hits, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    markers = (MinimapMarker * 8)()
    observation = (Observation * 1)(
        Observation(2, 2, Rect(0.10, 0.10, 0.02, 0.04), 0.95, 0)
    )

    def observe(at_ms: int, visible: bool) -> int:
        if visible:
            observation[0].timestamp_ms = at_ms
            return library.ma_engine_step(engine, observation, 1, at_ms, cue, 1)
        return library.ma_engine_step(engine, None, 0, at_ms, cue, 1)

    try:
        observe(0, True)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        if min_hits == 2:
            observe(83, False)
            assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
            cue_count = observe(166, True)
        else:
            observe(83, True)
            assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
            cue_count = observe(166, True)
        assert cue_count == 1
        assert cue[0].kind == 2
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].state == 1
        assert markers[0].event == 1
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_rollback_and_long_gap_require_fresh_confirmation(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 0, 0, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    markers = (MinimapMarker * 8)()
    observation = (Observation * 1)(
        Observation(2, 2, Rect(0.10, 0.10, 0.02, 0.04), 0.95, 0)
    )

    def observe(at_ms: int, visible: bool) -> None:
        if visible:
            observation[0].timestamp_ms = at_ms
            library.ma_engine_step(engine, observation, 1, at_ms, cue, 1)
        else:
            library.ma_engine_step(engine, None, 0, at_ms, cue, 1)

    try:
        observe(0, True)
        observe(83, True)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1

        observe(40, True)  # Clock rollback resets the old track.
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        observe(123, True)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].event == 1

        observe(1000, False)  # > 750 ms gap resets tracks silently.
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        observe(1083, True)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        observe(1166, True)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].event == 1
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_high_missing_threshold_starts_four_second_retention_on_disappear(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 1000, 0, 0, 2, 120)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    markers = (MinimapMarker * 8)()
    observation = (Observation * 1)(
        Observation(2, 2, Rect(0.10, 0.10, 0.02, 0.04), 0.95, 0)
    )

    def observe(at_ms: int, visible: bool) -> int:
        if visible:
            observation[0].timestamp_ms = at_ms
            return library.ma_engine_step(engine, observation, 1, at_ms, cue, 1)
        return library.ma_engine_step(engine, None, 0, at_ms, cue, 1)

    try:
        observe(0, True)
        observe(166, True)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1

        # At a 6 FPS sampling rate, four seconds elapse before 120 missed
        # frames. Keep the confirmed track until DISAPPEAR is actually proven.
        disappear_at_ms = 166 + 120 * 166
        for missing_frame in range(1, 121):
            at_ms = 166 + missing_frame * 166
            observe(at_ms, False)
            if missing_frame == 24:
                assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
                assert markers[0].state == 1

        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].state == 2
        assert markers[0].event == 2
        assert markers[0].age_ms == 0

        for age_ms in range(166, 3985, 166):
            observe(disappear_at_ms + age_ms, False)
        observe(disappear_at_ms + 3999, False)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].age_ms == 3999
        observe(disappear_at_ms + 4000, False)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_tracking_is_bounded_to_eight_tracks_and_read_capacity(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 0, 0, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    observations = (Observation * 9)()
    for index in range(9):
        x = 0.04 + index * 0.10
        observations[index] = Observation(
            2, 2, Rect(x, 0.10, 0.02, 0.04), 0.95, 0
        )
    markers = (MinimapMarker * 8)()
    limited = (MinimapMarker * 2)()
    limited[1].state = 991

    try:
        library.ma_engine_step(engine, observations, 9, 0, cue, 1)
        for index in range(9):
            observations[index].timestamp_ms = 83
        library.ma_engine_step(engine, observations, 9, 83, cue, 1)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 8
        assert library.ma_engine_read_minimap_markers(engine, limited, 1) == 1
        assert limited[1].state == 991
        assert all(marker.state == 1 for marker in markers)
        assert all(marker.bbox.x >= 0 and marker.bbox.y >= 0 for marker in markers)
        assert all(marker.bbox.x + marker.bbox.w <= 1 for marker in markers)
        assert all(marker.bbox.y + marker.bbox.h <= 1 for marker in markers)
    finally:
        library.ma_engine_destroy(engine)


def test_unconfirmed_single_frame_candidates_do_not_exhaust_track_slots(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    # The confirmation window is still three frames even when other engine
    # state uses a much larger missing-frame reset threshold.
    config = EngineConfig(0.75, 250, 0, 0, 2, 120)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    markers = (MinimapMarker * 8)()
    observation = (Observation * 1)()
    at_ms = 0

    try:
        for round_index in range(9):
            x = 0.04 + round_index * 0.10
            observation[0] = Observation(
                2, 2, Rect(x, 0.10, 0.02, 0.04), 0.95, at_ms
            )
            assert library.ma_engine_step(engine, observation, 1, at_ms, cue, 1) == 0
            assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
            for _ in range(3):
                at_ms += 83
                assert library.ma_engine_step(engine, None, 0, at_ms, cue, 1) == 0
                assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
            at_ms += 83

        # A real target can still claim a slot and pass normal confirmation.
        observation[0] = Observation(
            2, 2, Rect(0.96, 0.10, 0.02, 0.04), 0.95, at_ms
        )
        assert library.ma_engine_step(engine, observation, 1, at_ms, cue, 1) == 0
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        at_ms += 83
        observation[0].timestamp_ms = at_ms
        assert library.ma_engine_step(engine, observation, 1, at_ms, cue, 1) == 1
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].event == 1
    finally:
        library.ma_engine_destroy(engine)


def test_clearing_minimap_tracks_does_not_emit_disappear(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 1000, 5000, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    markers = (MinimapMarker * 8)()
    observation = (Observation * 1)(
        Observation(2, 2, Rect(0.10, 0.10, 0.02, 0.04), 0.95, 0)
    )

    try:
        library.ma_engine_step(engine, observation, 1, 0, cue, 1)
        observation[0].timestamp_ms = 83
        library.ma_engine_step(engine, observation, 1, 83, cue, 1)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 1
        assert markers[0].event == 1

        library.ma_engine_clear_minimap_tracks(engine)
        assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
        for at_ms in (166, 249, 332):
            library.ma_engine_step(engine, None, 0, at_ms, cue, 1)
            assert library.ma_engine_read_minimap_markers(engine, markers, 8) == 0
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_memory_reuses_short_loss_and_reconfirms_after_two_seconds(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    # Keep every empty-frame interval below the engine's session-gap reset so
    # the test exercises LOST retention rather than a new capture session.
    config = EngineConfig(0.75, 250, 0, 0, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    entities = (TrackedEntity * 9)()
    observation = (Observation * 1)(
        Observation(MA_MINIMAP_ENEMY, 2, Rect(0.10, 0.10, 0.02, 0.04), 0.95, 0)
    )

    def observe(at_ms: int, visible: bool) -> int:
        if visible:
            observation[0].timestamp_ms = at_ms
            return library.ma_engine_step(engine, observation, 1, at_ms, cue, 1)
        return library.ma_engine_step(engine, None, 0, at_ms, cue, 1)

    def read_entities() -> list[TrackedEntity]:
        count = library.ma_engine_read_tracked_entities(engine, entities, 9)
        return [entities[index] for index in range(count)]

    try:
        observe(0, True)
        observe(83, True)
        visible = read_entities()
        assert len(visible) == 1
        first_id = visible[0].track_id
        assert visible[0].entity_kind == MA_MINIMAP_ENEMY
        assert visible[0].state == MA_TRACK_STATE_VISIBLE
        assert visible[0].transition == MA_VISION_EVENT_APPEAR
        assert visible[0].confidence == pytest.approx(0.95, abs=1e-5)

        observe(166, False)
        observe(249, False)
        observe(332, False)
        lost = read_entities()
        assert len(lost) == 1
        assert lost[0].state == MA_TRACK_STATE_LOST
        assert lost[0].transition == MA_VISION_EVENT_DISAPPEAR
        assert lost[0].track_id == first_id

        # A short LOST interval resumes the same track and stays silent.
        observe(415, True)
        resumed = read_entities()
        assert len(resumed) == 1
        assert resumed[0].state == MA_TRACK_STATE_VISIBLE
        assert resumed[0].transition == MA_VISION_EVENT_NONE
        assert resumed[0].track_id == first_id
        assert observe(498, False) == 0

        # Lose it again, then walk forward in <=400ms steps.  The first hit
        # after the two-second grace boundary is only a candidate; the second
        # hit confirms a new appearance and emits the one cue.
        observe(581, False)
        observe(664, False)
        observe(1064, False)
        observe(1464, False)
        observe(1864, False)
        observe(2264, False)
        observe(2664, False)
        assert read_entities()[0].state == MA_TRACK_STATE_LOST
        assert observe(2747, True) == 0
        assert read_entities() == []
        assert observe(2830, True) == 1
        reappeared = read_entities()
        assert len(reappeared) == 1
        assert reappeared[0].state == MA_TRACK_STATE_VISIBLE
        assert reappeared[0].transition == MA_VISION_EVENT_APPEAR
        assert reappeared[0].track_id != first_id
        assert cue[0].kind == MA_MINIMAP_ENEMY

        # A confirmed track is retained through the four-second LOST window,
        # then expires on the boundary.
        observe(2913, False)
        observe(2996, False)
        observe(3079, False)
        for at_ms in range(3479, 7079, 400):
            observe(at_ms, False)
        observe(7079, False)
        assert read_entities() == []
    finally:
        library.ma_engine_destroy(engine)


def test_minimap_player_snapshot_is_silent_and_exposes_freshness(
    native_library: Path,
) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.10, 1000, 0, 0, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()
    entities = (TrackedEntity * 9)()
    player = (Observation * 1)(
        Observation(MA_MINIMAP_PLAYER, 0, Rect(0.50, 0.40, 0.02, 0.04), 0.88, 0)
    )

    def read_entities() -> list[TrackedEntity]:
        count = library.ma_engine_read_tracked_entities(engine, entities, 9)
        return [entities[index] for index in range(count)]

    try:
        assert library.ma_engine_step(engine, player, 1, 0, cue, 1) == 0
        player[0].timestamp_ms = 83
        assert library.ma_engine_step(engine, player, 1, 83, cue, 1) == 0
        snapshot = read_entities()
        assert len(snapshot) == 1
        assert snapshot[0].entity_kind == MA_MINIMAP_PLAYER
        assert snapshot[0].state == MA_TRACK_STATE_VISIBLE
        assert snapshot[0].transition == MA_VISION_EVENT_APPEAR
        assert snapshot[0].freshness_ms == 0
        assert snapshot[0].freshness_ms <= MA_PLAYER_RELEVANCE_MAX_AGE_MS
        # The player icon is a coordinate origin only and never a cue.
        assert cue[0].kind != MA_MINIMAP_PLAYER

        assert library.ma_engine_step(engine, None, 0, 584, cue, 1) == 0
        stale = read_entities()
        assert len(stale) == 1
        assert stale[0].freshness_ms == 501
        assert stale[0].freshness_ms > MA_PLAYER_RELEVANCE_MAX_AGE_MS
    finally:
        library.ma_engine_destroy(engine)


def test_danger_ping_preempts_recent_minimap_cue(native_library: Path) -> None:
    library = load_library(native_library)
    config = EngineConfig(0.75, 250, 1000, 5000, 2, 3)
    engine = library.ma_engine_create(ctypes.byref(config))
    assert engine
    cue = (Cue * 1)()

    def step(kind: int, direction: int, at_ms: int) -> int:
        observations = (Observation * 1)(
            Observation(kind, direction, Rect(0.1, 0.1, 0.02, 0.04), 0.95, at_ms)
        )
        return library.ma_engine_step(engine, observations, 1, at_ms, cue, 1)

    try:
        assert step(2, 1, 0) == 0
        assert step(2, 1, 83) == 1
        assert cue[0].priority == 1
        assert step(3, 0, 100) == 0
        assert step(3, 0, 183) == 1
        assert cue[0].priority == 3
    finally:
        library.ma_engine_destroy(engine)
