from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from training.yolox_decode import (
    candidates_from_raw,
    classes_from_coco,
    confidence_from_metadata,
    confidence_thresholds,
    decode_yolox,
    resolve_classes,
    yolox_tensor_contract,
)
from mapassist.finalize_review import finalize
from mapassist.detection_evaluate import _external_predictions, _match_boxes_by_class
from mapassist.native import Cue, MA_MINIMAP_PLAYER, Observation, Rect, cue_dict, observation_dict
from mapassist.combine_detection_manifests import combine
from mapassist.merge_detection_manifests import merge
from training.evaluate_yolox_review_queue import _prediction_rows


def _raw_two_class() -> np.ndarray:
    raw = np.zeros((1, 84, 7), dtype=np.float32)
    # Three coincident boxes at the first 8-pixel grid level.  The first two
    # belong to different classes and must both survive class-aware NMS; the
    # third belongs to enemy and should be suppressed by the stronger enemy.
    raw[0, :3, 2:4] = np.log(8.0)
    raw[0, :3, 4] = 1.0
    raw[0, 0, 5:] = [0.90, 0.10]
    raw[0, 1, 5:] = [0.10, 0.80]
    raw[0, 2, 5:] = [0.70, 0.10]
    return raw


def test_coco_categories_become_deterministic_model_order() -> None:
    document = {
        "categories": [
            {"id": 9, "name": "minimap_player"},
            {"id": 2, "name": "minimap_enemy"},
        ],
        "annotations": [{"image_id": 1, "category_id": 9}],
    }

    assert classes_from_coco(document) == ("minimap_enemy", "minimap_player")
    assert resolve_classes(
        classes=["minimap_enemy", "minimap_player"], coco=document,
    ) == ("minimap_enemy", "minimap_player")
    with pytest.raises(ValueError, match="class order mismatch"):
        resolve_classes(classes=["minimap_player", "minimap_enemy"], coco=document)


def test_multiclass_resolution_requires_coco_categories() -> None:
    legacy = {"images": [], "annotations": []}
    with pytest.raises(ValueError, match="must declare categories"):
        resolve_classes(
            classes=["minimap_enemy", "minimap_player"], coco=legacy,
        )
    assert resolve_classes(classes=["minimap_enemy"], coco=legacy) == ("minimap_enemy",)


def test_decode_bounds_extreme_log_dimensions_like_android() -> None:
    raw = np.zeros((1, 84, 6), dtype=np.float32)
    raw[0, 0, 2:4] = [100.0, -100.0]
    decoded = decode_yolox(raw, 64, np=np)
    assert np.isfinite(decoded).all()
    assert decoded[0, 0, 2] == pytest.approx(np.exp(10.0) * 8.0, rel=1e-6)
    assert decoded[0, 0, 3] == pytest.approx(np.exp(-10.0) * 8.0, rel=1e-6)


def test_yolox_tensor_contract_is_canonical_for_multiclass_metadata() -> None:
    assert yolox_tensor_contract(
        320, ("minimap_enemy", "minimap_player"),
    ) == {
        "input": [1, 3, 320, 320],
        "output": [1, 2100, 7],
    }
    with pytest.raises(ValueError, match="divisible by 32"):
        yolox_tensor_contract(300, ("minimap_enemy",))


def test_class_aware_nms_keeps_overlapping_different_classes() -> None:
    detections = candidates_from_raw(
        _raw_two_class(), 64, 64, 64,
        confidence={"minimap_enemy": 0.5, "minimap_player": 0.5},
        nms_threshold=0.5,
        classes=("minimap_enemy", "minimap_player"),
        np=np,
    )

    assert detections.shape == (2, 6)
    assert [int(item) for item in detections[:, 5]] == [0, 1]
    assert detections[:, 4].tolist() == pytest.approx([0.9, 0.8])


def test_per_class_threshold_filtering_precedes_argmax_like_android() -> None:
    raw = np.zeros((1, 84, 7), dtype=np.float32)
    raw[0, 0, 2:4] = np.log(8.0)
    raw[0, 0, 4] = 1.0
    # Class 0 is the raw argmax but misses its threshold.  Android filters
    # each class first, so class 1 remains a valid player detection.
    raw[0, 0, 5:] = [0.70, 0.60]

    detections = candidates_from_raw(
        raw, 64, 64, 64,
        confidence={"minimap_enemy": 0.80, "minimap_player": 0.50},
        nms_threshold=0.5,
        classes=("minimap_enemy", "minimap_player"),
        np=np,
    )

    assert detections.shape == (1, 6)
    assert int(detections[0, 5]) == 1
    assert float(detections[0, 4]) == pytest.approx(0.60)


def test_class_aware_matching_rejects_same_geometry_with_wrong_class() -> None:
    pairs = _match_boxes_by_class(
        [[0.1, 0.1, 0.2, 0.2]], [[0.1, 0.1, 0.2, 0.2]],
        ["minimap_enemy"], ["minimap_player"], 0.5,
    )
    assert pairs == []


def test_native_serialization_preserves_minimap_player_kind() -> None:
    observation = Observation(MA_MINIMAP_PLAYER, 1, Rect(0.1, 0.2, 0.3, 0.4), 0.8, 5)
    cue = Cue(MA_MINIMAP_PLAYER, 1, 2, 5, 10)
    assert observation_dict(observation)["type"] == "minimap_player"
    assert observation_dict(observation)["source"] == "minimap"
    assert cue_dict(cue)["kind"] == "minimap_player"


def test_manifest_combiners_preserve_canonical_classes_and_categories(tmp_path: Path) -> None:
    video = tmp_path / "match.mp4"
    video.write_bytes(b"fixture")
    document = {
        "schema_version": 1,
        "category": "minimap_enemy",
        "classes": ["minimap_enemy", "minimap_player"],
        "roi": [0.0, 0.0, 1.0, 1.0],
        "matches": [{
            "id": "match-1", "video": video.name, "split": "train",
            "frames": [{"at_ms": 1, "boxes": [[0.1, 0.1, 0.1, 0.1]],
                        "categories": ["minimap_player"]}],
        }],
    }
    first = tmp_path / "first.json"
    second = tmp_path / "second.json"
    first.write_text(json.dumps(document), encoding="utf-8")
    second_document = json.loads(json.dumps(document))
    second_document["matches"][0]["frames"][0]["at_ms"] = 2
    second.write_text(json.dumps(second_document), encoding="utf-8")

    combined = tmp_path / "combined.json"
    combine([first, second], combined)
    combined_document = json.loads(combined.read_text(encoding="utf-8"))
    assert combined_document["classes"] == document["classes"]
    assert combined_document["matches"][0]["frames"][0]["categories"] == [
        "minimap_player"
    ]

    merged = tmp_path / "merged.json"
    # Merge requires distinct recording ids; use one source to verify export.
    merge([first], merged)
    merged_document = json.loads(merged.read_text(encoding="utf-8"))
    assert merged_document["classes"] == document["classes"]
    assert merged_document["matches"][0]["frames"][0]["categories"] == [
        "minimap_player"
    ]


@pytest.mark.parametrize("combiner", [combine, merge])
def test_multiclass_manifest_combiners_reject_missing_categories(
    tmp_path: Path, combiner,
) -> None:
    video = tmp_path / "match.mp4"
    video.write_bytes(b"fixture")
    document = {
        "schema_version": 1,
        "category": "minimap_enemy",
        "classes": ["minimap_enemy", "minimap_player"],
        "roi": [0.0, 0.0, 1.0, 1.0],
        "matches": [{
            "id": "match-1", "video": video.name, "split": "train",
            "frames": [{"at_ms": 1, "boxes": [[0.1, 0.1, 0.1, 0.1]]}],
        }],
    }
    source = tmp_path / f"{combiner.__name__}-source.json"
    source.write_text(json.dumps(document), encoding="utf-8")
    with pytest.raises(ValueError, match="categories are required"):
        combiner([source], tmp_path / f"{combiner.__name__}-output.json")


def test_multiclass_prediction_rows_reject_missing_category() -> None:
    with pytest.raises(ValueError, match="category is required"):
        _prediction_rows(
            [[0.1, 0.1, 0.2, 0.2]], "predictions",
            ("minimap_enemy", "minimap_player"), "minimap_enemy",
        )
    boxes, categories = _prediction_rows(
        [[0.1, 0.1, 0.2, 0.2]], "predictions",
        ("minimap_enemy",), "minimap_enemy",
    )
    assert boxes == [[0.1, 0.1, 0.2, 0.2]]
    assert categories == ["minimap_enemy"]


def test_multiclass_external_predictions_reject_missing_category(tmp_path: Path) -> None:
    path = tmp_path / "predictions.jsonl"
    path.write_text(json.dumps({
        "timestamp_ms": 1,
        "detections": [{"bbox_norm": [0.1, 0.1, 0.2, 0.2]}],
    }) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="category is required"):
        _external_predictions(
            path, "minimap_enemy", ("minimap_enemy", "minimap_player"),
        )


def test_per_class_thresholds_require_complete_canonical_mapping() -> None:
    classes = ("minimap_enemy", "minimap_player")
    assert confidence_thresholds(
        {"minimap_enemy": 0.6, "minimap_player": 0.8}, classes,
    ) == {"minimap_enemy": 0.6, "minimap_player": 0.8}
    with pytest.raises(ValueError, match="keys must equal classes"):
        confidence_thresholds({"minimap_enemy": 0.6}, classes)
    assert confidence_from_metadata({
        "classes": list(classes),
        "postprocess": {"confidence_by_class": {
            "minimap_enemy": 0.61, "minimap_player": 0.73,
        }},
    }, classes) == {"minimap_enemy": 0.61, "minimap_player": 0.73}


def test_single_class_raw_shape_remains_compatible(tmp_path: Path) -> None:
    path = tmp_path / "instances_val2017.json"
    path.write_text(json.dumps({"images": [], "annotations": []}), encoding="utf-8")
    assert resolve_classes(coco=json.loads(path.read_text())) == ("minimap_enemy",)
    raw = np.zeros((1, 84, 6), dtype=np.float32)
    raw[0, 0, 4:6] = 1.0
    detections = candidates_from_raw(raw, 64, 64, 64, 0.1, 0.5, np=np)
    assert detections.shape == (1, 6)
    assert int(detections[0, 5]) == 0


def test_review_finalization_keeps_per_box_categories(tmp_path: Path) -> None:
    manifest = tmp_path / "review-manifest.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "classes": ["minimap_enemy", "minimap_player"],
        "matches": [{
            "id": "match-1",
            "video": "match.mp4",
            "split": "train",
            "samples": [{
                "at_ms": 1000,
                "review_status": "corrected",
                "reviewed_boxes": [[0.1, 0.1, 0.1, 0.1], [0.4, 0.2, 0.1, 0.1]],
                "reviewed_categories": ["minimap_enemy", "minimap_player"],
            }],
        }],
    }), encoding="utf-8")
    output = tmp_path / "detections.json"

    finalize(manifest, output)
    frame = json.loads(output.read_text(encoding="utf-8"))["matches"][0]["frames"][0]
    assert frame["categories"] == ["minimap_enemy", "minimap_player"]


def test_review_finalization_rejects_ambiguous_multiclass_boxes(tmp_path: Path) -> None:
    manifest = tmp_path / "review-manifest.json"
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_enemy",
        "classes": ["minimap_enemy", "minimap_player"],
        "matches": [{
            "id": "match-1",
            "video": "match.mp4",
            "split": "train",
            "samples": [{
                "at_ms": 1000,
                "review_status": "corrected",
                "reviewed_boxes": [[0.1, 0.1, 0.1, 0.1]],
            }],
        }],
    }), encoding="utf-8")

    with pytest.raises(ValueError, match="categories are required"):
        finalize(manifest, tmp_path / "detections.json")


def test_review_finalization_resolves_source_video_path_from_ancestor(
    tmp_path: Path,
) -> None:
    repository = tmp_path / "repository"
    video = repository / "video" / "player.mp4"
    video.parent.mkdir(parents=True)
    video.touch()
    manifest = repository / "data" / "private" / "review" / "review-manifest.json"
    manifest.parent.mkdir(parents=True)
    manifest.write_text(json.dumps({
        "schema_version": 1,
        "kind": "minimap_player",
        "classes": ["minimap_player"],
        "matches": [{
            "id": "video-player",
            "source_video_path": "video/player.mp4",
            "source_video_sha256": "a" * 64,
            "split": "train",
            "samples": [{
                "at_ms": 1000,
                "review_status": "corrected",
                "reviewed_boxes": [[0.1, 0.1, 0.1, 0.1]],
                "reviewed_categories": ["minimap_player"],
            }],
        }],
    }), encoding="utf-8")

    output = tmp_path / "detections.json"
    finalize(manifest, output)

    exported = json.loads(output.read_text(encoding="utf-8"))
    assert exported["matches"][0]["video"] == str(video.resolve())
    assert exported["matches"][0]["video_sha256"] == "a" * 64
