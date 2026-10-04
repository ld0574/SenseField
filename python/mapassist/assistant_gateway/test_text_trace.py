"""Opt-in private question/answer tracing, bounded to a single short test window."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import logging
import os
import stat
from pathlib import Path
import tempfile
import threading
import time
from typing import Any

_LOGGER = logging.getLogger("mapassist.assistant_gateway.audit")
_DETAIL_KEYS = {"raw_answer", "answer", "kind", "uncertain", "code", "stage", "elapsed_ms"}


class TestTextTraceRecorder:
    __test__ = False
    MAX_BYTES = 2 * 1024 * 1024

    def __init__(self, directory: Path, *, seconds: int = 3600,
                 clock=time.monotonic, max_bytes: int = MAX_BYTES):
        if isinstance(seconds, bool) or not isinstance(seconds, int) or not 1 <= seconds <= 3600:
            raise ValueError("Test text trace window must be between 1 and 3600 seconds")
        directory = Path(directory)
        if directory.is_symlink():
            raise ValueError("Test text trace directory cannot be a symlink")
        directory.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix="assistant-text-", suffix=".jsonl", dir=directory)
        os.fchmod(descriptor, 0o600)
        original_stat = os.fstat(descriptor)
        self._file_identity = (original_stat.st_dev, original_stat.st_ino)
        os.close(descriptor)
        self.path = Path(name)
        self._clock = clock
        self._expires = clock() + seconds
        self._max_bytes = max_bytes
        self._bytes = 0
        self._lock = threading.Lock()

    def record(self, event: str, request: Any, **details: Any) -> bool:
        """Whitelist text and scalar fields; never serialize request images or credentials."""
        with self._lock:
            if self._clock() >= self._expires or self._bytes >= self._max_bytes:
                return False
            entry = {
                "utc": datetime.now(timezone.utc).isoformat(),
                "event": str(event)[:48],
                "session_id": request.session_id,
                "generation": request.generation,
                "turn_id": request.turn_id,
                "frame_id": request.frame_id,
                "proactive": request.proactive,
            }
            if event == "request":
                entry["question"] = request.question[:300]
                entry["frame_age_ms"] = getattr(request, "frame_age_ms", None)
                entry["context_frames"] = [
                    {"frame_id": frame.frame_id, "frame_age_ms": frame.frame_age_ms}
                    for frame in getattr(request, "context_frames", ())
                ]
            for key in _DETAIL_KEYS & details.keys():
                value = details[key]
                if isinstance(value, str):
                    entry[key] = value[:4096 if key == "raw_answer" else 400]
                elif isinstance(value, (bool, int, float)) or value is None:
                    entry[key] = value
            payload = (json.dumps(entry, ensure_ascii=False) + "\n").encode("utf-8")
            if self._bytes + len(payload) > self._max_bytes:
                self._bytes = self._max_bytes
                return False
            try:
                descriptor = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW | os.O_NONBLOCK)
                with os.fdopen(descriptor, "ab") as output:
                    current_stat = os.fstat(output.fileno())
                    if (not stat.S_ISREG(current_stat.st_mode)
                            or (current_stat.st_dev, current_stat.st_ino) != self._file_identity
                            or stat.S_IMODE(current_stat.st_mode) != 0o600):
                        return False
                    output.write(payload)
                self._bytes += len(payload)
                return True
            except OSError as error:
                _LOGGER.warning("assistant_test_text_trace event=write_failed error_type=%s", type(error).__name__)
                return False
