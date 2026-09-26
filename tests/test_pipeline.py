from __future__ import annotations

import base64
import ctypes
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.evaluate import evaluate, read_labels, read_predictions
from mapassist.bundle_profile import bundle
from mapassist.annotation_server import AnnotationStore, ConflictError, _dataset_specs
from mapassist.apply_label_shards import apply as apply_label_shards
from mapassist.blind_review_dataset import _sample_indices
from mapassist.combine_detection_manifests import combine as combine_detection_manifests
from mapassist.detection_dataset import export as export_detection_dataset
from mapassist.detection_evaluate import evaluate_review as evaluate_detection_review
from mapassist.detection_evaluate import _match_boxes
from mapassist.extract_frame import extract
from mapassist.finalize_review import finalize as finalize_review
from mapassist.measure_latency import measure
from mapassist.merge_detection_manifests import merge as merge_detection_manifests
from mapassist.native import Cue, EngineConfig, Observation, Pipeline, Rect, load_library
from mapassist.replay import run
from mapassist.review_dataset import build as build_review_dataset
from mapassist.synthetic import create
from mapassist.uniform_review_dataset import sample_timestamps


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
    with pytest.raises(ValueError, match="inside the minimap roi"):
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
    with pytest.raises(ValueError, match="inside the minimap roi"):
        store.save(task["id"], "Layout reviewer", task["version"], "corrected",
                   [[0.06, 0.12, 0.05, 0.07]])
    saved = store.save(task["id"], "Layout reviewer", task["version"], "corrected",
                       [[0.22, 0.12, 0.05, 0.07]])
    assert saved["reviewed_boxes"] == [[0.22, 0.12, 0.05, 0.07]]


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
    with pytest.raises(ValueError, match="no suggestions"):
        store.save(task["id"], "Blind reviewer", task["version"], "accepted")


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
        "matches": [{
            "id": "match-01", "video": "match.mp4", "split": "train",
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


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="ffmpeg is needed to extract frames")
def test_detection_dataset_exports_boxes_and_negative_frames(tmp_path: Path) -> None:
    fixture = create(tmp_path)
    val_fixture = create(tmp_path / "other_match")
    manifest = tmp_path / "detections.json"
    manifest.write_text(json.dumps({"schema_version": 1,
                                    "roi": [0.05, 0.1, 0.5, 0.4], "matches": [
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
    assert all(Path(match["video"]).is_absolute() for match in merged["matches"])

    with pytest.raises(ValueError, match="did not match"):
        merge_detection_manifests([first], tmp_path / "bad.json", {"missing": "train"})


def test_combine_detection_manifests_adds_frames_for_same_recording(
    tmp_path: Path,
) -> None:
    video = tmp_path / "video.mp4"
    video.write_bytes(b"private fixture")
    paths = [tmp_path / "first.json", tmp_path / "second.json"]
    for path, timestamp in zip(paths, (100, 200)):
        path.write_text(json.dumps({
            "schema_version": 1,
            "category": "minimap_enemy",
            "roi": [0.0, 0.0, 0.25, 0.5],
            "matches": [{
                "id": "video1", "video": video.name, "split": "train",
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
        "matches": [{
            "id": "match-01", "split": "train",
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


def test_bundled_android_profile_matches_development_profile() -> None:
    root = Path(__file__).resolve().parents[1]
    desktop = json.loads((root / "profiles/hok_minimap_development.json").read_text())
    bundled = json.loads(
        (root / "profiles/hok_minimap_development.android.json").read_text()
    )
    android = json.loads((root / "android/app/src/main/assets/profile.json").read_text())
    assert android == bundled
    for key in ("schema_version", "name", "profile_version", "game", "verified", "rois",
                "detectors", "thresholds", "events"):
        assert android[key] == desktop[key]


def test_bundled_android_profile_enables_only_experimental_yolox_minimap() -> None:
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
    assert profile["thresholds"]["minimap_yolox_confidence"] == pytest.approx(0.29)
    assert profile["thresholds"]["minimap_yolox_nms"] == pytest.approx(0.5)
    assert profile["events"]["min_confidence"] == pytest.approx(0.29)


def test_adaptive_minimap_profile_is_importable_and_keeps_frozen_default() -> None:
    root = Path(__file__).resolve().parents[1]
    source = json.loads(
        (root / "profiles/hok_minimap_adaptive.experimental.json").read_text()
    )
    bundled = json.loads(
        (root / "profiles/hok_minimap_adaptive.experimental.android.json").read_text()
    )
    frozen_default = json.loads(
        (root / "android/app/src/main/assets/profile.json").read_text()
    )

    assert bundled["templates_b64"] == {}
    assert bundled["layout"] == source["layout"]
    locator = bundled["layout"]["minimap_locator"]
    descriptor = base64.b64decode(locator["descriptor_b64"], validate=True)
    assert len(descriptor) == locator["grid_width"] * locator["grid_height"]
    assert hashlib.sha256(descriptor).hexdigest() == locator["descriptor_sha256"]
    assert locator["training_match_count"] == 6
    assert locator["training_frame_count"] == 573
    # video8 was frozen against this exact fixed-ROI profile.  The adaptive
    # profile remains an explicit import until that one-shot evaluation ends.
    assert "layout" not in frozen_default
    assert frozen_default["profile_version"] == "0.5.0-yolox-nano-dense-320-dev"


def test_android_ncnn_assets_match_metadata_and_patched_focus() -> None:
    root = Path(__file__).resolve().parents[1]
    assets = root / "android/app/src/main/assets"
    metadata = json.loads(
        (assets / "minimap-yolox-nano-320.metadata.json").read_text(encoding="utf-8")
    )
    param = assets / "minimap-yolox-nano-320.param"
    weights = assets / "minimap-yolox-nano-320.bin"

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
