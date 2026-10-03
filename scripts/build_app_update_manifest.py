#!/usr/bin/env python3
"""Build a self-hosted Android APK update manifest from the APK itself.

The APK metadata is read with ``aapt2 dump badging``; this command deliberately
has no version override flags so a manifest cannot accidentally describe a
different build than the bytes it hashes.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
_PACKAGE_RE = re.compile(r"^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$")
_BADGING_FIELD_RE = re.compile(r"([A-Za-z][A-Za-z0-9]*)='([^']*)'")
_NATIVE_CODE_RE = re.compile(r"native-code:\s*(.*)$")


class ManifestError(ValueError):
    """Raised when the APK or requested manifest cannot be validated."""


@dataclass(frozen=True)
class ApkMetadata:
    package_name: str
    version_name: str
    version_code: int
    min_sdk_version: str
    native_abis: tuple[str, ...]


def parse_aapt2_badging(output: str) -> ApkMetadata:
    """Parse package, version, min SDK, and ABI facts from aapt2 badging."""
    package_lines = [line for line in output.splitlines() if line.startswith("package:")]
    if len(package_lines) != 1:
        raise ManifestError("aapt2 output must contain exactly one package line")
    fields = dict(_BADGING_FIELD_RE.findall(package_lines[0]))
    package_name = fields.get("name", "")
    version_name = fields.get("versionName", "")
    raw_version_code = fields.get("versionCode", "")
    if not _PACKAGE_RE.fullmatch(package_name):
        raise ManifestError("aapt2 output has a missing or invalid package name")
    if not version_name.strip():
        raise ManifestError("aapt2 output has a missing versionName")
    try:
        version_code = int(raw_version_code)
    except (TypeError, ValueError) as exc:
        raise ManifestError("aapt2 output has a missing or invalid versionCode") from exc
    if version_code <= 0:
        raise ManifestError("APK versionCode must be greater than zero")

    min_sdk_version = ""
    for line in output.splitlines():
        # Older aapt output called this sdkVersion; current aapt2 calls it
        # minSdkVersion. Both describe the APK's minimum platform API.
        match = re.search(r"(?:^|\s)(?:minSdkVersion|sdkVersion):'([^']+)'", line)
        if match:
            min_sdk_version = match.group(1)
            break
    if not min_sdk_version:
        raise ManifestError("aapt2 output is missing the APK minimum SDK version")
    if min_sdk_version.isdecimal() and int(min_sdk_version) <= 0:
        raise ManifestError("APK minimum SDK version must be greater than zero")

    native_abis: list[str] = []
    for line in output.splitlines():
        match = _NATIVE_CODE_RE.match(line)
        if match:
            native_abis = re.findall(r"'([^']+)'", match.group(1))
            break
    return ApkMetadata(
        package_name=package_name,
        version_name=version_name,
        version_code=version_code,
        min_sdk_version=min_sdk_version,
        native_abis=tuple(native_abis),
    )


def _version_key(value: str) -> tuple[int | str, ...]:
    return tuple(int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", value))


def _sdk_roots() -> list[Path]:
    roots: list[Path] = []
    local_properties = ROOT / "android" / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.strip().startswith("sdk.dir="):
                value = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                if value:
                    roots.append(Path(value).expanduser())
                break
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(variable, "").strip()
        if value:
            roots.append(Path(value).expanduser())
    roots.extend(
        [
            Path.home() / "Library/Android/sdk",
            Path.home() / "Android/Sdk",
            Path("/opt/android-sdk"),
            Path("/usr/lib/android-sdk"),
        ]
    )
    result: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        key = str(root.expanduser().resolve(strict=False))
        if key not in seen:
            result.append(Path(key))
            seen.add(key)
    return result


def discover_aapt2(requested: str | Path | None = None) -> str:
    """Resolve an explicit aapt2 or discover one in an installed Android SDK."""
    if requested is not None:
        raw = str(requested)
        candidate = shutil.which(raw) if Path(raw).parent == Path(".") else None
        path = Path(candidate) if candidate else Path(raw).expanduser()
        if not path.is_file() or not os.access(path, os.X_OK):
            raise ManifestError(f"aapt2 executable was not found or is not executable: {raw}")
        return str(path.resolve())

    candidates: list[tuple[str, Path]] = []
    for sdk_root in _sdk_roots():
        build_tools = sdk_root / "build-tools"
        if not build_tools.is_dir():
            continue
        for version_dir in build_tools.iterdir():
            binary = version_dir / "aapt2"
            if binary.is_file() and os.access(binary, os.X_OK):
                candidates.append((version_dir.name, binary))
    if candidates:
        # Prefer the requested major toolchain when available, otherwise the
        # newest installed build-tools version.
        preferred = [item for item in candidates if item[0].split(".", 1)[0] == "36"]
        version, binary = max(preferred or candidates, key=lambda item: _version_key(item[0]))
        del version
        return str(binary.resolve())

    in_path = shutil.which("aapt2")
    if in_path:
        return str(Path(in_path).resolve())
    raise ManifestError(
        "Could not locate aapt2. Install Android build-tools 36 or pass --aapt2 /path/to/aapt2."
    )


def read_apk_metadata(apk_path: str | Path, aapt2: str | Path) -> ApkMetadata:
    apk = Path(apk_path).expanduser().resolve()
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise ManifestError(f"APK must be an existing, non-empty file: {apk}")
    try:
        result = subprocess.run(
            [str(aapt2), "dump", "badging", str(apk)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as exc:
        raise ManifestError(f"Could not run aapt2: {exc}") from exc
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ManifestError(f"aapt2 dump badging failed (exit {result.returncode}): {detail}")
    return parse_aapt2_badging(result.stdout)


def validate_apk_url(value: str) -> str:
    if not value or any(character.isspace() or ord(character) < 0x20 for character in value):
        raise ManifestError("APK URL must be a non-empty HTTPS URL without whitespace")
    if "#" in value:
        raise ManifestError("APK URL must not contain a fragment")
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        # Accessing .port also validates malformed numeric ports.
        port = parsed.port
    except ValueError as exc:
        raise ManifestError(f"APK URL is invalid: {exc}") from exc
    if parsed.scheme.lower() != "https":
        raise ManifestError("APK URL must use HTTPS")
    if not parsed.netloc or not hostname:
        raise ManifestError("APK URL must include a host")
    if parsed.username is not None or parsed.password is not None or "@" in parsed.netloc:
        raise ManifestError("APK URL must not contain credentials")
    if port is not None and not (1 <= port <= 65535):
        raise ManifestError("APK URL port must be between 1 and 65535")
    return value


def hash_file(path: str | Path) -> tuple[int, str]:
    size = 0
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    if size <= 0:
        raise ManifestError("APK must not be empty")
    return size, digest.hexdigest()


def create_manifest(
    apk_path: str | Path,
    apk_url: str,
    aapt2: str | Path,
    notes_file: str | Path | None = None,
) -> dict[str, object]:
    url = validate_apk_url(apk_url)
    apk = Path(apk_path).expanduser().resolve()
    metadata = read_apk_metadata(apk, aapt2)
    apk_bytes, apk_sha256 = hash_file(apk)
    manifest: dict[str, object] = {
        "schema_version": 1,
        "package_name": metadata.package_name,
        "version_name": metadata.version_name,
        "version_code": metadata.version_code,
        "apk_url": url,
        "apk_bytes": apk_bytes,
        "apk_sha256": apk_sha256,
    }
    if notes_file is not None:
        try:
            manifest["release_notes"] = Path(notes_file).expanduser().read_text(encoding="utf-8").strip()
        except (OSError, UnicodeError) as exc:
            raise ManifestError(f"Could not read release notes file: {exc}") from exc
    return manifest


def write_manifest(manifest: dict[str, object], output: str | Path) -> None:
    destination = Path(output).expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Signed APK to describe and hash")
    parser.add_argument("--apk-url", required=True, help="HTTPS URL clients use to download this APK")
    parser.add_argument("--output", type=Path, required=True, help="Named JSON manifest output path")
    parser.add_argument("--notes-file", type=Path, help="Optional UTF-8 release-notes text file")
    parser.add_argument("--aapt2", help="aapt2 executable; otherwise discovered from Android SDK build-tools")
    args = parser.parse_args(argv)
    try:
        aapt2 = discover_aapt2(args.aapt2)
        manifest = create_manifest(args.apk, args.apk_url, aapt2, args.notes_file)
        write_manifest(manifest, args.output)
    except ManifestError as exc:
        parser.error(str(exc))
    except OSError as exc:
        parser.error(f"Could not write manifest: {exc}")
    print(f"Wrote update manifest: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
