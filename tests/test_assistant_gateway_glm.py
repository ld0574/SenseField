from __future__ import annotations

import asyncio
import json

import pytest

pytest.importorskip("httpx")

import httpx

from mapassist.assistant_gateway.errors import VisionRateLimited, VisionTimeout
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
        client = GlmVisionClient("server-only-test-key", http_client=http_client)
        try:
            result = await client.complete(question="Read the HUD", image_base64="/9j/RAWBASE64")
        finally:
            await http_client.aclose()
        assert json.loads(result)["answer"] == "生命值 82"

    asyncio.run(scenario())
    body = seen["body"]
    assert seen["url"] == GLM_URL
    assert seen["authorization"] == "Bearer server-only-test-key"
    assert body["model"] == GLM_MODEL
    assert body["stream"] is True
    assert body["max_tokens"] == 256
    assert body["thinking"] == {"type": "disabled"}
    assert "response_format" not in body
    assert "Simplified Chinese" in body["messages"][0]["content"]
    assert "kind=hud for changing" in body["messages"][0]["content"]
    assert "kind=ui_text only for stable" in body["messages"][0]["content"]
    assert body["messages"][0]["content"] == SYSTEM_PROMPT
    image = body["messages"][1]["content"][1]["image_url"]["url"]
    assert image == "/9j/RAWBASE64"
    assert not image.startswith("data:")


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
            with pytest.raises(VisionRateLimited):
                await client.complete(question="q", image_base64="a")
            with pytest.raises(VisionRateLimited):
                await client.complete(question="q", image_base64="a")
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
            for _ in range(2):
                with pytest.raises(VisionRateLimited):
                    await client.complete(question="q", image_base64="a")
            assert calls == 1
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
