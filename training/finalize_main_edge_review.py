"""Audit and finalize a completed main-screen edge review batch.

The edge batch is a human-reviewed diagnostic sample of red-bar candidates.
This command validates the copied review batch before exporting a fresh
detection manifest.  It reads SQLite through a read-only connection, never
opens source videos, and refuses any ``video9``/``video12`` identifier before
touching media.  The selection-time ``audit.json`` and ``summary.json`` are
left in place as historical records; completion output is written to
``review-completion-audit.json`` and ``detection-manifest.json`` by default.

The resulting manifest is deliberately marked as a red-candidate and
hard-negative diagnostic.  A red health bar can belong to a minion or neutral
monster, and camera drift means a screen target is not necessarily a nearby
player threat.  The result therefore cannot be used directly as enemy-hero
training truth or release capability evidence.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import re
import sqlite3
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any
from urllib.parse import quote

from PIL import Image

from mapassist.finalize_review import finalize
from mapassist.orientation import from_manifest


SCHEMA_VERSION = 1
SOURCE_KIND = "main_enemy"
DIAGNOSTIC_KIND = "main_red_candidate"
FINAL_STATUSES = frozenset({"accepted", "corrected", "negative", "skip", "excluded"})
REQUIRED_TASK_COLUMNS = frozenset({
    "id", "match_id", "split", "at_ms", "selection", "frame", "overlay",
    "suggested_boxes", "suggested_categories", "directions", "review_status",
    "reviewed_boxes", "reviewed_categories", "reviewed_by", "reviewed_at",
    "lease_owner", "lease_until", "version",
})
HISTORY_NAMES = ("audit.json", "summary.json")
_SEALED_COMPONENT = re.compile(
    r"^video[-_]?(?:9|12)(?:$|[-_.])", re.IGNORECASE
)


def _sealed(value: object) -> bool:
    """Return whether a token names a sealed video or path component."""
    if not isinstance(value, str):
        return False
    return any(
        _SEALED_COMPONENT.match(part) is not None
        for part in value.replace("\\", "/").split("/")
    )


def _json_file(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError(f"missing {label}: {path}") from error
    except OSError as error:
        raise ValueError(f"cannot read {label}: {path}") from error
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid JSON in {label}: {path}") from error


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _read_only_connection(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise ValueError(f"missing annotations database: {path}")
    uri = f"file:{quote(str(path.resolve()))}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True)
    except sqlite3.Error as error:
        raise ValueError(f"cannot open annotations database read-only: {path}") from error
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout = 10000")
    return connection


def _database_rows_from_connection(
    connection: sqlite3.Connection, path: Path,
) -> list[dict[str, Any]]:
    try:
        columns = {row[1] for row in connection.execute("PRAGMA table_info(tasks)")}
        missing = sorted(REQUIRED_TASK_COLUMNS - columns)
        if missing:
            raise ValueError(f"tasks schema is missing columns: {missing}")
        return [dict(row) for row in connection.execute("SELECT * FROM tasks ORDER BY id")]
    except sqlite3.DatabaseError as error:
        raise ValueError(f"cannot read tasks database: {path}") from error


def _open_database_snapshot(
    path: Path,
) -> tuple[sqlite3.Connection, list[dict[str, Any]]]:
    """Open one read-only transaction and return its stable task snapshot."""
    connection = _read_only_connection(path)
    try:
        connection.execute("BEGIN")
        return connection, _database_rows_from_connection(connection, path)
    except sqlite3.DatabaseError as error:
        connection.close()
        raise ValueError(f"cannot read tasks database: {path}") from error
    except Exception:
        connection.close()
        raise


def _close_database_snapshot(connection: sqlite3.Connection) -> None:
    try:
        connection.rollback()
    except sqlite3.Error:
        pass
    finally:
        connection.close()


def _database_rows(path: Path) -> list[dict[str, Any]]:
    connection, rows = _open_database_snapshot(path)
    try:
        return rows
    finally:
        _close_database_snapshot(connection)


def _database_content_sha256(rows: list[dict[str, Any]]) -> str:
    try:
        ordered = sorted(rows, key=lambda row: row.get("id", 0))
        return _canonical_sha256(ordered)
    except (TypeError, ValueError) as error:
        raise ValueError("annotations database contains values that cannot be hashed") from error


def _decode_json(value: object, label: str) -> Any:
    if value is None:
        return None
    try:
        return json.loads(value) if isinstance(value, str) else value
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is not valid JSON") from error


def _finite(number: float) -> bool:
    return math.isfinite(number)


def _validated_boxes(value: object, label: str, *, allow_none: bool = False) -> list[list[float]] | None:
    if value is None and allow_none:
        return None
    if not isinstance(value, list):
        raise ValueError(f"{label} must be a list")
    boxes: list[list[float]] = []
    for box in value:
        if (not isinstance(box, list) or len(box) != 4 or
                any(isinstance(item, bool) or not isinstance(item, (int, float))
                    for item in box)):
            raise ValueError(f"{label} contains an invalid [x, y, width, height] box")
        numbers = [float(item) for item in box]
        if any(not _finite(item) for item in numbers):
            raise ValueError(f"{label} contains a non-finite box")
        x, y, width, height = numbers
        if (x < 0 or y < 0 or width <= 0 or height <= 0 or
                x + width > 1.000001 or y + height > 1.000001):
            raise ValueError(f"{label} contains a box outside normalized frame")
        boxes.append(list(box))
    return boxes


def _validated_categories(value: object, boxes: list[list[float]], classes: list[str],
                          label: str, *, allow_none: bool = False) -> list[str] | None:
    if value is None and allow_none:
        return None
    if not isinstance(value, list) or len(value) != len(boxes):
        raise ValueError(f"{label} must contain one category per box")
    if any(not isinstance(item, str) or not item.strip() or item not in classes
           for item in value):
        raise ValueError(f"{label} contains an unknown or empty class")
    return list(value)


def _orientation(data: dict[str, Any], label: str,
                 fallback: dict[str, Any] | None = None) -> dict[str, Any]:
    value = from_manifest(data, label)
    if value is None and fallback is not None:
        value = from_manifest(fallback, f"{label} fallback")
    if value is None:
        raise ValueError(f"{label} needs orientation metadata")
    return value


def _metadata(data: dict[str, Any], label: str,
              fallback: dict[str, Any] | None = None) -> dict[str, Any]:
    match_id = data.get("id")
    if not isinstance(match_id, str) or not match_id:
        raise ValueError(f"{label} needs a non-empty id")
    if _sealed(match_id):
        raise ValueError(f"sealed video9/video12 match is present: {match_id}")
    video = data.get("video")
    if not isinstance(video, str) or not video:
        video = data.get("source_video_path")
    if not isinstance(video, str) or not video:
        raise ValueError(f"{label} needs video or source_video_path")
    if _sealed(video):
        raise ValueError(f"sealed video9/video12 media is present: {video}")
    try:
        resolved_video = Path(video).expanduser().resolve()
    except (OSError, RuntimeError, ValueError) as error:
        raise ValueError(f"{label} has an invalid video path: {video}") from error
    if _sealed(str(resolved_video)):
        raise ValueError(f"sealed video9/video12 media is present: {resolved_video}")
    video_sha256 = data.get("video_sha256")
    if not isinstance(video_sha256, str) or not video_sha256:
        video_sha256 = data.get("source_video_sha256")
    if (not isinstance(video_sha256, str) or len(video_sha256) != 64 or
            any(character not in "0123456789abcdefABCDEF" for character in video_sha256)):
        raise ValueError(f"{label} video_sha256 must be 64 hexadecimal characters")
    return {
        "video": video,
        "video_sha256": video_sha256.lower(),
        "orientation": _orientation(data, label, fallback),
    }


def _source_manifest_path(batch: Path, document: dict[str, Any], explicit: Path | None) -> Path:
    if explicit is not None:
        path = explicit.expanduser().resolve()
    else:
        source_queue: object = None
        for key in ("provenance", "sampling"):
            value = document.get(key)
            if isinstance(value, dict) and isinstance(value.get("source_queue"), str):
                source_queue = value["source_queue"]
                break
        if not isinstance(source_queue, str) or not source_queue:
            fallback = batch.parent / "queue"
            path = (fallback / "review-manifest.json").resolve()
        else:
            if _sealed(source_queue):
                raise ValueError("source queue path names sealed video9/video12")
            candidate = Path(source_queue).expanduser()
            if not candidate.is_absolute():
                candidate = batch / candidate
            path = (candidate if candidate.suffix.lower() == ".json"
                    else candidate / "review-manifest.json").resolve()
    if _sealed(str(path)):
        raise ValueError("source manifest path names sealed video9/video12")
    return path


def _load_manifest(batch: Path) -> tuple[dict[str, Any], dict[tuple[str, int], dict[str, Any]], list[str]]:
    path = batch / "review-manifest.json"
    document = _json_file(path, "review manifest")
    if not isinstance(document, dict):
        raise ValueError("review manifest must be an object")
    if document.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("review manifest must use schema_version=1")
    if document.get("kind") != SOURCE_KIND:
        raise ValueError(f"review manifest must have kind={SOURCE_KIND}")
    if document.get("review_mode") != "manual":
        raise ValueError("main edge completion requires review_mode=manual")
    classes_value = document.get("classes", [SOURCE_KIND])
    if (not isinstance(classes_value, list) or not classes_value or
            any(not isinstance(item, str) or not item.strip() for item in classes_value)):
        raise ValueError("review manifest classes must be a non-empty list")
    classes = [item.strip() for item in classes_value]
    if len(set(classes)) != len(classes) or classes != [SOURCE_KIND]:
        raise ValueError("review manifest classes must be exactly [main_enemy]")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("review manifest needs a non-empty matches list")
    root_orientation = from_manifest(document)
    indexed: dict[tuple[str, int], dict[str, Any]] = {}
    match_ids: set[str] = set()
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError("review manifest contains a non-object match")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id:
            raise ValueError("review manifest match needs a non-empty id")
        if _sealed(match_id):
            raise ValueError(f"sealed video9/video12 match is present: {match_id}")
        if match_id in match_ids:
            raise ValueError(f"review manifest has duplicate match id: {match_id}")
        match_ids.add(match_id)
        split = match.get("split")
        if not isinstance(split, str) or not split:
            raise ValueError(f"review manifest match {match_id} needs a split")
        metadata = _metadata(match, f"review match {match_id}", document)
        samples = match.get("samples")
        if not isinstance(samples, list) or not samples:
            raise ValueError(f"review match {match_id} needs samples")
        for sample in samples:
            if not isinstance(sample, dict):
                raise ValueError(f"review match {match_id} contains a non-object sample")
            at_ms = sample.get("at_ms")
            if not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0:
                raise ValueError(f"review sample {match_id} has invalid at_ms")
            key = (match_id, at_ms)
            if key in indexed:
                raise ValueError(f"review manifest has duplicate sample: {key}")
            for media_key in ("frame", "overlay"):
                media = sample.get(media_key)
                if not isinstance(media, str) or not media:
                    raise ValueError(f"review sample {match_id}@{at_ms} needs {media_key}")
                if _sealed(media):
                    raise ValueError(f"sealed video9/video12 media is present: {media}")
            indexed[key] = {
                "match": match,
                "match_id": match_id,
                "split": split,
                "metadata": metadata,
                "sample": sample,
            }
        # Ensure the root orientation fallback is parsed even if all matches
        # have their own orientation.  This catches malformed root metadata.
        if root_orientation is not None:
            _orientation({}, f"review root", {"orientation": root_orientation})
    return document, indexed, classes


def _load_source_metadata(source_manifest: Path, target_ids: set[str]) -> tuple[str, dict[str, dict[str, Any]]]:
    document = _json_file(source_manifest, "source review manifest")
    if not isinstance(document, dict):
        raise ValueError("source review manifest must be an object")
    if document.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("source review manifest must use schema_version=1")
    if document.get("kind") not in (None, SOURCE_KIND):
        raise ValueError("source review manifest kind does not match main_enemy")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("source review manifest needs a non-empty matches list")
    root_orientation = from_manifest(document)
    found: dict[str, dict[str, Any]] = {}
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError("source review manifest contains a non-object match")
        match_id = match.get("id")
        # Sealed entries are never inspected or opened.  The batch itself is
        # rejected if it names one, while unrelated source entries are ignored.
        if not isinstance(match_id, str) or not match_id:
            raise ValueError("source review manifest match needs a non-empty id")
        if isinstance(match_id, str) and _sealed(match_id):
            continue
        if match_id not in target_ids:
            continue
        if match_id in found:
            raise ValueError(f"source review manifest has duplicate match id: {match_id}")
        split = match.get("split")
        if not isinstance(split, str) or not split:
            raise ValueError(f"source match {match_id} needs a split")
        found[match_id] = {
            "split": split,
            "metadata": _metadata(match, f"source match {match_id}", document),
        }
        # A malformed source root orientation is only relevant when used as a
        # fallback, but parse it when declared so failures are deterministic.
        if root_orientation is not None:
            _orientation({}, "source root", {"orientation": root_orientation})
    missing = sorted(target_ids - set(found))
    if missing:
        raise ValueError(f"source review manifest is missing batch matches: {missing}")
    return _sha256(source_manifest), found


def _safe_media_path(batch: Path, value: object, label: str) -> Path:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a non-empty relative path")
    if _sealed(value):
        raise ValueError(f"sealed video9/video12 media is present: {value}")
    candidate = Path(value)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError(f"{label} must stay inside the review batch: {value}")
    resolved = (batch / candidate).resolve()
    if resolved == batch or batch not in resolved.parents:
        raise ValueError(f"{label} escapes the review batch: {value}")
    if _sealed(str(resolved)):
        raise ValueError(f"sealed video9/video12 media is present: {resolved}")
    if resolved.suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp"}:
        raise ValueError(f"{label} is not a supported image: {value}")
    if not resolved.is_file():
        raise ValueError(f"missing {label}: {resolved}")
    return resolved


def _check_image(path: Path, expected_size: tuple[int, int] | None, label: str) -> tuple[int, int]:
    try:
        with Image.open(path) as image:
            size = image.size
            image.load()
    except (OSError, ValueError) as error:
        raise ValueError(f"cannot decode {label}: {path}") from error
    if expected_size is not None and size != expected_size:
        raise ValueError(
            f"{label} is {size[0]}x{size[1]}, expected {expected_size[0]}x{expected_size[1]}"
        )
    return size


def _check_source_metadata(indexed: dict[tuple[str, int], dict[str, Any]],
                           source: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    by_match: dict[str, dict[str, Any]] = {}
    for entry in indexed.values():
        match_id = entry["match_id"]
        if match_id in by_match:
            continue
        expected = source[match_id]
        actual = entry["metadata"]
        if (actual != expected["metadata"] or
                entry["match"].get("split") != expected["split"]):
            raise ValueError(f"review/source metadata mismatch for match {match_id}")
        by_match[match_id] = {
            "id": match_id,
            "split": entry["match"]["split"],
            **copy.deepcopy(actual),
        }
    return [by_match[key] for key in sorted(by_match)]


def _validate_rows(
    batch: Path,
    document: dict[str, Any],
    indexed: dict[tuple[str, int], dict[str, Any]],
    classes: list[str],
    rows: list[dict[str, Any]],
) -> dict[str, Any]:
    if not rows:
        raise ValueError("review batch database contains no tasks")
    by_key: dict[tuple[str, int], dict[str, Any]] = {}
    by_id: set[int] = set()
    statuses: Counter[str] = Counter()
    leases = {"owner": 0, "until": 0, "any": 0}
    boxes_by_status: Counter[str] = Counter()
    image_entries: list[dict[str, Any]] = []
    dimensions: dict[str, dict[str, dict[str, int]]] = defaultdict(dict)
    per_match: dict[str, Counter[str]] = defaultdict(Counter)
    for row in rows:
        task_id = row.get("id")
        if not isinstance(task_id, int) or isinstance(task_id, bool) or task_id in by_id:
            raise ValueError(f"tasks contains duplicate or invalid id: {task_id!r}")
        by_id.add(task_id)
        match_id = row.get("match_id")
        at_ms = row.get("at_ms")
        if not isinstance(match_id, str) or not match_id:
            raise ValueError(f"task {task_id} has an invalid match_id: {match_id!r}")
        if not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0:
            raise ValueError(f"task {task_id} has an invalid at_ms: {at_ms!r}")
        key = (match_id, at_ms)
        if key in by_key:
            raise ValueError(f"tasks contains duplicate identity: {key}")
        by_key[key] = row
        if key not in indexed:
            raise ValueError(f"database task is missing from review manifest: {key}")
        entry = indexed[key]
        sample = entry["sample"]
        if _sealed(match_id):
            raise ValueError(f"sealed video9/video12 match is present in database: {match_id}")
        if row.get("split") != entry["split"]:
            raise ValueError(f"split mismatch at {key}")
        for field in ("selection", "frame", "overlay"):
            if row.get(field) != sample.get(field):
                raise ValueError(f"{field} mismatch at {key}")

        suggested_boxes = _validated_boxes(
            _decode_json(row.get("suggested_boxes"), f"task {task_id} suggested_boxes"),
            f"task {task_id} suggested_boxes",
        )
        suggested_categories = _validated_categories(
            _decode_json(row.get("suggested_categories"), f"task {task_id} suggested_categories"),
            suggested_boxes or [], classes, f"task {task_id} suggested_categories",
        )
        directions = _decode_json(row.get("directions"), f"task {task_id} directions")
        if (not isinstance(directions, list) or len(directions) != len(suggested_boxes or []) or
                any(not isinstance(direction, str) or direction not in {"left", "right"}
                    for direction in directions)):
            raise ValueError(f"task {task_id} directions must align with suggested boxes")
        if suggested_boxes != sample.get("suggested_boxes"):
            raise ValueError(f"suggested_boxes mismatch at {key}")
        if suggested_categories != sample.get("suggested_categories"):
            raise ValueError(f"suggested_categories mismatch at {key}")
        if directions != sample.get("directions"):
            raise ValueError(f"directions mismatch at {key}")

        status = row.get("review_status")
        if not isinstance(status, str) or status not in FINAL_STATUSES:
            raise ValueError(f"task {task_id} is not terminal: {status!r}")
        if status == "accepted":
            raise ValueError(
                f"task {task_id} uses accepted machine suggestions; manual review requires corrected or negative"
            )
        if status != sample.get("review_status"):
            raise ValueError(f"review_status mismatch at {key}")
        reviewed_boxes = _validated_boxes(
            _decode_json(row.get("reviewed_boxes"), f"task {task_id} reviewed_boxes"),
            f"task {task_id} reviewed_boxes", allow_none=True,
        )
        reviewed_categories = _decode_json(
            row.get("reviewed_categories"), f"task {task_id} reviewed_categories"
        )
        if status == "corrected":
            if not reviewed_boxes:
                raise ValueError(f"corrected task {task_id} needs at least one reviewed box")
            reviewed_categories = _validated_categories(
                reviewed_categories, reviewed_boxes, classes,
                f"task {task_id} reviewed_categories",
            )
            boxes_by_status[status] += len(reviewed_boxes)
        else:
            if reviewed_boxes is not None or reviewed_categories is not None:
                raise ValueError(f"{status} task {task_id} must not carry reviewed boxes")
        if reviewed_boxes != sample.get("reviewed_boxes"):
            raise ValueError(f"reviewed_boxes mismatch at {key}")
        if reviewed_categories != sample.get("reviewed_categories"):
            raise ValueError(f"reviewed_categories mismatch at {key}")
        for field in ("reviewed_by", "reviewed_at"):
            value = row.get(field)
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"terminal task {task_id} needs {field}")
            if value != sample.get(field):
                raise ValueError(f"{field} mismatch at {key}")
        version = row.get("version")
        if not isinstance(version, int) or isinstance(version, bool) or version < 1:
            raise ValueError(f"task {task_id} has invalid version")
        if row.get("lease_owner") is not None:
            leases["owner"] += 1
        if row.get("lease_until") is not None:
            leases["until"] += 1
        if row.get("lease_owner") is not None or row.get("lease_until") is not None:
            leases["any"] += 1

        metadata = entry["metadata"]
        orientation = metadata["orientation"]
        display_size = orientation.get("display_size")
        expected_size = tuple(display_size) if isinstance(display_size, list) else None
        for media_key in ("frame", "overlay"):
            path = _safe_media_path(batch, sample.get(media_key), f"{key} {media_key}")
            size = _check_image(path, expected_size, f"{key} {media_key}")
            image_entries.append({
                "match_id": match_id, "at_ms": at_ms, "kind": media_key,
                "path": sample[media_key], "size": list(size), "sha256": _sha256(path),
            })
            dimensions[match_id][media_key] = {"width": size[0], "height": size[1]}
        statuses[status] += 1
        per_match[match_id][status] += 1

    manifest_keys = set(indexed)
    db_keys = set(by_key)
    if manifest_keys != db_keys:
        raise ValueError(
            "database/manifest sample set mismatch; "
            f"missing={sorted(manifest_keys - db_keys)[:5]}, "
            f"extra={sorted(db_keys - manifest_keys)[:5]}"
        )
    if leases["any"]:
        raise ValueError(f"completed batch still has {leases['any']} leased tasks")
    pending = sum(1 for row in rows if row.get("review_status") == "pending")
    if pending:
        raise ValueError(f"completed batch still has {pending} pending tasks")
    if any(status not in FINAL_STATUSES for status in statuses):
        raise AssertionError("unexpected status after validation")
    return {
        "rows": len(rows),
        "status_counts": dict(sorted(statuses.items())),
        "pending": pending,
        "leases": leases,
        "boxes_by_status": dict(sorted(boxes_by_status.items())),
        "boxes": sum(boxes_by_status.values()),
        "per_match": {
            match_id: dict(sorted(counts.items()))
            for match_id, counts in sorted(per_match.items())
        },
        "images": {
            "media_files": len(image_entries),
            "frame_files": sum(item["kind"] == "frame" for item in image_entries),
            "overlay_files": sum(item["kind"] == "overlay" for item in image_entries),
            "dimensions_by_match": {
                match_id: dimensions[match_id]
                for match_id in sorted(dimensions)
            },
            "image_manifest_sha256": _canonical_sha256(sorted(
                image_entries, key=lambda item: (item["match_id"], item["at_ms"], item["kind"])
            )),
        },
    }


def _expected_export(document: dict[str, Any], indexed: dict[tuple[str, int], dict[str, Any]]) -> dict[tuple[str, int], dict[str, Any]]:
    expected: dict[tuple[str, int], dict[str, Any]] = {}
    for key, entry in indexed.items():
        sample = entry["sample"]
        status = sample.get("review_status")
        if status in {"skip", "excluded"}:
            continue
        if status == "accepted":
            boxes = sample.get("suggested_boxes")
            categories = sample.get("suggested_categories")
        elif status == "corrected":
            boxes = sample.get("reviewed_boxes")
            categories = sample.get("reviewed_categories")
        elif status == "negative":
            boxes, categories = [], []
        else:
            raise ValueError(f"cannot export non-terminal sample: {key}")
        expected[key] = {
            "match_id": key[0], "split": entry["split"], "at_ms": key[1],
            "boxes": boxes or [], "categories": categories or [],
            "metadata": entry["metadata"],
        }
    return expected


def _video_references_equal(expected: str, actual: object,
                            review_manifest: Path) -> bool:
    if not isinstance(actual, str) or not actual:
        return False
    if actual == expected:
        return True
    try:
        def resolve_like_finalizer(value: str) -> Path | None:
            candidate = Path(value).expanduser()
            if candidate.is_absolute():
                return candidate.resolve()
            for directory in (review_manifest.parent, *review_manifest.parent.parents):
                resolved = (directory / candidate).resolve()
                if resolved.is_file():
                    return resolved
            return None

        expected_path = resolve_like_finalizer(expected)
        actual_path = resolve_like_finalizer(actual)
        if expected_path is None or actual_path is None:
            return False
        return expected_path == actual_path
    except (OSError, RuntimeError, ValueError):
        return False


def _validate_detection_output(path: Path, expected: dict[tuple[str, int], dict[str, Any]],
                               classes: list[str], scope: dict[str, Any],
                               review_manifest: Path) -> dict[str, Any]:
    output = _json_file(path, "detection manifest")
    if not isinstance(output, dict) or output.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("detection manifest must use schema_version=1")
    if output.get("category") != DIAGNOSTIC_KIND:
        raise ValueError("detection manifest category does not match main_red_candidate")
    matches = output.get("matches")
    if not isinstance(matches, list):
        raise ValueError("detection manifest needs matches")
    observed: dict[tuple[str, int], dict[str, Any]] = {}
    match_ids: set[str] = set()
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError("detection manifest contains a non-object match")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id or _sealed(match_id):
            raise ValueError(f"invalid or sealed detection match: {match_id!r}")
        if match_id in match_ids:
            raise ValueError(f"detection manifest has duplicate match: {match_id}")
        match_ids.add(match_id)
        match_expected = next(
            (item for item in expected.values() if item["match_id"] == match_id), None
        )
        if match_expected is None:
            raise ValueError(f"detection manifest contains an unexpected match: {match_id}")
        if match.get("split") != match_expected["split"]:
            raise ValueError(f"detection match split mismatch: {match_id}")
        metadata = match_expected["metadata"]
        if not _video_references_equal(metadata["video"], match.get("video"), review_manifest):
            raise ValueError(f"detection match video metadata mismatch: {match_id}")
        if match.get("video_sha256") != metadata["video_sha256"]:
            raise ValueError(f"detection match video_sha256 mismatch: {match_id}")
        output_orientation = match.get("orientation")
        if output_orientation is None:
            output_orientation = output.get("orientation")
        if output_orientation != metadata["orientation"]:
            raise ValueError(f"detection match orientation mismatch: {match_id}")
        frames = match.get("frames")
        if not isinstance(frames, list):
            raise ValueError(f"detection match {match_id} needs frames")
        for frame in frames:
            if not isinstance(frame, dict):
                raise ValueError(f"detection match {match_id} has a non-object frame")
            at_ms = frame.get("at_ms")
            if not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0:
                raise ValueError(
                    f"detection frame {match_id} has an invalid at_ms: {at_ms!r}"
                )
            key = (match_id, at_ms)
            if key in observed:
                raise ValueError(f"detection manifest has duplicate frame: {key}")
            boxes = _validated_boxes(frame.get("boxes"), f"detection {key} boxes")
            categories = frame.get("categories", [])
            categories = _validated_categories(categories, boxes or [], classes,
                                               f"detection {key} categories")
            observed[key] = {"boxes": boxes or [], "categories": categories or []}
    if set(observed) != set(expected):
        raise ValueError(
            "detection manifest sample set mismatch; "
            f"missing={sorted(set(expected) - set(observed))[:5]}, "
            f"extra={sorted(set(observed) - set(expected))[:5]}"
        )
    for key, expected_row in expected.items():
        actual = observed[key]
        if actual != {"boxes": expected_row["boxes"], "categories": expected_row["categories"]}:
            raise ValueError(f"detection labels mismatch at {key}")
    if output.get("classes") != classes:
        raise ValueError("detection manifest classes are missing or incorrect")
    if output.get("dataset_scope") != scope:
        raise ValueError("detection manifest dataset scope metadata is missing or incorrect")
    return {
        "matches": len(matches),
        "frames": len(observed),
        "boxes": sum(len(frame["boxes"]) for frame in observed.values()),
            "sha256": _sha256(path),
    }


def _dataset_scope() -> dict[str, Any]:
    return {
        "label_semantics": "red_candidate_hard_negative_diagnostic",
        "source_annotation_category": SOURCE_KIND,
        "export_category": DIAGNOSTIC_KIND,
        "training_truth": False,
        "enemy_hero_training_truth": False,
        "release_eligible": False,
        "machine_suggestions_used_as_truth": False,
        "warning": (
            "A red health bar can belong to a minion or neutral monster. "
            "Camera drift also means a screen target is not necessarily a nearby player threat. "
            "Use this batch only for red-candidate and hard-negative diagnostics; "
            "do not use it directly as enemy-hero training truth or release capability evidence."
        ),
    }


def _has_symlink_component(path: Path) -> bool:
    candidate = path.expanduser()
    if not candidate.is_absolute():
        candidate = Path.cwd() / candidate
    for component in (candidate, *candidate.parents):
        try:
            if component.is_symlink():
                return True
        except OSError as error:
            raise ValueError(f"cannot inspect output path: {path}") from error
    return False


def _output_path(value: Path, label: str) -> Path:
    raw = Path(value).expanduser()
    if raw.suffix.lower() != ".json":
        raise ValueError(f"{label} must use a .json suffix: {value}")
    if _has_symlink_component(raw):
        raise ValueError(f"{label} must not use a symlink path: {value}")
    try:
        resolved = raw.resolve()
    except (OSError, RuntimeError, ValueError) as error:
        raise ValueError(f"cannot resolve {label}: {value}") from error
    if resolved.exists() and not resolved.is_file():
        raise ValueError(f"{label} must be a file path: {value}")
    return resolved


def _validate_output_paths(
    output_path: Path, audit_path: Path,
    input_paths: dict[str, Path],
) -> None:
    if output_path == audit_path:
        raise ValueError("completion outputs must be different paths")
    for output_label, output_path_value in (
        ("detection output", output_path), ("audit output", audit_path),
    ):
        for input_label, input_path in input_paths.items():
            if (output_path_value == input_path or
                    output_path_value in input_path.parents or
                    input_path in output_path_value.parents):
                raise ValueError(
                    f"{output_label} must not overlap {input_label}: {output_path_value}"
                )


def _batch_input_path(batch: Path, name: str, label: str) -> Path:
    raw = batch / name
    if _has_symlink_component(raw):
        raise ValueError(f"{label} must not use a symlink path: {raw}")
    try:
        return raw.resolve()
    except (OSError, RuntimeError, ValueError) as error:
        raise ValueError(f"cannot resolve {label}: {raw}") from error


def _verify_finalization_inputs(
    manifest_path: Path,
    manifest_hash: str,
    source_path: Path,
    source_hash: str,
    history_paths: dict[str, Path],
    history: dict[str, dict[str, Any]],
) -> None:
    if _sha256(manifest_path) != manifest_hash:
        raise ValueError("review manifest changed during finalization")
    if _sha256(source_path) != source_hash:
        raise ValueError("source review manifest changed during finalization")
    for name, path in history_paths.items():
        expected_hash = history[name]["sha256"]
        if _sha256(path) != expected_hash:
            raise ValueError(f"selection history {name} changed during finalization")


def _temporary_file(path: Path) -> Path:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    os.close(descriptor)
    return Path(temporary_name)


def _stage_json(path: Path, value: Any) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = _temporary_file(path)
    try:
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        return temporary
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _backup_existing(path: Path) -> Path | None:
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"completion output must be a regular file: {path}")
    backup = _temporary_file(path)
    backup.unlink(missing_ok=True)
    os.replace(path, backup)
    return backup


def _unlink_quietly(path: Path | None) -> None:
    if path is None:
        return
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


def _commit_outputs(
    staged_detection: Path, detection_path: Path,
    staged_audit: Path, audit_path: Path,
) -> None:
    """Replace both outputs with rollback if either replacement fails."""
    detection_backup: Path | None = None
    audit_backup: Path | None = None
    detection_installed = False
    audit_installed = False
    preserve_backups = False
    try:
        detection_backup = _backup_existing(detection_path)
        audit_backup = _backup_existing(audit_path)
        os.replace(staged_detection, detection_path)
        detection_installed = True
        os.replace(staged_audit, audit_path)
        audit_installed = True
    except BaseException:
        # Remove newly installed files before restoring the previous pair.
        if audit_installed:
            _unlink_quietly(audit_path)
        if detection_installed:
            _unlink_quietly(detection_path)
        try:
            if audit_backup is not None:
                os.replace(audit_backup, audit_path)
                audit_backup = None
            if detection_backup is not None:
                os.replace(detection_backup, detection_path)
                detection_backup = None
        except BaseException as restore_error:
            preserve_backups = True
            preserved = ", ".join(
                str(path) for path in (detection_backup, audit_backup)
                if path is not None
            ) or "<none>"
            raise RuntimeError(
                "cannot roll back completion outputs; "
                f"preserved backups: {preserved}"
            ) from restore_error
        raise
    finally:
        if not preserve_backups:
            _unlink_quietly(detection_backup)
            _unlink_quietly(audit_backup)


def finalize_main_edge_review(batch: Path, *, source_manifest: Path | None = None,
                              detection_output: Path | None = None,
                              audit_output: Path | None = None) -> dict[str, Any]:
    """Validate and write a rollback-capable pair of completion outputs."""
    batch = batch.expanduser().resolve()
    if not batch.is_dir():
        raise ValueError(f"review batch directory does not exist: {batch}")
    manifest_path = _batch_input_path(batch, "review-manifest.json", "review manifest")
    database_path = _batch_input_path(batch, "annotations.sqlite3", "annotations database")
    history_paths = {
        name: _batch_input_path(batch, name, f"selection history {name}")
        for name in HISTORY_NAMES
    }
    document, indexed, source_classes = _load_manifest(batch)
    manifest_hash_before = _sha256(manifest_path)
    source_path = _source_manifest_path(batch, document, source_manifest)
    source_path = source_path.resolve()
    if source_path == manifest_path:
        raise ValueError("source review manifest must differ from the batch manifest")
    source_hash, source_metadata = _load_source_metadata(
        source_path, {entry["match_id"] for entry in indexed.values()}
    )
    source_records = _check_source_metadata(indexed, source_metadata)
    history: dict[str, dict[str, Any]] = {}
    for name in HISTORY_NAMES:
        history_path = history_paths[name]
        if not history_path.is_file():
            raise ValueError(f"selection history is missing: {history_path}")
        history[name] = {
            "path": str(history_path),
            "exists": True,
            "sha256": _sha256(history_path),
        }
    scope = _dataset_scope()
    output_classes = [DIAGNOSTIC_KIND]
    output_path = _output_path(
        detection_output or batch / "detection-manifest.json", "detection output"
    )
    audit_path = _output_path(
        audit_output or batch / "review-completion-audit.json", "audit output"
    )
    input_paths = {
        "batch manifest": manifest_path,
        "annotations database": database_path,
        "source manifest": source_path,
        **{f"selection history {name}": path for name, path in history_paths.items()},
    }
    _validate_output_paths(output_path, audit_path, input_paths)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    audit_path.parent.mkdir(parents=True, exist_ok=True)

    database_connection, rows = _open_database_snapshot(database_path)
    try:
        summary = _validate_rows(batch, document, indexed, source_classes, rows)
        expected = _expected_export(document, indexed)
        if not expected:
            raise ValueError(
                "completed batch contains only skip/excluded samples; "
                "there is no detection manifest to export"
            )
        for frame in expected.values():
            frame["categories"] = [DIAGNOSTIC_KIND] * len(frame["boxes"])
        database_hash_before = _database_content_sha256(rows)
        temporary_output: Path | None = None
        temporary_audit: Path | None = None
        try:
            temporary_output = _temporary_file(output_path)
            finalize(manifest_path, temporary_output)
            detection_document = _json_file(temporary_output, "staged detection manifest")
            detection_document["source_category"] = SOURCE_KIND
            detection_document["category"] = DIAGNOSTIC_KIND
            detection_document["classes"] = output_classes
            for match in detection_document.get("matches", []):
                for frame in match.get("frames", []):
                    frame["categories"] = [
                        DIAGNOSTIC_KIND for _ in frame.get("boxes", [])
                    ]
            detection_document["dataset_scope"] = scope
            detection_document["warning"] = scope["warning"]
            _write_json_atomic(temporary_output, detection_document)
            # Validate the staged file before replacing either existing output.
            detection_summary = _validate_detection_output(
                temporary_output, expected, output_classes, scope, manifest_path,
            )
            _verify_finalization_inputs(
                manifest_path, manifest_hash_before,
                source_path, source_hash,
                history_paths, history,
            )
            current_rows = _database_rows(database_path)
            if _database_content_sha256(current_rows) != database_hash_before:
                raise ValueError("annotations database changed during finalization")

            audit = {
                "schema_version": SCHEMA_VERSION,
                "kind": "main_edge_review_completion_audit",
                "batch": str(batch),
                "review_manifest": {
                    "path": str(manifest_path),
                    "sha256": manifest_hash_before,
                },
                "database": {
                    "path": str(database_path),
                    "content_sha256": database_hash_before,
                    "read_only": True,
                },
                "source": {
                    "manifest": str(source_path),
                    "manifest_sha256": source_hash,
                    "matches": source_records,
                    "video_bytes_read": False,
                    "sealed_video9_video12_read": False,
                },
                "terminal": summary,
                "selection_history": history,
                "dataset_scope": scope,
                "detection_manifest": {
                    "path": str(output_path),
                    **detection_summary,
                },
                "audit_path": str(audit_path),
            }
            # Stage audit before committing detection.  Both old outputs remain
            # untouched until the pair is ready for the rollback-capable swap.
            temporary_audit = _stage_json(audit_path, audit)
            _verify_finalization_inputs(
                manifest_path, manifest_hash_before,
                source_path, source_hash,
                history_paths, history,
            )
            _commit_outputs(
                temporary_output, output_path, temporary_audit, audit_path,
            )
            temporary_output = None
            temporary_audit = None
            return audit
        finally:
            _unlink_quietly(temporary_output)
            _unlink_quietly(temporary_audit)
    finally:
        _close_database_snapshot(database_connection)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "batch", nargs="?", type=Path,
        default=Path("data/private/main-edge-review-v1/review-batch-v1"),
    )
    parser.add_argument("--source-manifest", type=Path)
    parser.add_argument("--detection-output", type=Path)
    parser.add_argument("--audit-output", type=Path)
    args = parser.parse_args()
    try:
        audit = finalize_main_edge_review(
            args.batch,
            source_manifest=args.source_manifest,
            detection_output=args.detection_output,
            audit_output=args.audit_output,
        )
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print(json.dumps({
        "audit": audit["audit_path"],
        "detection_manifest": audit["detection_manifest"]["path"],
        "rows": audit["terminal"]["rows"],
        "status_counts": audit["terminal"]["status_counts"],
        "boxes": audit["detection_manifest"]["boxes"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
