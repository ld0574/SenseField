from __future__ import annotations

import sys

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
import uvicorn

from mapassist.assistant_gateway import cli
from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.config import GatewaySettings


TOKEN = "synthetic-local-proxy-device-token"


class FixtureVision:
    async def complete(self, **kwargs):
        raise AssertionError("Validation probes must not call a vision provider")

    async def close(self):
        pass


@pytest.fixture
def gateway_env(monkeypatch):
    for key in list(cli.os.environ):
        if key.startswith("ASSISTANT_GATEWAY_") or key == "ZHIPU_API_KEY":
            monkeypatch.delenv(key)
    monkeypatch.setenv("ASSISTANT_GATEWAY_DEVICE_TOKENS", TOKEN)
    monkeypatch.setenv("ASSISTANT_GATEWAY_ASR_BACKEND", "disabled")


@pytest.mark.parametrize("source", ["argument", "environment"])
def test_explicit_proxy_mode_binds_only_loopback_and_uses_real_production(monkeypatch, gateway_env, source):
    argv = ["gateway", "--port", "18765"]
    if source == "argument":
        argv.append("--behind-local-proxy")
    else:
        monkeypatch.setenv("ASSISTANT_GATEWAY_LOCAL_TLS_PROXY", "1")
    monkeypatch.setattr(sys, "argv", argv)
    captured = {}
    monkeypatch.setattr(cli, "_uvicorn_logging_config", lambda: {})
    monkeypatch.setattr("mapassist.assistant_gateway.app.create_app", lambda settings: captured.update(settings=settings) or "app")
    monkeypatch.setattr(uvicorn, "run", lambda app, **kwargs: captured.update(app=app, **kwargs))
    cli.main()
    settings = captured["settings"]
    assert settings.mode == "production" and settings.local_tls_proxy and not settings.require_tls
    assert settings.device_tokens == (TOKEN,)
    assert captured["host"] == "127.0.0.1" and captured["port"] == 18765
    assert captured["ssl_certfile"] is None and captured["ssl_keyfile"] is None
    assert captured["proxy_headers"] is False


@pytest.mark.parametrize("flags,changes", [
    (["--host", "0.0.0.0"], {}),
    (["--host", "localhost"], {}),
    (["--host", "::1"], {}),
    ([], {"ASSISTANT_GATEWAY_HOST": "192.0.2.1"}),
    (["--tls-cert", "/unused/cert"], {}),
    ([], {"ASSISTANT_GATEWAY_TLS_KEY": "/unused/key"}),
    ([], {"ASSISTANT_GATEWAY_DEVICE_TOKENS": ""}),
    ([], {"ASSISTANT_GATEWAY_ASR_BACKEND": "paraformer_streaming"}),
    (["--devtest-mock"], {}),
    ([], {"ASSISTANT_GATEWAY_LOCAL_TLS_PROXY": "invalid"}),
])
def test_proxy_mode_rejects_unsafe_or_ambiguous_startup(monkeypatch, gateway_env, flags, changes):
    for key, value in changes.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(sys, "argv", ["gateway", "--behind-local-proxy", *flags])
    monkeypatch.setattr(uvicorn, "run", lambda *a, **k: pytest.fail("Unsafe server must not start"))
    with pytest.raises(SystemExit) as error:
        cli.main()
    assert error.value.code == 2


def test_existing_production_still_requires_backend_tls_without_opt_in(monkeypatch, gateway_env):
    monkeypatch.setattr(sys, "argv", ["gateway"])
    with pytest.raises(SystemExit) as error:
        cli.main()
    assert error.value.code == 2
    with pytest.raises(ValueError, match="requires TLS"):
        GatewaySettings(device_tokens=(TOKEN,), require_tls=False).validate_production()


def proxy_settings():
    return GatewaySettings(device_tokens=(TOKEN,), asr_backend="disabled", require_tls=False,
                           local_tls_proxy=True)


def test_loopback_backend_health_and_authentication_without_upstream_calls():
    with TestClient(create_app(proxy_settings(), vision_client=FixtureVision()),
                    client=("127.0.0.1", 55555)) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "vision_only"
        assert response.json()["mode"] == "production"
        assert response.json()["asr_ready"] is False
        assert client.post("/v1/visual", json={}).status_code == 401
        headers = {"Authorization": "Bearer " + TOKEN}
        assert client.post("/v1/visual", json={}, headers=headers).status_code == 422
        assert client.post("/v1/visual", content=b"a" * (2300 * 1024), headers=headers).status_code == 413
        with pytest.raises(WebSocketDisconnect) as error:
            with client.websocket_connect("/v1/audio", headers=headers):
                pass
        assert error.value.code == 1013


@pytest.mark.parametrize("peer", ["192.0.2.5", "testclient", "::1"])
def test_non_proxy_peers_and_forged_forwarded_headers_are_rejected(peer):
    with TestClient(create_app(proxy_settings(), vision_client=FixtureVision()),
                    client=(peer, 55555)) as client:
        headers = {"Authorization": "Bearer " + TOKEN,
                   "X-Forwarded-For": "127.0.0.1", "X-Forwarded-Proto": "https"}
        assert client.get("/health", headers=headers).status_code == 403
        assert client.post("/v1/visual", json={}, headers=headers).status_code == 403
        with pytest.raises(WebSocketDisconnect) as error:
            with client.websocket_connect("/v1/audio", headers=headers):
                pass
        assert error.value.code == 4403
