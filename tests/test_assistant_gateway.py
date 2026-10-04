from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
import threading

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from PIL import Image

from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.audio import GenerationHighWater, _merge_recognized_text
from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.errors import AsrBusy
from mapassist.assistant_gateway.validation import UNKNOWN_ANSWER, parse_visual_answer


TOKEN = "test-device-token-not-a-secret"


class FakeRecognizer:
    def __init__(self, *, text: str = "ammo 24"):
        self.text = text
        self.calls: list[tuple[bytes, dict[str, object], bool]] = []

    async def initialize(self) -> None:
        return None

    def new_cache(self) -> dict[str, object]:
        return {}

    async def transcribe(self, pcm16le: bytes, *, cache: dict[str, object], is_final: bool) -> str:
        self.calls.append((pcm16le, cache, is_final))
        return self.text


class FakeVision:
    def __init__(self, raw: str = '{"kind":"hud","answer":"弹药 24 发","uncertain":false}'):
        self.raw = raw
        self.calls: list[tuple[str, str]] = []
        self.started: asyncio.Event | None = None
        self.release: asyncio.Event | None = None

    async def complete(self, *, question: str, image_base64: str) -> str:
        self.calls.append((question, image_base64))
        if self.started is not None:
            self.started.set()
        if self.release is not None:
            await self.release.wait()
        return self.raw

    async def close(self) -> None:
        return None


def _settings() -> GatewaySettings:
    return GatewaySettings(
        device_tokens=(TOKEN,),
        zhipu_api_key="test-api-key",
        require_tls=False,
        mode="test",
    )


def _image_base64() -> str:
    image = Image.new("RGB", (32, 20), "white")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def _visual_payload(**overrides: object) -> dict[str, object]:
    return {
        "session_id": "session-1",
        "generation": 3,
        "turn_id": "turn-2",
        "frame_id": "frame-19",
        "question": "What does the HUD show?",
        "image_base64": _image_base64(),
        "frame_age_ms": 40,
        "proactive": False,
        **overrides,
    }


def test_health_and_visual_auth_and_response_ids() -> None:
    vision = FakeVision()
    with TestClient(create_app(_settings(), recognizer=FakeRecognizer(), vision_client=vision)) as client:
        assert client.get("/health").json()["status"] == "ready"
        rejected = client.post(f"/v1/visual?token={TOKEN}", json=_visual_payload())
        assert rejected.status_code == 401
        assert rejected.json()["error"]["code"] == "unauthorized"

        response = client.post(
            "/v1/visual",
            json=_visual_payload(),
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
    assert response.status_code == 200
    body = response.json()
    assert {name: body[name] for name in ("session_id", "generation", "turn_id", "frame_id")} == {
        "session_id": "session-1",
        "generation": 3,
        "turn_id": "turn-2",
        "frame_id": "frame-19",
    }
    assert body["answer"] == "弹药 24 发"
    assert body["kind"] == "hud"
    assert not body["uncertain"]
    assert vision.calls[0][1].startswith("/9j/")
    assert not vision.calls[0][1].startswith("data:")


def test_visual_rejects_oversized_body_and_malformed_image() -> None:
    with TestClient(create_app(_settings(), recognizer=FakeRecognizer(), vision_client=FakeVision())) as client:
        auth = {"Authorization": f"Bearer {TOKEN}"}
        oversized = client.post("/v1/visual", content=b" " * (700 * 1024 + 1), headers=auth)
        assert oversized.status_code == 413
        assert oversized.json()["error"]["code"] == "request_too_large"

        malformed = client.post(
            "/v1/visual",
            json=_visual_payload(image_base64="bm90LWEtcG5n"),
            headers=auth,
        )
        assert malformed.status_code == 422
        assert malformed.json()["error"]["code"] == "invalid_request"

        over_decoded_limit = base64.b64encode(bytes(512 * 1024 + 1)).decode("ascii")
        too_large_image = client.post(
            "/v1/visual",
            json=_visual_payload(image_base64=over_decoded_limit),
            headers=auth,
        )
        assert too_large_image.status_code == 422


def test_https_is_required_for_production_health_and_api() -> None:
    settings = GatewaySettings(device_tokens=(TOKEN,), zhipu_api_key="test-api-key", require_tls=True)
    with TestClient(create_app(settings, recognizer=FakeRecognizer(), vision_client=FakeVision())) as client:
        health = client.get("/health")
        assert health.status_code == 426
        assert health.json()["error"]["code"] == "https_required"
    with TestClient(
        create_app(settings, recognizer=FakeRecognizer(), vision_client=FakeVision()),
        base_url="https://gateway.test",
    ) as secure_client:
        assert secure_client.get("/health").status_code == 200


def test_unsafe_or_unreadable_model_output_becomes_unknown() -> None:
    unsafe = FakeVision('{"kind":"hud","answer":"Enemy is to the left; move now.","uncertain":false}')
    with TestClient(create_app(_settings(), recognizer=FakeRecognizer(), vision_client=unsafe)) as client:
        response = client.post("/v1/visual", json=_visual_payload(), headers={"Authorization": f"Bearer {TOKEN}"})
    assert response.status_code == 200
    assert response.json()["kind"] == "unknown"
    assert response.json()["uncertain"] is True


@pytest.mark.parametrize(
    "answer",
    [
        "敌人在右上，往左走。",
        "建议撤退。",
        "敌方在地图西侧，向前推进并开火。",
        "去楼上绕后包抄。",
    ],
)
def test_chinese_enemy_location_or_tactical_output_becomes_unknown(answer: str) -> None:
    parsed = parse_visual_answer(
        '{"kind":"hud","answer":' + json.dumps(answer, ensure_ascii=False) + ',"uncertain":false}'
    )
    assert parsed.kind == "unknown"
    assert parsed.answer == UNKNOWN_ANSWER
    assert parsed.uncertain is True


@pytest.mark.parametrize(
    ("kind", "answer"),
    [("hud", "生命值 82，弹药 24 发。"), ("ui_text", "主菜单中有设置选项。")],
)
def test_chinese_hud_values_and_stable_menu_text_remain_readable(kind: str, answer: str) -> None:
    parsed = parse_visual_answer(
        '{"kind":' + json.dumps(kind) + ',"answer":'
        + json.dumps(answer, ensure_ascii=False)
        + ',"uncertain":false}'
    )
    assert parsed.kind == kind
    assert parsed.answer == answer


def test_websocket_requires_authorization() -> None:
    with TestClient(create_app(_settings(), recognizer=FakeRecognizer(), vision_client=FakeVision())) as client:
        with pytest.raises(WebSocketDisconnect) as disconnected:
            with client.websocket_connect(f"/v1/audio?token={TOKEN}") as websocket:
                websocket.receive_text()
    assert disconnected.value.code == 4401


def test_production_without_zhipu_key_runs_asr_only_and_fails_closed_for_vision() -> None:
    settings = GatewaySettings(device_tokens=(TOKEN,), zhipu_api_key="", require_tls=True)
    app = create_app(settings, recognizer=FakeRecognizer())
    with TestClient(app, base_url="https://gateway.test") as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["status"] == "asr_only"
        assert health.json()["asr_ready"] is True
        assert health.json()["vision_ready"] is False

        vision = client.post(
            "/v1/visual",
            json=_visual_payload(),
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        assert vision.status_code == 503
        assert vision.json()["error"]["code"] == "vision_unconfigured"

        with client.websocket_connect("wss://gateway.test/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "asr-only-session", "generation": 0, "sample_rate": 16000})
            assert websocket.receive_json()["status"] == "ready"
            websocket.send_json({"type": "speech_start", "turn_id": "asr-only-turn", "generation": 0})
            websocket.send_bytes(bytes(19_200))
            websocket.send_json({"type": "speech_end", "turn_id": "asr-only-turn", "generation": 0})
            while True:
                final = websocket.receive_json()
                if final["type"] == "final":
                    break
        assert final["text"] == "ammo 24"


def test_production_configuration_still_requires_device_token_and_tls() -> None:
    missing_token = GatewaySettings(device_tokens=(), zhipu_api_key="", require_tls=True)
    with pytest.raises(ValueError, match="ASSISTANT_GATEWAY_DEVICE_TOKEN"):
        missing_token.validate_production()
    missing_tls = GatewaySettings(device_tokens=(TOKEN,), zhipu_api_key="", require_tls=False)
    with pytest.raises(ValueError, match="requires TLS"):
        missing_tls.validate_production()


def test_websocket_streams_final_and_uses_new_cache_per_utterance() -> None:
    recognizer = FakeRecognizer()
    with TestClient(create_app(_settings(), recognizer=recognizer, vision_client=FakeVision())) as client:
        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-a", "generation": 0, "sample_rate": 16000})
            final_messages = []
            for turn_id in ("turn-a", "turn-b"):
                websocket.send_json({"type": "speech_start", "turn_id": turn_id, "generation": 0})
                websocket.send_bytes(bytes(19_200))
                websocket.send_json({"type": "speech_end", "turn_id": turn_id, "generation": 0})
                while True:
                    message = websocket.receive_json()
                    if message["type"] == "final" and message["turn_id"] == turn_id:
                        final_messages.append(message)
                        break
    assert [item["text"] for item in final_messages] == ["ammo 24", "ammo 24"]
    assert [item["turn_id"] for item in final_messages] == ["turn-a", "turn-b"]
    assert all(item["finalization_ms"] >= 0 for item in final_messages)
    assert all(item["inference_ms"] >= 0 for item in final_messages)
    assert all(item["asr_ms"] >= item["finalization_ms"] for item in final_messages)
    assert len(recognizer.calls) == 2
    assert recognizer.calls[0][1] is not recognizer.calls[1][1]
    assert all(call[2] for call in recognizer.calls)


def test_audio_backlog_resets_and_suppresses_cancelled_stale_text() -> None:
    class BlockedRecognizer(FakeRecognizer):
        def __init__(self) -> None:
            super().__init__(text="stale speech")
            self.entered = threading.Event()
            self.release = threading.Event()

        async def transcribe(self, pcm16le: bytes, *, cache: dict[str, object], is_final: bool) -> str:
            self.entered.set()
            await asyncio.to_thread(self.release.wait)
            self.calls.append((pcm16le, cache, is_final))
            return self.text

    recognizer = BlockedRecognizer()
    with TestClient(create_app(_settings(), recognizer=recognizer, vision_client=FakeVision())) as client:
        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-backlog", "generation": 0, "sample_rate": 16000})
            assert websocket.receive_json()["status"] == "ready"
            websocket.send_json({"type": "speech_start", "turn_id": "old-turn", "generation": 0})
            for _ in range(10):
                websocket.send_bytes(bytes(19_200))
            assert recognizer.entered.wait(timeout=1)
            reset = websocket.receive_json()
            final = websocket.receive_json()
            assert reset["type"] == "status"
            assert reset["reason"] == "asr_backlog_reset"
            assert final["type"] == "final"
            assert final["text"] == ""

            recognizer.release.set()
            websocket.send_json({"type": "speech_start", "turn_id": "new-turn", "generation": 1})
            websocket.send_bytes(bytes(19_200))
            websocket.send_json({"type": "speech_end", "turn_id": "new-turn", "generation": 1})
            messages = []
            while True:
                message = websocket.receive_json()
                messages.append(message)
                if message["type"] == "final" and message["turn_id"] == "new-turn":
                    break
    assert not any(item["type"] == "partial" and item["turn_id"] == "old-turn" for item in messages)
    assert messages[-1]["text"] == "stale speech"


def test_audio_start_ack_and_generation_high_water_survive_reconnect() -> None:
    app = create_app(_settings(), recognizer=FakeRecognizer(), vision_client=FakeVision())
    with TestClient(app) as client:
        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-resume", "generation": 0, "sample_rate": 16000})
            ready = websocket.receive_json()
            assert ready["type"] == "status"
            assert ready["status"] == ready["reason"] == "ready"
            websocket.send_json({"type": "reset", "turn_id": "reset-g2", "generation": 2})
            reset = websocket.receive_json()
            assert reset["status"] == reset["reason"] == "reset"

        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-resume", "generation": 1, "sample_rate": 16000})
            stale = websocket.receive_json()
            assert stale["type"] == "error"
            assert stale["error"]["code"] == "stale_generation"

        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-resume", "generation": 2, "sample_rate": 16000})
            ready = websocket.receive_json()
            assert ready["status"] == "ready"


def test_equal_generation_reset_does_not_cancel_active_turn() -> None:
    recognizer = FakeRecognizer(text="继续说话")
    with TestClient(create_app(_settings(), recognizer=recognizer, vision_client=FakeVision())) as client:
        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-equal-reset", "generation": 0, "sample_rate": 16000})
            assert websocket.receive_json()["status"] == "ready"
            websocket.send_json({"type": "speech_start", "turn_id": "live-turn", "generation": 0})
            websocket.send_json({"type": "reset", "turn_id": "reset-g0", "generation": 0})
            stale_reset = websocket.receive_json()
            assert stale_reset["reason"] == "stale_reset"
            websocket.send_bytes(bytes(19_200))
            websocket.send_json({"type": "speech_end", "turn_id": "live-turn", "generation": 0})
            while True:
                result = websocket.receive_json()
                if result["type"] == "final":
                    break
    assert result["turn_id"] == "live-turn"
    assert result["text"] == "继续说话"


def test_global_asr_busy_is_reported_without_returning_stale_text(caplog: pytest.LogCaptureFixture) -> None:
    class BusyRecognizer(FakeRecognizer):
        async def transcribe(self, pcm16le: bytes, *, cache: dict[str, object], is_final: bool) -> str:
            raise AsrBusy()

    caplog.set_level(logging.INFO, logger="mapassist.assistant_gateway.audit")

    with TestClient(create_app(_settings(), recognizer=BusyRecognizer(), vision_client=FakeVision())) as client:
        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "session-asr-busy", "generation": 0, "sample_rate": 16000})
            assert websocket.receive_json()["status"] == "ready"
            websocket.send_json({"type": "speech_start", "turn_id": "busy-turn", "generation": 0})
            websocket.send_bytes(bytes(19_200))
            websocket.send_json({"type": "speech_end", "turn_id": "busy-turn", "generation": 0})
            received = []
            while not any(item["type"] == "final" for item in received):
                received.append(websocket.receive_json())
    busy = next(item for item in received if item["type"] == "status")
    final = next(item for item in received if item["type"] == "final")
    assert busy["status"] == busy["reason"] == "asr_busy"
    assert final["text"] == ""
    assert final["finalization_ms"] >= 0
    audit = "\n".join(record.getMessage() for record in caplog.records)
    assert "assistant_gateway_audio event=status reason=asr_busy" in audit
    assert "assistant_gateway_audio event=final" in audit
    assert "queue_depth=" in audit and "processing=" in audit


@pytest.mark.parametrize("fails", [False, True])
def test_audio_audit_omits_recognized_text_and_exception_body(
    fails: bool, caplog: pytest.LogCaptureFixture,
) -> None:
    marker = "DO_NOT_LOG_RECOGNITION_OR_EXCEPTION_TEXT"

    class AuditRecognizer(FakeRecognizer):
        async def transcribe(self, pcm16le: bytes, *, cache: dict[str, object], is_final: bool) -> str:
            if fails:
                raise RuntimeError(marker)
            return marker

    caplog.set_level(logging.INFO, logger="mapassist.assistant_gateway.audit")
    with TestClient(create_app(_settings(), recognizer=AuditRecognizer(), vision_client=FakeVision())) as client:
        with client.websocket_connect("/v1/audio", headers={"Authorization": f"Bearer {TOKEN}"}) as websocket:
            websocket.send_json({"type": "start", "session_id": "audit-session", "generation": 0, "sample_rate": 16000})
            assert websocket.receive_json()["status"] == "ready"
            websocket.send_json({"type": "speech_start", "turn_id": "audit-turn", "generation": 0})
            websocket.send_bytes(bytes(19_200))
            websocket.send_json({"type": "speech_end", "turn_id": "audit-turn", "generation": 0})
            while True:
                result = websocket.receive_json()
                if result["type"] == "final":
                    break
    audit = "\n".join(record.getMessage() for record in caplog.records)
    assert marker not in audit and TOKEN not in audit
    assert "assistant_gateway_audio event=final" in audit
    if fails:
        assert result["text"] == ""
        assert "reason=asr_error" in audit
    else:
        assert result["text"] == marker
        assert f"text_chars={len(marker)}" in audit


def test_generation_high_water_has_a_fixed_lru_bound() -> None:
    high_water = GenerationHighWater(capacity=2)
    assert high_water.accept("session-a", 1)
    assert high_water.accept("session-b", 1)
    assert high_water.accept("session-c", 1)
    assert len(high_water._generations) == 2
    assert high_water.accept("session-b", 0) is False


def test_streaming_asr_final_joins_incremental_chunk_hypotheses() -> None:
    transcript = ""
    for piece in ("请", "读出当", "前比分"):
        transcript = _merge_recognized_text(transcript, piece)
    assert transcript == "请读出当前比分"

    cumulative = ""
    for piece in ("请", "请读出当", "请读出当前比分"):
        cumulative = _merge_recognized_text(cumulative, piece)
    assert cumulative == "请读出当前比分"


def test_cancelled_visual_request_releases_session_and_health_stays_responsive() -> None:
    async def scenario() -> None:
        vision = FakeVision()
        vision.started = asyncio.Event()
        vision.release = asyncio.Event()
        app = create_app(_settings(), recognizer=FakeRecognizer(), vision_client=vision)
        async with app.router.lifespan_context(app):
            import httpx

            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                headers = {"Authorization": f"Bearer {TOKEN}"}
                first = asyncio.create_task(client.post("/v1/visual", json=_visual_payload(), headers=headers))
                await asyncio.wait_for(vision.started.wait(), timeout=1)
                # A same-session overlap is rejected while the first request is live.
                overlap = await client.post("/v1/visual", json=_visual_payload(frame_id="other"), headers=headers)
                assert overlap.status_code == 409
                # The event loop can still answer health while the upstream is waiting.
                health = await client.get("/health")
                assert health.status_code == 200
                first.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await first
                vision.release.set()
                retry = await client.post("/v1/visual", json=_visual_payload(frame_id="retry"), headers=headers)
                assert retry.status_code == 200

    asyncio.run(scenario())
