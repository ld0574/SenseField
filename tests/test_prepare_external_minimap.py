from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.prepare_external_minimap import (
    prepare_external_minimap,
    prepare_external_minimap_enemy,
)


def _write_roboflow_split(root: Path, split: str, category_name: str = "7") -> None:
    directory = root / split
    directory.mkdir(parents=True)
    (directory / "image.jpg").write_bytes(split.encode())
    (directory / "_annotations.coco.json").write_text(json.dumps({
        "images": [{"id": 1, "file_name": "image.jpg", "width": 640, "height": 640}],
        "annotations": [{
            "id": 1, "image_id": 1, "category_id": 8, "bbox": [1, 2, 3, 4],
        }],
        "categories": [
            {"id": 0, "name": "heroes"},
            {"id": 8, "name": category_name},
        ],
        "licenses": [{"id": 1, "name": "CC BY 4.0"}],
    }), encoding="utf-8")


def test_prepare_collapses_numeric_classes_and_preserves_geometry(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_roboflow_split(source, "train")
    _write_roboflow_split(source, "valid")
    output = tmp_path / "output"

    provenance = prepare_external_minimap(source, output)
    result = json.loads(
        (output / "annotations/instances_train2017.json").read_text(encoding="utf-8")
    )

    assert result["categories"] == [
        {"id": 1, "name": "minimap_hero", "supercategory": "none"}
    ]
    assert result["annotations"][0]["category_id"] == 1
    assert result["annotations"][0]["bbox"] == [1, 2, 3, 4]
    assert (output / "train2017/image.jpg").read_bytes() == b"train"
    assert provenance["transformation"]["referenced_numeric_classes"] == 1
    assert provenance["source_images"] == 2
    assert len(provenance["source_image_manifest_sha256"]) == 64
    assert len(provenance["splits"]["train"]["source_image_manifest_sha256"]) == 64


def test_prepare_rejects_non_numeric_referenced_classes(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_roboflow_split(source, "train", "enemy")
    _write_roboflow_split(source, "valid", "enemy")

    with pytest.raises(ValueError, match="numeric hero identities"):
        prepare_external_minimap(source, tmp_path / "output")


def test_prepare_rejects_duplicate_annotation_ids(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_roboflow_split(source, "train")
    _write_roboflow_split(source, "valid")
    annotation_path = source / "train/_annotations.coco.json"
    document = json.loads(annotation_path.read_text(encoding="utf-8"))
    document["annotations"].append(dict(document["annotations"][0]))
    annotation_path.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(ValueError, match="Duplicate annotation IDs"):
        prepare_external_minimap(source, tmp_path / "output")


def test_prepare_rejects_traversal_without_leaving_partial_output(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_roboflow_split(source, "train")
    _write_roboflow_split(source, "valid")
    annotation_path = source / "train/_annotations.coco.json"
    document = json.loads(annotation_path.read_text(encoding="utf-8"))
    document["images"][0]["file_name"] = "../../outside.jpg"
    annotation_path.write_text(json.dumps(document), encoding="utf-8")
    output = tmp_path / "output"

    with pytest.raises(ValueError, match="Unsafe COCO image file_name"):
        prepare_external_minimap(source, output)

    assert not output.exists()
    assert not list(tmp_path.glob(".output.tmp-*"))


def test_prepare_provenance_uses_relative_paths_and_rejects_overlap(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_roboflow_split(source, "train")
    _write_roboflow_split(source, "valid")
    annotation_path = source / "train/_annotations.coco.json"
    document = json.loads(annotation_path.read_text(encoding="utf-8"))
    document["info"] = {"description": str(tmp_path)}
    annotation_path.write_text(json.dumps(document), encoding="utf-8")
    output = tmp_path / "output"

    provenance = prepare_external_minimap(source, output)

    serialized = json.dumps(provenance)
    assert str(tmp_path) not in serialized
    assert provenance["source"]["root"] is None
    assert provenance["source_image_manifest_hash_algorithm"]
    assert len(provenance["source_image_manifest_sha256"]) == 64
    assert provenance["splits"]["train"]["output_annotation"] == (
        "annotations/instances_train2017.json"
    )
    converted = json.loads(
        (output / "annotations/instances_train2017.json").read_text(encoding="utf-8")
    )
    assert str(tmp_path) not in json.dumps(converted)
    with pytest.raises(ValueError, match="must not overlap"):
        prepare_external_minimap(source, source / "generated")


def test_prepare_source_image_manifest_is_stable_and_content_sensitive(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    _write_roboflow_split(source, "train")
    _write_roboflow_split(source, "valid")
    source_copy = tmp_path / "source-copy"
    shutil.copytree(source, source_copy)

    first = prepare_external_minimap(source, tmp_path / "output-one")
    same_content = prepare_external_minimap(source_copy, tmp_path / "output-two")
    assert first["source_image_manifest_sha256"] == same_content[
        "source_image_manifest_sha256"
    ]

    (source_copy / "train/image.jpg").write_bytes(b"changed image")
    changed = prepare_external_minimap(source_copy, tmp_path / "output-three")
    assert changed["source_image_manifest_sha256"] != first[
        "source_image_manifest_sha256"
    ]


def _write_ring_split(root: Path, split: str, ambiguous: bool = False) -> None:
    directory = root / split
    directory.mkdir(parents=True)
    image = Image.new("RGB", (128, 64), "#25364a")
    draw = ImageDraw.Draw(image)
    draw.rectangle((8, 8, 55, 55), outline="#f02020", width=8)
    if ambiguous:
        draw.rectangle((72, 8, 119, 55), fill="#111111")
        draw.line((72, 8, 119, 8), fill="#f02020", width=8)
        draw.line((72, 55, 119, 55), fill="#f02020", width=8)
        draw.line((72, 8, 72, 55), fill="#20dd50", width=8)
        draw.line((119, 8, 119, 55), fill="#20dd50", width=8)
    else:
        draw.rectangle((72, 8, 119, 55), outline="#20dd50", width=8)
    image.save(directory / "image.png")
    (directory / "_annotations.coco.json").write_text(json.dumps({
        "images": [{"id": 1, "file_name": "image.png", "width": 128, "height": 64}],
        "annotations": [
            {"id": 1, "image_id": 1, "category_id": 1, "bbox": [8, 8, 48, 48],
             "area": 2304, "iscrowd": 0},
            {"id": 2, "image_id": 1, "category_id": 2, "bbox": [72, 8, 48, 48],
             "area": 2304, "iscrowd": 0},
        ],
        "categories": [{"id": 1, "name": "0"}, {"id": 2, "name": "1"}],
    }), encoding="utf-8")


def test_prepare_enemy_keeps_only_red_and_preserves_confident_negative(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_ring_split(source, "train")
    _write_ring_split(source, "valid")

    provenance = prepare_external_minimap_enemy(source, tmp_path / "output")
    result = json.loads(
        (tmp_path / "output/annotations/instances_train2017.json").read_text()
    )

    assert len(result["images"]) == 1
    assert [item["id"] for item in result["annotations"]] == [1]
    assert result["annotations"][0]["category_id"] == 1
    assert provenance["splits"]["train"]["enemy_boxes"] == 1
    assert provenance["splits"]["train"]["confident_icon_colors"] == {
        "red": 1, "green": 1
    }
    assert len(provenance["splits"]["train"]["source_image_manifest_sha256"]) == 64
    assert len(provenance["source_image_manifest_sha256"]) == 64


def test_prepare_enemy_excludes_whole_image_with_ambiguous_icon(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_ring_split(source, "train", ambiguous=True)
    _write_ring_split(source, "valid", ambiguous=True)

    provenance = prepare_external_minimap_enemy(source, tmp_path / "output")

    assert provenance["splits"]["train"]["kept_images"] == 0
    assert provenance["splits"]["train"]["excluded_images_with_ambiguous_icons"] == 1
    assert provenance["splits"]["train"]["confident_icon_colors"] == {
        "red": 1
    }
