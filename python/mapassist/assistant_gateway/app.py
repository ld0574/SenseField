"""FastAPI application for the authenticated audio and visual protocols."""

from __future__ import annotations

import asyncio
import hmac
import json
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import JSONResponse

from .asr import FunAsrStreamingRecognizer, MockStreamingRecognizer
from .audio import AudioProtocolError, AudioWebSocketSession, GenerationHighWater, validate_start
from .config import GatewaySettings, safe_configuration_status
from .errors import GatewayError
from .glm import GlmVisionClient
from .validation import UNKNOWN_ANSWER, InvalidRequest, VisualAnswer, parse_visual_answer, validate_visual_request

_MAX_REQUEST_BODY_BYTES = 700 * 1024


class HttpBodyLimitMiddleware:
    """Reject oversized JSON before FastAPI buffers an unbounded request."""

    def __init__(self, app: Any, max_bytes: int = _MAX_REQUEST_BODY_BYTES):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope.get("type") != "http" or scope.get("path") != "/v1/visual":
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        raw_length = headers.get(b"content-length")
        if raw_length:
            try:
                if int(raw_length) > self.max_bytes:
                    await self._too_large(send)
                    return
            except ValueError:
                await self._too_large(send)
                return

        chunks: list[bytes] = []
        total = 0
        while True:
            message = await receive()
            if message.get("type") == "http.disconnect":
                return
            if message.get("type") != "http.request":
                continue
            body = message.get("body", b"")
            total += len(body)
            if total > self.max_bytes:
                await self._too_large(send)
                return
            chunks.append(body)
            if not message.get("more_body", False):
                break

        body = b"".join(chunks)
        delivered = False

        async def replay_receive() -> dict[str, Any]:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, replay_receive, send)

    @staticmethod
    async def _too_large(send: Any) -> None:
        body = json.dumps(
            {"error": {"code": "request_too_large", "message": "Request body exceeds the limit."}},
            separators=(",", ":"),
        ).encode("utf-8")
        await send({"type": "http.response.start", "status": 413, "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
        await send({"type": "http.response.body", "body": body})


class MockVisionClient:
    async def complete(self, *, question: str, image_base64: str) -> str:
        del question, image_base64
        return '{"kind":"unknown","answer":"","uncertain":true}'

    async def close(self) -> None:
        return None


def _authorization_matches(header: str | None, tokens: tuple[str, ...]) -> bool:
    if not header:
        return False
    scheme, separator, token = header.partition(" ")
    if scheme.lower() != "bearer" or not separator or not token or " " in token:
        return False
    return any(hmac.compare_digest(token.encode("utf-8"), candidate.encode("utf-8")) for candidate in tokens)


def _secure_scheme(scope: dict[str, Any]) -> bool:
    return scope.get("scheme") in {"https", "wss"}


def create_app(
    settings: GatewaySettings | None = None,
    *,
    recognizer: Any | None = None,
    vision_client: Any | None = None,
) -> FastAPI:
    settings = settings or GatewaySettings.from_env()
    if recognizer is None:
        if settings.mode == "development_mock":
            recognizer = MockStreamingRecognizer()
        else:
            recognizer = FunAsrStreamingRecognizer(cache_dir=settings.model_cache_dir)
    if vision_client is None:
        if settings.mode == "development_mock":
            vision_client = MockVisionClient()
        elif settings.zhipu_api_key:
            vision_client = GlmVisionClient(
                settings.zhipu_api_key,
                concurrency=settings.account_concurrency,
                timeout_seconds=settings.request_timeout_seconds,
            )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.asr_ready = False
        app.state.vision_ready = vision_client is not None
        app.state.active_audio_sessions = set()
        app.state.audio_sessions_lock = asyncio.Lock()
        app.state.audio_generation_high_water = GenerationHighWater()
        app.state.visual_sessions_busy = set()
        app.state.visual_sessions_lock = asyncio.Lock()
        try:
            if settings.mode == "production":
                settings.validate_production()
            initialize = getattr(recognizer, "initialize", None)
            if initialize is None:
                raise RuntimeError("ASR adapter has no initialization step")
            await initialize()
            app.state.asr_ready = True
            app.state.vision_ready = vision_client is not None
            yield
        finally:
            app.state.asr_ready = False
            close = getattr(vision_client, "close", None)
            if close is not None:
                await close()

    app = FastAPI(title="MapAssist Assistant Gateway", docs_url=None, redoc_url=None, lifespan=lifespan)
    app.add_middleware(HttpBodyLimitMiddleware)

    @app.exception_handler(GatewayError)
    async def gateway_error_handler(_request: Request, exc: GatewayError) -> JSONResponse:
        headers = {}
        retry_after = getattr(exc, "retry_after_seconds", None)
        if retry_after is not None:
            headers["Retry-After"] = str(retry_after)
        return JSONResponse(
            {"error": {"code": exc.code, "message": exc.message}},
            status_code=exc.http_status,
            headers=headers,
        )

    @app.get("/health")
    async def health(request: Request) -> JSONResponse:
        if settings.require_tls and not _secure_scheme(request.scope):
            return JSONResponse(
                {"error": {"code": "https_required", "message": "Use HTTPS for this service."}},
                status_code=426,
            )
        result = safe_configuration_status(
            settings,
            asr_ready=request.app.state.asr_ready,
            vision_ready=request.app.state.vision_ready,
        )
        status_code = 200 if result["status"] in {"ready", "asr_only", "development_mock"} else 503
        return JSONResponse(result, status_code=status_code)

    @app.post("/v1/visual")
    async def visual(request: Request) -> JSONResponse:
        if settings.require_tls and not _secure_scheme(request.scope):
            raise GatewayError("https_required", "Use HTTPS for this service.", http_status=426)
        if not _authorization_matches(request.headers.get("authorization"), settings.device_tokens):
            raise GatewayError("unauthorized", "A valid bearer device token is required.", http_status=401)
        if not request.app.state.asr_ready:
            raise GatewayError("not_ready", "The assistant gateway is not ready.", http_status=503)
        if not request.app.state.vision_ready or vision_client is None:
            raise GatewayError(
                "vision_unconfigured",
                "Vision is not configured; set ZHIPU_API_KEY to enable screen understanding.",
                http_status=503,
            )
        try:
            payload = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise GatewayError("invalid_request", "Request body must be valid JSON.", http_status=400)
        try:
            visual_request = validate_visual_request(payload)
        except InvalidRequest as exc:
            raise GatewayError("invalid_request", str(exc), http_status=422) from exc
        del payload

        async with request.app.state.visual_sessions_lock:
            if visual_request.session_id in request.app.state.visual_sessions_busy:
                raise GatewayError("vision_busy", "A visual request is already active for this session.", http_status=409)
            request.app.state.visual_sessions_busy.add(visual_request.session_id)
        started = time.monotonic()
        try:
            raw_answer = await vision_client.complete(
                question=visual_request.question,
                image_base64=visual_request.image.jpeg_base64,
            )
            try:
                answer = parse_visual_answer(raw_answer)
            except InvalidRequest:
                answer = VisualAnswer(
                    "unknown",
                    UNKNOWN_ANSWER,
                    True,
                )
            result = {
                "session_id": visual_request.session_id,
                "generation": visual_request.generation,
                "turn_id": visual_request.turn_id,
                "frame_id": visual_request.frame_id,
                "kind": answer.kind,
                "answer": answer.answer,
                "uncertain": answer.uncertain,
                "elapsed_ms": max(0, int((time.monotonic() - started) * 1000)),
            }
            return JSONResponse(result)
        finally:
            async with request.app.state.visual_sessions_lock:
                request.app.state.visual_sessions_busy.discard(visual_request.session_id)

    @app.websocket("/v1/audio")
    async def audio(websocket: WebSocket) -> None:
        if settings.require_tls and not _secure_scheme(websocket.scope):
            await websocket.close(code=4403, reason="WSS required")
            return
        if not _authorization_matches(websocket.headers.get("authorization"), settings.device_tokens):
            await websocket.close(code=4401, reason="Bearer device token required")
            return
        if not websocket.app.state.asr_ready:
            await websocket.close(code=1013, reason="ASR unavailable")
            return
        await websocket.accept()
        try:
            first = await asyncio.wait_for(websocket.receive(), timeout=10.0)
            if first.get("type") == "websocket.disconnect":
                return
            if first.get("text") is None or len(first.get("text", "")) > 4096:
                raise AudioProtocolError("first message must be a start object")
            start = json.loads(first["text"])
            session_id, generation = validate_start(start)
        except (AudioProtocolError, json.JSONDecodeError, TimeoutError, TypeError) as exc:
            await websocket.send_json({"type": "error", "error": {"code": "invalid_start", "message": "A valid start message is required."}})
            await websocket.close(code=4400)
            return
        async with websocket.app.state.audio_sessions_lock:
            if session_id in websocket.app.state.active_audio_sessions:
                await websocket.send_json({"type": "error", "error": {"code": "audio_session_busy", "message": "An audio stream is already active for this session."}})
                await websocket.close(code=4409)
                return
            high_water = websocket.app.state.audio_generation_high_water
            if not high_water.accept(session_id, generation):
                await websocket.send_json({
                    "type": "error",
                    "error": {
                        "code": "stale_generation",
                        "message": "The audio session generation is older than the latest accepted generation.",
                    },
                })
                await websocket.close(code=4409)
                return
            websocket.app.state.active_audio_sessions.add(session_id)
        try:
            await websocket.send_json({
                "type": "status",
                "status": "ready",
                "reason": "ready",
                "session_id": session_id,
                "generation": generation,
                "turn_id": "",
                "text": "",
                "asr_ms": 0,
                "inference_ms": 0,
            })
            await AudioWebSocketSession(
                session_id,
                generation,
                recognizer,
                generation_high_water=high_water,
            ).run(websocket)
        finally:
            async with websocket.app.state.audio_sessions_lock:
                websocket.app.state.active_audio_sessions.discard(session_id)

    return app
