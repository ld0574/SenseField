from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime

import pytest

pytest.importorskip("httpx")
pytest.importorskip("fastapi")

import httpx
from fastapi.testclient import TestClient
from PIL import Image

from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.errors import VisionRateLimited, VisionTimeout, VisionUpstreamError
from mapassist.assistant_gateway.compatible import CompatibleVisionClient
from mapassist.assistant_gateway.glm import GLM_MODEL, GLM_URL, SYSTEM_PROMPT, GlmVisionClient


def _sse(content: str) -> bytes:
    event = {"choices": [{"delta": {"content": content}, "finish_reason": "stop"}]}
    return (": keep-alive\r\ndata: " + json.dumps(event) + "\r\n\r\ndata: [DONE]\r\n\r\n").encode()


def test_glm_direct_stream_uses_pinned_request_shape_and_raw_base64_image() -> None:
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=_sse('{"kind":"hud","answer":"生命值 82","uncertain":false}'))

    async def scenario() -> None:
        transport = httpx.MockTransport(handler)
        http_client = httpx.AsyncClient(transport=transport)
        client = GlmVisionClient("server-only-test-key", model="glm-4.6v", http_client=http_client)
        try:
            result = await client.complete(question="Read the HUD", image_base64="/9j/RAWBASE64")
        finally:
            await http_client.aclose()
        assert json.loads(result)["answer"] == "生命值 82"

    asyncio.run(scenario())
    body = seen["body"]
    assert seen["url"] == GLM_URL
    assert seen["authorization"] == "Bearer server-only-test-key"
    assert body["model"] == "glm-4.6v"
    assert body["stream"] is True
    assert body["max_tokens"] == 256
    assert body["thinking"] == {"type": "disabled"}
    assert "response_format" not in body
    assert "Simplified Chinese" in body["messages"][0]["content"]
    assert "kind=hud for live match values" in body["messages"][0]["content"]
    assert "Use kind=ui_text for stable menu text" in body["messages"][0]["content"]
    assert "Do not guess hidden positions, cooldowns" in body["messages"][0]["content"]
    assert "do not answer with only gold or a timer" in body["messages"][0]["content"]
    assert "后羿/鲁班七号" in body["messages"][0]["content"]
    assert "kind=hud for live match values and advice" in body["messages"][0]["content"]
    assert "开心消消乐" in body["messages"][0]["content"]
    assert body["messages"][0]["content"] == SYSTEM_PROMPT
    image = body["messages"][1]["content"][1]["image_url"]["url"]
    assert image == "/9j/RAWBASE64"
    assert not image.startswith("data:")


def test_context_images_precede_current_image_and_get_labels() -> None:
    async def scenario() -> tuple[dict[str, object], dict[str, object]]:
        http_client = httpx.AsyncClient()
        glm = GlmVisionClient("test-key", http_client=http_client)
        compatible = CompatibleVisionClient(
            "test-key",
            base_url="https://vision.example/v1",
            model="test-model",
            http_client=http_client,
        )
        try:
            context_body = glm._request_body(
                question="What build fits this hero? [context frame 1 is 5000 ms before current]",
                image_base64="CURRENT",
                context_images=("OLDEST", "NEWER"),
            )
            compatible_body = compatible._request_body(
                question="q", image_base64="CURRENT", context_images=("OLDER",)
            )
            return context_body, compatible_body
        finally:
            await http_client.aclose()

    context_body, compatible_body = asyncio.run(scenario())
    content = context_body["messages"][1]["content"]
    assert [item.get("text") for item in content if item["type"] == "text"] == [
        "What build fits this hero? [context frame 1 is 5000 ms before current]",
        "Earlier context frame 1, oldest first.",
        "Earlier context frame 2, oldest first.",
        "Current primary frame.",
    ]
    images = [item["image_url"]["url"] for item in content if item["type"] == "image_url"]
    assert images == ["OLDEST", "NEWER", "CURRENT"]

    compatible_content = compatible_body["messages"][1]["content"]
    compatible_images = [item["image_url"]["url"] for item in compatible_content if item["type"] == "image_url"]
    assert compatible_images == [
        "data:image/jpeg;base64,OLDER",
        "data:image/jpeg;base64,CURRENT",
    ]


@pytest.mark.parametrize(
    ("stream_body", "expected_outcome"),
    [
        (b"", "empty"),
        (b"data: {broken-json}\n\n", "malformed"),
        (b"x-private-frame: value\n\n", "unexpected_framing"),
        (
            b'event: model_chunk\ndata: {"choices":[]}\n\n',
            "unexpected_event",
        ),
        (
            b'event: error\ndata: {"error":{"message":"DO_NOT_LOG_PROVIDER_TEXT"}}\n\n',
            "error_event",
        ),
        (
            b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n',
            "no_content",
        ),
    ],
)
def test_sse_failures_are_classified_with_scalar_audit_only(
    stream_body: bytes,
    expected_outcome: str,
    caplog: pytest.LogCaptureFixture,
) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=stream_body)

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client)
        try:
            with pytest.raises(VisionUpstreamError):
                await client.complete(question="q", image_base64="a")
        finally:
            await http_client.aclose()

    with caplog.at_level(logging.INFO, logger="mapassist.assistant_gateway.audit"):
        asyncio.run(scenario())

    audit = "\n".join(record.getMessage() for record in caplog.records)
    assert audit.count("assistant_gateway_vision_stream") == 1
    assert f"outcome={expected_outcome}" in audit
    assert "elapsed_ms=" in audit and "first_content_ms=None" in audit
    assert "DO_NOT_LOG_PROVIDER_TEXT" not in audit


def test_sse_success_logs_timing_and_shape_without_provider_text(caplog: pytest.LogCaptureFixture) -> None:
    now = [2.0]
    answer = '{"kind":"hud","answer":"PRIVATE_ANSWER_TEXT","uncertain":false}'
    reasoning = "PRIVATE_REASONING_TEXT"
    frames = [
        {
            "choices": [
                {"delta": {"reasoning_content": reasoning, "content": answer}, "finish_reason": None},
                {"delta": {"content": "PRIVATE_ALTERNATE_TEXT"}, "finish_reason": "stop"},
            ]
        },
        {"choices": [{"delta": {}, "finish_reason": "stop"}]},
    ]
    stream_body = "".join(f"data: {json.dumps(frame)}\n\n" for frame in frames).encode()

    async def handler(_request: httpx.Request) -> httpx.Response:
        now[0] = 4.5
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=stream_body)

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client, clock=lambda: now[0])
        try:
            result = await client.complete(question="q", image_base64="a")
            assert json.loads(result)["answer"] == "PRIVATE_ANSWER_TEXT"
        finally:
            await http_client.aclose()

    with caplog.at_level(logging.INFO, logger="mapassist.assistant_gateway.audit"):
        asyncio.run(scenario())

    audit = "\n".join(record.getMessage() for record in caplog.records)
    assert "outcome=success" in audit
    assert "elapsed_ms=2500" in audit
    assert "first_content_ms=2500" in audit
    assert "event_count=2" in audit and "choice_count=3" in audit
    assert "reasoning_content_present=true" in audit
    assert f"reasoning_content_chars={len(reasoning)}" in audit
    assert f"content_chars={len(answer)}" in audit
    assert "finish_reason=stop" in audit
    assert "PRIVATE_ANSWER_TEXT" not in audit
    assert "PRIVATE_REASONING_TEXT" not in audit
    assert "PRIVATE_ALTERNATE_TEXT" not in audit


def test_429_opens_a_sixty_second_circuit_without_retrying() -> None:
    clock = [10.0]
    calls = 0

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(429, json={"error": {"message": "rate limited"}})
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=_sse('{"kind":"unknown","answer":"","uncertain":true}'))

    async def scenario() -> None:
        transport = httpx.MockTransport(handler)
        http_client = httpx.AsyncClient(transport=transport)
        client = GlmVisionClient("test", http_client=http_client, clock=lambda: clock[0])
        try:
            with pytest.raises(VisionRateLimited) as provider_error:
                await client.complete(question="q", image_base64="a")
            assert provider_error.value.safe_metadata() == {
                "source": "provider",
                "provider_code": None,
                "upstream_http_status": 429,
                "retry_after_seconds": 60,
                "retry_after_source": "gateway_backoff",
            }
            with pytest.raises(VisionRateLimited) as cooldown_error:
                await client.complete(question="q", image_base64="a")
            assert cooldown_error.value.safe_metadata() == {
                "source": "cooldown",
                "provider_code": None,
                "upstream_http_status": None,
                "retry_after_seconds": 60,
                "retry_after_source": "gateway_backoff",
            }
            assert calls == 1
            clock[0] += 60.1
            await client.complete(question="q", image_base64="a")
            assert calls == 2
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("error_code", [1302, 1305])
def test_provider_account_limit_errors_stop_followup_requests(error_code: int) -> None:
    calls = 0

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(400, json={"error": {"code": error_code, "message": "account limit"}})

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client)
        try:
            for expected_source in ("provider", "cooldown"):
                with pytest.raises(VisionRateLimited) as captured:
                    await client.complete(question="q", image_base64="a")
                assert captured.value.source == expected_source
                if expected_source == "provider":
                    assert captured.value.provider_code == str(error_code)
                    assert captured.value.upstream_http_status == 400
                    assert captured.value.retry_after_seconds == (10 if error_code == 1305 else 60)
                else:
                    assert captured.value.provider_code is None
                    assert captured.value.upstream_http_status is None
            assert calls == 1
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


def test_unrecognized_nearby_provider_code_is_not_treated_as_rate_limit() -> None:
    calls = 0

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(400, json={"error": {"code": 129, "message": "private detail"}})

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client)
        try:
            with pytest.raises(VisionUpstreamError) as captured:
                await client.complete(question="q", image_base64="a")
            assert captured.value.provider_code == "129"
            assert "private detail" not in str(captured.value)
            assert calls == 1
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


def test_provider_1305_uses_short_shared_backoff_and_recovers_on_a_new_request() -> None:
    clock = [10.0]
    calls = 0
    seen_questions: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        seen_questions.append(json.loads(request.content)["messages"][1]["content"][0]["text"])
        if calls == 1:
            return httpx.Response(
                429,
                json={"error": {"code": 1305, "message": "private provider detail"}},
            )
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=_sse('{"kind":"unknown","answer":"","uncertain":true}'),
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client, clock=lambda: clock[0])
        try:
            with pytest.raises(VisionRateLimited) as provider_error:
                await client.complete(question="first session request", image_base64="a")
            assert provider_error.value.retry_after_seconds == 10
            assert provider_error.value.retry_after_source == "gateway_backoff"
            assert "private provider detail" not in str(provider_error.value)

            # A different caller shares the provider cooldown without getting provider text or metadata.
            with pytest.raises(VisionRateLimited) as cooldown_error:
                await client.complete(question="other session request", image_base64="b")
            assert cooldown_error.value.source == "cooldown"
            assert cooldown_error.value.provider_code is None
            assert cooldown_error.value.upstream_http_status is None
            assert cooldown_error.value.retry_after_seconds == 10
            assert cooldown_error.value.retry_after_source == "gateway_backoff"
            assert "private provider detail" not in str(cooldown_error.value)
            assert calls == 1
            assert seen_questions == ["first session request"]

            # Cooldown expiry allows a fresh explicit request; no stale request is replayed automatically.
            clock[0] += 10.1
            await client.complete(question="new frame request", image_base64="c")
            assert calls == 2
            assert seen_questions == ["first session request", "new frame request"]
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("retry_after_value", ["120", format_datetime(datetime(2030, 1, 1, 0, 2, tzinfo=timezone.utc), usegmt=True)])
def test_provider_retry_after_seconds_and_http_date_are_respected(retry_after_value: str) -> None:
    calls = 0
    wall_now = datetime(2030, 1, 1, 0, 0, tzinfo=timezone.utc)

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            429,
            headers={"Retry-After": retry_after_value},
            json={"error": {"code": 1305, "message": "private provider detail"}},
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient(
            "test",
            http_client=http_client,
            clock=lambda: 100.0,
            wall_clock=lambda: wall_now,
        )
        try:
            with pytest.raises(VisionRateLimited) as provider_error:
                await client.complete(question="q", image_base64="a")
            assert provider_error.value.retry_after_seconds == 120
            assert provider_error.value.retry_after_source == "provider_header"
            with pytest.raises(VisionRateLimited) as cooldown_error:
                await client.complete(question="another user", image_base64="b")
            assert cooldown_error.value.source == "cooldown"
            assert cooldown_error.value.retry_after_seconds == 120
            assert cooldown_error.value.retry_after_source == "provider_header"
            assert calls == 1
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


def test_provider_retry_after_is_capped_at_twenty_four_hours() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            429,
            headers={"Retry-After": "1000000"},
            json={"error": {"code": 1305, "message": "private provider detail"}},
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client)
        try:
            with pytest.raises(VisionRateLimited) as captured:
                await client.complete(question="q", image_base64="a")
            assert captured.value.retry_after_seconds == 24 * 60 * 60
            assert captured.value.retry_after_source == "provider_header"
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


def test_sse_rate_limit_uses_retry_after_and_keeps_provider_text_private() -> None:
    calls = 0
    provider_message = "DO_NOT_LEAK_STREAMING_PROVIDER_MESSAGE"
    stream_error = {"error": {"code": 1305, "message": provider_message}}

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        content = ("data: " + json.dumps(stream_error) + "\n\n").encode()
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream", "Retry-After": "120"},
            content=content,
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client)
        try:
            with pytest.raises(VisionRateLimited) as captured:
                await client.complete(question="q", image_base64="a")
            assert captured.value.source == "provider"
            assert captured.value.upstream_http_status == 200
            assert captured.value.provider_code == "1305"
            assert captured.value.retry_after_seconds == 120
            assert captured.value.retry_after_source == "provider_header"
            assert provider_message not in str(captured.value)
            assert provider_message not in repr(captured.value.safe_metadata())
            with pytest.raises(VisionRateLimited) as cooldown:
                await client.complete(question="another user", image_base64="b")
            assert cooldown.value.source == "cooldown"
            assert cooldown.value.provider_code is None
            assert cooldown.value.upstream_http_status is None
            assert calls == 1
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


def test_provider_rejection_metadata_is_safe_in_response_and_audit(caplog: pytest.LogCaptureFixture) -> None:
    calls = 0
    provider_message = "DO_NOT_LEAK_PROVIDER_MESSAGE"
    api_key = "DO_NOT_LEAK_SERVER_KEY"

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            400,
            json={"error": {"code": 1302, "message": provider_message}},
        )

    class ReadyRecognizer:
        async def initialize(self) -> None:
            return None

        def new_cache(self) -> dict[str, object]:
            return {}

        async def transcribe(self, _pcm16le: bytes, *, cache: dict[str, object], is_final: bool) -> str:
            return ""

    async def close_http_client(client: httpx.AsyncClient) -> None:
        await client.aclose()

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    vision = GlmVisionClient(api_key, http_client=http_client)
    settings = GatewaySettings(device_tokens=("test-device-token",), require_tls=False, mode="test")
    app = create_app(settings, recognizer=ReadyRecognizer(), vision_client=vision)
    image = Image.new("RGB", (16, 12), "white")
    image_buffer = io.BytesIO()
    image.save(image_buffer, format="PNG")
    payload = {
        "session_id": "rate-limit-test",
        "generation": 1,
        "turn_id": "turn-1",
        "frame_id": "frame-1",
        "question": "Read the test image",
        "image_base64": base64.b64encode(image_buffer.getvalue()).decode("ascii"),
        "frame_age_ms": 0,
        "proactive": False,
    }

    try:
        with caplog.at_level(logging.WARNING, logger="mapassist.assistant_gateway.audit"):
            with TestClient(app) as client:
                headers = {"Authorization": "Bearer test-device-token"}
                provider_response = client.post("/v1/visual", json=payload, headers=headers)
                cooldown_response = client.post("/v1/visual", json=payload, headers=headers)
        assert provider_response.status_code == cooldown_response.status_code == 429
        provider_error = provider_response.json()["error"]
        assert provider_error == {
            "code": "vision_rate_limited",
            "message": "The vision service is temporarily unavailable. Try again later.",
            "source": "provider",
            "provider_code": "1302",
            "upstream_http_status": 400,
            "retry_after_seconds": 60,
            "retry_after_source": "gateway_backoff",
        }
        assert provider_response.headers["retry-after"] == "60"
        cooldown_error = cooldown_response.json()["error"]
        assert cooldown_error["source"] == "cooldown"
        assert cooldown_error["provider_code"] is None
        assert cooldown_error["upstream_http_status"] is None
        assert 1 <= cooldown_error["retry_after_seconds"] <= 60
        assert cooldown_error["retry_after_source"] == "gateway_backoff"
        assert calls == 1
        audit = "\n".join(record.getMessage() for record in caplog.records)
        assert "source=provider" in audit and "provider_code=1302" in audit and "upstream_http_status=400" in audit
        assert "source=cooldown" in audit
        for secret in (provider_message, api_key, "Read the test image"):
            assert secret not in provider_response.text
            assert secret not in cooldown_response.text
            assert secret not in audit
    finally:
        asyncio.run(close_http_client(http_client))


def test_non_rate_limit_provider_error_keeps_only_numeric_diagnostics() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            403,
            json={"error": {"code": "40101", "message": "private upstream detail"}},
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client)
        try:
            with pytest.raises(VisionUpstreamError) as captured:
                await client.complete(question="q", image_base64="a")
            assert captured.value.safe_metadata() == {
                "source": "provider",
                "provider_code": "40101",
                "upstream_http_status": 403,
            }
            assert "private upstream detail" not in str(captured.value)
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


def test_total_timeout_is_bounded_and_safe() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.2)
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=_sse("{}"))

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = GlmVisionClient("test", http_client=http_client, timeout_seconds=0.02)
        try:
            with pytest.raises(VisionTimeout):
                await client.complete(question="q", image_base64="a")
        finally:
            await http_client.aclose()

    asyncio.run(scenario())
