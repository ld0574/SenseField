"""FastAPI application for the authenticated audio and visual protocols."""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Any

from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import JSONResponse

from .asr import FunAsrStreamingRecognizer, MockStreamingRecognizer
from .sensevoice import SenseVoiceRecognizer
from .audio import AudioProtocolError, AudioWebSocketSession, GenerationHighWater, validate_start
from .config import GatewaySettings, safe_configuration_status
from .errors import GatewayError
from .glm import GlmVisionClient
from .compatible import CompatibleVisionClient
from .validation import (
    UNKNOWN_ANSWER,
    InvalidRequest,
    VisualAnswer,
    VisualRequest,
    parse_visual_answer,
    validate_visual_request,
)

# Three bounded images (the primary plus at most two context frames), JSON
# metadata, and the validated question fit below this cap.
_MAX_REQUEST_BODY_BYTES = 3 * 700 * 1024 + 16 * 1024
_VISUAL_PREEMPTION_TIMEOUT_SECONDS = 2.0
_DISCONNECT_POLL_INTERVAL_SECONDS = 0.05
_AUDIT_LOGGER = logging.getLogger("mapassist.assistant_gateway.audit")


@dataclass
class _ActiveVisualRequest:
    owner_task: asyncio.Task[Any]
    proactive: bool
    generation: int
    upstream_task: asyncio.Task[str] | None = None
    pending_manual: asyncio.Task[Any] | None = None
    handoff: asyncio.Event = field(default_factory=asyncio.Event)


async def _wait_for_client_disconnect(request: Request) -> None:
    while not await request.is_disconnected():
        await asyncio.sleep(_DISCONNECT_POLL_INTERVAL_SECONDS)


async def _cancel_and_wait(task: asyncio.Task[Any] | None) -> None:
    if task is None:
        return
    if not task.done():
        task.cancel()
    await asyncio.gather(task, return_exceptions=True)


async def _complete_or_disconnect(
    task: asyncio.Task[str],
    disconnect_task: asyncio.Task[None],
) -> str:
    done, _pending = await asyncio.wait(
        {task, disconnect_task},
        return_when=asyncio.FIRST_COMPLETED,
    )
    if disconnect_task in done:
        await _cancel_and_wait(task)
        raise asyncio.CancelledError
    return await task


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
    async def complete(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
    ) -> str:
        del question, image_base64, context_images
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


def _vision_call(vision_client: Any, visual_request: VisualRequest) -> Any:
    context_images = tuple(frame.image.jpeg_base64 for frame in visual_request.context_frames)
    question = visual_request.question
    if context_images:
        context_ages = ", ".join(
            f"context frame {index} is {frame.frame_age_ms} ms before the primary frame"
            for index, frame in enumerate(visual_request.context_frames, start=1)
        )
        question = (
            f"{question}\n\n"
            "[Frame timing metadata, separate from the user question: "
            f"{context_ages}; the primary frame is {visual_request.frame_age_ms} ms old. "
            "Attached images follow this chronological order, with the primary frame last.]"
        )
        # Keep the legacy no-context call shape intact for third-party clients
        # and existing adapters that only accept question and image_base64.
        return vision_client.complete(
            question=question,
            image_base64=visual_request.image.jpeg_base64,
            context_images=context_images,
        )
    return vision_client.complete(
        question=question,
        image_base64=visual_request.image.jpeg_base64,
    )


def create_app(
    settings: GatewaySettings | None = None,
    *,
    recognizer: Any | None = None,
    vision_client: Any | None = None,
) -> FastAPI:
    settings = settings or GatewaySettings.from_env()
    if recognizer is None and settings.asr_backend != "disabled":
        if settings.mode == "development_mock":
            recognizer = MockStreamingRecognizer()
        elif settings.asr_backend == "sensevoice_int8":
            recognizer = SenseVoiceRecognizer(model_dir=settings.sensevoice_model_dir)
        else:
            recognizer = FunAsrStreamingRecognizer(cache_dir=settings.model_cache_dir)
    if vision_client is None:
        if settings.mode == "development_mock":
            vision_client = MockVisionClient()
        elif settings.vision_provider == "compatible" and settings.vision_api_key:
            vision_client = CompatibleVisionClient(
                settings.vision_api_key,
                base_url=settings.vision_base_url,
                model=settings.vision_model,
                max_tokens=settings.vision_max_tokens,
                concurrency=settings.account_concurrency,
                timeout_seconds=settings.request_timeout_seconds,
            )
        elif settings.vision_provider == "zhipu" and settings.zhipu_api_key:
            vision_client = GlmVisionClient(
                settings.zhipu_api_key,
                model=settings.zhipu_model,
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
        app.state.active_visual_requests = {}
        app.state.visual_sessions_lock = asyncio.Lock()
        try:
            if settings.mode == "production":
                settings.validate_production()
            if settings.asr_backend != "disabled":
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
        metadata = exc.safe_metadata()
        if metadata:
            _AUDIT_LOGGER.warning(
                "assistant_gateway_audit event=%s source=%s provider_code=%s upstream_http_status=%s retry_after_seconds=%s retry_after_source=%s",
                exc.code,
                metadata.get("source"),
                metadata.get("provider_code"),
                metadata.get("upstream_http_status"),
                metadata.get("retry_after_seconds"),
                metadata.get("retry_after_source"),
            )
        return JSONResponse(
            {"error": {"code": exc.code, "message": exc.message, **metadata}},
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
        status_code = 200 if result["status"] in {"ready", "vision_only", "asr_only", "development_mock"} else 503
        return JSONResponse(result, status_code=status_code)

    @app.post("/v1/visual")
    async def visual(request: Request) -> JSONResponse:
        if settings.require_tls and not _secure_scheme(request.scope):
            raise GatewayError("https_required", "Use HTTPS for this service.", http_status=426)
        if not _authorization_matches(request.headers.get("authorization"), settings.device_tokens):
            raise GatewayError("unauthorized", "A valid bearer device token is required.", http_status=401)
        if not request.app.state.vision_ready or vision_client is None:
            raise GatewayError(
                "vision_unconfigured",
                "The selected vision provider is not configured on the server.",
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

        _AUDIT_LOGGER.info(
            "assistant_gateway_visual event=request_accepted generation=%s turn_id=%s frame_id=%s context_count=%s",
            visual_request.generation,
            visual_request.turn_id,
            visual_request.frame_id,
            len(visual_request.context_frames),
        )

        session_id = visual_request.session_id
        current_task = asyncio.current_task()
        if current_task is None:
            raise RuntimeError("visual request has no owning task")
        started = time.monotonic()
        disconnect_task = asyncio.create_task(_wait_for_client_disconnect(request))
        active: _ActiveVisualRequest | None = None
        owns_slot = False
        handoff_task: asyncio.Task[bool] | None = None
        try:
            async with request.app.state.visual_sessions_lock:
                active = request.app.state.active_visual_requests.get(session_id)
                if active is None:
                    active = _ActiveVisualRequest(
                        owner_task=current_task,
                        proactive=visual_request.proactive,
                        generation=visual_request.generation,
                    )
                    active.upstream_task = asyncio.create_task(
                        _vision_call(vision_client, visual_request)
                    )
                    request.app.state.active_visual_requests[session_id] = active
                    owns_slot = True
                    preempted_task = None
                elif (
                    not visual_request.proactive
                    and active.proactive
                    and active.pending_manual is None
                    and visual_request.generation >= active.generation
                ):
                    # Reserve the slot for this manual request before asking
                    # the proactive request to stop. Other arrivals stay busy
                    # until cancellation has fully released the upstream call.
                    active.pending_manual = current_task
                    preempted_task = active.upstream_task
                else:
                    raise GatewayError(
                        "vision_busy",
                        "A visual request is already active for this session.",
                        http_status=409,
                    )

            if not owns_slot:
                if preempted_task is not None and not preempted_task.done():
                    preempted_task.cancel()
                handoff_task = asyncio.create_task(active.handoff.wait())
                try:
                    done, _pending = await asyncio.wait(
                        {handoff_task, disconnect_task},
                        timeout=_VISUAL_PREEMPTION_TIMEOUT_SECONDS,
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if disconnect_task in done:
                        raise asyncio.CancelledError
                    if handoff_task not in done:
                        async with request.app.state.visual_sessions_lock:
                            transferred = active.owner_task is current_task
                            if not transferred and active.pending_manual is current_task:
                                active.pending_manual = None
                        if not transferred:
                            raise GatewayError(
                                "vision_busy",
                                "The previous visual request is still stopping.",
                                http_status=409,
                            )
                finally:
                    if not handoff_task.done():
                        handoff_task.cancel()
                    await asyncio.gather(handoff_task, return_exceptions=True)

                async with request.app.state.visual_sessions_lock:
                    if (
                        request.app.state.active_visual_requests.get(session_id) is not active
                        or active.owner_task is not current_task
                    ):
                        raise GatewayError(
                            "vision_busy",
                            "A visual request is already active for this session.",
                            http_status=409,
                        )
                    active.proactive = False
                    active.generation = visual_request.generation
                    active.upstream_task = asyncio.create_task(
                        _vision_call(vision_client, visual_request)
                    )
                    owns_slot = True

            if active.upstream_task is None:
                raise RuntimeError("visual request slot has no upstream task")
            raw_answer = await _complete_or_disconnect(active.upstream_task, disconnect_task)
            try:
                answer = parse_visual_answer(raw_answer, question=visual_request.question)
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
            _AUDIT_LOGGER.info(
                "assistant_gateway_visual event=response_validated generation=%s turn_id=%s frame_id=%s context_count=%s kind=%s uncertain=%s answer_chars=%s elapsed_ms=%s",
                visual_request.generation,
                visual_request.turn_id,
                visual_request.frame_id,
                len(visual_request.context_frames),
                answer.kind,
                answer.uncertain,
                len(answer.answer),
                result["elapsed_ms"],
            )
            return JSONResponse(result)
        finally:
            if not disconnect_task.done():
                disconnect_task.cancel()
            await asyncio.gather(disconnect_task, return_exceptions=True)
            if handoff_task is not None and not handoff_task.done():
                handoff_task.cancel()
                await asyncio.gather(handoff_task, return_exceptions=True)
            if active is not None:
                if active.owner_task is current_task:
                    await _cancel_and_wait(active.upstream_task)
                async with request.app.state.visual_sessions_lock:
                    if active.owner_task is current_task:
                        if active.pending_manual is not None:
                            active.owner_task = active.pending_manual
                            active.pending_manual = None
                            active.proactive = False
                            active.upstream_task = None
                            active.handoff.set()
                        else:
                            if request.app.state.active_visual_requests.get(session_id) is active:
                                request.app.state.active_visual_requests.pop(session_id, None)
                            active.handoff.set()
                    elif active.pending_manual is current_task:
                        active.pending_manual = None

    @app.websocket("/v1/audio")
    async def audio(websocket: WebSocket) -> None:
        if settings.require_tls and not _secure_scheme(websocket.scope):
            await websocket.close(code=4403, reason="WSS required")
            return
        if not _authorization_matches(websocket.headers.get("authorization"), settings.device_tokens):
            await websocket.close(code=4401, reason="Bearer device token required")
            return
        if not websocket.app.state.asr_ready:
            _AUDIT_LOGGER.warning("assistant_gateway_audio event=rejected reason=asr_unavailable")
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
