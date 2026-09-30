#!/usr/bin/env python3
"""Export a provenance-checked two-class minimap COCO dataset.

The player queue is a manual review queue.  This exporter deliberately reads
the queue's SQLite database as the authority for player terminal status and
uses each match's recorded ``source_manifest``/``source_database`` and each
sample's ``source_frame`` to locate the original enemy review.  It never
merges two unrelated frame lists by timestamp alone.

Only ``corrected`` and ``negative`` player reviews are eligible.  A ``skip``
review, a pending review, an enemy review without a human terminal state, or
any provenance/identity disagreement excludes that sample.  The whole export
is fail-closed: if a structural or identity conflict is found, no COCO files
are written.  An audit JSON is written even for a blocked export.

The command does not run a detector or read a video frame.  It uses the
already-reviewed queue image and crops its declared minimap ROI.  Video files
are only resolved and their declared SHA-256 values are compared; callers may
request full byte hashing with ``--verify-video-bytes``.

Example::

    python training/export_minimap_dual_class.py \
        --player-queue data/private/minimap-player-review-queue-v1 \
        --output build/data/minimap-dual-coco \
        --audit build/data/minimap-dual-coco.audit.json

The output is private data and must not be committed or pushed.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote

from PIL import Image

from mapassist.roi_safety import (
    DEFAULT_ROI_EDGE_TOLERANCE_PX,
    inspect_box_roi,
)


CLASSES = ("minimap_enemy", "minimap_player")
CATEGORY_IDS = {name: index + 1 for index, name in enumerate(CLASSES)}
PLAYER_KIND = "minimap_player"
ENEMY_KIND = "minimap_enemy"
PLAYER_TERMINAL = frozenset({"corrected", "negative"})
ENEMY_TERMINAL = frozenset({"corrected", "negative"})
OUTPUT_SPLITS = ("train", "val")
DATABASE_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
FRAME_SHA256 = DATABASE_SHA256


class ExportBlocked(ValueError):
    """A validation failure that must leave no training dataset behind."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))


def _canonical_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()


def _is_sealed(value: object) -> bool:
    """Return true for a path/id naming video9 or video12.

    This guard runs before path resolution or filesystem access.  It is
    intentionally segment based so ``video90`` is not accidentally treated
    as sealed while ``video9-player`` and ``video12-hd`` are.
    """
    if not isinstance(value, str):
        return False
    for segment in value.replace("\\", "/").split("/"):
        if re.match(r"^video[-_]?(?:9|12)(?:$|[-_.])", segment.casefold()):
            return True
    return False


def _guard_reference(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ExportBlocked(f"{label} must be a non-empty string")
    if _is_sealed(value):
        raise ExportBlocked(f"sealed video9/video12 reference in {label}: {value}")
    return value


def _json_file(path: Path, label: str) -> Any:
    _guard_reference(str(path), label)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ExportBlocked(f"missing {label}: {path}") from error
    except json.JSONDecodeError as error:
        raise ExportBlocked(f"invalid JSON in {label}: {path}: {error}") from error


def _resolve_reference(value: object, roots: Iterable[Path], label: str,
                       *, must_exist: bool = True) -> Path:
    """Resolve a private relative reference without escaping its repository.

    Queue provenance stores repository-relative paths while test fixtures often
    use paths relative to the queue directory.  We accept either form, but
    reject ambiguity and parent traversal.  Sealed references are rejected
    before any candidate's ``is_file`` call.
    """
    raw = _guard_reference(value, label)
    candidate = Path(raw).expanduser()
    candidates: list[Path]
    if candidate.is_absolute():
        candidates = [candidate.resolve()]
    else:
        candidates = [(root / candidate).resolve() for root in roots]
    # Do not follow a relative reference that escapes the queue/repository
    # roots.  An absolute source path is allowed only when it is explicit in
    # the provenance and still protected by the sealed guard above.
    existing: list[Path] = []
    for item in candidates:
        if _is_sealed(str(item)):
            raise ExportBlocked(f"sealed video9/video12 reference in {label}: {raw}")
        if item.is_file():
            existing.append(item)
    unique = list(dict.fromkeys(existing))
    if len(unique) > 1:
        raise ExportBlocked(f"ambiguous {label}: {raw} resolves to {unique}")
    if unique:
        return unique[0]
    if must_exist:
        raise ExportBlocked(f"missing {label}: {raw}")
    return candidates[0]


def _read_only_connection(path: Path, label: str) -> sqlite3.Connection:
    _guard_reference(str(path), label)
    if not path.is_file():
        raise ExportBlocked(f"missing {label}: {path}")
    uri = f"file:{quote(str(path.resolve()))}?mode=ro"
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(uri, uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 10000")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise ExportBlocked(f"{label} failed SQLite integrity_check: {integrity}")
        return connection
    except sqlite3.DatabaseError as error:
        if connection is not None:
            connection.close()
        raise ExportBlocked(f"cannot read {label}: {error}") from error
    except Exception:
        if connection is not None:
            connection.close()
        raise


def _task_columns(connection: sqlite3.Connection, label: str) -> set[str]:
    columns = {row[1] for row in connection.execute("PRAGMA table_info(tasks)")}
    required = {
        "id", "match_id", "split", "at_ms", "frame", "review_status",
        "reviewed_boxes", "reviewed_by", "reviewed_at", "lease_owner",
        "lease_until", "suggested_boxes", "suggested_categories",
    }
    missing = sorted(required - columns)
    if missing:
        raise ExportBlocked(f"{label} tasks schema missing columns: {missing}")
    return columns


def _decode_json(value: object, label: str) -> Any:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ExportBlocked(f"{label} is not JSON text")
    try:
        return json.loads(value)
    except json.JSONDecodeError as error:
        raise ExportBlocked(f"{label} contains invalid JSON") from error


def _json_value(value: object, label: str) -> Any:
    """Decode a SQLite JSON string while accepting manifest-native values."""
    if isinstance(value, str):
        return _decode_json(value, label)
    return copy.deepcopy(value)


def _json_semantically_equal(left: object, right: object) -> bool:
    return _canonical(left) == _canonical(right)


def _finite_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def _normalized_roi(value: object, label: str, *, required: bool = True) -> list[float] | None:
    if value is None:
        if required:
            raise ExportBlocked(f"{label} is required")
        return None
    if not isinstance(value, list) or len(value) != 4 or any(not _finite_number(item) for item in value):
        raise ExportBlocked(f"{label} must be normalized [x,y,width,height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1.000001 or y + height > 1.000001:
        raise ExportBlocked(f"{label} is outside normalized frame: {value}")
    return result


def _normalized_boxes(value: object, label: str, *, allow_none: bool = False) -> list[list[float]] | None:
    if value is None:
        if allow_none:
            return None
        raise ExportBlocked(f"{label} must be a list")
    if not isinstance(value, list):
        raise ExportBlocked(f"{label} must be a list")
    result: list[list[float]] = []
    for index, box in enumerate(value):
        if not isinstance(box, list) or len(box) != 4 or any(not _finite_number(item) for item in box):
            raise ExportBlocked(f"{label}[{index}] must be finite [x,y,width,height]")
        x, y, width, height = [float(item) for item in box]
        if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1.000001 or y + height > 1.000001:
            raise ExportBlocked(f"{label}[{index}] is outside normalized frame: {box}")
        result.append([float(item) for item in box])
    return result


def _boxes_inside_roi(boxes: list[list[float]], roi: list[float], label: str) -> None:
    rx, ry, rw, rh = roi
    for index, (x, y, width, height) in enumerate(boxes):
        if x < rx - 1e-6 or y < ry - 1e-6 or x + width > rx + rw + 1e-6 or y + height > ry + rh + 1e-6:
            raise ExportBlocked(f"{label}[{index}] crosses crop ROI: {boxes[index]} vs {roi}")


def _valid_sha(value: object, label: str, pattern: re.Pattern[str] = DATABASE_SHA256) -> str:
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise ExportBlocked(f"{label} must be a 64-character SHA-256")
    return value.lower()


def _relative_or_same(root: Path, value: object, label: str) -> Path:
    path = _resolve_reference(value, [root, *root.parents], label)
    try:
        path.relative_to(root.resolve())
    except ValueError as error:
        raise ExportBlocked(f"{label} escapes its source root: {value}") from error
    return path


def _image_hash_and_size(path: Path, expected_hash: str, expected_size: object,
                         label: str) -> tuple[str, tuple[int, int]]:
    actual_hash = _sha256(path)
    if actual_hash != expected_hash:
        raise ExportBlocked(f"{label} frame SHA-256 mismatch: expected {expected_hash}, got {actual_hash}")
    with Image.open(path) as opened:
        size = (int(opened.width), int(opened.height))
        opened.verify()
    if (not isinstance(expected_size, list) or len(expected_size) != 2 or
            any(isinstance(item, bool) or not isinstance(item, int) for item in expected_size) or
            tuple(expected_size) != size):
        raise ExportBlocked(f"{label} frame dimensions mismatch: expected {expected_size}, got {list(size)}")
    return actual_hash, size


def _compare_optional(left: object, right: object, label: str) -> None:
    if left is not None and right is not None and not _json_semantically_equal(left, right):
        raise ExportBlocked(f"{label} conflict: {left!r} vs {right!r}")


def _empty_split_report() -> dict[str, Any]:
    return {
        "images": 0,
        "annotations": 0,
        "negative_images": 0,
        "negative_player_images": 0,
        "negative_enemy_images": 0,
        "boxes_by_class": {name: 0 for name in CLASSES},
        "source_matches": [],
        "roi_boundary_audit": _new_roi_boundary_audit(),
    }


def _new_roi_boundary_audit(
    crop_edge_contacts: list[dict[str, Any]] | None = None,
    physical_edge_contacts: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Return the complete ROI provenance object used by COCO audit tools."""
    crop_contacts = copy.deepcopy(crop_edge_contacts or [])
    physical_contacts = copy.deepcopy(physical_edge_contacts or [])
    return {
        "schema_version": 1,
        "edge_tolerance_px": DEFAULT_ROI_EDGE_TOLERANCE_PX,
        "crop_edge_contacts": crop_contacts,
        "physical_edge_contacts": physical_contacts,
        # Keep the alias for readers of the first boundary-audit schema.
        "edge_contacts": copy.deepcopy(crop_contacts),
        "training_eligible": not crop_contacts,
        "usable_for_training_or_evaluation": not crop_contacts,
        "policy": (
            "Target boxes within an expandable crop-edge safety band require "
            "manual review. Physical frame-edge contacts are reported separately "
            "because the source cannot be expanded."
        ),
    }


def _new_audit(player_queue: Path, output: Path, audit_path: Path) -> dict[str, Any]:
    return {
        "schema": "mapassist.minimap_dual_class_export_audit",
        "schema_version": 1,
        "created_at": _now(),
        "status": "blocked",
        "classes": list(CLASSES),
        "input": {
            "player_queue": str(player_queue),
            "player_manifest": str(player_queue / "review-manifest.json"),
            "player_database": str(player_queue / "annotations.sqlite3"),
        },
        "output": {"dataset": str(output), "audit": str(audit_path)},
        "sources": {
            "source_count": 0,
            "player_match_count": 0,
            "player_sample_count": 0,
            "source_manifest_count": 0,
            "source_database_count": 0,
            "source_video_count": 0,
            "source_matches": [],
        },
        "counts": {
            "player_status": {},
            "enemy_status": {},
            "eligible_player_samples": 0,
            "exported_images": 0,
            "exported_annotations": 0,
            "boxes_by_class": {name: 0 for name in CLASSES},
            "negative_images": 0,
            "negative_player_images": 0,
            "negative_enemy_images": 0,
        },
        "splits": {split: _empty_split_report() for split in OUTPUT_SPLITS},
        "excluded": [],
        "missing": [],
        "conflicts": [],
        "warnings": [],
        "checks": {
            "sealed_sources_read_or_run": False,
            "machine_suggestions_used_as_truth": False,
            "player_database_read_only": True,
            "source_database_read_only": True,
            "frame_hashes_verified": False,
            "video_declarations_match": False,
            "video_bytes_verified": False,
            "all_output_boxes_inside_crop": False,
        },
    }


def _audit_issue(audit: dict[str, Any], kind: str, reason: str, details: dict[str, Any]) -> None:
    entry = {"reason": reason, **details}
    audit.setdefault(kind, []).append(entry)


def _write_audit(path: Path, audit: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _validate_orientation(value: object, label: str) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ExportBlocked(f"{label} must be an object")
    for key in ("source_coded_size", "display_size"):
        if key in value:
            raw = value[key]
            if (not isinstance(raw, list) or len(raw) != 2 or
                    any(isinstance(item, bool) or not isinstance(item, int) or item <= 0 for item in raw)):
                raise ExportBlocked(f"{label}.{key} must be positive [width,height]")
    if "display_rotation_degrees" in value:
        rotation = value["display_rotation_degrees"]
        if rotation not in (0, 90, 180, 270):
            raise ExportBlocked(f"{label}.display_rotation_degrees is invalid")
    return copy.deepcopy(value)


def _validate_status_fields(row: dict[str, Any], status: str, category: str,
                            label: str, *, require_no_lease: bool = True) -> list[list[float]]:
    reviewer = row.get("reviewed_by")
    reviewed_at = row.get("reviewed_at")
    if status in {"corrected", "negative"}:
        if not isinstance(reviewer, str) or not reviewer.strip() or not isinstance(reviewed_at, str) or not reviewed_at.strip():
            raise ExportBlocked(f"{label} lacks human review audit")
        if (require_no_lease and
                (row.get("lease_owner") is not None or row.get("lease_until") is not None)):
            raise ExportBlocked(f"{label} still has an active lease")
    raw_boxes = row.get("reviewed_boxes")
    raw_categories = row.get("reviewed_categories")
    if status == "negative":
        if raw_boxes is not None or raw_categories is not None:
            raise ExportBlocked(f"{label} negative review has boxes/categories")
        return []
    decoded_boxes = _decode_json(raw_boxes, f"{label}.reviewed_boxes")
    boxes = _normalized_boxes(decoded_boxes, f"{label}.reviewed_boxes")
    if category == PLAYER_KIND and len(boxes) != 1:
        raise ExportBlocked(f"{label} corrected player review must contain exactly one box")
    if raw_categories is not None:
        categories = _decode_json(raw_categories, f"{label}.reviewed_categories")
        if categories != [category] * len(boxes):
            raise ExportBlocked(f"{label} reviewed_categories are not human {category!r} labels")
    elif category == PLAYER_KIND:
        raise ExportBlocked(f"{label} corrected player review lacks reviewed_categories")
    return boxes


def _manifest_sample_index(document: dict[str, Any], label: str,
                           category: str) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ExportBlocked(f"{label} needs a non-empty matches list")
    by_id: dict[str, dict[str, Any]] = {}
    samples: dict[str, dict[str, Any]] = {}
    for match in matches:
        if not isinstance(match, dict):
            raise ExportBlocked(f"{label} has a non-object match")
        match_id = _guard_reference(match.get("id"), f"{label} match id")
        if match_id in by_id:
            raise ExportBlocked(f"{label} has duplicate match id: {match_id}")
        split = match.get("split")
        if split not in {"train", "val", "test"}:
            raise ExportBlocked(f"{label} {match_id} has invalid split: {split!r}")
        video_value = match.get("video")
        if video_value is not None:
            _guard_reference(video_value, f"{label} {match_id}.video")
        raw_samples = match.get("samples")
        if not isinstance(raw_samples, list) or not raw_samples:
            raise ExportBlocked(f"{label} {match_id} needs samples")
        by_id[match_id] = match
        seen_times: set[int] = set()
        for sample in raw_samples:
            if not isinstance(sample, dict):
                raise ExportBlocked(f"{label} {match_id} has a non-object sample")
            at_ms = sample.get("at_ms")
            if not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0 or at_ms in seen_times:
                raise ExportBlocked(f"{label} {match_id} has invalid/duplicate at_ms: {at_ms!r}")
            seen_times.add(at_ms)
            frame = _guard_reference(sample.get("frame"), f"{label} {match_id}@{at_ms}.frame")
            key = f"{match_id}\x00{at_ms}"
            if key in samples:
                raise ExportBlocked(f"{label} duplicate sample: {match_id}@{at_ms}")
            samples[key] = {"match": match, "sample": sample, "category": category,
                            "frame": frame}
    return by_id, samples


def _load_player_queue(player_queue: Path, audit: dict[str, Any]) -> tuple[dict[str, Any], dict[str, dict[str, Any]], dict[str, dict[str, Any]], sqlite3.Connection]:
    if _is_sealed(str(player_queue)):
        raise ExportBlocked(f"sealed player queue path: {player_queue}")
    player_queue = player_queue.resolve()
    manifest_path = player_queue / "review-manifest.json"
    database_path = player_queue / "annotations.sqlite3"
    document = _json_file(manifest_path, "player review manifest")
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise ExportBlocked("player review manifest must use schema_version=1")
    if document.get("kind") != PLAYER_KIND or document.get("classes") != [PLAYER_KIND]:
        raise ExportBlocked("player review manifest must be manual minimap_player with classes=[minimap_player]")
    if document.get("review_mode") not in (None, "manual"):
        raise ExportBlocked("player review manifest is not a manual queue")
    matches, samples = _manifest_sample_index(document, "player manifest", PLAYER_KIND)
    audit["sources"]["player_match_count"] = len(matches)
    audit["sources"]["player_sample_count"] = len(samples)
    audit["input"].update({
        "player_manifest_sha256": _sha256(manifest_path),
        "player_database_sha256": _sha256(database_path) if database_path.is_file() else None,
    })
    connection = _read_only_connection(database_path, "player annotations.sqlite3")
    columns = _task_columns(connection, "player")
    rows = [dict(row) for row in connection.execute("SELECT * FROM tasks ORDER BY id")]
    row_by_key: dict[str, dict[str, Any]] = {}
    for row in rows:
        match_id = row.get("match_id")
        at_ms = row.get("at_ms")
        key = f"{match_id}\x00{at_ms}"
        if key in row_by_key:
            raise ExportBlocked(f"player database duplicate task identity: {match_id}@{at_ms}")
        row_by_key[key] = row
    if set(row_by_key) != set(samples):
        missing = sorted(set(samples) - set(row_by_key))[:10]
        extra = sorted(set(row_by_key) - set(samples))[:10]
        raise ExportBlocked(f"player database/manifest sample set mismatch: missing={missing}, extra={extra}")
    status_counts = Counter()
    for key, entry in samples.items():
        sample = entry["sample"]
        row = row_by_key[key]
        label = f"player {entry['match']['id']}@{sample['at_ms']}"
        if row.get("split") != entry["match"].get("split") or row.get("frame") != sample.get("frame"):
            raise ExportBlocked(f"{label} database identity differs from manifest")
        for field in ("review_status", "reviewed_by", "reviewed_at"):
            if row.get(field) != sample.get(field):
                raise ExportBlocked(f"{label} {field} differs between database and manifest")
        for field in ("reviewed_boxes", "reviewed_categories"):
            db_value = _decode_json(row.get(field), f"{label}.{field}")
            if not _json_semantically_equal(db_value, sample.get(field)):
                raise ExportBlocked(f"{label} {field} differs between database and manifest")
        status = row.get("review_status")
        status_counts[str(status)] += 1
        entry["row"] = row
    audit["counts"]["player_status"] = dict(sorted(status_counts.items()))
    # Referenced suggestions are retained only as audit metadata.  The export
    # path below never reads them as boxes, so a populated suggestion cannot be
    # silently promoted to truth.
    if "suggested_boxes" not in columns or "suggested_categories" not in columns:
        raise ExportBlocked("player database lacks suggestion provenance columns")
    return document, matches, samples, connection


def _validate_enemy_database(
    source_root: Path,
    source_document: dict[str, Any],
    source_database: Path,
    label: str,
    audit: dict[str, Any],
    *,
    expected_sha256: str | None,
    strict_hash: bool,
) -> tuple[dict[str, dict[str, Any]], str]:
    actual_sha256 = _sha256(source_database)
    if expected_sha256 is not None and actual_sha256 != expected_sha256:
        details = {"source_database": str(source_database),
                   "expected_sha256": expected_sha256,
                   "actual_sha256": actual_sha256}
        if strict_hash:
            _audit_issue(audit, "conflicts", "source_database_sha256_mismatch", details)
            raise ExportBlocked(
                f"{label} source database SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}"
            )
        _audit_issue(audit, "warnings", "source_database_sha256_mismatch_semantically_validated", details)
    connection = _read_only_connection(source_database, label)
    try:
        _task_columns(connection, label)
        rows = [dict(row) for row in connection.execute("SELECT * FROM tasks ORDER BY id")]
        by_key: dict[str, dict[str, Any]] = {}
        for row in rows:
            match_id = row.get("match_id")
            at_ms = row.get("at_ms")
            key = f"{match_id}\x00{at_ms}"
            if key in by_key:
                raise ExportBlocked(f"{label} database duplicate task identity: {match_id}@{at_ms}")
            by_key[key] = row
        # Validate every source manifest sample against the DB.  This catches a
        # manifest/database pair being swapped even when the selected timestamp
        # happens to exist in both.
        _, source_samples = _manifest_sample_index(source_document, label + " manifest", ENEMY_KIND)
        source_keys = set(source_samples)
        database_keys = set(by_key)
        if database_keys != source_keys:
            missing = sorted(source_keys - database_keys)[:10]
            extra = sorted(database_keys - source_keys)[:10]
            raise ExportBlocked(
                f"{label} database/manifest sample set mismatch: missing={missing}, extra={extra}"
            )
        for key, entry in source_samples.items():
            row = by_key.get(key)
            if row is None:
                raise ExportBlocked(f"{label} database is missing manifest sample: {key!r}")
            sample = entry["sample"]
            if row.get("split") != entry["match"].get("split") or row.get("frame") != sample.get("frame"):
                raise ExportBlocked(f"{label} database identity differs from source manifest at {key!r}")
            for field in ("review_status", "reviewed_by", "reviewed_at"):
                if row.get(field) != sample.get(field):
                    raise ExportBlocked(f"{label} {field} differs from source manifest at {key!r}")
            for field in ("reviewed_boxes", "reviewed_categories"):
                db_value = _decode_json(row.get(field), f"{label} {key}.{field}")
                manifest_value = sample.get(field)
                # Legacy enemy manifests omit reviewed_categories; the class is
                # inferred only because the manifest kind was validated as enemy.
                if field == "reviewed_categories" and manifest_value is None:
                    boxes = _json_value(sample.get("reviewed_boxes"), f"{label} {key}.reviewed_boxes")
                    manifest_value = ([ENEMY_KIND] * len(boxes)
                                      if boxes is not None else None)
                # Very old enemy task databases predate reviewed_categories.  The
                # source manifest kind is already validated as manual enemy, so a
                # missing category column is safe only when the manifest also
                # omitted that redundant field; player corrected labels always
                # require their explicit reviewed_categories below.
                if (field == "reviewed_categories" and field not in row and
                        manifest_value is not None):
                    continue
                if not _json_semantically_equal(db_value, manifest_value):
                    raise ExportBlocked(f"{label} {field} differs from source manifest at {key!r}")
        status_counts = Counter(str(row.get("review_status")) for row in rows)
        existing = Counter(audit["counts"].get("enemy_status", {}))
        existing.update(status_counts)
        audit["counts"]["enemy_status"] = dict(sorted(existing.items()))
        return by_key, actual_sha256
    finally:
        connection.close()


def _load_source_context(
    player_queue: Path,
    player_match: dict[str, Any],
    audit: dict[str, Any],
    source_cache: dict[Path, dict[str, Any]],
    *,
    strict_source_database_hash: bool,
    verify_video_bytes: bool,
    video_hash_cache: dict[Path, str],
) -> dict[str, Any]:
    match_id = _guard_reference(player_match.get("id"), "player match id")
    source_match_id = _guard_reference(player_match.get("source_match_id"), f"{match_id}.source_match_id")
    source_manifest_value = _guard_reference(player_match.get("source_manifest"), f"{match_id}.source_manifest")
    source_database_value = _guard_reference(player_match.get("source_database"), f"{match_id}.source_database")
    source_manifest_sha256 = _valid_sha(player_match.get("source_manifest_sha256"), f"{match_id}.source_manifest_sha256")
    source_database_sha256 = _valid_sha(player_match.get("source_database_sha256"), f"{match_id}.source_database_sha256")
    source_video_sha256 = _valid_sha(player_match.get("source_video_sha256"), f"{match_id}.source_video_sha256")
    source_video_path_value = _guard_reference(player_match.get("source_video_path"), f"{match_id}.source_video_path")
    player_roi = _normalized_roi(player_match.get("roi"), f"{match_id}.roi")
    player_widget_roi = _normalized_roi(player_match.get("widget_roi"), f"{match_id}.widget_roi", required=False)
    player_orientation = _validate_orientation(player_match.get("orientation"), f"{match_id}.orientation")
    source_manifest = _resolve_reference(source_manifest_value, [player_queue, *player_queue.parents],
                                          f"{match_id}.source_manifest")
    source_root = source_manifest.parent.resolve()
    source_database = _resolve_reference(source_database_value, [player_queue, *player_queue.parents, source_root, *source_root.parents],
                                          f"{match_id}.source_database")
    source_key = source_manifest.resolve()
    if source_key not in source_cache:
        document = _json_file(source_manifest, f"{match_id} enemy source manifest")
        if not isinstance(document, dict) or document.get("schema_version") != 1 or document.get("kind") != ENEMY_KIND:
            raise ExportBlocked(f"{match_id} source manifest is not schema 1 kind={ENEMY_KIND}")
        declared_classes = document.get("classes")
        if declared_classes is not None and declared_classes != [ENEMY_KIND]:
            raise ExportBlocked(f"{match_id} source enemy manifest classes are not [{ENEMY_KIND!r}]")
        source_matches = document.get("matches")
        if not isinstance(source_matches, list) or not source_matches:
            raise ExportBlocked(f"{match_id} source enemy manifest needs matches")
        # Guard every source match id/video before selecting one.  A mixed file
        # containing a sealed recording must never be partially consumed.
        for source_match in source_matches:
            if not isinstance(source_match, dict):
                raise ExportBlocked(f"{match_id} source manifest has a non-object match")
            _guard_reference(source_match.get("id"), f"{match_id} source match id")
            if source_match.get("video") is not None:
                _guard_reference(source_match.get("video"), f"{match_id} source match video")
        actual_manifest_sha256 = _sha256(source_manifest)
        if actual_manifest_sha256 != source_manifest_sha256:
            raise ExportBlocked(f"{match_id} source manifest SHA-256 mismatch: expected {source_manifest_sha256}, got {actual_manifest_sha256}")
        source_matches_by_id, _ = _manifest_sample_index(document, f"{match_id} enemy source", ENEMY_KIND)
        enemy_rows, actual_db_sha256 = _validate_enemy_database(
            source_root, document, source_database, f"{match_id} enemy source database", audit,
            expected_sha256=source_database_sha256, strict_hash=strict_source_database_hash,
        )
        source_cache[source_key] = {
            "document": document,
            "matches": source_matches_by_id,
            "rows": enemy_rows,
            "database": source_database,
            "database_sha256_declared": source_database_sha256,
            "database_sha256": actual_db_sha256,
            "manifest_sha256_declared": source_manifest_sha256,
            "manifest_sha256": actual_manifest_sha256,
        }
        audit["sources"]["source_manifest_count"] += 1
        audit["sources"]["source_database_count"] += 1
    cached = source_cache[source_key]
    if cached["database"].resolve() != source_database.resolve():
        raise ExportBlocked(
            f"{match_id} source_database differs for cached source manifest: "
            f"{source_database} vs {cached['database']}"
        )
    if cached["database_sha256_declared"] != source_database_sha256:
        raise ExportBlocked(
            f"{match_id} source_database_sha256 differs for cached source manifest: "
            f"{source_database_sha256} vs {cached['database_sha256_declared']}"
        )
    if cached["manifest_sha256_declared"] != source_manifest_sha256:
        raise ExportBlocked(
            f"{match_id} source_manifest_sha256 differs for cached source manifest: "
            f"{source_manifest_sha256} vs {cached['manifest_sha256_declared']}"
        )
    source_match = cached["matches"].get(source_match_id)
    if source_match is None:
        raise ExportBlocked(f"{match_id} source manifest lacks source_match_id={source_match_id}")
    source_split = source_match.get("split")
    if source_split not in {"train", "val", "test"}:
        raise ExportBlocked(f"{match_id} source split is invalid: {source_split!r}")
    if player_match.get("source_manifest_split") is not None and player_match.get("source_manifest_split") != source_split:
        raise ExportBlocked(f"{match_id} source_manifest_split disagrees with source manifest")
    source_video_sha256_value = _valid_sha(source_match.get("video_sha256"), f"{match_id} source video_sha256")
    if source_video_sha256_value != source_video_sha256:
        raise ExportBlocked(f"{match_id} source video SHA-256 disagrees with player provenance")
    source_roi = _normalized_roi(source_match.get("roi", cached["document"].get("roi")), f"{match_id} source roi")
    if not _json_semantically_equal(source_roi, player_roi):
        raise ExportBlocked(f"{match_id} ROI differs between player and enemy provenance")
    source_widget_roi = _normalized_roi(source_match.get("widget_roi", cached["document"].get("widget_roi")),
                                         f"{match_id} source widget_roi", required=False)
    _compare_optional(source_widget_roi, player_widget_roi, f"{match_id} widget_roi")
    source_orientation = _validate_orientation(source_match.get("orientation", cached["document"].get("orientation")),
                                               f"{match_id} source orientation")
    _compare_optional(source_orientation, player_orientation, f"{match_id} orientation")
    source_video_value = source_match.get("video")
    source_video = None
    if source_video_value is not None:
        try:
            source_video = _resolve_reference(source_video_value, [source_root, *source_root.parents, player_queue, *player_queue.parents],
                                              f"{match_id} source video")
        except ExportBlocked as error:
            # Some HD re-exports retain the old enemy queue's video path even
            # though the player queue records the current path.  The declared
            # hash and source frame still provide a precise identity; keep the
            # missing old path in the audit and use the player path below.
            # The one-per-source warning is added after the source context is
            # assembled below; avoid emitting the same warning once per crop.
            pass
    player_video = _resolve_reference(source_video_path_value, [player_queue, *player_queue.parents],
                                      f"{match_id} player source video")
    if source_video is not None and source_video_sha256_value != source_video_sha256:
        raise ExportBlocked(f"{match_id} source video declarations conflict")
    # Hashing is optional because the queue already carries immutable hashes;
    # when enabled, hash only allowed player/source paths, never source video9/12.
    if verify_video_bytes:
        for video_path in dict.fromkeys(path for path in (player_video, source_video) if path is not None):
            if _is_sealed(str(video_path)):
                raise ExportBlocked(f"sealed source video reached byte verification: {video_path}")
            actual_video_hash = video_hash_cache.get(video_path)
            if actual_video_hash is None:
                actual_video_hash = _sha256(video_path)
                video_hash_cache[video_path] = actual_video_hash
            if actual_video_hash != source_video_sha256:
                raise ExportBlocked(f"{match_id} source video byte hash mismatch for {video_path}")
    source_audit_key = (match_id, source_match_id)
    if not any(tuple(item.get("key", ())) == source_audit_key
               for item in audit["sources"]["source_matches"]):
        audit["sources"]["source_matches"].append({
            "key": list(source_audit_key),
            "player_match_id": match_id,
            "source_match_id": source_match_id,
            "output_split": player_match.get("split"),
            "source_split": source_split,
            "source_manifest": str(source_manifest),
            "source_database": str(cached["database"]),
            "source_database_sha256_declared": source_database_sha256,
            "source_database_sha256_actual": cached["database_sha256"],
            "source_video_sha256": source_video_sha256,
            "source_video_path": str(source_video_value) if source_video_value is not None else None,
            "source_video_path_available": source_video is not None,
        })
        if source_split != player_match.get("split"):
            _audit_issue(audit, "warnings", "source_split_reassigned_by_player_queue", {
                "match_id": match_id,
                "source_match_id": source_match_id,
                "source_split": source_split,
                "output_split": player_match.get("split"),
                "split_assignment": player_match.get("split_assignment"),
            })
    audit["sources"]["source_video_count"] = len({
        item["source_video_sha256"] for item in audit["sources"]["source_matches"]
    })
    audit["sources"]["source_count"] = len(audit["sources"]["source_matches"])
    if source_video is None and not any(
            item.get("reason") == "source_video_path_unavailable_using_player_path" and
            item.get("match_id") == match_id
            for item in audit.get("warnings", [])):
        _audit_issue(audit, "warnings", "source_video_path_unavailable_using_player_path", {
            "match_id": match_id, "source_video": str(source_video_value),
            "detail": "source video declaration is absent on this host; player provenance video path and SHA-256 were used",
        })
    return {
        "match_id": match_id,
        "source_match_id": source_match_id,
        "source_root": source_root,
        "source_manifest": source_manifest,
        "source_database": cached["database"],
        "source_database_sha256": cached["database_sha256"],
        "source_match": source_match,
        "source_rows": cached["rows"],
        "player_video": player_video,
        "source_video": source_video,
        "source_video_sha256": source_video_sha256,
        "roi": player_roi,
        "widget_roi": player_widget_roi,
        "orientation": player_orientation,
    }


def _source_sample(source_context: dict[str, Any], at_ms: int) -> dict[str, Any]:
    source_match = source_context["source_match"]
    samples = source_match.get("samples")
    if not isinstance(samples, list):
        raise ExportBlocked(f"{source_context['match_id']} source match samples are invalid")
    matches = [item for item in samples if isinstance(item, dict) and item.get("at_ms") == at_ms]
    if len(matches) != 1:
        raise ExportBlocked(f"{source_context['match_id']} source enemy sample at_ms={at_ms} is missing or duplicated")
    return matches[0]


def _prepare_records(
    player_queue: Path,
    document: dict[str, Any],
    samples: dict[str, dict[str, Any]],
    audit: dict[str, Any],
    *,
    strict_source_database_hash: bool,
    verify_video_bytes: bool,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    source_cache: dict[Path, dict[str, Any]] = {}
    video_hash_cache: dict[Path, str] = {}
    frame_hashes_ok = True
    for key, entry in sorted(samples.items(), key=lambda item: (item[1]["match"]["split"], item[1]["match"]["id"], item[1]["sample"]["at_ms"])):
        match = entry["match"]
        sample = entry["sample"]
        row = entry["row"]
        match_id = match["id"]
        at_ms = sample["at_ms"]
        status = row.get("review_status")
        if status not in PLAYER_TERMINAL:
            reason = {
                "skip": "player_skip",
                "pending": "player_pending",
                "excluded": "player_excluded",
                "accepted": "machine_accepted_status",
            }.get(status, "player_unknown_status")
            _audit_issue(audit, "excluded", reason, {"match_id": match_id, "at_ms": at_ms, "status": status})
            if status == "accepted":
                raise ExportBlocked(f"{match_id}@{at_ms} uses machine accepted status as truth")
            continue
        # Validate player human fields before doing any source lookup.
        player_boxes = _validate_status_fields(row, status, PLAYER_KIND, f"player {match_id}@{at_ms}")
        player_frame = _relative_or_same(player_queue, sample.get("frame"), f"player {match_id}@{at_ms}.frame")
        provenance_samples = match.get("provenance_samples")
        if not isinstance(provenance_samples, list):
            raise ExportBlocked(f"{match_id} lacks provenance_samples")
        provenance = [item for item in provenance_samples if isinstance(item, dict) and item.get("at_ms") == at_ms]
        if len(provenance) != 1:
            _audit_issue(audit, "missing", "player_provenance_sample_missing_or_duplicate", {"match_id": match_id, "at_ms": at_ms})
            continue
        provenance = provenance[0]
        source_frame_value = _guard_reference(provenance.get("source_frame"), f"{match_id}@{at_ms}.source_frame")
        source_frame_sha256 = _valid_sha(provenance.get("source_frame_sha256"), f"{match_id}@{at_ms}.source_frame_sha256", FRAME_SHA256)
        source_frame_size = provenance.get("source_frame_size")
        queue_frame_hash = _sha256(player_frame)
        if queue_frame_hash != source_frame_sha256:
            _audit_issue(audit, "conflicts", "player_queue_frame_sha256_mismatch", {
                "match_id": match_id, "at_ms": at_ms, "expected": source_frame_sha256, "actual": queue_frame_hash,
            })
            continue
        try:
            with Image.open(player_frame) as opened:
                queue_size = (int(opened.width), int(opened.height))
                opened.verify()
        except (OSError, ValueError) as error:
            _audit_issue(audit, "conflicts", "player_frame_unreadable", {"match_id": match_id, "at_ms": at_ms, "detail": str(error)})
            continue
        if (not isinstance(source_frame_size, list) or len(source_frame_size) != 2 or tuple(source_frame_size) != queue_size):
            _audit_issue(audit, "conflicts", "player_frame_size_mismatch", {
                "match_id": match_id, "at_ms": at_ms, "expected": source_frame_size, "actual": list(queue_size),
            })
            continue
        try:
            source_context = _load_source_context(
                player_queue, match, audit, source_cache,
                strict_source_database_hash=strict_source_database_hash,
                verify_video_bytes=verify_video_bytes,
                video_hash_cache=video_hash_cache,
            )
            source_frame = _relative_or_same(source_context["source_root"], source_frame_value, f"{match_id}@{at_ms}.source_frame")
            source_hash, source_size = _image_hash_and_size(source_frame, source_frame_sha256, source_frame_size, f"{match_id}@{at_ms}.source_frame")
            if source_size != queue_size:
                raise ExportBlocked(f"{match_id}@{at_ms} source and player frame dimensions differ")
            enemy_sample = _source_sample(source_context, at_ms)
            source_row = source_context["source_rows"].get(f"{source_context['source_match_id']}\x00{at_ms}")
            if source_row is None:
                raise ExportBlocked(f"{match_id}@{at_ms} enemy source database row is missing")
            enemy_manifest_frame = _relative_or_same(
                source_context["source_root"], enemy_sample.get("frame"),
                f"{match_id}@{at_ms}.enemy_manifest_frame",
            )
            if (source_row.get("frame") != enemy_sample.get("frame") or
                    enemy_manifest_frame.resolve() != source_frame.resolve()):
                raise ExportBlocked(f"{match_id}@{at_ms} source_frame does not exactly match enemy manifest/database")
            enemy_status = source_row.get("review_status")
            if enemy_status not in ENEMY_TERMINAL:
                _audit_issue(audit, "excluded", "enemy_not_human_terminal", {
                    "match_id": match_id, "at_ms": at_ms, "enemy_status": enemy_status,
                })
                continue
            enemy_label = f"enemy {source_context['source_match_id']}@{at_ms}"
            lease_until = source_row.get("lease_until")
            if source_row.get("lease_owner") is not None or lease_until is not None:
                try:
                    lease_expired = lease_until is not None and float(lease_until) <= time.time()
                except (TypeError, ValueError):
                    lease_expired = False
                if not lease_expired:
                    raise ExportBlocked(f"{enemy_label} has an active or invalid lease")
                _audit_issue(audit, "warnings", "enemy_terminal_stale_lease", {
                    "match_id": match_id, "at_ms": at_ms,
                    "lease_owner": source_row.get("lease_owner"),
                    "lease_until": float(lease_until),
                    "lease_expired_at": datetime.fromtimestamp(float(lease_until), timezone.utc).isoformat(),
                    "checked_at": _now(),
                    "expired_seconds": round(time.time() - float(lease_until), 3),
                })
            enemy_boxes = _validate_status_fields(
                source_row, enemy_status, ENEMY_KIND, enemy_label,
                require_no_lease=False,
            )
            # Source manifest's reviewed fields are also checked by
            # _validate_enemy_database; use the DB row directly as label data.
            _boxes_inside_roi(player_boxes, source_context["roi"], f"player {match_id}@{at_ms}")
            _boxes_inside_roi(enemy_boxes, source_context["roi"], f"enemy {match_id}@{at_ms}")
            records.append({
                "key": key,
                "match": match,
                "sample": sample,
                "at_ms": at_ms,
                "player_status": status,
                "enemy_status": enemy_status,
                "player_boxes": player_boxes,
                "enemy_boxes": enemy_boxes,
                "queue_frame": player_frame,
                "source_frame": source_frame,
                "source_frame_sha256": source_hash,
                "source_frame_size": source_size,
                "source_context": source_context,
                "roi": source_context["roi"],
            })
        except ExportBlocked as error:
            # Missing source labels are sample-level exclusions; structural
            # provenance conflicts are fatal so they cannot silently produce a
            # partially trusted dataset.
            text = str(error)
            if any(token in text for token in (
                "missing", "lacks source_match_id", "not human", "pending", "source enemy sample",
            )):
                _audit_issue(audit, "missing", "source_pair_unavailable", {
                    "match_id": match_id, "at_ms": at_ms, "detail": text,
                })
                continue
            _audit_issue(audit, "conflicts", "source_pair_conflict", {
                "match_id": match_id, "at_ms": at_ms, "detail": text,
            })
            raise
    audit["checks"]["frame_hashes_verified"] = frame_hashes_ok and bool(records or samples)
    audit["checks"]["video_declarations_match"] = bool(source_cache)
    audit["checks"]["video_bytes_verified"] = bool(verify_video_bytes and video_hash_cache)
    audit["counts"]["eligible_player_samples"] = len(records)
    return records


def _inspect_record_roi(record: dict[str, Any]) -> dict[str, Any]:
    """Audit every source box before it is transformed into crop coordinates."""
    frame_size = record.get("source_frame_size")
    if (not isinstance(frame_size, tuple) or len(frame_size) != 2 or
            any(isinstance(value, bool) or not isinstance(value, int) or value <= 0
                for value in frame_size)):
        raise ExportBlocked(
            f"{record['match']['id']}@{record['at_ms']} has invalid source frame dimensions"
        )
    frame_width, frame_height = frame_size
    all_boxes = [(ENEMY_KIND, box) for box in record["enemy_boxes"]]
    all_boxes += [(PLAYER_KIND, box) for box in record["player_boxes"]]
    crop_contacts: list[dict[str, Any]] = []
    physical_contacts: list[dict[str, Any]] = []
    for box_index, (category, box) in enumerate(all_boxes, start=1):
        inspected = inspect_box_roi(
            box, record["roi"], frame_width, frame_height,
            DEFAULT_ROI_EDGE_TOLERANCE_PX,
        )
        if inspected["crop_touches"]:
            crop_contacts.append({
                "match_id": record["source_context"]["source_match_id"],
                "at_ms": record["at_ms"],
                "box_index": box_index,
                "sides": inspected["crop_touches"],
            })
        if inspected["frame_touches"]:
            physical_contacts.append({
                "match_id": record["source_context"]["source_match_id"],
                "at_ms": record["at_ms"],
                "box_index": box_index,
                "sides": inspected["frame_touches"],
            })
        if inspected["outside"]:
            raise ExportBlocked(
                f"{record['match']['id']}@{record['at_ms']} {category} box "
                f"{box_index} crosses the crop ROI ({', '.join(inspected['outside'])})"
            )
    if crop_contacts:
        raise ExportBlocked(
            f"{record['match']['id']}@{record['at_ms']} has target boxes touching "
            "an expandable crop edge; re-annotate before export"
        )
    return _new_roi_boundary_audit(crop_contacts, physical_contacts)


def _crop_and_box(frame_path: Path, roi: list[float], boxes: list[list[float]], output_path: Path,
                  label: str) -> tuple[int, int, list[list[float]]]:
    with Image.open(frame_path) as opened:
        image = opened.convert("RGB")
    width, height = image.size
    rx, ry, rw, rh = roi
    left, top = math.floor(rx * width), math.floor(ry * height)
    right, bottom = math.ceil((rx + rw) * width), math.ceil((ry + rh) * height)
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise ExportBlocked(f"{label} ROI pixel bounds are invalid: {roi} for {image.size}")
    crop = image.crop((left, top, right, bottom))
    crop_width, crop_height = crop.size
    transformed: list[list[float]] = []
    for index, (x, y, box_width, box_height) in enumerate(boxes):
        px = (x * width - left) / crop_width
        py = (y * height - top) / crop_height
        pw = box_width * width / crop_width
        ph = box_height * height / crop_height
        if px < -1e-6 or py < -1e-6 or px + pw > 1.000001 or py + ph > 1.000001 or pw <= 0 or ph <= 0:
            raise ExportBlocked(f"{label} transformed box {index} escapes crop")
        transformed.append([px * crop_width, py * crop_height, pw * crop_width, ph * crop_height])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    crop.save(output_path, format="PNG")
    return crop_width, crop_height, transformed


def _coco_document(split: str, images: list[dict[str, Any]], annotations: list[dict[str, Any]],
                   audit: dict[str, Any],
                   roi_boundary_audit: dict[str, Any] | None = None) -> dict[str, Any]:
    if roi_boundary_audit is None:
        roi_boundary_audit = _new_roi_boundary_audit()
    return {
        "info": {
            "description": "Human-reviewed dual-class minimap crops",
            "version": "1",
            "split": split,
            "classes": list(CLASSES),
            "audit_schema": audit["schema"],
            "roi_boundary_audit": roi_boundary_audit,
        },
        "licenses": [],
        "images": images,
        "annotations": annotations,
        "categories": [
            {"id": CATEGORY_IDS[name], "name": name, "supercategory": "minimap"}
            for name in CLASSES
        ],
    }


def _write_dataset(records: list[dict[str, Any]], output: Path, audit: dict[str, Any]) -> None:
    if output.exists():
        raise ExportBlocked(f"refusing to overwrite existing output dataset: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=str(output.parent)))
    try:
        split_images: dict[str, list[dict[str, Any]]] = {split: [] for split in OUTPUT_SPLITS}
        split_annotations: dict[str, list[dict[str, Any]]] = {split: [] for split in OUTPUT_SPLITS}
        split_roi_audits: dict[str, dict[str, Any]] = {
            split: _new_roi_boundary_audit() for split in OUTPUT_SPLITS
        }
        for split in OUTPUT_SPLITS:
            audit["splits"][split]["roi_boundary_audit"] = copy.deepcopy(
                split_roi_audits[split]
            )
        used_names: set[str] = set()
        image_id = 1
        annotation_id = 1
        for record in records:
            split = record["match"]["split"]
            if split not in OUTPUT_SPLITS:
                raise ExportBlocked(f"eligible player sample uses unsupported output split: {split}")
            stem = f"{record['source_context']['source_match_id']}_{record['at_ms']:09d}"
            relative_name = f"{stem}.png"
            if relative_name in used_names:
                raise ExportBlocked(f"duplicate output crop name: {relative_name}")
            used_names.add(relative_name)
            output_path = temporary / f"{split}2017" / relative_name
            all_boxes = [(ENEMY_KIND, box) for box in record["enemy_boxes"]]
            all_boxes += [(PLAYER_KIND, box) for box in record["player_boxes"]]
            record_roi_audit = _inspect_record_roi(record)
            split_roi_audit = split_roi_audits[split]
            for contact_type in ("crop_edge_contacts", "physical_edge_contacts"):
                split_roi_audit[contact_type].extend(
                    record_roi_audit[contact_type]
                )
            split_roi_audit["edge_contacts"] = copy.deepcopy(
                split_roi_audit["crop_edge_contacts"]
            )
            split_roi_audit["training_eligible"] = not bool(
                split_roi_audit["crop_edge_contacts"]
            )
            split_roi_audit["usable_for_training_or_evaluation"] = (
                split_roi_audit["training_eligible"]
            )
            audit["splits"][split]["roi_boundary_audit"] = copy.deepcopy(
                split_roi_audit
            )
            crop_width, crop_height, transformed = _crop_and_box(
                record["queue_frame"], record["roi"], [box for _, box in all_boxes], output_path,
                f"{record['match']['id']}@{record['at_ms']}",
            )
            image_entry = {
                "id": image_id,
                "file_name": relative_name,
                "width": crop_width,
                "height": crop_height,
                "source_match_id": record["source_context"]["source_match_id"],
                "source_player_match_id": record["match"]["id"],
                "at_ms": record["at_ms"],
                "source_frame_sha256": record["source_frame_sha256"],
                "source_video_sha256": record["source_context"]["source_video_sha256"],
                "roi": record["roi"],
                "player_status": record["player_status"],
                "enemy_status": record["enemy_status"],
            }
            split_images[split].append(image_entry)
            image_annotations = []
            for (category, _), bbox in zip(all_boxes, transformed):
                annotation = {
                    "id": annotation_id,
                    "image_id": image_id,
                    "category_id": CATEGORY_IDS[category],
                    "bbox": [round(float(value), 6) for value in bbox],
                    "area": round(float(bbox[2] * bbox[3]), 6),
                    "iscrowd": 0,
                }
                image_annotations.append(annotation)
                split_annotations[split].append(annotation)
                annotation_id += 1
            image_id += 1
            report = audit["splits"][split]
            report["images"] += 1
            report["annotations"] += len(image_annotations)
            report["source_matches"].append(record["source_context"]["source_match_id"])
            if not record["player_boxes"]:
                report["negative_player_images"] += 1
            if not record["enemy_boxes"]:
                report["negative_enemy_images"] += 1
            if not all_boxes:
                report["negative_images"] += 1
            for category, _ in all_boxes:
                report["boxes_by_class"][category] += 1
        for split in OUTPUT_SPLITS:
            split_dir = temporary / f"{split}2017"
            split_dir.mkdir(parents=True, exist_ok=True)
            (temporary / "annotations").mkdir(parents=True, exist_ok=True)
            document = _coco_document(
                split, split_images[split], split_annotations[split], audit,
                split_roi_audits[split],
            )
            (temporary / "annotations" / f"instances_{split}2017.json").write_text(
                json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # Keep an empty test artifact explicit so existing COCO/YOLOX audits
        # can identify the absence of an independent test split.
        (temporary / "test2017").mkdir(parents=True, exist_ok=True)
        (temporary / "annotations" / "instances_test2017.json").write_text(
            json.dumps(
                _coco_document("test", [], [], audit, _new_roi_boundary_audit()),
                ensure_ascii=False, indent=2,
            ) + "\n",
            encoding="utf-8",
        )
        audit["counts"]["exported_images"] = sum(item["images"] for item in audit["splits"].values())
        audit["counts"]["exported_annotations"] = sum(item["annotations"] for item in audit["splits"].values())
        audit["counts"]["boxes_by_class"] = {
            category: sum(item["boxes_by_class"][category] for item in audit["splits"].values())
            for category in CLASSES
        }
        audit["counts"]["negative_images"] = sum(item["negative_images"] for item in audit["splits"].values())
        audit["counts"]["negative_player_images"] = sum(item["negative_player_images"] for item in audit["splits"].values())
        audit["counts"]["negative_enemy_images"] = sum(item["negative_enemy_images"] for item in audit["splits"].values())
        for report in audit["splits"].values():
            report["source_matches"] = sorted(set(report["source_matches"]))
        if audit["conflicts"] or audit["missing"]:
            raise ExportBlocked("audit contains unresolved source conflicts or missing labels")
        audit["checks"]["all_output_boxes_inside_crop"] = True
        os.replace(temporary, output)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def export(
    player_queue: Path,
    output: Path,
    *,
    audit_path: Path | None = None,
    strict_source_database_hash: bool = False,
    verify_video_bytes: bool = False,
) -> dict[str, Any]:
    """Export the checked dataset and return its audit document.

    ``strict_source_database_hash`` defaults to false because the recorded
    source DB snapshot can legitimately differ after a SQLite checkpoint or a
    schema migration.  A raw hash drift is always recorded as a warning, while
    the source manifest and every DB row are compared semantically.  Callers
    may set it true when an immutable byte snapshot is required.
    """
    player_queue = Path(player_queue).resolve()
    output = Path(output).resolve()
    audit_path = (Path(audit_path).resolve() if audit_path is not None
                  else output / "audit.json")
    audit = _new_audit(player_queue, output, audit_path)
    player_connection: sqlite3.Connection | None = None
    try:
        document, matches, samples, player_connection = _load_player_queue(player_queue, audit)
        records = _prepare_records(
            player_queue, document, samples, audit,
            strict_source_database_hash=strict_source_database_hash,
            verify_video_bytes=verify_video_bytes,
        )
        _write_dataset(records, output, audit)
        audit["status"] = "passed_with_warnings" if audit.get("warnings") else "passed"
        audit["checks"]["all_output_boxes_inside_crop"] = True
    except Exception as error:
        if isinstance(error, ExportBlocked):
            audit["status"] = "blocked"
            audit["fatal_error"] = str(error)
        else:
            audit["status"] = "blocked"
            audit["fatal_error"] = f"{type(error).__name__}: {error}"
        # The audit must remain available even if a source DB or image fails.
        _write_audit(audit_path, audit)
        raise
    finally:
        if player_connection is not None:
            player_connection.close()
        # Source connections are deliberately read-only and are closed after
        # validation; SQLite tolerates the process ending with no writes.
    _write_audit(output / "audit.json", audit)
    if audit_path != output / "audit.json":
        _write_audit(audit_path, audit)
    return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--player-queue", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--audit", type=Path, help="Audit JSON path (default: <output>/audit.json)")
    parser.add_argument(
        "--require-source-database-hash", action="store_true",
        help="Require the recorded source DB raw SHA-256 snapshot to match (default uses full semantic manifest/DB validation and audits drift as a warning).",
    )
    parser.add_argument(
        "--verify-video-bytes", action="store_true",
        help="Hash each resolved allowed source video file; this may read many gigabytes.",
    )
    args = parser.parse_args()
    try:
        audit = export(
            args.player_queue, args.output, audit_path=args.audit,
            strict_source_database_hash=args.require_source_database_hash,
            verify_video_bytes=args.verify_video_bytes,
        )
        print(json.dumps({
            "status": audit["status"],
            "output": audit["output"]["dataset"],
            "audit": audit["output"]["audit"],
            "counts": audit["counts"],
            "splits": audit["splits"],
            "excluded": len(audit["excluded"]),
            "missing": len(audit["missing"]),
            "conflicts": len(audit["conflicts"]),
            "warnings": len(audit.get("warnings", [])),
        }, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, sqlite3.DatabaseError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
