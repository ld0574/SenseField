from __future__ import annotations
import base64
from concurrent.futures import ThreadPoolExecutor
import io
import uuid
from fastapi.testclient import TestClient
from PIL import Image
import pytest
from starlette.websockets import WebSocketDisconnect
from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.public_access import PublicVisionQuota, PublicQuotaExceeded, installation_key

class VisionFixture:
    calls = 0
    async def complete(self, **kwargs):
        self.calls += 1
        return '{"kind":"ui_text","answer":"菜单已打开。","uncertain":false}'
    async def close(self): pass

def payload():
    out = io.BytesIO(); Image.new("RGB", (32, 20), "white").save(out, format="JPEG")
    return dict(session_id=str(uuid.uuid4()), generation=1, turn_id="t1", frame_id="f1", question="请读菜单",
                image_base64=base64.b64encode(out.getvalue()).decode(), frame_age_ms=0, proactive=False)

def test_public_service_needs_no_code_and_rejects_missing_identity_audio_and_overuse(tmp_path):
    settings = GatewaySettings(public_access=True, public_quota_db=tmp_path / "quota.db",
        public_installation_minute=1, asr_backend="disabled", require_tls=False, local_tls_proxy=True)
    vision = VisionFixture()
    # TestClient is not a loopback peer; test public logic without proxy middleware.
    from dataclasses import replace
    settings = replace(settings, require_tls=True, local_tls_proxy=False)
    with TestClient(create_app(settings, vision_client=vision), base_url="https://testserver") as client:
        assert client.get("/health").json()["public_vision_access"] is True
        assert client.post("/v1/visual", json=payload()).status_code == 401
        assert client.post("/v1/visual", json=payload(), headers={"X-SenseField-Installation": "bad"}).status_code == 401
        headers = {"X-SenseField-Installation": str(uuid.uuid4())}
        assert client.post("/v1/visual", json={}, headers=headers).status_code == 422
        assert client.post("/v1/visual", json=payload(), headers=headers).status_code == 200
        response = client.post("/v1/visual", json=payload(), headers=headers)
        assert response.status_code == 429
        assert response.json()["error"]["code"] == "public_quota_exceeded"
        assert int(response.headers["retry-after"]) > 0
        assert vision.calls == 1
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/v1/audio", headers=headers): pass

def test_private_mode_remains_authenticated(tmp_path):
    with TestClient(create_app(GatewaySettings(device_tokens=("private",), asr_backend="disabled", require_tls=True), vision_client=VisionFixture()), base_url="https://testserver") as client:
        assert client.post("/v1/visual", json=payload(), headers={"X-SenseField-Installation": str(uuid.uuid4())}).status_code == 401
        assert client.post("/v1/visual", json=payload(), headers={"Authorization":"Bearer private"}).status_code == 200

def quota(path, **limits):
    return PublicVisionQuota(path, global_daily=limits.get("global_daily", 4), installation_daily=limits.get("installation_daily", 3), installation_minute=limits.get("installation_minute", 2))

def test_persistence_minute_and_daily_limits_and_reset(tmp_path):
    path = tmp_path / "quota.db"; q = quota(path); q.initialize()
    q.charge("a", now=100); q.charge("a", now=100)
    with pytest.raises(PublicQuotaExceeded): quota(path).charge("a", now=100)
    quota(path).charge("a", now=160)
    with pytest.raises(PublicQuotaExceeded): q.charge("a", now=220)
    q.charge("b", now=220)
    with pytest.raises(PublicQuotaExceeded): q.charge("c", now=220)
    q.charge("a", now=86400)

def test_atomic_global_cap_under_concurrent_installations(tmp_path):
    q = quota(tmp_path / "quota.db", global_daily=7); q.initialize()
    def charge(i):
        try: q.charge(str(i), now=100); return 1
        except PublicQuotaExceeded: return 0
    with ThreadPoolExecutor(max_workers=8) as executor:
        assert sum(executor.map(charge, range(30))) == 7

def test_public_requires_disabled_audio_and_persistent_store(tmp_path):
    with pytest.raises(ValueError): GatewaySettings(public_access=True)
    with pytest.raises(ValueError): GatewaySettings(public_access=True, asr_backend="disabled")
    with pytest.raises(ValueError): GatewaySettings(public_access=True, asr_backend="disabled", public_quota_db=tmp_path / "db", public_global_daily=0)
    value = str(uuid.uuid4()); assert len(installation_key(value)) == 64
    assert installation_key(value) != value


def test_real_https_public_deployment_verifier_with_synthetic_adapter(tmp_path):
    """TLS sockets + actual production handlers, with no paid provider/real player data."""
    import json
    from pathlib import Path
    import runpy
    import shutil
    import socket
    import subprocess
    import threading
    import time
    import uvicorn
    openssl = shutil.which("openssl")
    if openssl is None: pytest.skip("openssl unavailable")
    cert, key = tmp_path / "cert.pem", tmp_path / "key.pem"
    subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
        "-keyout", str(key), "-out", str(cert), "-subj", "/CN=localhost",
        "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"], check=True, capture_output=True)
    class SyntheticVision(VisionFixture):
        async def complete(self, **kwargs):
            self.calls += 1
            return '{"kind":"ui_text","answer":"SYNTHETIC GATEWAY TEST","uncertain":false}'
    vision = SyntheticVision()
    settings = GatewaySettings(public_access=True, public_quota_db=tmp_path / "quota.db", asr_backend="disabled")
    listener = socket.socket(); listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(settings, vision_client=vision),
        host="127.0.0.1", port=port, log_level="error", ssl_certfile=str(cert), ssl_keyfile=str(key)))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True); thread.start()
    try:
        deadline = time.monotonic() + 5
        while not server.started and thread.is_alive() and time.monotonic() < deadline: time.sleep(0.02)
        assert server.started
        verifier = runpy.run_path(str(Path(__file__).resolve().parents[1] / "deploy/assistant/verify.py"))
        args = verifier["_make_parser"]().parse_args(["--base-url", f"https://localhost:{port}",
            "--ca", str(cert), "--visual", "--config", str(tmp_path / "no-private-config")])
        code, report = verifier["execute"](args, stderr=io.StringIO())
        assert code == 0, report
        assert report["tls_verified"] is True
        assert report["config"] == "public_vision_no_code"
        assert report["synthetic_visual_passed"] is True
        assert vision.calls == 1
        print("PUBLIC_HTTPS_SMOKE=" + json.dumps(report))
    finally:
        server.should_exit = True; thread.join(timeout=5); listener.close()
        assert not thread.is_alive()
