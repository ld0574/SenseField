from __future__ import annotations

import asyncio
import json

import pytest

pytest.importorskip("httpx")

import httpx

from mapassist.assistant_gateway.compatible import CompatibleVisionClient
from mapassist.assistant_gateway.errors import VisionRateLimited


def _sse(content: str) -> bytes:
    event = {"choices": [{"delta": {"content": content}, "finish_reason": "stop"}]}
    return ("data: " + json.dumps(event) + "\n\n" "data: [DONE]\n\n").encode()


def test_compatible_client_uses_openai_streaming_shape_and_jpeg_data_uri() -> None:
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization")
        seen["accept"] = request.headers.get("accept")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=_sse('{"kind":"hud","answer":"生命值 82","uncertain":false}'))

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = CompatibleVisionClient(
            "server-only-test-key",
            base_url="https://vision.example/v1/",
            model="qwen3.8-27b",
            http_client=http_client,
        )
        try:
            result = await client.complete(question="Read the HUD", image_base64="/9j/TESTIMAGE")
        finally:
            await http_client.aclose()
        assert json.loads(result)["answer"] == "生命值 82"

    asyncio.run(scenario())
    assert seen["url"] == "https://vision.example/v1/chat/completions"
    assert seen["authorization"] == "Bearer server-only-test-key"
    assert seen["accept"] == "text/event-stream"
    body = seen["body"]
    assert body["model"] == "qwen3.8-27b"
    assert body["stream"] is True
    assert body["max_tokens"] == 256
    assert "thinking" not in body
    assert body["messages"][1]["content"][0] == {"type": "text", "text": "Read the HUD"}
    image = body["messages"][1]["content"][1]["image_url"]["url"]
    assert image == "data:image/jpeg;base64,/9j/TESTIMAGE"


def test_compatible_client_reuses_safe_rate_limit_metadata_and_cooldown() -> None:
    calls = 0
    private_message = "DO_NOT_LEAK_COMPATIBLE_PROVIDER_MESSAGE"

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            429,
            headers={"Retry-After": "7"},
            json={"error": {"code": "429", "message": private_message}},
        )

    async def scenario() -> None:
        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = CompatibleVisionClient(
            "server-only-test-key",
            base_url="https://vision.example/v1",
            model="MiniMax-M3",
            http_client=http_client,
        )
        try:
            with pytest.raises(VisionRateLimited) as provider_error:
                await client.complete(question="q", image_base64="a")
            assert provider_error.value.safe_metadata() == {
                "source": "provider",
                "provider_code": "429",
                "upstream_http_status": 429,
                "retry_after_seconds": 7,
                "retry_after_source": "provider_header",
            }
            assert private_message not in str(provider_error.value)
            assert private_message not in repr(provider_error.value.safe_metadata())
            with pytest.raises(VisionRateLimited) as cooldown_error:
                await client.complete(question="another request", image_base64="b")
            assert cooldown_error.value.source == "cooldown"
            assert cooldown_error.value.retry_after_seconds == 7
            assert private_message not in str(cooldown_error.value)
            assert calls == 1
        finally:
            await http_client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("api_key", "base_url", "model"),
    [
        ("", "https://vision.example/v1", "model"),
        ("key", "", "model"),
        ("key", "https://vision.example/v1", ""),
        ("key", "http://vision.example/v1", "model"),
        ("key", "https://user:password@vision.example/v1", "model"),
    ],
)
def test_compatible_client_requires_private_credentials_and_https_endpoint(
    api_key: str,
    base_url: str,
    model: str,
) -> None:
    with pytest.raises(ValueError):
        CompatibleVisionClient(api_key, base_url=base_url, model=model)
