#!/usr/bin/env python3
"""Safely merge a completed minimap-player active review batch.

The active batch is a review-only copy of the original queue.  Its SQLite
``id`` values are local to the copy, so the only supported join key is
``sample.active_learning_source.source_task_id``.  Before changing anything
this command validates that join against the source task's match, timestamp,
and media paths.  It reads only JSON and SQLite metadata; it never opens a
frame, overlay, or source video.

The command is a dry-run by default.  ``--apply`` is required to update the
source queue.  An apply creates a fresh backup directory containing the
source manifest and a consistent SQLite backup, then records an audit of the
mapping.  Learned suggestions are intentionally ignored; only human
``reviewed_*`` fields from terminal active tasks are copied.

Example::

    python training/merge_minimap_player_active_review.py \
        --source data/private/minimap-player-review-queue-v1 \
        --active data/private/minimap-player-active-review-v2

    python training/merge_minimap_player_active_review.py \
        --source data/private/minimap-player-review-queue-v1 \
        --active data/private/minimap-player-active-review-v2 \
        --apply --backup-dir /tmp/minimap-player-merge-backup
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import shutil
import sqlite3
import tempfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote


SCHEMA = "mapassist.minimap_player_active_review_merge_audit"
SCHEMA_VERSION = 1
ACTIVE_BATCH_SCHEMA = "mapassist.minimap_player_active_batch"
KIND = "minimap_player"
SEALED_PREFIXES = ("video9", "video12")
FINAL_STATUSES = frozenset({"corrected", "negative", "skip", "excluded"})
REQUIRED_TASK_COLUMNS = frozenset({
    "id", "match_id", "split", "at_ms", "selection", "frame", "overlay",
    "suggested_boxes", "suggested_categories", "directions", "review_status",
    "reviewed_boxes", "reviewed_categories", "reviewed_by", "reviewed_at",
    "lease_owner", "lease_until", "version",
})


def _sealed(value: object) -> bool:
    """Return whether a match/path token names one of the sealed videos."""
    if not isinstance(value, str):
        return False
    token = value.casefold().replace("_", "-")
    # Match identifiers in this project are prefixed with videoN.  Checking
    # the prefix avoids opening or even resolving a media file from a sealed
    # source while still rejecting video9-player and video12-hd-player.
    return any(
        part.startswith(SEALED_PREFIXES)
        for part in token.replace("\\", "/").split("/")
    )


def _json_file(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError(f"missing required manifest: {path}") from error
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid JSON manifest: {path}: {error}") from error


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _read_only_connection(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise ValueError(f"missing required SQLite database: {path}")
    # The URI is important: validation must not create, migrate, or otherwise
    # modify either queue.  In particular, do not construct AnnotationStore
    # here because it initializes/migrates a database on open.
    uri = f"file:{quote(str(path.resolve()))}?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout = 10000")
    return connection


def _task_columns(connection: sqlite3.Connection, label: str) -> set[str]:
    columns = {row[1] for row in connection.execute("PRAGMA table_info(tasks)")}
    missing = sorted(REQUIRED_TASK_COLUMNS - columns)
    if missing:
        raise ValueError(f"{label} tasks schema is missing columns: {missing}")
    return columns


def _task_rows(path: Path, label: str) -> list[dict[str, Any]]:
    connection = _read_only_connection(path)
    try:
        _task_columns(connection, label)
        rows = [dict(row) for row in connection.execute(
            "SELECT * FROM tasks ORDER BY id"
        )]
    except sqlite3.DatabaseError as error:
        raise ValueError(f"cannot read {label} tasks database: {error}") from error
    finally:
        connection.close()
    return rows


def _validate_root(root: Path, label: str) -> tuple[Path, dict[str, Any]]:
    root = root.resolve()
    if not root.is_dir():
        raise ValueError(f"{label} queue directory does not exist: {root}")
    manifest_path = root / "review-manifest.json"
    document = _json_file(manifest_path)
    if not isinstance(document, dict):
        raise ValueError(f"{label} manifest must be an object")
    if document.get("schema_version") != 1:
        raise ValueError(f"{label} manifest must use schema_version=1")
    if document.get("kind") != KIND:
        raise ValueError(f"{label} manifest must have kind={KIND}")
    classes = document.get("classes")
    if (not isinstance(classes, list) or classes != [KIND]):
        raise ValueError(f"{label} manifest must have classes=[{KIND!r}]")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError(f"{label} manifest needs a non-empty matches list")
    return root, document


def _index_manifest(document: dict[str, Any], label: str) -> dict[tuple[str, int], dict[str, Any]]:
    indexed: dict[tuple[str, int], dict[str, Any]] = {}
    match_ids: set[str] = set()
    for match in document["matches"]:
        if not isinstance(match, dict):
            raise ValueError(f"{label} manifest contains a non-object match")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id:
            raise ValueError(f"{label} match needs a non-empty id")
        if _sealed(match_id):
            raise ValueError(
                f"sealed video9/video12 match is present in {label}: {match_id}"
            )
        if match_id in match_ids:
            raise ValueError(f"{label} manifest has duplicate match id: {match_id}")
        match_ids.add(match_id)
        split = match.get("split")
        if not isinstance(split, str) or not split:
            raise ValueError(f"{label} match {match_id} needs a split")
        samples = match.get("samples")
        if not isinstance(samples, list) or not samples:
            raise ValueError(f"{label} match {match_id} needs samples")
        for sample in samples:
            if not isinstance(sample, dict):
                raise ValueError(f"{label} match {match_id} has a non-object sample")
            at_ms = sample.get("at_ms")
            if not isinstance(at_ms, int) or isinstance(at_ms, bool):
                raise ValueError(f"{label} sample {match_id} has invalid at_ms")
            for media_key in ("frame", "overlay"):
                media = sample.get(media_key)
                if not isinstance(media, str) or not media:
                    raise ValueError(
                        f"{label} sample {match_id}@{at_ms} needs {media_key}"
                    )
                # This is a string-only sealed-source guard.  We deliberately
                # do not call is_file(), open(), or Image.open() on media.
                if _sealed(media):
                    raise ValueError(
                        f"sealed video9/video12 media is present in {label}: {media}"
                    )
            key = (match_id, at_ms)
            if key in indexed:
                raise ValueError(f"{label} manifest has duplicate sample: {key}")
            indexed[key] = {
                "match_id": match_id,
                "split": split,
                "sample": sample,
            }
    return indexed


def _check_db_manifest_identity(
    rows: list[dict[str, Any]],
    samples: dict[tuple[str, int], dict[str, Any]],
    label: str,
) -> dict[int, dict[str, Any]]:
    by_id: dict[int, dict[str, Any]] = {}
    by_key: dict[tuple[str, int], dict[str, Any]] = {}
    for row in rows:
        task_id = row.get("id")
        if not isinstance(task_id, int) or isinstance(task_id, bool):
            raise ValueError(f"{label} task has invalid id: {task_id!r}")
        if task_id in by_id:
            raise ValueError(f"{label} database has duplicate task id: {task_id}")
        match_id = row.get("match_id")
        at_ms = row.get("at_ms")
        key = (match_id, at_ms)
        if key in by_key:
            raise ValueError(f"{label} database has duplicate identity: {key}")
        by_id[task_id] = row
        by_key[key] = row
        if _sealed(match_id):
            raise ValueError(
                f"sealed video9/video12 match is present in {label} database: {match_id}"
            )
        manifest_entry = samples.get(key)
        if manifest_entry is None:
            raise ValueError(f"{label} database row is missing from manifest: {key}")
        if row.get("split") != manifest_entry["split"]:
            raise ValueError(f"{label} split mismatch at {key}")
        sample = manifest_entry["sample"]
        for field in ("frame", "overlay"):
            if row.get(field) != sample.get(field):
                raise ValueError(f"{label} {field} mismatch at {key}")
        if row.get("review_status") != sample.get("review_status", "pending"):
            raise ValueError(f"{label} review_status mismatch at {key}")
        for field in ("reviewed_boxes", "reviewed_categories"):
            raw = row.get(field)
            expected = sample.get(field)
            decoded = json.loads(raw) if raw is not None else None
            if decoded != expected:
                raise ValueError(f"{label} {field} mismatch at {key}")
        for field in ("reviewed_by", "reviewed_at"):
            if row.get(field) != sample.get(field):
                raise ValueError(f"{label} {field} mismatch at {key}")
    db_keys = set(by_key)
    manifest_keys = set(samples)
    if db_keys != manifest_keys:
        missing = sorted(manifest_keys - db_keys)
        extra = sorted(db_keys - manifest_keys)
        raise ValueError(
            f"{label} database/manifest sample set mismatch; "
            f"missing={missing[:5]}, extra={extra[:5]}"
        )
    return by_id


def _decode_json_field(row: dict[str, Any], field: str, label: str) -> Any:
    raw = row.get(field)
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} has invalid {field} JSON") from error


def _validated_boxes(value: Any, label: str) -> list[list[float]]:
    if not isinstance(value, list):
        raise ValueError(f"{label} must be a list")
    boxes: list[list[float]] = []
    for box in value:
        if (not isinstance(box, list) or len(box) != 4 or
                any(isinstance(number, bool) or not isinstance(number, (int, float))
                    for number in box)):
            raise ValueError(f"{label} contains an invalid box")
        numbers = [float(number) for number in box]
        if any(not _finite(number) for number in numbers):
            raise ValueError(f"{label} contains a non-finite box")
        x, y, width, height = numbers
        if (x < 0 or y < 0 or width <= 0 or height <= 0 or
                x + width > 1.000001 or y + height > 1.000001):
            raise ValueError(f"{label} contains an out-of-bounds box")
        boxes.append(box)
    return boxes


def _finite(value: float) -> bool:
    # Importing math just for this tiny helper makes the validation intent
    # explicit and keeps bools excluded by the caller above.
    return value == value and value not in (float("inf"), float("-inf"))


def _validate_active_rows(
    active_rows: dict[int, dict[str, Any]],
    active_samples: dict[tuple[str, int], dict[str, Any]],
) -> list[dict[str, Any]]:
    """Validate terminal human results and return normalized active records."""
    records: list[dict[str, Any]] = []
    for key, entry in sorted(active_samples.items()):
        sample = entry["sample"]
        # The active DB identity was already checked against this manifest
        # sample.  Use the key to locate it without trusting its local id.
        active_row = next(
            row for row in active_rows.values()
            if (row["match_id"], row["at_ms"]) == key
        )
        status = active_row.get("review_status")
        if status == "pending":
            raise ValueError(f"active batch still has pending task: {key}")
        if status == "accepted":
            raise ValueError(
                f"active task {active_row['id']} treats a machine suggestion as truth: {key}"
            )
        if status not in FINAL_STATUSES:
            raise ValueError(f"active task {active_row['id']} has invalid final status: {status!r}")
        if active_row.get("lease_owner") is not None or active_row.get("lease_until") is not None:
            raise ValueError(f"active task {active_row['id']} still has a lease: {key}")
        reviewer = active_row.get("reviewed_by")
        reviewed_at = active_row.get("reviewed_at")
        if not isinstance(reviewer, str) or not reviewer.strip() or not isinstance(reviewed_at, str) or not reviewed_at.strip():
            raise ValueError(f"active task {active_row['id']} has no human review audit: {key}")

        reviewed_boxes = _decode_json_field(active_row, "reviewed_boxes", f"active task {active_row['id']}")
        reviewed_categories = _decode_json_field(active_row, "reviewed_categories", f"active task {active_row['id']}")
        if status == "corrected":
            boxes = _validated_boxes(reviewed_boxes, f"active task {active_row['id']} reviewed_boxes")
            if len(boxes) != 1:
                raise ValueError(
                    f"active task {active_row['id']} has {len(boxes)} player boxes; exactly one is required"
                )
            if reviewed_categories != [KIND]:
                raise ValueError(
                    f"active task {active_row['id']} has invalid reviewed_categories"
                )
        else:
            # A negative/skip/excluded result has no human target box.  Any
            # boxes here would either be malformed or an accidental promotion
            # of the machine suggestion.
            if reviewed_boxes is not None or reviewed_categories is not None:
                raise ValueError(
                    f"active task {active_row['id']} has boxes for {status}; "
                    "suggestions cannot become truth"
                )
            boxes = None

        source_ref = sample.get("active_learning_source")
        if not isinstance(source_ref, dict):
            raise ValueError(f"active sample {key} lacks active_learning_source")
        source_task_id = source_ref.get("source_task_id")
        if not isinstance(source_task_id, int) or isinstance(source_task_id, bool):
            raise ValueError(f"active sample {key} has invalid source_task_id")
        if source_ref.get("is_ground_truth") is not False:
            raise ValueError(
                f"active sample {key} marks machine active_learning_source as truth"
            )
        suggestion_metadata = sample.get("suggestion_metadata")
        if not isinstance(suggestion_metadata, dict) or suggestion_metadata.get("is_ground_truth") is not False:
            raise ValueError(f"active sample {key} has invalid suggestion provenance")

        records.append({
            "active_task_id": active_row["id"],
            "source_task_id": source_task_id,
            "match_id": key[0],
            "at_ms": key[1],
            "frame": active_row["frame"],
            "overlay": active_row["overlay"],
            "review_status": status,
            "reviewed_boxes": boxes,
            "reviewed_categories": [KIND] if status == "corrected" else None,
            "reviewed_by": reviewer,
            "reviewed_at": reviewed_at,
        })
    source_ids = [record["source_task_id"] for record in records]
    if len(source_ids) != len(set(source_ids)):
        duplicates = sorted({task_id for task_id in source_ids if source_ids.count(task_id) > 1})
        raise ValueError(f"active batch has duplicate source_task_id values: {duplicates}")
    return records


def _database_content_hash(rows: list[dict[str, Any]]) -> str:
    return _canonical_sha256(sorted(rows, key=lambda row: row["id"]))


def _source_queue_hint(active_root: Path, active_document: dict[str, Any], source_root: Path) -> None:
    active_learning = active_document.get("active_learning")
    if not isinstance(active_learning, dict):
        raise ValueError("active manifest needs an active_learning object")
    if active_learning.get("schema") != ACTIVE_BATCH_SCHEMA:
        raise ValueError("active manifest has an unexpected active_learning schema")
    if active_learning.get("schema_version") != 1:
        raise ValueError("active manifest active_learning must use schema_version=1")
    source_queue = active_learning.get("source_queue")
    if not isinstance(source_queue, str) or not source_queue:
        raise ValueError("active manifest active_learning needs source_queue")
    hinted = Path(source_queue)
    if not hinted.is_absolute():
        hinted = (active_root / hinted).resolve()
    else:
        hinted = hinted.resolve()
    if hinted != source_root:
        raise ValueError(
            f"active batch source_queue does not match source queue: {hinted} != {source_root}"
        )


def _build_plan(source: Path, active: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    source_root, source_document = _validate_root(source, "source")
    active_root, active_document = _validate_root(active, "active")
    if source_root == active_root:
        raise ValueError("source and active queues must be different directories")
    _source_queue_hint(active_root, active_document, source_root)

    source_samples = _index_manifest(source_document, "source")
    active_samples = _index_manifest(active_document, "active")
    source_rows = _task_rows(source_root / "annotations.sqlite3", "source")
    active_rows_list = _task_rows(active_root / "annotations.sqlite3", "active")
    source_by_id = _check_db_manifest_identity(source_rows, source_samples, "source")
    active_by_id = _check_db_manifest_identity(active_rows_list, active_samples, "active")
    records = _validate_active_rows(active_by_id, active_samples)

    for record in records:
        source_row = source_by_id.get(record["source_task_id"])
        if source_row is None:
            raise ValueError(
                f"active source_task_id does not exist in source queue: {record['source_task_id']}"
            )
        source_key = (source_row["match_id"], source_row["at_ms"])
        expected_key = (record["match_id"], record["at_ms"])
        if source_key != expected_key:
            raise ValueError(
                f"identity mismatch for source_task_id {record['source_task_id']}: "
                f"source={source_key}, active={expected_key}"
            )
        for field in ("frame", "overlay"):
            if source_row[field] != record[field]:
                raise ValueError(
                    f"identity mismatch for source_task_id {record['source_task_id']} {field}"
                )
        if source_row.get("lease_owner") is not None or source_row.get("lease_until") is not None:
            raise ValueError(f"source task {record['source_task_id']} still has a lease")
        source_sample = source_samples[source_key]["sample"]
        source_status = source_row.get("review_status")
        if source_status == "pending":
            if (source_row.get("reviewed_boxes") is not None or
                    source_row.get("reviewed_categories") is not None or
                    source_row.get("reviewed_by") is not None or
                    source_row.get("reviewed_at") is not None):
                raise ValueError(
                    f"source task {record['source_task_id']} is pending but has review data"
                )
            record["source_needs_update"] = True
        elif source_status in FINAL_STATUSES:
            source_boxes = _decode_json_field(
                source_row, "reviewed_boxes", f"source task {record['source_task_id']}"
            )
            source_categories = _decode_json_field(
                source_row, "reviewed_categories", f"source task {record['source_task_id']}"
            )
            if (
                source_status != record["review_status"] or
                source_boxes != record["reviewed_boxes"] or
                source_categories != record["reviewed_categories"] or
                source_row.get("reviewed_by") != record["reviewed_by"] or
                source_row.get("reviewed_at") != record["reviewed_at"]
            ):
                raise ValueError(
                    f"source task {record['source_task_id']} already has a conflicting review"
                )
            record["source_needs_update"] = False
        else:
            raise ValueError(
                f"source task {record['source_task_id']} has invalid status: {source_status!r}"
            )
        if source_sample.get("review_status", "pending") != source_status:
            raise ValueError(f"source manifest task {record['source_task_id']} is inconsistent")
        record["source_version"] = source_row["version"]

    if not records:
        raise ValueError("active batch has no review samples")
    records = sorted(records, key=lambda row: (row["source_task_id"], row["active_task_id"]))
    return source_document, active_document, source_by_id, records


def _default_backup_dir(source: Path) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    candidate = source / "backups" / f"active-review-merge-{stamp}"
    suffix = 1
    while candidate.exists():
        candidate = source / "backups" / f"active-review-merge-{stamp}-{suffix}"
        suffix += 1
    return candidate


def _ensure_backup_location(backup_dir: Path, source: Path, active: Path) -> Path:
    backup_dir = backup_dir.resolve()
    if backup_dir in (source, active):
        raise ValueError("backup directory must differ from both queues")
    if active in backup_dir.parents:
        raise ValueError("backup directory cannot be inside the active queue")
    # A backup under ``source/backups`` is the default and is safe.  Reject a
    # directory that is itself a parent of either queue, which could make a
    # later restore ambiguous or allow the backup to overlap both inputs.
    if backup_dir in source.parents or backup_dir in active.parents:
        raise ValueError("backup directory cannot be a parent of a queue directory")
    if backup_dir.exists():
        raise ValueError(f"backup directory already exists: {backup_dir}")
    backup_dir.parent.mkdir(parents=True, exist_ok=True)
    backup_dir.mkdir()
    return backup_dir


def _write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _copy_atomic(source: Path, destination: Path) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output, source.open("rb") as input_file:
            shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
        shutil.copystat(source, temporary)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def _backup_database(source: Path, destination: Path) -> None:
    """Create a consistent SQLite backup without holding a write lock.

    SQLite's backup API waits forever when called from a connection that owns
    ``BEGIN IMMEDIATE`` on some WAL builds.  Take the consistent backup first,
    then acquire the write lock and compare the complete logical database
    snapshot before committing.  A concurrent change therefore aborts the
    apply instead of producing a backup for the wrong source state.
    """
    connection = _read_only_connection(source)
    backup = sqlite3.connect(destination)
    try:
        connection.backup(backup)
        backup.commit()
    finally:
        connection.close()
        backup.close()


def _apply_plan(
    source: Path,
    active: Path,
    source_document: dict[str, Any],
    source_by_id: dict[int, dict[str, Any]],
    records: list[dict[str, Any]],
    audit: dict[str, Any],
    backup_dir: Path,
) -> dict[str, Any]:
    backup_dir = _ensure_backup_location(backup_dir, source, active)
    source_manifest = source / "review-manifest.json"
    source_database = source / "annotations.sqlite3"
    backup_manifest = backup_dir / "review-manifest.json"
    backup_database = backup_dir / "annotations.sqlite3"
    _copy_atomic(source_manifest, backup_manifest)
    _backup_database(source_database, backup_database)
    if (_sha256(active / "review-manifest.json") != audit["active_manifest_sha256"] or
            _database_content_hash(_task_rows(active / "annotations.sqlite3", "active")) !=
            audit["active_database_content_sha256"]):
        raise ValueError("active batch changed during validation")
    updates = [record for record in records if record["source_needs_update"]]

    if not updates:
        # A completed merge is a valid no-op.  This makes a dry-run/apply
        # workflow repeatable while still refusing any conflicting source
        # label during validation.
        if (_sha256(source_manifest) != audit["source_manifest_sha256_before"] or
                _database_content_hash(_task_rows(source_database, "source")) !=
                audit["source_database_content_sha256_before"]):
            raise ValueError("source changed while preparing idempotent merge")
        audit["mode"] = "apply-noop"
        audit["backup_dir"] = str(backup_dir)
        audit["source_manifest_sha256_after"] = _sha256(source_manifest)
        audit["source_database_content_sha256_after"] = audit[
            "source_database_content_sha256_before"
        ]
        audit_path = backup_dir / "audit.json"
        audit["audit_path"] = str(audit_path)
        _write_json_atomic(audit_path, audit)
        return audit

    updated_document = copy.deepcopy(source_document)
    indexed = _index_manifest(updated_document, "source")
    for record in updates:
        sample = indexed[(record["match_id"], record["at_ms"])]["sample"]
        sample["review_status"] = record["review_status"]
        sample["reviewed_boxes"] = copy.deepcopy(record["reviewed_boxes"])
        sample["reviewed_categories"] = copy.deepcopy(record["reviewed_categories"])
        sample["reviewed_by"] = record["reviewed_by"]
        sample["reviewed_at"] = record["reviewed_at"]

    # Stage the new manifest before taking the database write lock.  It is
    # still invisible under the real path until the DB transaction succeeds.
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".review-manifest.merge-", suffix=".tmp", dir=source
    )
    staged_manifest = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(updated_document, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())

        connection = sqlite3.connect(source_database, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 10000")
        try:
            connection.execute("BEGIN IMMEDIATE")
            _task_columns(connection, "source")
            if _sha256(source_manifest) != audit["source_manifest_sha256_before"]:
                raise ValueError("source manifest changed during validation")
            # Recheck the exact rows while the write lock is held.  A review
            # server cannot race an apply into overwriting a newly reviewed
            # source task.
            for record in records:
                row = connection.execute(
                    "SELECT * FROM tasks WHERE id = ?", (record["source_task_id"],)
                ).fetchone()
                if row is None:
                    raise ValueError(f"source task disappeared: {record['source_task_id']}")
                current = dict(row)
                if (current["match_id"], current["at_ms"], current["frame"], current["overlay"]) != (
                    record["match_id"], record["at_ms"], record["frame"], record["overlay"]
                ):
                    raise ValueError(f"source task identity changed: {record['source_task_id']}")
                if current["lease_owner"] is not None or current["lease_until"] is not None:
                    raise ValueError(f"source task lease appeared: {record['source_task_id']}")
                if record["source_needs_update"]:
                    if current["review_status"] != "pending":
                        raise ValueError(
                            f"source task is no longer pending: {record['source_task_id']}"
                        )
                else:
                    current_boxes = _decode_json_field(
                        current, "reviewed_boxes", f"source task {record['source_task_id']}"
                    )
                    current_categories = _decode_json_field(
                        current, "reviewed_categories", f"source task {record['source_task_id']}"
                    )
                    if (
                        current["review_status"] != record["review_status"] or
                        current_boxes != record["reviewed_boxes"] or
                        current_categories != record["reviewed_categories"] or
                        current["reviewed_by"] != record["reviewed_by"] or
                        current["reviewed_at"] != record["reviewed_at"]
                    ):
                        raise ValueError(
                            f"source task changed since validation: {record['source_task_id']}"
                        )
                if current["version"] != record["source_version"]:
                    raise ValueError(f"source task changed since validation: {record['source_task_id']}")
            current_rows = [dict(row) for row in connection.execute(
                "SELECT * FROM tasks ORDER BY id"
            )]
            if _database_content_hash(current_rows) != audit[
                "source_database_content_sha256_before"
            ]:
                raise ValueError("source database changed during validation")
            for record in updates:
                updated = connection.execute(
                    """
                    UPDATE tasks SET review_status = ?, reviewed_boxes = ?,
                        reviewed_categories = ?, reviewed_by = ?, reviewed_at = ?,
                        lease_owner = NULL, lease_until = NULL, version = version + 1
                    WHERE id = ? AND review_status = 'pending'
                      AND lease_owner IS NULL AND lease_until IS NULL
                      AND version = ?
                    """,
                    (
                        record["review_status"],
                        json.dumps(record["reviewed_boxes"], ensure_ascii=False)
                        if record["reviewed_boxes"] is not None else None,
                        json.dumps(record["reviewed_categories"], ensure_ascii=False)
                        if record["reviewed_categories"] is not None else None,
                        record["reviewed_by"], record["reviewed_at"],
                        record["source_task_id"], record["source_version"],
                    ),
                )
                if updated.rowcount != 1:
                    raise ValueError(
                        f"source task update precondition failed: {record['source_task_id']}"
                    )
            connection.execute("COMMIT")
        except Exception:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

        try:
            if _sha256(source_manifest) != audit["source_manifest_sha256_before"]:
                raise ValueError("source manifest changed before commit")
            os.replace(staged_manifest, source_manifest)
        except Exception:
            # Restore the database from the backup if the second half of the
            # two-file commit cannot complete.  No annotation server is
            # allowed to write while this process owns the SQLite transaction;
            # this recovery is best-effort for filesystem failures.
            restore = sqlite3.connect(source_database)
            try:
                backup = sqlite3.connect(backup_database)
                try:
                    backup.backup(restore)
                    restore.commit()
                finally:
                    backup.close()
            finally:
                restore.close()
            _copy_atomic(backup_manifest, source_manifest)
            raise
    finally:
        staged_manifest.unlink(missing_ok=True)

    audit["mode"] = "apply"
    audit["backup_dir"] = str(backup_dir)
    audit["source_manifest_sha256_after"] = _sha256(source_manifest)
    after_rows = _task_rows(source_database, "source")
    audit["source_database_content_sha256_after"] = _database_content_hash(after_rows)
    audit_path = backup_dir / "audit.json"
    _write_json_atomic(audit_path, audit)
    audit["audit_path"] = str(audit_path)
    # The audit file itself records its path; rewrite it once with that field
    # so recovery tooling and humans see the same complete JSON document.
    _write_json_atomic(audit_path, audit)
    return audit


def merge(
    source: Path,
    active: Path,
    *,
    apply: bool = False,
    backup_dir: Path | None = None,
) -> dict[str, Any]:
    """Validate, and optionally apply, an active review merge.

    ``apply=False`` performs no writes and is the safe default.  The returned
    audit is deterministic except for the backup path and apply timestamp.
    """
    source = source.resolve()
    active = active.resolve()
    if backup_dir is not None and not apply:
        raise ValueError("--backup-dir requires --apply; dry-run does not write files")
    source_document, active_document, source_by_id, records = _build_plan(source, active)
    active_manifest = active / "review-manifest.json"
    active_database = active / "annotations.sqlite3"
    source_manifest = source / "review-manifest.json"
    source_database = source / "annotations.sqlite3"
    counts = Counter(record["review_status"] for record in records)
    mapping = [
        {
            "active_task_id": record["active_task_id"],
            "source_task_id": record["source_task_id"],
            "match_id": record["match_id"],
            "at_ms": record["at_ms"],
            "review_status": record["review_status"],
            "reviewed_by": record["reviewed_by"],
            "reviewed_at": record["reviewed_at"],
            "reviewed_box_count": len(record["reviewed_boxes"] or []),
            "action": "update" if record["source_needs_update"] else "already_applied",
        }
        for record in records
    ]
    audit: dict[str, Any] = {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "mode": "dry-run",
        "source_queue": str(source),
        "active_batch": str(active),
        "source_manifest_sha256_before": _sha256(source_manifest),
        "source_database_content_sha256_before": _database_content_hash(
            list(source_by_id.values())
        ),
        "active_manifest_sha256": _sha256(active_manifest),
        "active_database_sha256": _sha256(active_database),
        "active_database_content_sha256": _database_content_hash(
            _task_rows(active_database, "active")
        ),
        "mapping_sha256": _canonical_sha256(mapping),
        "mapping": mapping,
        "counts": dict(sorted(counts.items())),
        "would_update": sum(record["source_needs_update"] for record in records),
        "already_applied": sum(
            not record["source_needs_update"] for record in records
        ),
        "suggestions_used_as_truth": False,
        "suggestion_fields_ignored": [
            "suggested_boxes", "suggested_categories", "suggestion_metadata",
            "active_learning_source",
        ],
    }
    if not apply:
        return audit
    if backup_dir is None:
        backup_dir = _default_backup_dir(source)
    return _apply_plan(
        source, active, source_document, source_by_id, records, audit, backup_dir
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True,
                        help="original minimap_player review queue")
    parser.add_argument("--active", type=Path, required=True,
                        help="completed active review batch")
    parser.add_argument("--apply", action="store_true",
                        help="write terminal human results back to --source")
    parser.add_argument("--backup-dir", type=Path,
                        help="new empty directory for the pre-apply backup")
    args = parser.parse_args()
    try:
        result = merge(args.source, args.active, apply=args.apply,
                       backup_dir=args.backup_dir)
    except (OSError, sqlite3.DatabaseError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
