#!/usr/bin/env python3
"""Serve one APK and its update manifest over a loopback-only HTTPS fixture.

The server is intended for an Android emulator: the generated endpoint uses
10.0.2.2 while this process binds only to host loopback. A short-lived
self-signed test CA and runtime endpoint config are created below ignored
output/ and removed when the process exits.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import ssl
import tempfile
from threading import Lock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

try:
    from .build_app_update_manifest import (
        ManifestError,
        create_manifest,
        discover_aapt2,
        hash_file,
        validate_apk_url,
    )
except ImportError:  # Direct invocation: python scripts/app_update_https_fixture.py
    from build_app_update_manifest import ManifestError, create_manifest, discover_aapt2, hash_file, validate_apk_url


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = ROOT / "output" / "app-update" / "test"
APK_CHUNK_SIZE = 256 * 1024
_PACKAGE_RE = re.compile(r"^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$")
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_MANIFEST_REQUIRED_FIELDS = {
    "schema_version",
    "package_name",
    "version_name",
    "version_code",
    "apk_url",
    "apk_bytes",
    "apk_sha256",
}
_MANIFEST_ALLOWED_FIELDS = _MANIFEST_REQUIRED_FIELDS | {"release_notes"}


@dataclass
class FixtureStats:
    values: dict[str, int] = field(
        default_factory=lambda: {
            "manifest_requests": 0,
            "apk_requests": 0,
            "not_found_requests": 0,
            "manifest_bytes_sent": 0,
            "apk_bytes_sent": 0,
        }
    )
    lock: Lock = field(default_factory=Lock)

    def add(self, key: str, count: int = 1) -> None:
        with self.lock:
            self.values[key] += count

    def snapshot(self) -> dict[str, int]:
        with self.lock:
            return dict(self.values)


@dataclass
class FixturePayload:
    apk_path: Path
    apk_bytes: int
    apk_sha256: str
    manifest_body: bytes = b""
    stats: FixtureStats = field(default_factory=FixtureStats)


def generate_tls_material(directory: str | Path) -> tuple[Path, Path]:
    """Write a one-day localhost/emulator certificate and private key."""
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    except ImportError as exc:
        raise ManifestError(
            "The HTTPS fixture requires cryptography; install it in the active Python environment."
        ) from exc

    folder = Path(directory)
    cert_path = folder / "app-update-test-ca.pem"
    key_path = folder / "app-update-test-key.pem"
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "app-update-loopback-test")])
    now = datetime.now(timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=2))
        .not_valid_after(now + timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(
            x509.SubjectAlternativeName(
                [
                    x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                    x509.IPAddress(ipaddress.ip_address("10.0.2.2")),
                ]
            ),
            critical=False,
        )
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=True,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=True,
                crl_sign=True,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )
    private_bytes = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    key_path.write_bytes(private_bytes)
    key_path.chmod(0o600)
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    cert_path.chmod(0o644)
    return cert_path, key_path


def copy_apk_snapshot(source: str | Path, destination: str | Path) -> int:
    """Copy an APK in bounded chunks so metadata and served bytes stay paired."""
    source_path = Path(source).expanduser().resolve()
    destination_path = Path(destination)
    if not source_path.is_file() or source_path.stat().st_size <= 0:
        raise ManifestError(f"APK must be an existing, non-empty file: {source_path}")
    count = 0
    with source_path.open("rb") as source_stream, destination_path.open("xb") as target_stream:
        while chunk := source_stream.read(APK_CHUNK_SIZE):
            target_stream.write(chunk)
            count += len(chunk)
        target_stream.flush()
        os.fsync(target_stream.fileno())
    destination_path.chmod(0o600)
    if count <= 0:
        destination_path.unlink(missing_ok=True)
        raise ManifestError("APK copy unexpectedly contained no bytes")
    return count


def load_prebuilt_manifest(manifest_path: str | Path, apk_path: str | Path) -> dict[str, object]:
    """Validate schema-v1 metadata and prove its size/hash match the local APK.

    This verifies consistency only. In particular, a pre-generated manifest
    does not establish that the APK is signed or trusted.
    """
    source = Path(manifest_path).expanduser()
    try:
        document = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ManifestError(f"Could not read pre-generated update manifest: {exc}") from exc
    if not isinstance(document, dict):
        raise ManifestError("Pre-generated update manifest must be a JSON object")
    missing = _MANIFEST_REQUIRED_FIELDS - set(document)
    extra = set(document) - _MANIFEST_ALLOWED_FIELDS
    if missing:
        raise ManifestError("Pre-generated manifest is missing required fields: " + ", ".join(sorted(missing)))
    if extra:
        raise ManifestError("Pre-generated manifest has unsupported fields: " + ", ".join(sorted(extra)))

    schema_version = document["schema_version"]
    if type(schema_version) is not int or schema_version != 1:
        raise ManifestError("Pre-generated manifest schema_version must be integer 1")
    package_name = document["package_name"]
    if not isinstance(package_name, str) or not _PACKAGE_RE.fullmatch(package_name):
        raise ManifestError("Pre-generated manifest has an invalid package_name")
    version_name = document["version_name"]
    if not isinstance(version_name, str) or not version_name.strip():
        raise ManifestError("Pre-generated manifest has an invalid version_name")
    version_code = document["version_code"]
    if type(version_code) is not int or version_code <= 0:
        raise ManifestError("Pre-generated manifest version_code must be a positive integer")
    apk_url = document["apk_url"]
    if not isinstance(apk_url, str):
        raise ManifestError("Pre-generated manifest apk_url must be a string")
    validate_apk_url(apk_url)
    expected_bytes = document["apk_bytes"]
    if type(expected_bytes) is not int or expected_bytes <= 0:
        raise ManifestError("Pre-generated manifest apk_bytes must be a positive integer")
    expected_sha256 = document["apk_sha256"]
    if not isinstance(expected_sha256, str) or not _SHA256_RE.fullmatch(expected_sha256):
        raise ManifestError("Pre-generated manifest apk_sha256 must be 64 hexadecimal characters")
    if "release_notes" in document and not isinstance(document["release_notes"], str):
        raise ManifestError("Pre-generated manifest release_notes must be a string")

    actual_bytes, actual_sha256 = hash_file(apk_path)
    if expected_bytes != actual_bytes:
        raise ManifestError(
            f"Pre-generated manifest apk_bytes mismatch: expected {expected_bytes}, actual {actual_bytes}"
        )
    if expected_sha256.lower() != actual_sha256:
        raise ManifestError("Pre-generated manifest apk_sha256 does not match the local APK")
    normalized = dict(document)
    normalized["apk_sha256"] = actual_sha256
    return normalized


def prepare_fixture_manifest(
    apk_path: str | Path,
    port: int,
    manifest_path: str | Path | None = None,
    aapt2: str | Path | None = None,
) -> dict[str, object]:
    """Build or load metadata, then point its download URL at this fixture."""
    _, apk_url = fixture_urls(port)
    if manifest_path is not None:
        manifest = load_prebuilt_manifest(manifest_path, apk_path)
    else:
        if aapt2 is None:
            aapt2 = discover_aapt2()
        manifest = create_manifest(apk_path, apk_url, aapt2)
    manifest["apk_url"] = apk_url
    return manifest


def fixture_urls(port: int) -> tuple[str, str]:
    """Return manifest and APK URLs sharing the emulator-visible origin."""
    origin = f"https://10.0.2.2:{port}"
    return f"{origin}/latest.json", f"{origin}/app.apk"


def make_request_handler(payload: FixturePayload) -> type[BaseHTTPRequestHandler]:
    class UpdateFixtureHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "AppUpdateFixture"
        sys_version = ""

        def log_message(self, _format: str, *_args: Any) -> None:
            # Request targets are intentionally not written to persistent logs.
            return

        def _headers(self, status: int, content_type: str, content_length: int) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(content_length))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path == "/latest.json":
                body = payload.manifest_body
                if not body:
                    self._headers(503, "text/plain; charset=utf-8", 0)
                    return
                self._headers(200, "application/json; charset=utf-8", len(body))
                try:
                    self.wfile.write(body)
                    payload.stats.add("manifest_requests")
                    payload.stats.add("manifest_bytes_sent", len(body))
                except OSError:
                    return
                return

            if self.path == "/app.apk":
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(payload.apk_bytes))
                self.send_header("Content-Disposition", 'attachment; filename="app.apk"')
                self.send_header("Cache-Control", "no-store")
                self.send_header("ETag", f'"{payload.apk_sha256}"')
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                sent = 0
                try:
                    with payload.apk_path.open("rb") as stream:
                        remaining = payload.apk_bytes
                        while remaining > 0:
                            chunk = stream.read(min(APK_CHUNK_SIZE, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            sent += len(chunk)
                            remaining -= len(chunk)
                except OSError:
                    return
                if sent:
                    payload.stats.add("apk_requests")
                    payload.stats.add("apk_bytes_sent", sent)
                return

            payload.stats.add("not_found_requests")
            body = b"Not found\n"
            self._headers(404, "text/plain; charset=utf-8", len(body))
            try:
                self.wfile.write(body)
            except OSError:
                return

    return UpdateFixtureHandler


def create_loopback_server(payload: FixturePayload, port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("127.0.0.1", port), make_request_handler(payload))
    server.daemon_threads = True
    server.timeout = 0.25
    return server


def _resolve_output_dir(value: str | Path) -> Path:
    path = Path(value).expanduser().resolve()
    output_root = (ROOT / "output").resolve()
    if not path.is_relative_to(output_root):
        raise ManifestError(f"Keep fixture output under ignored output/: {path}")
    path.mkdir(parents=True, exist_ok=True)
    return path


def _write_stats_file(path: str | Path, stats: FixtureStats) -> None:
    destination = Path(path).expanduser().resolve()
    output_root = (ROOT / "output").resolve()
    if not destination.is_relative_to(output_root):
        raise ManifestError(f"Keep fixture stats under ignored output/: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    content = json.dumps({"fixture": "app-update-https", **stats.snapshot()}, indent=2) + "\n"
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(content, encoding="utf-8")
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Signed APK to serve")
    parser.add_argument("--port", type=int, default=18766, help="Loopback HTTPS port (default: 18766; 0 picks one)")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR,
                        help="Generated files directory under ignored output/ (default: output/app-update/test)")
    parser.add_argument("--manifest", type=Path,
                        help="Optional pre-generated schema-v1 JSON; validates APK size/hash and skips aapt2")
    parser.add_argument("--aapt2", help="aapt2 executable; otherwise discovered from Android SDK build-tools")
    parser.add_argument("--stats-file", type=Path, help="Optional sanitized request-count JSON under output/")
    args = parser.parse_args(argv)
    if not (0 <= args.port <= 65535):
        parser.error("Use an unprivileged port between 0 and 65535.")

    server: ThreadingHTTPServer | None = None
    payload: FixturePayload | None = None
    def terminate(_number: int, _frame: Any) -> None:
        raise KeyboardInterrupt

    previous_sigterm = signal.signal(signal.SIGTERM, terminate)
    try:
        source_apk = args.apk.expanduser().resolve()
        if not source_apk.is_file() or source_apk.stat().st_size <= 0:
            raise ManifestError(f"APK must be an existing, non-empty file: {source_apk}")
        output_dir = _resolve_output_dir(args.output_dir)
        # A Linux fixture host can consume the signed build's manifest exported
        # on another machine without having any Android SDK installed.
        aapt2 = None if args.manifest is not None else discover_aapt2(args.aapt2)

        # Keep all temporary material in one private, uniquely owned directory.
        # The input APK remains untouched and is never deleted by cleanup.
        with tempfile.TemporaryDirectory(prefix="app-update-fixture-", dir=output_dir) as owned_dir_text:
            owned_dir = Path(owned_dir_text)
            owned_dir.chmod(0o700)
            snapshot_path = owned_dir / "app.apk"
            copy_apk_snapshot(source_apk, snapshot_path)
            cert_path, key_path = generate_tls_material(owned_dir)
            payload = FixturePayload(apk_path=snapshot_path, apk_bytes=0, apk_sha256="")
            server = create_loopback_server(payload, args.port)
            port = int(server.server_address[1])
            endpoint_url, _ = fixture_urls(port)
            manifest = prepare_fixture_manifest(snapshot_path, port, args.manifest, aapt2)
            payload.apk_bytes = int(manifest["apk_bytes"])
            payload.apk_sha256 = str(manifest["apk_sha256"])
            payload.manifest_body = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            config_path = owned_dir / "app-update-test.json"
            config_path.write_text(
                json.dumps({"endpoint_url": endpoint_url}, indent=2) + "\n",
                encoding="utf-8",
            )
            config_path.chmod(0o600)

            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
            server.socket = tls.wrap_socket(server.socket, server_side=True)

            print("App update HTTPS fixture is ready.", flush=True)
            print("Bound: 127.0.0.1:" + str(port), flush=True)
            print("Manifest: https://10.0.2.2:" + str(port) + "/latest.json", flush=True)
            print("APK: https://10.0.2.2:" + str(port) + "/app.apk", flush=True)
            print("Runtime config: " + str(config_path), flush=True)
            print("Debug CA: " + str(cert_path), flush=True)
            print(f"APK bytes: {payload.apk_bytes}; SHA-256: {payload.apk_sha256}", flush=True)
            try:
                server.serve_forever(poll_interval=0.25)
            except KeyboardInterrupt:
                print("Stopping app update HTTPS fixture and removing its temporary key/config.", flush=True)
            finally:
                server.server_close()
                if previous_sigterm is not None:
                    signal.signal(signal.SIGTERM, previous_sigterm)
                if args.stats_file is not None and payload is not None:
                    _write_stats_file(args.stats_file, payload.stats)
                    print("Sanitized request counts: " + json.dumps(payload.stats.snapshot(), sort_keys=True), flush=True)
    except (ManifestError, OSError, ssl.SSLError) as exc:
        parser.error(str(exc))
    except KeyboardInterrupt:
        print("Stopping app update HTTPS fixture and removing its temporary key/config.", flush=True)
    finally:
        if server is not None:
            try:
                server.server_close()
            except OSError:
                pass
        if previous_sigterm is not None:
            try:
                signal.signal(signal.SIGTERM, previous_sigterm)
            except ValueError:
                pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
