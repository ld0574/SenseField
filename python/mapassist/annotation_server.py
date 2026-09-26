"""Serve a collaborative local web UI for reviewing detector suggestions."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import webbrowser
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


FINAL_STATUSES = {"accepted", "corrected", "negative", "skip", "excluded"}
ALL_STATUSES = {"pending", *FINAL_STATUSES}
ANNOTATOR = re.compile(r"^[^\x00-\x1f\x7f]{1,40}$")
CONTEXT_OFFSETS_MS = (-500, 500)


def _validate_boxes(boxes: object) -> list[list[float]]:
    if not isinstance(boxes, list):
        raise ValueError("boxes must be a list")
    result = []
    for box in boxes:
        if (not isinstance(box, list) or len(box) != 4 or
                any(not isinstance(value, (int, float)) or isinstance(value, bool)
                    for value in box)):
            raise ValueError("each box must be [x, y, width, height]")
        x, y, width, height = (float(value) for value in box)
        if (x < 0 or y < 0 or width <= 0 or height <= 0 or
                x + width > 1.000001 or y + height > 1.000001):
            raise ValueError("box is outside the normalized frame")
        result.append([round(x, 7), round(y, 7), round(width, 7), round(height, 7)])
    return result


def _validate_roi(value: object, label: str = "roi") -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    roi = [float(item) for item in value]
    x, y, width, height = roi
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return roi


def _annotator(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("annotator is required")
    value = value.strip()
    if not ANNOTATOR.fullmatch(value):
        raise ValueError("annotator must be 1–40 printable characters")
    return value


class ConflictError(RuntimeError):
    pass


class AnnotationStore:
    def __init__(self, dataset: Path, database: Path | None = None,
                 lease_seconds: int = 15 * 60) -> None:
        self.dataset = dataset.resolve()
        self.manifest = self.dataset / "review-manifest.json"
        if not self.manifest.is_file():
            raise ValueError(f"Missing review-manifest.json in {self.dataset}")
        self.database = (database or self.dataset / "annotations.sqlite3").resolve()
        self.lease_seconds = lease_seconds
        self._export_lock = threading.Lock()
        self._context_lock = threading.Lock()
        self._media_files: set[str] = set()
        self._videos: dict[str, Path] = {}
        self._ffmpeg = shutil.which("ffmpeg")
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 10000")
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def _initialize(self) -> None:
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        if data.get("schema_version") != 1 or not isinstance(data.get("matches"), list):
            raise ValueError("Expected review manifest schema_version 1 with matches")
        self.kind = data.get("kind")
        self.review_mode = data.get("review_mode", "suggestion")
        if self.review_mode not in {"suggestion", "blind", "manual"}:
            raise ValueError("review_mode must be suggestion, blind, or manual")
        self.roi = _validate_roi(data.get("roi"), "Review manifest roi")
        self.match_rois: dict[str, list[float]] = {}
        self.database.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id INTEGER PRIMARY KEY,
                    match_id TEXT NOT NULL,
                    split TEXT NOT NULL,
                    at_ms INTEGER NOT NULL,
                    selection TEXT NOT NULL,
                    frame TEXT NOT NULL,
                    overlay TEXT NOT NULL,
                    suggested_boxes TEXT NOT NULL,
                    directions TEXT NOT NULL,
                    review_status TEXT NOT NULL DEFAULT 'pending',
                    reviewed_boxes TEXT,
                    reviewed_by TEXT,
                    reviewed_at TEXT,
                    lease_owner TEXT,
                    lease_until REAL,
                    version INTEGER NOT NULL DEFAULT 1,
                    UNIQUE(match_id, at_ms)
                );
                CREATE INDEX IF NOT EXISTS tasks_status ON tasks(review_status, id);
                CREATE INDEX IF NOT EXISTS tasks_lease ON tasks(lease_until);
            """)
            connection.execute("BEGIN IMMEDIATE")
            try:
                for match in data["matches"]:
                    match_id = match.get("id")
                    if not isinstance(match_id, str) or not match_id:
                        raise ValueError("Each review match needs an id")
                    self.match_rois[match_id] = _validate_roi(
                        match.get("roi", self.roi), f"{match_id} roi"
                    )
                    video_value = match.get("video")
                    if isinstance(match_id, str) and isinstance(video_value, str):
                        video = Path(video_value)
                        if not video.is_absolute():
                            video = (self.dataset / video).resolve()
                        else:
                            video = video.resolve()
                        if video.is_file():
                            self._videos[match_id] = video
                    for sample in match.get("samples", []):
                        for media_key in ("frame", "overlay"):
                            relative = sample.get(media_key)
                            if not isinstance(relative, str) or not relative:
                                raise ValueError(f"Review sample needs {media_key}")
                            candidate = (self.dataset / relative).resolve()
                            if (self.dataset not in candidate.parents or
                                    candidate.suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp"}):
                                raise ValueError(f"Invalid review media path: {relative}")
                            self._media_files.add(relative)
                        status = sample.get("review_status", "pending")
                        if status not in ALL_STATUSES:
                            status = "pending"
                        boxes = sample.get("reviewed_boxes")
                        connection.execute("""
                            INSERT INTO tasks (
                                match_id, split, at_ms, selection, frame, overlay,
                                suggested_boxes, directions, review_status,
                                reviewed_boxes, reviewed_by, reviewed_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(match_id, at_ms) DO UPDATE SET
                                split=excluded.split,
                                selection=excluded.selection,
                                frame=excluded.frame,
                                overlay=excluded.overlay,
                                suggested_boxes=excluded.suggested_boxes,
                                directions=excluded.directions
                        """, (
                            match_id, match["split"], sample["at_ms"],
                            sample["selection"], sample["frame"], sample["overlay"],
                            json.dumps(sample.get("suggested_boxes", [])),
                            json.dumps(sample.get("directions", [])), status,
                            json.dumps(boxes) if boxes is not None else None,
                            sample.get("reviewed_by"), sample.get("reviewed_at"),
                        ))
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise

    def _task(self, row: sqlite3.Row, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        lease_active = row["lease_until"] is not None and row["lease_until"] > now
        return {
            "id": row["id"], "match_id": row["match_id"], "split": row["split"],
            "at_ms": row["at_ms"], "selection": row["selection"],
            "frame": row["frame"], "overlay": row["overlay"],
            "roi": self.match_rois[row["match_id"]],
            "suggested_boxes": json.loads(row["suggested_boxes"]),
            "directions": json.loads(row["directions"]),
            "review_status": row["review_status"],
            "reviewed_boxes": (json.loads(row["reviewed_boxes"])
                               if row["reviewed_boxes"] is not None else None),
            "reviewed_by": row["reviewed_by"], "reviewed_at": row["reviewed_at"],
            "lease_owner": row["lease_owner"] if lease_active else None,
            "lease_until": row["lease_until"] if lease_active else None,
            "version": row["version"],
        }

    def bootstrap(self) -> dict:
        with self._connect() as connection:
            matches = [dict(row) for row in connection.execute("""
                SELECT match_id AS id, split, COUNT(*) AS samples
                FROM tasks GROUP BY match_id, split ORDER BY MIN(id)
            """)]
            suggestions_available = connection.execute("""
                SELECT EXISTS(
                    SELECT 1 FROM tasks WHERE suggested_boxes != '[]' LIMIT 1
                )
            """).fetchone()[0] == 1
        return {"kind": self.kind, "review_mode": self.review_mode,
                "suggestions_available": suggestions_available,
                "roi": self.roi, "matches": matches,
                "context_offsets_ms": (list(CONTEXT_OFFSETS_MS)
                                       if self._ffmpeg and self._videos else []),
                "context_matches": sorted(self._videos),
                "lease_seconds": self.lease_seconds, "stats": self.stats()}

    def stats(self) -> dict:
        with self._connect() as connection:
            counts = {status: 0 for status in ALL_STATUSES}
            for row in connection.execute(
                    "SELECT review_status, COUNT(*) AS count FROM tasks GROUP BY review_status"):
                counts[row["review_status"]] = row["count"]
            by_match = [dict(row) for row in connection.execute("""
                SELECT match_id, review_status, COUNT(*) AS count
                FROM tasks GROUP BY match_id, review_status ORDER BY match_id
            """)]
            contributors = [dict(row) for row in connection.execute("""
                SELECT reviewed_by AS name, COUNT(*) AS count
                FROM tasks WHERE reviewed_by IS NOT NULL
                GROUP BY reviewed_by ORDER BY count DESC, reviewed_by
            """)]
            active = [dict(row) for row in connection.execute("""
                SELECT lease_owner AS name, COUNT(*) AS count
                FROM tasks WHERE lease_until > ? GROUP BY lease_owner
                ORDER BY lease_owner
            """, (time.time(),))]
        return {"total": sum(counts.values()), "counts": counts,
                "completed": sum(counts[status] for status in FINAL_STATUSES),
                "by_match": by_match, "contributors": contributors,
                "active_leases": active}

    def list_tasks(self, status: str | None = None, match_id: str | None = None,
                   annotator: str | None = None, limit: int = 150) -> list[dict]:
        clauses = []
        values: list[object] = []
        if status:
            if status not in ALL_STATUSES:
                raise ValueError("invalid status")
            clauses.append("review_status = ?")
            values.append(status)
        if match_id:
            clauses.append("match_id = ?")
            values.append(match_id)
        if annotator:
            clauses.append("reviewed_by = ?")
            values.append(_annotator(annotator))
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        values.append(max(1, min(limit, 500)))
        with self._connect() as connection:
            rows = connection.execute(f"""
                SELECT * FROM tasks {where} ORDER BY id LIMIT ?
            """, values).fetchall()
        return [self._task(row) for row in rows]

    def get(self, task_id: int) -> dict:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if row is None:
            raise KeyError(task_id)
        return self._task(row)

    def claim(self, task_id: int, annotator: str) -> dict:
        annotator = _annotator(annotator)
        now = time.time()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
                if row is None:
                    raise KeyError(task_id)
                if (row["lease_until"] is not None and row["lease_until"] > now and
                        row["lease_owner"] != annotator):
                    raise ConflictError(f"Task is being reviewed by {row['lease_owner']}")
                connection.execute("""
                    UPDATE tasks SET lease_owner = ?, lease_until = ? WHERE id = ?
                """, (annotator, now + self.lease_seconds, task_id))
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise
        return self.get(task_id)

    def claim_next(self, annotator: str, match_id: str | None = None) -> dict | None:
        annotator = _annotator(annotator)
        now = time.time()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                values: list[object] = [now, annotator]
                match_clause = ""
                if match_id:
                    match_clause = "AND match_id = ?"
                    values.append(match_id)
                row = connection.execute(f"""
                    SELECT * FROM tasks
                    WHERE review_status = 'pending'
                      AND (lease_until IS NULL OR lease_until <= ? OR lease_owner = ?)
                      {match_clause}
                    ORDER BY id LIMIT 1
                """, values).fetchone()
                if row is None:
                    connection.execute("COMMIT")
                    return None
                connection.execute("""
                    UPDATE tasks SET lease_owner = ?, lease_until = ? WHERE id = ?
                """, (annotator, now + self.lease_seconds, row["id"]))
                connection.execute("COMMIT")
                task_id = row["id"]
            except Exception:
                connection.execute("ROLLBACK")
                raise
        return self.get(task_id)

    def heartbeat(self, task_id: int, annotator: str) -> dict:
        annotator = _annotator(annotator)
        now = time.time()
        with self._connect() as connection:
            changed = connection.execute("""
                UPDATE tasks SET lease_until = ?
                WHERE id = ? AND lease_owner = ? AND lease_until > ?
            """, (now + self.lease_seconds, task_id, annotator, now)).rowcount
        if not changed:
            raise ConflictError("Task lease is no longer active")
        return self.get(task_id)

    def release(self, task_id: int, annotator: str) -> dict:
        annotator = _annotator(annotator)
        now = time.time()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute(
                    "SELECT lease_owner, lease_until FROM tasks WHERE id = ?", (task_id,)
                ).fetchone()
                if row is None:
                    raise KeyError(task_id)
                if (row["lease_owner"] != annotator or row["lease_until"] is None or
                        row["lease_until"] <= now):
                    if row["lease_until"] is not None and row["lease_until"] > now:
                        raise ConflictError(f"Task is being reviewed by {row['lease_owner']}")
                    raise ConflictError("Task lease already expired")
                connection.execute(
                    "UPDATE tasks SET lease_owner = NULL, lease_until = NULL WHERE id = ?",
                    (task_id,),
                )
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise
        return self.get(task_id)

    def save(self, task_id: int, annotator: str, version: int,
             status: str, boxes: object = None) -> dict:
        annotator = _annotator(annotator)
        if not isinstance(version, int) or isinstance(version, bool) or version < 1:
            raise ValueError("version must be a positive integer")
        if status not in FINAL_STATUSES:
            raise ValueError("invalid final status")
        if self.review_mode in {"blind", "manual"} and status == "accepted":
            raise ValueError(
                "manual review requires explicit human confirmation; use corrected or negative"
            )
        reviewed_boxes = _validate_boxes(boxes or []) if status == "corrected" else None
        if status == "corrected" and not reviewed_boxes:
            raise ValueError("corrected samples need at least one box; use negative for no target")
        now = time.time()
        reviewed_at = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
                if row is None:
                    raise KeyError(task_id)
                if reviewed_boxes is not None:
                    roi_x, roi_y, roi_width, roi_height = self.match_rois[row["match_id"]]
                    for x, y, width, height in reviewed_boxes:
                        if (x < roi_x - 0.000001 or y < roi_y - 0.000001 or
                                x + width > roi_x + roi_width + 0.000001 or
                                y + height > roi_y + roi_height + 0.000001):
                            raise ValueError(
                                "corrected boxes must stay inside the minimap roi"
                            )
                if row["version"] != version:
                    raise ConflictError("Task changed in another browser; reload it")
                if (row["lease_owner"] != annotator or row["lease_until"] is None or
                        row["lease_until"] <= now):
                    if row["lease_until"] is not None and row["lease_until"] > now:
                        raise ConflictError(f"Task is being reviewed by {row['lease_owner']}")
                    raise ConflictError("Task lease expired; claim it again before saving")
                connection.execute("""
                    UPDATE tasks SET review_status = ?, reviewed_boxes = ?,
                        reviewed_by = ?, reviewed_at = ?, lease_owner = NULL,
                        lease_until = NULL, version = version + 1
                    WHERE id = ? AND version = ?
                """, (status, json.dumps(reviewed_boxes) if reviewed_boxes is not None else None,
                      annotator, reviewed_at, task_id, version))
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise
        self.export_manifest()
        return self.get(task_id)

    def export_manifest(self) -> None:
        with self._export_lock:
            data = json.loads(self.manifest.read_text(encoding="utf-8"))
            with self._connect() as connection:
                rows = connection.execute("SELECT * FROM tasks").fetchall()
            indexed = {(row["match_id"], row["at_ms"]): row for row in rows}
            for match in data["matches"]:
                for sample in match.get("samples", []):
                    row = indexed[(match["id"], sample["at_ms"])]
                    sample["review_status"] = row["review_status"]
                    sample["reviewed_boxes"] = (json.loads(row["reviewed_boxes"])
                                                if row["reviewed_boxes"] is not None else None)
                    if row["reviewed_by"]:
                        sample["reviewed_by"] = row["reviewed_by"]
                        sample["reviewed_at"] = row["reviewed_at"]
                    else:
                        sample.pop("reviewed_by", None)
                        sample.pop("reviewed_at", None)
            temporary = self.manifest.with_suffix(".json.tmp")
            temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
            os.replace(temporary, self.manifest)

    def media_path(self, relative: str) -> Path:
        if not isinstance(relative, str) or not relative:
            raise ValueError("media path is required")
        if relative not in self._media_files:
            raise ValueError("media path is not part of the review manifest")
        candidate = (self.dataset / relative).resolve()
        if self.dataset not in candidate.parents or not candidate.is_file():
            raise ValueError("invalid media path")
        return candidate

    def context_frame(self, task_id: int, offset_ms: int) -> Path:
        if isinstance(offset_ms, bool) or offset_ms not in CONTEXT_OFFSETS_MS:
            raise ValueError(f"offset_ms must be one of {list(CONTEXT_OFFSETS_MS)}")
        if not self._ffmpeg:
            raise ValueError("ffmpeg is unavailable")
        task = self.get(task_id)
        video = self._videos.get(task["match_id"])
        if video is None:
            raise ValueError("source video is unavailable")
        timestamp_ms = max(0, task["at_ms"] + offset_ms)
        cache = self.dataset / ".context-cache"
        cache.mkdir(parents=True, exist_ok=True)
        output = cache / f"task-{task_id}-{offset_ms:+d}.png"
        if output.is_file() and output.stat().st_size > 0:
            return output
        with self._context_lock:
            if output.is_file() and output.stat().st_size > 0:
                return output
            temporary_handle = tempfile.NamedTemporaryFile(
                prefix=f"task-{task_id}-{offset_ms:+d}-", suffix=".png",
                dir=cache, delete=False,
            )
            temporary = Path(temporary_handle.name)
            temporary_handle.close()
            try:
                completed = subprocess.run([
                    self._ffmpeg, "-loglevel", "error", "-ss",
                    f"{timestamp_ms / 1000:.3f}", "-i", str(video),
                    "-frames:v", "1", "-y", str(temporary),
                ], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                   timeout=15, check=False)
                if completed.returncode or not temporary.is_file() or not temporary.stat().st_size:
                    message = completed.stderr.decode("utf-8", errors="replace").strip()
                    raise ValueError(f"could not extract context frame: {message[:240]}")
                os.replace(temporary, output)
            finally:
                temporary.unlink(missing_ok=True)
        return output


class AnnotationHandler(BaseHTTPRequestHandler):
    server: "AnnotationHTTPServer"

    def log_message(self, message: str, *args: object) -> None:
        sys.stderr.write(f"[{self.log_date_time_string()}] {message % args}\n")

    def _json(self, value: object, status: int = 200) -> None:
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _error(self, status: int, message: str) -> None:
        self._json({"error": message}, status)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 1024 * 1024:
            raise ValueError("invalid request body")
        value = json.loads(self.rfile.read(length))
        if not isinstance(value, dict):
            raise ValueError("request body must be an object")
        return value

    def _asset(self, path: Path) -> None:
        if not path.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        body = path.read_bytes()
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    @staticmethod
    def _task_id(path: str, suffix: str = "") -> int | None:
        match = re.fullmatch(rf"/api/tasks/(\d+){re.escape(suffix)}", path)
        return int(match.group(1)) if match else None

    def _store(self, parsed: urllib.parse.ParseResult) -> AnnotationStore:
        query = urllib.parse.parse_qs(parsed.query)
        dataset_id = query.get("dataset", [self.server.default_dataset])[0]
        try:
            return self.server.stores[dataset_id]
        except KeyError as error:
            raise ValueError(f"unknown dataset: {dataset_id}") from error

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        try:
            if parsed.path == "/":
                self._asset(self.server.web_root / "index.html")
            elif parsed.path.startswith("/assets/"):
                name = parsed.path.removeprefix("/assets/")
                if "/" in name or ".." in name:
                    raise ValueError("invalid asset")
                self._asset(self.server.web_root / name)
            elif parsed.path == "/api/bootstrap":
                store = self._store(parsed)
                bootstrap = store.bootstrap()
                bootstrap["current_dataset"] = self.server.dataset_id(store)
                bootstrap["datasets"] = self.server.catalog()
                self._json(bootstrap)
            elif parsed.path == "/api/stats":
                self._json(self._store(parsed).stats())
            elif parsed.path == "/api/tasks":
                query = urllib.parse.parse_qs(parsed.query)
                self._json({"tasks": self._store(parsed).list_tasks(
                    status=query.get("status", [None])[0],
                    match_id=query.get("match_id", [None])[0],
                    annotator=query.get("annotator", [None])[0],
                    limit=int(query.get("limit", [150])[0]),
                )})
            elif (task_id := self._task_id(parsed.path, "/context")) is not None:
                query = urllib.parse.parse_qs(parsed.query)
                try:
                    offset_ms = int(query.get("offset_ms", [""])[0])
                except (TypeError, ValueError) as error:
                    raise ValueError("offset_ms must be an integer") from error
                self._asset(self._store(parsed).context_frame(task_id, offset_ms))
            elif (task_id := self._task_id(parsed.path)) is not None:
                self._json(self._store(parsed).get(task_id))
            elif parsed.path == "/media":
                query = urllib.parse.parse_qs(parsed.query)
                self._asset(self._store(parsed).media_path(query.get("path", [""])[0]))
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except KeyError:
            self._error(HTTPStatus.NOT_FOUND, "task not found")
        except (ValueError, json.JSONDecodeError) as error:
            self._error(HTTPStatus.BAD_REQUEST, str(error))

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        try:
            body = self._body()
            store = self._store(parsed)
            if parsed.path == "/api/tasks/next":
                task = store.claim_next(body.get("annotator"), body.get("match_id"))
                self._json({"task": task})
            elif (task_id := self._task_id(parsed.path, "/claim")) is not None:
                self._json(store.claim(task_id, body.get("annotator")))
            elif (task_id := self._task_id(parsed.path, "/heartbeat")) is not None:
                self._json(store.heartbeat(task_id, body.get("annotator")))
            elif (task_id := self._task_id(parsed.path, "/release")) is not None:
                self._json(store.release(task_id, body.get("annotator")))
            elif parsed.path == "/api/export":
                store.export_manifest()
                self._json({"ok": True})
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except KeyError:
            self._error(HTTPStatus.NOT_FOUND, "task not found")
        except ConflictError as error:
            self._error(HTTPStatus.CONFLICT, str(error))
        except (ValueError, json.JSONDecodeError) as error:
            self._error(HTTPStatus.BAD_REQUEST, str(error))

    def do_PUT(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        try:
            task_id = self._task_id(parsed.path)
            if task_id is None:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            body = self._body()
            task = self._store(parsed).save(
                task_id, body.get("annotator"), body.get("version"),
                body.get("status"), body.get("boxes")
            )
            self._json(task)
        except KeyError:
            self._error(HTTPStatus.NOT_FOUND, "task not found")
        except ConflictError as error:
            self._error(HTTPStatus.CONFLICT, str(error))
        except (ValueError, json.JSONDecodeError) as error:
            self._error(HTTPStatus.BAD_REQUEST, str(error))


class AnnotationHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], stores: dict[str, AnnotationStore],
                 web_root: Path) -> None:
        if not stores:
            raise ValueError("at least one dataset is required")
        self.stores = stores
        self.default_dataset = next(iter(stores))
        self.web_root = web_root
        super().__init__(address, AnnotationHandler)

    def dataset_id(self, store: AnnotationStore) -> str:
        return next(key for key, candidate in self.stores.items() if candidate is store)

    def catalog(self) -> list[dict]:
        return [{
            "id": dataset_id,
            "label": dataset_id,
            "kind": store.kind,
            "review_mode": store.review_mode,
            "stats": store.stats(),
        } for dataset_id, store in self.stores.items()]


def _dataset_specs(values: list[str] | None) -> list[tuple[str, Path]]:
    if not values:
        return [("video1-5", Path("data/private/minimap-review-v3"))]
    result: list[tuple[str, Path]] = []
    used: set[str] = set()
    for value in values:
        if "=" in value:
            dataset_id, raw_path = value.split("=", 1)
        else:
            raw_path = value
            dataset_id = Path(value).name
        dataset_id = dataset_id.strip()
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,39}", dataset_id):
            raise ValueError(f"invalid dataset name: {dataset_id}")
        if dataset_id in used:
            raise ValueError(f"duplicate dataset name: {dataset_id}")
        if not raw_path.strip():
            raise ValueError(f"missing path for dataset: {dataset_id}")
        used.add(dataset_id)
        result.append((dataset_id, Path(raw_path)))
    return result


def _lan_addresses(port: int) -> list[str]:
    addresses: list[str] = []

    def add(address: str) -> None:
        if address and not address.startswith("127.") and address != "0.0.0.0":
            url = f"http://{address}:{port}/"
            if url not in addresses:
                addresses.append(url)

    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            add(info[4][0])
    except OSError:
        pass
    # macOS often maps the local hostname only to loopback. A UDP connect chooses
    # the interface address without sending a packet, and also works on Linux.
    for target in ("192.0.2.1", "8.8.8.8"):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                probe.connect((target, 80))
                add(probe.getsockname()[0])
        except OSError:
            continue
    return addresses


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset", action="append", metavar="[NAME=]PATH",
        help="Dataset directory; repeat to serve several queues on one site",
    )
    parser.add_argument("--database", type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--lease-minutes", type=int, default=15)
    parser.add_argument("--open", action="store_true", help="Open the local site in a browser")
    args = parser.parse_args()
    if args.port < 1 or args.port > 65535 or args.lease_minutes < 1:
        parser.error("invalid port or lease duration")
    try:
        specs = _dataset_specs(args.dataset)
        if args.database and len(specs) != 1:
            raise ValueError("--database can only be used with one dataset")
        stores = {
            dataset_id: AnnotationStore(
                path, args.database if len(specs) == 1 else None,
                args.lease_minutes * 60,
            )
            for dataset_id, path in specs
        }
        web_root = Path(__file__).with_name("annotation_web")
        server = AnnotationHTTPServer((args.host, args.port), stores, web_root)
    except (OSError, ValueError, sqlite3.Error, json.JSONDecodeError) as error:
        parser.error(str(error))
    local_url = f"http://127.0.0.1:{server.server_address[1]}/"
    print(f"标注网站：{local_url}")
    if args.host in {"0.0.0.0", "::"}:
        for address in _lan_addresses(server.server_address[1]):
            print(f"同一局域网：{address}")
    for dataset_id, store in stores.items():
        print(f"数据集 {dataset_id}：{store.dataset}")
    print("按 Ctrl+C 停止。", flush=True)
    if args.open:
        webbrowser.open(local_url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
