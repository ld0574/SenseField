#!/usr/bin/env python3
"""Verify or prepare the pinned Android SenseVoice model assets.

The source repository, immutable revision, filenames, sizes, and SHA-256 values
come from android/app/src/main/assets/sensevoice.metadata.json. Without
--source-dir, missing or invalid files are fetched only from that repository's
Hugging Face ``resolve/<revision>/`` URLs. This script is never run implicitly
by the Android build.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import BinaryIO


ROOT = Path(__file__).resolve().parents[1]
METADATA_PATH = ROOT / "android/app/src/main/assets/sensevoice.metadata.json"
ASSET_DIR = ROOT / "android/app/src/main/assets/sensevoice"
EXPECTED_FILENAMES = ("model.int8.onnx", "tokens.txt")
MAX_FILE_SIZES = {
    "model.int8.onnx": 512 * 1024 * 1024,
    "tokens.txt": 8 * 1024 * 1024,
}
CHUNK_SIZE = 1024 * 1024
HTTP_TIMEOUT_SECONDS = 60
REPO_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_REDIRECT_SUFFIXES = ("huggingface.co", "hf.co", "huggingfaceusercontent.com")


class AssetError(Exception):
    """A user-facing asset preparation failure."""


def _under_root(path: Path) -> bool:
    try:
        path.relative_to(ROOT)
        return True
    except ValueError:
        return False


def _load_manifest() -> tuple[str, str, dict[str, dict[str, int | str]]]:
    try:
        raw = json.loads(METADATA_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AssetError(f"Cannot read SenseVoice metadata: {exc}") from exc
    if not isinstance(raw, dict):
        raise AssetError("SenseVoice metadata must be a JSON object.")

    source = raw.get("source")
    revision = raw.get("revision")
    files = raw.get("files")
    if not isinstance(source, str) or not REPO_ID_RE.fullmatch(source):
        raise AssetError("Metadata has an invalid Hugging Face repository id.")
    if not isinstance(revision, str) or not REVISION_RE.fullmatch(revision):
        raise AssetError("Metadata revision must be a full 40-character commit hash.")
    if not isinstance(files, dict) or set(files) != set(EXPECTED_FILENAMES):
        raise AssetError("Metadata must pin exactly model.int8.onnx and tokens.txt.")

    validated: dict[str, dict[str, int | str]] = {}
    for name in EXPECTED_FILENAMES:
        item = files[name]
        if not isinstance(item, dict):
            raise AssetError(f"Metadata entry for {name} must be an object.")
        size = item.get("size")
        digest = item.get("sha256")
        if type(size) is not int or size <= 0 or size > MAX_FILE_SIZES[name]:
            raise AssetError(f"Metadata has an invalid or excessive size for {name}.")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            raise AssetError(f"Metadata has an invalid SHA-256 value for {name}.")
        validated[name] = {"size": size, "sha256": digest}
    return source, revision, validated


def _hash_stream(stream: BinaryIO, expected_size: int) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    while True:
        block = stream.read(CHUNK_SIZE)
        if not block:
            break
        total += len(block)
        if total > expected_size:
            raise AssetError("Input exceeds its pinned size.")
        digest.update(block)
    return total, digest.hexdigest()


def _check_file(path: Path, expected: dict[str, int | str]) -> tuple[bool, str]:
    name = path.name
    try:
        info = path.lstat()
    except FileNotFoundError:
        return False, "missing"
    except OSError:
        return False, "unreadable"
    if not stat.S_ISREG(info.st_mode):
        return False, "not a regular file"
    expected_size = int(expected["size"])
    if info.st_size != expected_size:
        return False, f"size mismatch ({info.st_size} bytes; expected {expected_size})"
    try:
        with path.open("rb") as stream:
            actual_size, actual_digest = _hash_stream(stream, expected_size)
    except (OSError, AssetError):
        return False, "could not verify contents"
    if actual_size != expected_size:
        return False, f"size changed while reading ({actual_size} bytes; expected {expected_size})"
    if actual_digest != expected["sha256"]:
        return False, "SHA-256 mismatch"
    return True, "verified"


def _approved_https_url(url: str) -> bool:
    parsed = urllib.parse.urlsplit(url)
    hostname = (parsed.hostname or "").lower().rstrip(".")
    try:
        port = parsed.port
    except ValueError:
        return False
    if parsed.scheme != "https" or port not in (None, 443) or not hostname or parsed.username or parsed.password:
        return False
    return any(hostname == suffix or hostname.endswith("." + suffix) for suffix in ALLOWED_REDIRECT_SUFFIXES)


class _HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        if not _approved_https_url(new_url):
            raise AssetError("Refusing a non-HTTPS or non-Hugging Face download redirect.")
        return super().redirect_request(request, file_pointer, code, message, headers, new_url)


def _download_url(source: str, revision: str, filename: str) -> str:
    repo_path = urllib.parse.quote(source, safe="/")
    name_path = urllib.parse.quote(filename, safe="")
    return f"https://huggingface.co/{repo_path}/resolve/{revision}/{name_path}?download=true"


def _make_temp_path(destination_dir: Path, filename: str) -> tuple[int, Path]:
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{filename}.", suffix=".tmp", dir=destination_dir
        )
    except OSError as exc:
        raise AssetError(f"Cannot create a temporary file for {filename}: {exc}") from exc
    return descriptor, Path(temporary_name)


def _copy_verified(
    source: BinaryIO,
    destination: BinaryIO,
    filename: str,
    expected: dict[str, int | str],
) -> None:
    try:
        size, digest = _hash_and_copy(source, destination, int(expected["size"]))
    except AssetError as exc:
        raise AssetError(f"{filename}: {exc}") from exc
    if size != expected["size"]:
        raise AssetError(f"{filename}: size mismatch ({size} bytes; expected {expected['size']}).")
    if digest != expected["sha256"]:
        raise AssetError(f"{filename}: SHA-256 mismatch.")


def _hash_and_copy(source: BinaryIO, destination: BinaryIO, expected_size: int) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    while True:
        block = source.read(CHUNK_SIZE)
        if not block:
            break
        total += len(block)
        if total > expected_size:
            raise AssetError("input exceeds its pinned size.")
        destination.write(block)
        digest.update(block)
    return total, digest.hexdigest()


def _stage_from_source(
    source_path: Path,
    destination_dir: Path,
    filename: str,
    expected: dict[str, int | str],
) -> Path:
    try:
        source_info = source_path.lstat()
    except OSError as exc:
        raise AssetError(f"Cannot read local source {filename}: {exc}") from exc
    if not stat.S_ISREG(source_info.st_mode):
        raise AssetError(f"Local source {filename} must be a regular, non-symlink file.")
    if source_info.st_size != expected["size"]:
        raise AssetError(f"Local source {filename} has an unexpected size.")

    descriptor, temporary_path = _make_temp_path(destination_dir, filename)
    try:
        with os.fdopen(descriptor, "wb") as destination:
            with source_path.open("rb") as source:
                _copy_verified(source, destination, filename, expected)
                destination.flush()
                os.fsync(destination.fileno())
        os.chmod(temporary_path, 0o644)
        return temporary_path
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


def _stage_from_hugging_face(
    source: str,
    revision: str,
    destination_dir: Path,
    filename: str,
    expected: dict[str, int | str],
) -> Path:
    url = _download_url(source, revision, filename)
    request = urllib.request.Request(
        url,
        headers={"Accept-Encoding": "identity", "User-Agent": "sensefield-android-asr-assets/1"},
    )
    opener = urllib.request.build_opener(_HttpsOnlyRedirectHandler())
    descriptor, temporary_path = _make_temp_path(destination_dir, filename)
    try:
        with os.fdopen(descriptor, "wb") as destination:
            try:
                response = opener.open(request, timeout=HTTP_TIMEOUT_SECONDS)
            except urllib.error.HTTPError as exc:
                raise AssetError(f"Hugging Face returned HTTP {exc.code} for {filename}.") from exc
            except urllib.error.URLError as exc:
                # Do not display the URL: a redirected URL can contain a signed query string.
                reason = exc.reason
                summary = type(reason).__name__ if not isinstance(reason, str) else "network error"
                raise AssetError(f"Hugging Face download failed for {filename} ({summary}).") from exc
            with response:
                if not _approved_https_url(response.geturl()):
                    raise AssetError("Refusing a download response from an unapproved host.")
                content_length = response.headers.get("Content-Length")
                if content_length is not None:
                    if not content_length.isdecimal() or int(content_length) != expected["size"]:
                        raise AssetError(f"Hugging Face response has an unexpected Content-Length for {filename}.")
                _copy_verified(response, destination, filename, expected)
                destination.flush()
                os.fsync(destination.fileno())
        os.chmod(temporary_path, 0o644)
        return temporary_path
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


def _fsync_directory(path: Path) -> None:
    try:
        descriptor = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def _run(check_only: bool, source_dir_arg: Path | None) -> int:
    source, revision, files = _load_manifest()
    destination_dir = ASSET_DIR.resolve()
    if not _under_root(destination_dir):
        raise AssetError("The Android assets destination resolves outside the repository.")

    if check_only:
        all_valid = True
        for filename in EXPECTED_FILENAMES:
            valid, reason = _check_file(destination_dir / filename, files[filename])
            print(f"{'OK' if valid else 'INVALID'} {filename}: {reason}")
            all_valid = all_valid and valid
        return 0 if all_valid else 1

    source_dir: Path | None = None
    if source_dir_arg is not None:
        try:
            source_dir = source_dir_arg.expanduser().resolve(strict=True)
        except OSError as exc:
            raise AssetError(f"Cannot resolve --source-dir: {exc}") from exc
        if not source_dir.is_dir():
            raise AssetError("--source-dir must name an existing directory.")

    status: dict[str, bool] = {}
    for filename in EXPECTED_FILENAMES:
        valid, reason = _check_file(destination_dir / filename, files[filename])
        status[filename] = valid
        if valid:
            print(f"OK {filename}: already verified; skipping")
        elif source_dir is not None:
            print(f"PREPARE {filename}: local source required ({reason})")
        else:
            print(f"PREPARE {filename}: pinned Hugging Face download required ({reason})")

    pending = [filename for filename in EXPECTED_FILENAMES if not status[filename]]
    if not pending:
        return 0
    if source_dir is not None:
        for filename in pending:
            source_path = source_dir / filename
            try:
                source_info = source_path.lstat()
            except OSError as exc:
                raise AssetError(f"Local source is missing or unreadable for {filename}: {exc}") from exc
            if not stat.S_ISREG(source_info.st_mode):
                raise AssetError(f"Local source {filename} must be a regular, non-symlink file.")

    try:
        destination_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise AssetError(f"Cannot create the Android SenseVoice assets directory: {exc}") from exc
    resolved_after_mkdir = destination_dir.resolve()
    if not _under_root(resolved_after_mkdir):
        raise AssetError("The Android assets destination resolves outside the repository.")

    staged: dict[str, Path] = {}
    try:
        for filename in pending:
            if source_dir is not None:
                staged[filename] = _stage_from_source(
                    source_dir / filename, destination_dir, filename, files[filename]
                )
            else:
                staged[filename] = _stage_from_hugging_face(
                    source, revision, destination_dir, filename, files[filename]
                )
        for filename in pending:
            os.replace(staged[filename], destination_dir / filename)
            print(f"INSTALLED {filename}: verified and atomically replaced")
        _fsync_directory(destination_dir)
    except Exception:
        for temporary_path in staged.values():
            temporary_path.unlink(missing_ok=True)
        raise
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir",
        type=Path,
        help="Copy pinned files from this local directory instead of using Hugging Face.",
    )
    parser.add_argument(
        "--check-only",
        action="store_true",
        help="Hash-check the existing Android assets without copying or making network requests.",
    )
    args = parser.parse_args()
    if args.check_only and args.source_dir is not None:
        parser.error("--source-dir cannot be combined with --check-only.")
    try:
        return _run(args.check_only, args.source_dir)
    except AssetError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"ERROR: filesystem operation failed ({type(exc).__name__}).", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
