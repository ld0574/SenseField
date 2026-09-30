"""Audit a downloaded COCO dataset before it enters training.

The command never changes the dataset.  It reports class semantics, image
dimensions, missing files and exact duplicate images across splits.  This is
especially useful for public datasets whose published image-level split cannot
be trusted as an independent match-level evaluation split.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from math import isfinite
from pathlib import Path
from typing import Any

from .dataset_scope import coco_dataset_scope
from .image_manifest import IMAGE_MANIFEST_HASH_ALGORITHM, image_manifest_sha256
from .roi_safety import (
    coco_roi_blocker,
    has_supported_coco_roi_audit,
    inspect_box_roi,
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _discover_splits(root: Path) -> dict[str, tuple[Path, Path, str]]:
    """Return canonical split names for COCO and Roboflow COCO layouts."""
    found: dict[str, tuple[Path, Path, str]] = {}
    annotations_dir = root / "annotations"
    for split in ("train", "val", "test"):
        annotation = annotations_dir / f"instances_{split}2017.json"
        if annotation.is_file():
            found[split] = (annotation, root / f"{split}2017", "coco_2017")
    for directory, split in (("train", "train"), ("valid", "val"), ("test", "test")):
        annotation = root / directory / "_annotations.coco.json"
        if not annotation.is_file():
            continue
        if split in found:
            raise ValueError(f"Dataset contains two layouts for split {split}")
        found[split] = (annotation, root / directory, "roboflow_coco")
    return found


def _assert_within(root: Path, path: Path, description: str) -> None:
    try:
        path.resolve().relative_to(root.resolve())
    except ValueError as exc:
        raise ValueError(f"{description} escapes the dataset root: {path}") from exc


def _safe_image_path(image_dir: Path, file_name: Any) -> Path:
    """Resolve an image path while preventing absolute and parent traversal names."""
    if not isinstance(file_name, str) or not file_name:
        raise ValueError("COCO image file_name must be a non-empty relative path")
    relative = Path(file_name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"Unsafe COCO image file_name: {file_name!r}")
    path = (image_dir / relative).resolve()
    try:
        path.relative_to(image_dir.resolve())
    except ValueError as exc:
        raise ValueError(f"COCO image path escapes its split directory: {file_name!r}") from exc
    return path


def audit_coco_dataset(root: Path) -> dict[str, Any]:
    root = root.resolve()
    discovered = _discover_splits(root)
    split_reports: dict[str, Any] = {}
    hashes: dict[str, list[dict[str, Any]]] = defaultdict(list)
    category_sets: dict[str, list[tuple[int, str]]] = {}
    blockers: list[str] = []
    all_image_manifest_entries: list[tuple[str, str | None]] = []

    referenced_category_ids: set[int] = set()
    for split in ("train", "val", "test"):
        if split not in discovered:
            continue
        annotation_path, image_dir, layout = discovered[split]
        _assert_within(root, annotation_path, "Annotation path")
        _assert_within(root, image_dir, "Image directory")
        document = json.loads(annotation_path.read_text(encoding="utf-8"))
        document_scope = None
        try:
            document_scope = coco_dataset_scope(document, f"{split} COCO")
        except ValueError as error:
            blockers.append(f"{split}: {error}")
        if (isinstance(document_scope, dict) and
                document_scope.get("training_truth") is False):
            blockers.append(
                f"{split}: dataset_scope.training_truth=false; diagnostic-only "
                "data cannot be used as training truth"
            )
        images = document.get("images")
        annotations = document.get("annotations")
        categories = document.get("categories")
        if not isinstance(images, list) or not isinstance(annotations, list) or not isinstance(categories, list):
            raise ValueError(f"Invalid COCO document: {annotation_path}")
        image_id_values = [int(item["id"]) for item in images]
        category_id_values = [int(item["id"]) for item in categories]
        annotation_id_values = [int(item["id"]) for item in annotations]
        duplicate_image_ids = sorted(
            identifier for identifier, count in Counter(image_id_values).items() if count > 1
        )
        duplicate_category_ids = sorted(
            identifier for identifier, count in Counter(category_id_values).items() if count > 1
        )
        duplicate_annotation_ids = sorted(
            identifier for identifier, count in Counter(annotation_id_values).items() if count > 1
        )
        category_sets[split] = sorted(
            (int(item["id"]), str(item["name"])) for item in categories
        )
        image_ids = set(image_id_values)
        image_dimensions = {
            int(item["id"]): (int(item["width"]), int(item["height"]))
            for item in images
        }
        annotation_image_ids = {int(item["image_id"]) for item in annotations}
        unknown_image_ids = sorted(annotation_image_ids - image_ids)
        category_ids = {identifier for identifier, _ in category_sets[split]}
        annotation_category_ids = {int(item["category_id"]) for item in annotations}
        referenced_category_ids.update(annotation_category_ids)
        unknown_category_ids = sorted(annotation_category_ids - category_ids)
        edge_touch_annotations = []
        invalid_annotation_boxes = []
        for annotation in annotations:
            image_id = int(annotation["image_id"])
            bbox = annotation.get("bbox")
            if (image_id not in image_dimensions or not isinstance(bbox, list) or
                    len(bbox) != 4 or any(
                        not isinstance(value, (int, float)) or isinstance(value, bool)
                        for value in bbox
                    )):
                invalid_annotation_boxes.append(annotation.get("id"))
                continue
            box_width, box_height = image_dimensions[image_id]
            try:
                x, y, width, height = (float(value) for value in bbox)
            except (OverflowError, ValueError):
                invalid_annotation_boxes.append(annotation.get("id"))
                continue
            if (box_width <= 0 or box_height <= 0 or width <= 0 or height <= 0 or
                    not all(isfinite(value) for value in (x, y, width, height)) or
                    not isfinite(x + width) or not isfinite(y + height)):
                invalid_annotation_boxes.append(annotation.get("id"))
                continue
            audit = inspect_box_roi(
                [x / box_width, y / box_height,
                 width / box_width, height / box_height],
                [0.0, 0.0, 1.0, 1.0], box_width, box_height,
            )
            if audit["outside"]:
                invalid_annotation_boxes.append(annotation.get("id"))
            elif audit["touches"]:
                edge_touch_annotations.append({
                    "annotation_id": annotation.get("id"),
                    "image_id": image_id,
                    "sides": audit["touches"],
                })
        missing_files: list[str] = []
        dimensions: Counter[str] = Counter()
        split_image_manifest_entries: list[tuple[str, str | None]] = []
        for image in images:
            file_name = str(image["file_name"])
            path = _safe_image_path(image_dir, file_name)
            dimensions[f'{int(image["width"])}x{int(image["height"])}'] += 1
            if not path.is_file():
                missing_files.append(file_name)
                image_sha256 = None
            else:
                image_sha256 = _sha256(path)
                hashes[image_sha256].append({"split": split, "file_name": file_name})
            split_image_manifest_entries.append((file_name, image_sha256))
            all_image_manifest_entries.append((f"{split}/{file_name}", image_sha256))
        class_counts = Counter(int(item["category_id"]) for item in annotations)
        split_report = {
            "layout": layout,
            "image_directory": f"{image_dir.relative_to(root)}",
            "annotation_file": f"{annotation_path.relative_to(root)}",
            "annotation_sha256": _sha256(annotation_path),
            "source_image_manifest_sha256": image_manifest_sha256(
                split_image_manifest_entries
            ),
            "images": len(images),
            "annotations": len(annotations),
            "categories": len(categories),
            "category_annotation_counts": {
                str(key): class_counts[key] for key in sorted(class_counts)
            },
            "dimensions": dict(dimensions.most_common()),
            "missing_files": missing_files,
            "unknown_annotation_image_ids": unknown_image_ids,
            "unknown_annotation_category_ids": unknown_category_ids,
            "duplicate_image_ids": duplicate_image_ids,
            "duplicate_category_ids": duplicate_category_ids,
            "duplicate_annotation_ids": duplicate_annotation_ids,
            "roi_edge_touch_annotations": edge_touch_annotations,
            "invalid_annotation_boxes": invalid_annotation_boxes,
        }
        if document_scope is not None:
            split_report["dataset_scope"] = document_scope
        split_reports[split] = split_report
        if missing_files:
            blockers.append(f"{split}: {len(missing_files)} image files are missing")
        if unknown_image_ids:
            blockers.append(f"{split}: annotations reference unknown image IDs")
        if unknown_category_ids:
            blockers.append(f"{split}: annotations reference unknown category IDs")
        if duplicate_image_ids:
            blockers.append(f"{split}: duplicate image IDs")
        if duplicate_category_ids:
            blockers.append(f"{split}: duplicate category IDs")
        if duplicate_annotation_ids:
            blockers.append(f"{split}: duplicate annotation IDs")
        has_roi_audit = has_supported_coco_roi_audit(document)
        crop_blocker = coco_roi_blocker(document, split)
        crop_blocked = crop_blocker is not None
        split_reports[split]["roi_crop_completeness"] = (
            "blocked" if crop_blocked else
            "provenance_clear" if has_roi_audit else "unknown"
        )
        if crop_blocker:
            blockers.append(crop_blocker)
        if invalid_annotation_boxes:
            blockers.append(f"{split}: annotation boxes are outside or invalid for their image")

    if not split_reports:
        raise ValueError(
            "No COCO annotations found in annotations/instances_<split>2017.json "
            "or <split>/_annotations.coco.json"
        )
    canonical_categories = next(iter(category_sets.values()))
    for split, categories in category_sets.items():
        if categories != canonical_categories:
            blockers.append(f"{split}: category mapping differs from the other splits")
    category_by_id = dict(canonical_categories)
    referenced_names = [category_by_id[identifier]
                        for identifier in sorted(referenced_category_ids)
                        if identifier in category_by_id]
    unused_categories = [
        {"id": identifier, "name": name}
        for identifier, name in canonical_categories
        if identifier not in referenced_category_ids
    ]
    numeric_only = bool(referenced_names) and all(name.isdigit() for name in referenced_names)
    if numeric_only:
        blockers.append(
            "All category names are numeric; provide a reviewed semantic class map before remapping labels"
        )
    duplicate_groups = [items for items in hashes.values() if len(items) > 1]
    cross_split_duplicates = [
        items for items in duplicate_groups if len({item["split"] for item in items}) > 1
    ]
    if cross_split_duplicates:
        blockers.append(
            f"{len(cross_split_duplicates)} exact image duplicate groups cross split boundaries"
        )
    test_images = split_reports.get("test", {}).get("images", 0)
    if test_images == 0:
        blockers.append("No populated test split; do not quote this dataset as independent evaluation")

    return {
        "schema_version": 1,
        "dataset_root": None,
        "source_image_manifest_sha256": image_manifest_sha256(all_image_manifest_entries),
        "source_image_manifest_hash_algorithm": IMAGE_MANIFEST_HASH_ALGORITHM,
        "categories": [{"id": identifier, "name": name} for identifier, name in canonical_categories],
        "referenced_categories": [
            {"id": identifier, "name": category_by_id[identifier]}
            for identifier in sorted(referenced_category_ids)
            if identifier in category_by_id
        ],
        "unused_categories": unused_categories,
        "numeric_category_names_only": numeric_only,
        "splits": split_reports,
        "exact_duplicate_groups": duplicate_groups,
        "cross_split_duplicate_groups": cross_split_duplicates,
        "training_blockers": blockers,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "dataset", type=Path,
        help="COCO root in COCO-2017 or Roboflow COCO export layout",
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit_coco_dataset(args.dataset)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": args.output.name,
        "splits": {key: value["images"] for key, value in report["splits"].items()},
        "categories": len(report["categories"]),
        "training_blockers": report["training_blockers"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
