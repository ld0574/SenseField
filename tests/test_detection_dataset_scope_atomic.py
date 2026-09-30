from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from PIL import Image

from mapassist import detection_dataset
from mapassist.coco_dataset_audit import audit_coco_dataset

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training"))
from train_yolox_minimap import _assert_roi_boundaries_clear  # noqa: E402


def _manifest(path: Path, video: Path, *, scope: object = None,
              frames: list[dict] | None = None) -> None:
    document: dict = {
        "schema_version": 1,
        "orientation": {"display_rotation_degrees": 0},
        "matches": [{
            "id": "match-1",
            "video": video.name,
            "split": "train",
            "frames": frames or [
                {"at_ms": 0, "boxes": []},
            ],
        }],
    }
    if scope is not None:
        document["dataset_scope"] = scope
    path.write_text(json.dumps(document), encoding="utf-8")


def _fake_extract(_video: Path, _at_ms: int, output: Path,
                  *, display_rotation: int | None = None) -> tuple[int, int]:
    del display_rotation
    Image.new("RGB", (8, 6), (20, 30, 40)).save(output)
    return 8, 6


def _write_coco_split(root: Path, split: str, scope: object) -> None:
    image_dir = root / f"{split}2017"
    image_dir.mkdir(parents=True, exist_ok=True)
    (image_dir / "frame.png").write_bytes(b"frame")
    annotations = root / "annotations"
    annotations.mkdir(exist_ok=True)
    document: dict = {
        "images": [{"id": 1, "file_name": "frame.png", "width": 8, "height": 6}],
        "annotations": [],
        "categories": [{"id": 1, "name": "minimap_enemy"}],
        "info": {"dataset_scope": scope},
    }
    (annotations / f"instances_{split}2017.json").write_text(
        json.dumps(document), encoding="utf-8"
    )


def test_partial_scope_is_rejected_by_export_audit_and_train(tmp_path: Path) -> None:
    video = tmp_path / "fixture.mp4"
    video.write_bytes(b"fixture")
    manifest = tmp_path / "manifest.json"
    _manifest(manifest, video, scope={"label_semantics": "partial"})

    with pytest.raises(ValueError, match="training_truth must be a boolean"):
        detection_dataset.export(manifest, tmp_path / "export")

    dataset = tmp_path / "coco"
    _write_coco_split(dataset, "train", {"label_semantics": "partial"})
    report = audit_coco_dataset(dataset)
    assert any(
        "train: train COCO.info.dataset_scope.training_truth must be a boolean" in item
        for item in report["training_blockers"]
    )

    train_dataset = tmp_path / "train-coco"
    for split in ("train", "val"):
        _write_coco_split(
            train_dataset, split, {"label_semantics": "partial"}
        )
    with pytest.raises(ValueError, match="training_truth must be a boolean"):
        _assert_roi_boundaries_clear(train_dataset)


def test_explicit_null_scope_is_rejected(tmp_path: Path) -> None:
    video = tmp_path / "fixture.mp4"
    video.write_bytes(b"fixture")
    manifest = tmp_path / "manifest.json"
    _manifest(manifest, video)
    document = json.loads(manifest.read_text(encoding="utf-8"))
    document["dataset_scope"] = None
    manifest.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(ValueError, match="dataset_scope must be an object"):
        detection_dataset.export(manifest, tmp_path / "export")

    dataset = tmp_path / "coco"
    _write_coco_split(dataset, "train", None)
    report = audit_coco_dataset(dataset)
    assert any("dataset_scope must be an object" in item
               for item in report["training_blockers"])


def test_failed_export_preserves_existing_output(monkeypatch: pytest.MonkeyPatch,
                                                 tmp_path: Path) -> None:
    monkeypatch.setattr(detection_dataset, "extract", _fake_extract)
    video = tmp_path / "fixture.mp4"
    video.write_bytes(b"fixture")
    manifest = tmp_path / "manifest.json"
    _manifest(manifest, video, frames=[
        {"at_ms": 0, "boxes": []},
        {"at_ms": 1, "boxes": []},
    ])
    output = tmp_path / "dataset"
    output.mkdir()
    (output / "old-marker.txt").write_text("old", encoding="utf-8")

    calls = 0

    def fail_on_second(*args: object, **kwargs: object) -> tuple[int, int]:
        nonlocal calls
        calls += 1
        result = _fake_extract(*args, **kwargs)  # type: ignore[arg-type]
        if calls == 2:
            raise RuntimeError("simulated extraction failure")
        return result

    monkeypatch.setattr(detection_dataset, "extract", fail_on_second)
    with pytest.raises(RuntimeError, match="simulated extraction failure"):
        detection_dataset.export(manifest, output)

    assert (output / "old-marker.txt").read_text(encoding="utf-8") == "old"
    assert not (output / "annotations").exists()
    assert not list(tmp_path.glob(".dataset.staging-*"))
    assert not list(tmp_path.glob(".dataset.backup-*"))


def test_successful_export_replaces_existing_output(monkeypatch: pytest.MonkeyPatch,
                                                    tmp_path: Path) -> None:
    monkeypatch.setattr(detection_dataset, "extract", _fake_extract)
    video = tmp_path / "fixture.mp4"
    video.write_bytes(b"fixture")
    manifest = tmp_path / "manifest.json"
    _manifest(manifest, video)
    output = tmp_path / "dataset"
    output.mkdir()
    (output / "old-marker.txt").write_text("old", encoding="utf-8")

    summary = detection_dataset.export(manifest, output)

    assert summary["train"] == {"images": 1, "boxes": 0, "negative_images": 1}
    assert not (output / "old-marker.txt").exists()
    assert (output / "train2017/match-1_000000000.png").is_file()
    assert (output / "annotations/instances_train2017.json").is_file()
    assert not list(tmp_path.glob(".dataset.staging-*"))
    assert not list(tmp_path.glob(".dataset.backup-*"))


def test_export_rejects_manifest_overlap_and_symlink_output(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path,
) -> None:
    monkeypatch.setattr(detection_dataset, "extract", _fake_extract)
    video = tmp_path / "fixture.mp4"
    video.write_bytes(b"fixture")

    container = tmp_path / "container"
    container.mkdir()
    manifest = container / "manifest.json"
    _manifest(manifest, video)
    (container / "old-marker.txt").write_text("old", encoding="utf-8")
    with pytest.raises(ValueError, match="overlap"):
        detection_dataset.export(manifest, container)
    assert (container / "old-marker.txt").read_text(encoding="utf-8") == "old"

    target = tmp_path / "target"
    target.mkdir()
    (target / "old-marker.txt").write_text("old", encoding="utf-8")
    symlink = tmp_path / "symlink-output"
    symlink.symlink_to(target, target_is_directory=True)
    with pytest.raises(ValueError, match="symlink"):
        detection_dataset.export(tmp_path / "missing.json", symlink)
    assert (target / "old-marker.txt").read_text(encoding="utf-8") == "old"
