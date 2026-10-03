from __future__ import annotations

import hashlib
import json
from pathlib import Path
import ssl
from threading import Thread
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import urlopen

import pytest

from scripts import app_update_https_fixture as fixture


def _write_prebuilt_manifest(path: Path, apk_bytes: bytes) -> dict[str, object]:
    manifest: dict[str, object] = {
        "schema_version": 1,
        "package_name": "com.openkhub.sensefield",
        "version_name": "0.4.0",
        "version_code": 17,
        "apk_url": "https://updates.example.invalid/app.apk",
        "apk_bytes": len(apk_bytes),
        "apk_sha256": hashlib.sha256(apk_bytes).hexdigest(),
    }
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest


def test_prebuilt_manifest_rejects_size_and_hash_mismatches(tmp_path: Path) -> None:
    apk_bytes = b"the actual copied APK bytes"
    apk = tmp_path / "app.apk"
    apk.write_bytes(apk_bytes)
    manifest_path = tmp_path / "latest.json"
    manifest = _write_prebuilt_manifest(manifest_path, apk_bytes)

    manifest["apk_bytes"] = len(apk_bytes) + 1
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(fixture.ManifestError, match="apk_bytes mismatch"):
        fixture.load_prebuilt_manifest(manifest_path, apk)

    manifest["apk_bytes"] = len(apk_bytes)
    manifest["apk_sha256"] = "0" * 64
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(fixture.ManifestError, match="apk_sha256 does not match"):
        fixture.load_prebuilt_manifest(manifest_path, apk)


def test_valid_prebuilt_manifest_skips_aapt2_and_rewrites_to_fixture_origin(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    apk_bytes = b"real artifact bytes for consistency validation"
    apk = tmp_path / "app.apk"
    apk.write_bytes(apk_bytes)
    manifest_path = tmp_path / "latest.json"
    original = _write_prebuilt_manifest(manifest_path, apk_bytes)

    def should_not_be_called(*_args: object, **_kwargs: object) -> object:
        pytest.fail("pre-generated manifest mode must not require aapt2")

    monkeypatch.setattr(fixture, "discover_aapt2", should_not_be_called)
    monkeypatch.setattr(fixture, "create_manifest", should_not_be_called)
    port = 18766
    manifest = fixture.prepare_fixture_manifest(apk, port, manifest_path=manifest_path)
    endpoint_url, apk_url = fixture.fixture_urls(port)

    manifest_origin = urlsplit(endpoint_url)[:2]
    assert manifest["apk_url"] == apk_url
    assert urlsplit(apk_url)[:2] == manifest_origin
    assert manifest["apk_sha256"] == original["apk_sha256"]
    assert manifest["apk_bytes"] == len(apk_bytes)
    assert manifest["package_name"] == original["package_name"]


def test_loopback_https_fixture_serves_only_manifest_and_apk(tmp_path: Path) -> None:
    pytest.importorskip("cryptography")
    from cryptography import x509
    from cryptography.x509.oid import ExtensionOID

    apk = tmp_path / "snapshot.apk"
    apk_bytes = bytes(range(256)) * 5200
    apk.write_bytes(apk_bytes)
    cert_path, key_path = fixture.generate_tls_material(tmp_path)
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    sans = cert.extensions.get_extension_for_oid(ExtensionOID.SUBJECT_ALTERNATIVE_NAME).value
    assert set(sans.get_values_for_type(x509.DNSName)) == {"localhost"}
    assert {str(address) for address in sans.get_values_for_type(x509.IPAddress)} == {
        "127.0.0.1", "10.0.2.2",
    }
    assert key_path.stat().st_mode & 0o777 == 0o600

    manifest = {
        "schema_version": 1,
        "package_name": "com.openkhub.sensefield",
        "version_name": "0.4.0",
        "version_code": 17,
        "apk_url": "",
        "apk_bytes": len(apk_bytes),
        "apk_sha256": hashlib.sha256(apk_bytes).hexdigest(),
    }
    payload = fixture.FixturePayload(
        apk_path=apk,
        apk_bytes=len(apk_bytes),
        apk_sha256=manifest["apk_sha256"],
    )
    server = fixture.create_loopback_server(payload, 0)
    assert server.server_address[0] == "127.0.0.1"
    manifest["apk_url"] = f"https://10.0.2.2:{server.server_address[1]}/app.apk"
    body = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
    payload.manifest_body = body
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    thread = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()

    client_tls = ssl.create_default_context(cafile=str(cert_path))
    base_url = f"https://127.0.0.1:{server.server_address[1]}"
    try:
        with urlopen(base_url + "/latest.json", context=client_tls, timeout=5) as response:
            assert response.headers["Content-Type"] == "application/json; charset=utf-8"
            assert json.loads(response.read()) == manifest
        with urlopen(base_url + "/app.apk", context=client_tls, timeout=5) as response:
            assert response.headers["Content-Length"] == str(len(apk_bytes))
            assert response.headers["ETag"] == f'"{manifest["apk_sha256"]}"'
            assert response.read() == apk_bytes
        with pytest.raises(HTTPError) as missing:
            urlopen(base_url + "/etc/passwd", context=client_tls, timeout=5)
        assert missing.value.code == 404
        assert b"Not found" in missing.value.read()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    assert not thread.is_alive()
    assert payload.stats.snapshot() == {
        "manifest_requests": 1,
        "apk_requests": 1,
        "not_found_requests": 1,
        "manifest_bytes_sent": len(body),
        "apk_bytes_sent": len(apk_bytes),
    }
