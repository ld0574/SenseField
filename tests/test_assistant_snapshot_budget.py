from __future__ import annotations

import base64
import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.config import GatewaySettings, safe_configuration_status


def payload(*, snapshot=True, proactive=False):
    image = io.BytesIO()
    Image.new("RGB", (64, 32), "white").save(image, "JPEG")
    return dict(session_id="snapshot-budget", generation=1, turn_id="g1-t1", frame_id="1",
                question="我应该选哪个英雄？", frame_age_ms=20, proactive=proactive,
                snapshot_read=snapshot, image_base64=base64.b64encode(image.getvalue()).decode())


@pytest.mark.parametrize(("snapshot", "proactive", "expected"),
                         [(True, False, 17), (False, False, 12), (False, True, 12)])
def test_only_explicit_manual_snapshot_uses_extended_budget(snapshot, proactive, expected):
    class Vision:
        budget = None

        async def complete(self, *, question, image_base64, timeout_seconds=None):
            self.budget = timeout_seconds
            return '{"kind":"hud","answer":"队友选择还没齐。","uncertain":false}'

        async def close(self):
            pass

    vision = Vision()
    with TestClient(create_app(GatewaySettings(mode="development_mock", require_tls=False,
                    device_tokens=("test",)), vision_client=vision)) as client:
        response = client.post("/v1/visual", json=payload(snapshot=snapshot, proactive=proactive),
                               headers={"Authorization": "Bearer test"})
    assert response.status_code == 200
    assert vision.budget == expected
    assert (response.json().get("answer_scope") == "snapshot") == (snapshot and not proactive)


def test_automatic_request_cannot_claim_manual_snapshot_budget():
    class Vision:
        async def complete(self, **_kwargs):
            pytest.fail("Invalid automatic snapshot must not reach the provider")

        async def close(self):
            pass

    with TestClient(create_app(GatewaySettings(mode="development_mock", require_tls=False,
                    device_tokens=("test",)), vision_client=Vision())) as client:
        response = client.post("/v1/visual", json=payload(snapshot=True, proactive=True),
                               headers={"Authorization": "Bearer test"})
    assert response.status_code == 422


@pytest.mark.parametrize(("raw", "reason"), [
    ('{"kind":"unknown","answer":"","uncertain":true}', "model_uncertain"),
    ('{"kind":"hud","answer":"","uncertain":false}', "empty_answer"),
    ('{"kind":"hud","answer":"双杀","uncertain":false}', "unhelpful_answer"),
    ('{"extra":"PRIVATE_MODEL_OUTPUT"}', "invalid_output"),
    (json.dumps(dict(kind="hud", answer="长" * 101, uncertain=False)), "response_limits"),
])
def test_unknown_response_has_safe_reason_and_never_snapshot_scope(raw, reason):
    class Vision:
        async def complete(self, **_kwargs):
            return raw

        async def close(self):
            pass

    with TestClient(create_app(GatewaySettings(mode="development_mock", require_tls=False,
                    device_tokens=("test",)), vision_client=Vision())) as client:
        result = client.post("/v1/visual", json=payload(),
                             headers={"Authorization": "Bearer test"}).json()
    assert result["kind"] == "unknown" and result["uncertain"]
    assert result["unknown_reason"] == reason
    assert "answer_scope" not in result
    assert "PRIVATE" not in repr(result)


@pytest.mark.parametrize("value", ["1", "19", "nan", "invalid"])
def test_snapshot_budget_is_bounded_by_original_frame_lifetime(monkeypatch, value):
    monkeypatch.setenv("ASSISTANT_GATEWAY_SNAPSHOT_TIMEOUT_SECONDS", value)
    with pytest.raises(ValueError, match="SNAPSHOT_TIMEOUT_SECONDS"):
        GatewaySettings.from_env()


def test_health_exposes_effective_budgets_and_thinking_without_credentials():
    settings = GatewaySettings(vision_provider="compatible", vision_model="Kimi-K3",
        vision_base_url="https://example.invalid/v1", vision_api_key="PRIVATE_KEY",
        vision_disable_thinking=True)
    status = safe_configuration_status(settings, asr_ready=False, vision_ready=True)
    assert status["vision_timeout_seconds"] == 12
    assert status["snapshot_timeout_seconds"] == 17
    assert status["vision_disable_thinking"] is True
    assert "PRIVATE" not in repr(status)
