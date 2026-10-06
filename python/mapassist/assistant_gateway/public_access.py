"""Explicit public vision access with persisted, bounded usage (not device attestation)."""
from __future__ import annotations

import hashlib
import re
import sqlite3
import time
from pathlib import Path

from .errors import GatewayError


class PublicQuotaExceeded(GatewayError):
    def __init__(self, retry_after: int):
        super().__init__("public_quota_exceeded", "Public service usage limit reached.", http_status=429)
        self.retry_after_seconds = max(1, retry_after)

    def safe_metadata(self) -> dict:
        return {"source": "public_quota", "retry_after_seconds": self.retry_after_seconds}


def installation_key(value: str | None) -> str:
    if value is None or not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value):
        raise GatewayError("installation_required", "A valid installation identifier is required.", http_status=401)
    return hashlib.sha256(value.encode("ascii")).hexdigest()


class PublicVisionQuota:
    def __init__(self, path: Path, *, global_daily: int, installation_daily: int, installation_minute: int):
        self.path = path
        self.global_daily = global_daily
        self.installation_daily = installation_daily
        self.installation_minute = installation_minute

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute("CREATE TABLE IF NOT EXISTS daily (day INTEGER, identity TEXT, count INTEGER NOT NULL, PRIMARY KEY(day, identity))")
            db.execute("CREATE TABLE IF NOT EXISTS minute (identity TEXT PRIMARY KEY, bucket INTEGER NOT NULL, count INTEGER NOT NULL)")

    def charge(self, identity: str, *, now: float | None = None) -> None:
        timestamp = int(time.time() if now is None else now)
        day, minute = timestamp // 86400, timestamp // 60
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute("BEGIN IMMEDIATE")
            def daily(key: str) -> int:
                row = db.execute("SELECT count FROM daily WHERE day=? AND identity=?", (day, key)).fetchone()
                return row[0] if row else 0
            if daily("global") >= self.global_daily or daily(identity) >= self.installation_daily:
                raise PublicQuotaExceeded((day + 1) * 86400 - timestamp)
            row = db.execute("SELECT bucket,count FROM minute WHERE identity=?", (identity,)).fetchone()
            count = row[1] if row and row[0] == minute else 0
            if count >= self.installation_minute:
                raise PublicQuotaExceeded((minute + 1) * 60 - timestamp)
            for key in ("global", identity):
                db.execute("INSERT INTO daily VALUES (?,?,1) ON CONFLICT(day,identity) DO UPDATE SET count=count+1", (day, key))
            db.execute("INSERT INTO minute VALUES (?,?,?) ON CONFLICT(identity) DO UPDATE SET bucket=excluded.bucket,count=excluded.count", (identity, minute, count + 1))
            db.execute("DELETE FROM daily WHERE day < ?", (day - 1,))
            db.execute("DELETE FROM minute WHERE bucket < ?", (minute - 2,))
