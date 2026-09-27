"""Deterministic aggregate hashes for image manifests."""

from __future__ import annotations

import hashlib
import json
from pathlib import PurePosixPath
from typing import Iterable


IMAGE_MANIFEST_HASH_ALGORITHM = (
    "sha256 of UTF-8 compact JSON sorted by relative path, with image SHA-256 or null if missing"
)


def image_manifest_sha256(entries: Iterable[tuple[str, str | None]]) -> str:
    """Hash relative image names and file hashes without exposing per-image paths."""
    canonical: list[dict[str, str | None]] = []
    for name, content_sha256 in entries:
        normalized = name.replace("\\", "/")
        relative = PurePosixPath(normalized)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Image manifest paths must be relative and stay within the dataset")
        canonical.append({"path": relative.as_posix(), "sha256": content_sha256})
    canonical.sort(key=lambda item: (item["path"], item["sha256"] or ""))
    payload = json.dumps(
        canonical, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()
