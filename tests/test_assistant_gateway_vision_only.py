from __future__ import annotations

import base64
import io

from fastapi.testclient import TestClient
from PIL import Image
from starlette.websockets import WebSocketDisconnect
import pytest

from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.config import GatewaySettings


class NoAudioRecognizer:
    async def initialize(self) -> None:
        raise AssertionError("A vision-only deployment must not load an ASR model")


class VisualFixture:
    async def complete(self, **kwargs: object) -> str:
        return '{"kind":"ui_text","answer":"设置菜单已打开。","uncertain":false}'

    async def close(self) -> None:
        pass


def test_local_asr_deployment_serves_vision_without_loading_server_asr() -> None:
    token = "test-device-token-with-no-real-secret"
    settings = GatewaySettings(device_tokens=(token,), asr_backend="disabled", require_tls=False, mode="test")
    app = create_app(settings, recognizer=NoAudioRecognizer(), vision_client=VisualFixture())
    stream = io.BytesIO()
    Image.new("RGB", (32, 20), "white").save(stream, format="JPEG")
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["status"] == "vision_only"
        assert health.json()["asr_ready"] is False
        payload = dict(session_id="s-local", generation=1, turn_id="t-1", frame_id="f-1",
                       question="请读设置菜单", image_base64=base64.b64encode(stream.getvalue()).decode(),
                       frame_age_ms=10, proactive=False)
        assert client.post("/v1/visual", json=payload).status_code == 401
        response = client.post("/v1/visual", json=payload, headers={"Authorization": f"Bearer {token}"})
        assert response.status_code == 200
        assert response.json()["answer"] == "设置菜单已打开。"
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {token}"}):
                pass
