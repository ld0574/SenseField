"""Prepare a Roboflow COCO minimap dataset for generic hero pretraining.

All referenced numeric hero-identity classes are collapsed to one
``minimap_hero`` class.  The result is for representation pretraining only; it
does not assign enemy/friendly semantics and never becomes evaluation truth.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image

from .image_manifest import IMAGE_MANIFEST_HASH_ALGORITHM, image_manifest_sha256


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _duplicate_ids(values: list[int]) -> list[int]:
    return sorted(identifier for identifier, count in Counter(values).items() if count > 1)


def _link_or_copy(source: Path, destination: Path) -> str:
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(source, destination)
        return "hardlink"
    except OSError:
        shutil.copy2(source, destination)
        return "copy"


def _image_path(directory: Path, file_name: Any) -> Path:
    if not isinstance(file_name, str) or not file_name:
        raise ValueError("COCO image file_name must be a non-empty relative path")
    relative = Path(file_name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"Unsafe COCO image file_name: {file_name!r}")
    path = (directory / relative).resolve()
    try:
        path.relative_to(directory.resolve())
    except ValueError as exc:
        raise ValueError(f"COCO image path escapes its split directory: {file_name!r}") from exc
    return path


def _assert_within(root: Path, path: Path, description: str) -> None:
    try:
        path.resolve().relative_to(root.resolve())
    except ValueError as exc:
        raise ValueError(f"{description} escapes the source directory: {path}") from exc


def _prepare_in_staging(source: Path, output: Path, prepare: Any, *args: Any) -> dict[str, Any]:
    source, output = source.resolve(), output.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("Source and output directories must not overlap")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError(f"Output directory is not empty: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.tmp-", dir=output.parent))
    try:
        result = prepare(source, staging, *args)
        if output.exists():
            output.rmdir()
        os.replace(staging, output)
        return result
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def _ring_color_scores(image: Image.Image, bbox: list[float]) -> dict[str, float]:
    """Measure vivid red, green, and blue pixels near an annotated icon edge."""
    import numpy as np

    left, top, width, height = (float(value) for value in bbox)
    x0 = max(0, int(round(left)))
    y0 = max(0, int(round(top)))
    x1 = min(image.width, int(round(left + width)))
    y1 = min(image.height, int(round(top + height)))
    if x1 <= x0 or y1 <= y0:
        return {"red": 0.0, "green": 0.0, "blue": 0.0}
    rgb = np.asarray(image.crop((x0, y0, x1, y1)).convert("RGB"), dtype=np.float32) / 255.0
    red, green, blue = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    maximum = rgb.max(axis=2)
    minimum = rgb.min(axis=2)
    delta = maximum - minimum
    saturation = np.divide(
        delta, maximum, out=np.zeros_like(delta), where=maximum > 0
    )
    hue = np.zeros_like(delta)
    nonzero = delta > 0
    red_max = nonzero & (maximum == red)
    green_max = nonzero & (maximum == green) & ~red_max
    blue_max = nonzero & ~(red_max | green_max)
    hue[red_max] = np.mod((green[red_max] - blue[red_max]) / delta[red_max], 6.0)
    hue[green_max] = (blue[green_max] - red[green_max]) / delta[green_max] + 2.0
    hue[blue_max] = (red[blue_max] - green[blue_max]) / delta[blue_max] + 4.0
    hue /= 6.0

    crop_height, crop_width = delta.shape
    band_x = max(2, int(crop_width * 0.25))
    band_y = max(2, int(crop_height * 0.25))
    yy, xx = np.ogrid[:crop_height, :crop_width]
    border = ((xx < band_x) | (xx >= crop_width - band_x) |
              (yy < band_y) | (yy >= crop_height - band_y))
    vivid = border & (saturation >= 90 / 255) & (maximum >= 65 / 255)
    denominator = max(1, int(border.sum()))
    return {
        "red": float((vivid & ((hue <= 12 / 180) | (hue >= 170 / 180))).sum()
                     / denominator),
        "green": float((vivid & (hue >= 35 / 180) & (hue <= 85 / 180)).sum()
                       / denominator),
        "blue": float((vivid & (hue >= 86 / 180) & (hue <= 135 / 180)).sum()
                      / denominator),
    }


def _ring_color_label(scores: dict[str, float], minimum_score: float,
                      dominance_ratio: float) -> str | None:
    ordered = sorted(scores.items(), key=lambda item: item[1], reverse=True)
    if (ordered[0][1] < minimum_score or
            ordered[0][1] < ordered[1][1] * dominance_ratio):
        return None
    return ordered[0][0]


def _prepare_external_minimap_into(source: Path, output: Path) -> dict[str, Any]:
    source = source.resolve()
    output.mkdir(parents=True, exist_ok=True)
    annotations_dir = output / "annotations"
    annotations_dir.mkdir()
    provenance_splits: dict[str, Any] = {}
    link_modes: Counter[str] = Counter()
    all_referenced_names: set[str] = set()
    all_image_manifest_entries: list[tuple[str, str | None]] = []

    for source_name, output_name in (("train", "train"), ("valid", "val")):
        source_dir = source / source_name
        annotation_path = source_dir / "_annotations.coco.json"
        _assert_within(source, source_dir, "Split directory")
        _assert_within(source, annotation_path, "Annotation path")
        if not annotation_path.is_file():
            raise ValueError(f"Missing Roboflow COCO annotations: {annotation_path}")
        document = json.loads(annotation_path.read_text(encoding="utf-8"))
        images = document.get("images")
        annotations = document.get("annotations")
        categories = document.get("categories")
        if not isinstance(images, list) or not isinstance(annotations, list) or not isinstance(categories, list):
            raise ValueError(f"Invalid COCO document: {annotation_path}")
        image_ids = [int(item["id"]) for item in images]
        image_id_set = set(image_ids)
        duplicate_image_ids = _duplicate_ids(image_ids)
        if duplicate_image_ids:
            raise ValueError(f"Duplicate image IDs {duplicate_image_ids} in {annotation_path}")
        annotation_ids = [int(item["id"]) for item in annotations]
        duplicate_annotation_ids = _duplicate_ids(annotation_ids)
        if duplicate_annotation_ids:
            raise ValueError(
                f"Duplicate annotation IDs {duplicate_annotation_ids} in {annotation_path}"
            )
        if any(int(item["image_id"]) not in image_id_set for item in annotations):
            raise ValueError(f"Annotation references unknown image IDs in {annotation_path}")
        category_names = {int(item["id"]): str(item["name"]) for item in categories}
        duplicate_category_ids = _duplicate_ids([int(item["id"]) for item in categories])
        if duplicate_category_ids:
            raise ValueError(
                f"Duplicate category IDs {duplicate_category_ids} in {annotation_path}"
            )
        referenced = {int(item["category_id"]) for item in annotations}
        unknown = referenced - set(category_names)
        if unknown:
            raise ValueError(f"Annotations reference unknown categories: {sorted(unknown)}")
        referenced_names = {category_names[identifier] for identifier in referenced}
        if not referenced_names or any(not name.isdigit() for name in referenced_names):
            raise ValueError(
                "Expected referenced Roboflow classes to be numeric hero identities"
            )
        all_referenced_names.update(referenced_names)

        output_dir = output / f"{output_name}2017"
        output_dir.mkdir()
        output_names: set[str] = set()
        split_image_manifest_entries: list[tuple[str, str | None]] = []
        for image in images:
            file_name = str(image["file_name"])
            if file_name in output_names:
                raise ValueError(f"Duplicate image file_name {file_name!r} in {annotation_path}")
            output_names.add(file_name)
            image_source = _image_path(source_dir, file_name)
            if not image_source.is_file():
                raise ValueError(f"Missing source image: {image_source}")
            image_sha256 = _sha256(image_source)
            split_image_manifest_entries.append((file_name, image_sha256))
            all_image_manifest_entries.append(
                (f"{source_name}/{file_name}", image_sha256)
            )
            link_modes[_link_or_copy(image_source, output_dir / file_name)] += 1

        collapsed = {
            "info": {
                "description": (
                    "Roboflow Honor of Kings Minimap v1; 128 numeric hero identity "
                    "classes collapsed to minimap_hero for representation pretraining"
                ),
            },
            "licenses": document.get("licenses", []),
            "images": images,
            "annotations": [
                {**item, "category_id": 1} for item in annotations
            ],
            "categories": [{"id": 1, "name": "minimap_hero", "supercategory": "none"}],
        }
        destination_annotation = (
            annotations_dir / f"instances_{output_name}2017.json"
        )
        destination_annotation.write_text(
            json.dumps(collapsed, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        provenance_splits[output_name] = {
            "source_split": source_name,
            "images": len(images),
            "annotations": len(annotations),
            "source_annotation": f"{source_name}/_annotations.coco.json",
            "source_annotation_sha256": _sha256(annotation_path),
            "source_images": len(images),
            "source_image_manifest_sha256": image_manifest_sha256(
                split_image_manifest_entries
            ),
            "output_annotation": f"annotations/instances_{output_name}2017.json",
            "output_annotation_sha256": _sha256(destination_annotation),
        }

    readmes = []
    for name in ("README.dataset.txt", "README.roboflow.txt"):
        path = source / name
        if path.is_file():
            readmes.append({"path": name, "sha256": _sha256(path)})
    provenance = {
        "schema_version": 1,
        "purpose": "generic_minimap_hero_representation_pretraining_only",
        "warning": (
            "All hero identities and teams are collapsed. Do not use this dataset as "
            "minimap_enemy truth or as independent evaluation."
        ),
        "source": {
            "dataset": "Honor of Kings Minimap v1 1.0",
            "url": "https://universe.roboflow.com/zyhe/honor-of-kings-minimap/dataset/1",
            "license_as_published": "CC BY 4.0",
            "root": None,
            "readmes": readmes,
        },
        "source_image_manifest_sha256": image_manifest_sha256(all_image_manifest_entries),
        "source_image_manifest_hash_algorithm": IMAGE_MANIFEST_HASH_ALGORITHM,
        "source_images": len(all_image_manifest_entries),
        "transformation": {
            "referenced_numeric_classes": len(all_referenced_names),
            "class_names": sorted(all_referenced_names, key=int),
            "output_class": {"id": 1, "name": "minimap_hero"},
            "geometry_changed": False,
            "image_storage": dict(link_modes),
        },
        "splits": provenance_splits,
    }
    provenance_path = output / "provenance.json"
    provenance_path.write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return provenance


def _prepare_external_minimap_enemy_into(
    source: Path,
    output: Path,
    minimum_score: float = 0.04,
    dominance_ratio: float = 1.5,
) -> dict[str, Any]:
    """Build conservative red-ring enemy pseudo labels from the public dataset."""
    if not 0 < minimum_score <= 1:
        raise ValueError("minimum_score must be between 0 and 1")
    if dominance_ratio <= 1:
        raise ValueError("dominance_ratio must be greater than 1")
    source = source.resolve()
    output.mkdir(parents=True, exist_ok=True)
    annotations_dir = output / "annotations"
    annotations_dir.mkdir()
    provenance_splits: dict[str, Any] = {}
    link_modes: Counter[str] = Counter()
    all_image_manifest_entries: list[tuple[str, str | None]] = []

    for source_name, output_name in (("train", "train"), ("valid", "val")):
        source_dir = source / source_name
        annotation_path = source_dir / "_annotations.coco.json"
        _assert_within(source, source_dir, "Split directory")
        _assert_within(source, annotation_path, "Annotation path")
        if not annotation_path.is_file():
            raise ValueError(f"Missing Roboflow COCO annotations: {annotation_path}")
        document = json.loads(annotation_path.read_text(encoding="utf-8"))
        images = document.get("images")
        annotations = document.get("annotations")
        categories = document.get("categories")
        if (not isinstance(images, list) or not isinstance(annotations, list) or
                not isinstance(categories, list)):
            raise ValueError(f"Invalid COCO document: {annotation_path}")
        image_ids = [int(image["id"]) for image in images]
        duplicate_image_ids = _duplicate_ids(image_ids)
        if duplicate_image_ids:
            raise ValueError(f"Duplicate image IDs {duplicate_image_ids} in {annotation_path}")
        annotation_ids = [int(item["id"]) for item in annotations]
        duplicate_annotation_ids = _duplicate_ids(annotation_ids)
        if duplicate_annotation_ids:
            raise ValueError(
                f"Duplicate annotation IDs {duplicate_annotation_ids} in {annotation_path}"
            )
        category_ids = [int(item["id"]) for item in categories]
        duplicate_category_ids = _duplicate_ids(category_ids)
        if duplicate_category_ids:
            raise ValueError(
                f"Duplicate category IDs {duplicate_category_ids} in {annotation_path}"
            )
        annotations_by_image: dict[int, list[dict[str, Any]]] = {
            image_id: [] for image_id in image_ids
        }
        known_category_ids = set(category_ids)
        if any(int(item["category_id"]) not in known_category_ids for item in annotations):
            raise ValueError(f"Annotation references unknown category IDs in {annotation_path}")
        for annotation in annotations:
            image_id = int(annotation["image_id"])
            if image_id not in annotations_by_image:
                raise ValueError(f"Annotation references unknown image ID {image_id}")
            annotations_by_image[image_id].append(annotation)

        kept_images: list[dict[str, Any]] = []
        kept_annotations: list[dict[str, Any]] = []
        excluded_ambiguous = excluded_no_annotations = positive_images = negative_images = 0
        color_counts: Counter[str] = Counter()
        output_dir = output / f"{output_name}2017"
        output_dir.mkdir()
        split_image_manifest_entries: list[tuple[str, str | None]] = []
        for image_info in images:
            image_id = int(image_info["id"])
            file_name = str(image_info["file_name"])
            image_source = _image_path(source_dir, file_name)
            if not image_source.is_file():
                raise ValueError(f"Missing source image: {image_source}")
            image_sha256 = _sha256(image_source)
            split_image_manifest_entries.append((file_name, image_sha256))
            all_image_manifest_entries.append(
                (f"{source_name}/{file_name}", image_sha256)
            )
            with Image.open(image_source) as image:
                labeled = []
                for annotation in annotations_by_image[image_id]:
                    scores = _ring_color_scores(image, annotation["bbox"])
                    label = _ring_color_label(scores, minimum_score, dominance_ratio)
                    labeled.append((annotation, label))
            for _, label in labeled:
                if label is not None:
                    color_counts[label] += 1
            if not labeled:
                excluded_no_annotations += 1
                continue
            if any(label is None for _, label in labeled):
                excluded_ambiguous += 1
                continue
            red_annotations = [
                {**annotation, "category_id": 1}
                for annotation, label in labeled if label == "red"
            ]
            kept_images.append(image_info)
            kept_annotations.extend(red_annotations)
            if red_annotations:
                positive_images += 1
            else:
                negative_images += 1
            link_modes[_link_or_copy(image_source, output_dir / file_name)] += 1

        converted = {
            "info": {
                "description": (
                    "Roboflow Honor of Kings Minimap v1; conservative red-ring "
                    "minimap_enemy pseudo labels for development pretraining"
                ),
            },
            "licenses": document.get("licenses", []),
            "images": kept_images,
            "annotations": kept_annotations,
            "categories": [{"id": 1, "name": "minimap_enemy", "supercategory": "none"}],
        }
        destination_annotation = annotations_dir / f"instances_{output_name}2017.json"
        destination_annotation.write_text(
            json.dumps(converted, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        provenance_splits[output_name] = {
            "source_split": source_name,
            "source_images": len(images),
            "source_annotations": len(annotations),
            "kept_images": len(kept_images),
            "excluded_images_with_ambiguous_icons": excluded_ambiguous,
            "excluded_images_without_annotations": excluded_no_annotations,
            "positive_images": positive_images,
            "negative_images": negative_images,
            "enemy_boxes": len(kept_annotations),
            "confident_icon_colors": dict(color_counts),
            "source_annotation": f"{source_name}/_annotations.coco.json",
            "source_annotation_sha256": _sha256(annotation_path),
            "source_image_manifest_sha256": image_manifest_sha256(
                split_image_manifest_entries
            ),
            "output_annotation": f"annotations/instances_{output_name}2017.json",
            "output_annotation_sha256": _sha256(destination_annotation),
        }

    provenance = {
        "schema_version": 1,
        "purpose": "red_ring_minimap_enemy_pseudo_label_pretraining_only",
        "warning": (
            "Red ring equals enemy is a pixel-derived heuristic supported by development "
            "recordings, not publisher-provided team metadata. Do not use as evaluation truth."
        ),
        "source": {
            "dataset": "Honor of Kings Minimap v1 1.0",
            "url": "https://universe.roboflow.com/zyhe/honor-of-kings-minimap/dataset/1",
            "license_as_published": "CC BY 4.0",
            "root": None,
        },
        "source_image_manifest_sha256": image_manifest_sha256(all_image_manifest_entries),
        "source_image_manifest_hash_algorithm": IMAGE_MANIFEST_HASH_ALGORITHM,
        "source_images": len(all_image_manifest_entries),
        "transformation": {
            "output_class": {"id": 1, "name": "minimap_enemy"},
            "minimum_ring_score": minimum_score,
            "dominance_ratio": dominance_ratio,
            "whole_image_policy": "exclude_if_any_annotated_icon_is_ambiguous",
            "red_icon_policy": "positive_minimap_enemy",
            "green_blue_icon_policy": "confirmed_negative_not_annotated",
            "geometry_changed": False,
            "image_storage": dict(link_modes),
        },
        "splits": provenance_splits,
    }
    (output / "provenance.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return provenance


def prepare_external_minimap(source: Path, output: Path) -> dict[str, Any]:
    return _prepare_in_staging(source, output, _prepare_external_minimap_into)


def prepare_external_minimap_enemy(
    source: Path,
    output: Path,
    minimum_score: float = 0.04,
    dominance_ratio: float = 1.5,
) -> dict[str, Any]:
    if not 0 < minimum_score <= 1:
        raise ValueError("minimum_score must be between 0 and 1")
    if dominance_ratio <= 1:
        raise ValueError("dominance_ratio must be greater than 1")
    return _prepare_in_staging(
        source, output, _prepare_external_minimap_enemy_into,
        minimum_score, dominance_ratio,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--mode", choices=("generic-hero", "red-ring-enemy"), default="generic-hero"
    )
    parser.add_argument("--minimum-ring-score", type=float, default=0.04)
    parser.add_argument("--ring-dominance-ratio", type=float, default=1.5)
    args = parser.parse_args()
    if args.mode == "generic-hero":
        provenance = prepare_external_minimap(args.source, args.output)
    else:
        provenance = prepare_external_minimap_enemy(
            args.source, args.output, args.minimum_ring_score,
            args.ring_dominance_ratio,
        )
    print(json.dumps({
        "output": args.output.name,
        "purpose": provenance["purpose"],
        "splits": provenance["splits"],
        "transformation": provenance["transformation"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
