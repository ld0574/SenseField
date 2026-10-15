from __future__ import annotations

import asyncio
import base64
import io
import json
import logging

import pytest
pytest.importorskip("httpx")
pytest.importorskip("fastapi")

import httpx
from fastapi.testclient import TestClient
from PIL import Image

from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.compatible import CompatibleVisionClient
from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.errors import VisionProviderAuthError
from mapassist.assistant_gateway.glm import GlmVisionClient


@pytest.mark.parametrize("status,code", [(401, "vision_provider_auth_failed"),
                                       (403, "vision_provider_access_denied")])
@pytest.mark.parametrize("compatible", [False, True])
def test_model_auth_is_not_a_transient_error_or_rate_limit(status: int, code: str, compatible: bool) -> None:
    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(
                status, json={"error": {"code": "429", "message": "PRIVATE_PROVIDER_DETAIL"}}))) as upstream:
            vision = (CompatibleVisionClient("PRIVATE_SERVER_KEY", base_url="https://provider.example/v1",
                                              model="test-vision", http_client=upstream)
                      if compatible else GlmVisionClient("PRIVATE_SERVER_KEY", http_client=upstream))
            with pytest.raises(VisionProviderAuthError) as error:
                await vision.complete(question="test", image_base64="test")
            assert error.value.http_status == 502
            assert error.value.code == code
            assert error.value.safe_metadata() == {
                "source": "provider", "provider_code": "429", "upstream_http_status": status}
            assert "PRIVATE" not in str(error.value)
            assert "PRIVATE" not in json.dumps(error.value.safe_metadata())

    asyncio.run(scenario())


def test_visual_endpoint_preserves_provider401_without_disclosing_body_or_key(caplog) -> None:
    upstream = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(
        401, json={"error": {"message": "PRIVATE_PROVIDER_DETAIL"}})))
    vision = CompatibleVisionClient("PRIVATE_SERVER_KEY", base_url="https://provider.example/v1",
                                    model="test-vision", http_client=upstream)
    settings = GatewaySettings(require_tls=False, mode="test", asr_backend="disabled",
                               device_tokens=("test-client-token",))
    image = io.BytesIO()
    Image.new("RGB", (16, 12), "white").save(image, format="JPEG")
    payload = {"session_id": "synthetic-auth", "generation": 1, "turn_id": "t1",
               "frame_id": "f1", "question": "test", "frame_age_ms": 0, "proactive": False,
               "image_base64": base64.b64encode(image.getvalue()).decode("ascii")}
    try:
        with caplog.at_level(logging.WARNING, logger="mapassist.assistant_gateway.audit"):
            with TestClient(create_app(settings, vision_client=vision)) as gateway:
                response = gateway.post("/v1/visual", json=payload,
                                        headers={"Authorization": "Bearer test-client-token"})
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "vision_provider_auth_failed"
        assert response.json()["error"]["upstream_http_status"] == 401
        assert "PRIVATE" not in response.text
        assert "PRIVATE" not in caplog.text
        assert "vision_provider_auth_failed" in caplog.text
    finally:
        asyncio.run(upstream.aclose())
