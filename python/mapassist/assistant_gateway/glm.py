"""Direct, bounded streaming client for GLM-4.6V-Flash."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import math
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Any, Callable, Mapping

import httpx

from .errors import GatewayError, VisionProviderAuthError, VisionRateLimited, VisionTimeout, VisionUpstreamError
from .request_trace import visual_request_context
from .prompts import load_vision_prompt

GLM_MODEL = "glm-4.6v-flash"
GLM_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
DEFAULT_PROVIDER_OVERLOAD_BACKOFF_SECONDS = 10
DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 60
MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60
_ALLOWED_FINISH_REASONS = {"stop", "length", "content_filter", "tool_calls", "function_call"}
_AUDIT_LOGGER = logging.getLogger("mapassist.assistant_gateway.audit")

SYSTEM_PROMPT = load_vision_prompt()


@dataclass
class _StreamMetrics:
    event_count: int = 0
    choice_count: int = 0
    reasoning_content_present: bool = False
    reasoning_content_chars: int = 0
    content_chars: int = 0
    first_content_ms: int | None = None
    finish_reason: str = "none"
    malformed_events: int = 0
    unexpected_events: int = 0
    unexpected_framing: int = 0
    provider_error_event: bool = False

    def outcome(self) -> str:
        if self.provider_error_event:
            return "error_event"
        if self.content_chars:
            return "success"
        if self.malformed_events:
            return "malformed"
        if self.unexpected_framing:
            return "unexpected_framing"
        if self.unexpected_events:
            return "unexpected_event"
        if not self.event_count:
            return "empty"
        return "no_content"


@dataclass
class _CallProgress:
    stage: str = "account_slot"
    upstream_http_status: int | None = None
    metrics: _StreamMetrics = field(default_factory=_StreamMetrics)

    def timeout_stage(self) -> str:
        if self.stage == "upstream_stream":
            return "stream_end" if self.metrics.content_chars else "first_content"
        return self.stage


def _extract_provider_code(data: Any) -> str | None:
    if not isinstance(data, dict):
        return None
    error = data.get("error", data)
    if not isinstance(error, dict):
        return None
    code = error.get("code")
    if isinstance(code, bool):
        return None
    if isinstance(code, int) and 0 <= code <= 2**31 - 1:
        return str(code)
    if isinstance(code, str) and code.isascii() and code.isdigit() and len(code) <= 10:
        return str(int(code))
    return None


def _parse_provider_code(raw: bytes) -> str | None:
    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    return _extract_provider_code(payload)


def _parse_retry_after_seconds(value: str | None, *, now: datetime) -> int | None:
    """Parse delta-seconds or an HTTP date, bounded to a useful 24-hour maximum."""
    if not isinstance(value, str):
        return None
    raw = value.strip()
    if not raw:
        return None
    if raw.isascii() and raw.isdigit():
        digits = raw.lstrip("0") or "0"
        if len(digits) > len(str(MAX_RETRY_AFTER_SECONDS)):
            return MAX_RETRY_AFTER_SECONDS
        try:
            return min(max(1, int(digits)), MAX_RETRY_AFTER_SECONDS)
        except ValueError:
            return None
    try:
        retry_at = parsedate_to_datetime(raw)
        if retry_at is None:
            return None
        if retry_at.tzinfo is None:
            retry_at = retry_at.replace(tzinfo=timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        seconds = math.ceil(
            (retry_at.astimezone(timezone.utc) - now.astimezone(timezone.utc)).total_seconds()
        )
    except (TypeError, ValueError, OverflowError):
        return None
    return min(max(1, seconds), MAX_RETRY_AFTER_SECONDS)


class GlmVisionClient:
    def __init__(
        self,
        api_key: str,
        *,
        model: str = GLM_MODEL,
        concurrency: int = 1,
        timeout_seconds: float = 8.0,
        system_prompt: str | None = None,
        http_client: httpx.AsyncClient | None = None,
        clock=time.monotonic,
        wall_clock: Callable[[], datetime] | None = None,
    ):
        if not api_key:
            raise ValueError("ZHIPU_API_KEY is required; vision fallback is disabled")
        self._api_key = api_key
        self._system_prompt = system_prompt if system_prompt is not None else SYSTEM_PROMPT
        self._prompt_sha256 = hashlib.sha256(self._system_prompt.encode("utf-8")).hexdigest()
        self._model = model
        self._semaphore = asyncio.Semaphore(concurrency)
        self._timeout_seconds = timeout_seconds
        self._client = http_client or httpx.AsyncClient(timeout=None)
        self._owns_client = http_client is None
        self._clock = clock
        self._wall_clock = wall_clock or (lambda: datetime.now(timezone.utc))
        self._blocked_until = 0.0
        self._blocked_retry_after_source = "gateway_backoff"
        self._closed = False

    @property
    def prompt_sha256(self) -> str:
        """Fingerprint the text loaded by this client, not the current disk file."""
        return self._prompt_sha256

    async def close(self) -> None:
        if not self._closed and self._owns_client:
            await self._client.aclose()
        self._closed = True

    async def complete(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
        timeout_seconds: float | None = None,
    ) -> str:
        if self._closed:
            raise VisionUpstreamError(source="transport")
        if self._clock() < self._blocked_until:
            raise self._local_cooldown_error()
        budget = self._timeout_seconds if timeout_seconds is None else timeout_seconds
        if (isinstance(budget, bool) or not isinstance(budget, (int, float))
                or not math.isfinite(budget) or not 0 < budget <= 30):
            raise ValueError("Vision timeout must be positive and at most 30 seconds")
        progress = _CallProgress()
        started_at = self._clock()
        try:
            return await asyncio.wait_for(
                self._complete_with_account_slot(
                    question=question,
                    image_base64=image_base64,
                    context_images=context_images,
                    progress=progress,
                ),
                timeout=budget,
            )
        except (asyncio.TimeoutError, httpx.TimeoutException) as exc:
            error = VisionTimeout(stage=progress.timeout_stage(), timeout_ms=int(budget * 1000),
                upstream_http_status=progress.upstream_http_status,
                first_content_ms=progress.metrics.first_content_ms,
                content_chars=progress.metrics.content_chars,
                reasoning_content_chars=progress.metrics.reasoning_content_chars)
            trace = visual_request_context.get() or ("none", -1, "none", "none")
            _AUDIT_LOGGER.warning(
                "assistant_gateway_vision_timeout stage=%s timeout_ms=%d elapsed_ms=%d "
                "upstream_http_status=%s first_content_ms=%s content_chars=%d reasoning_content_chars=%d "
                "session_id=%s generation=%s turn_id=%s frame_id=%s",
                error.stage, int(budget * 1000), max(0, int((self._clock() - started_at) * 1000)),
                progress.upstream_http_status, progress.metrics.first_content_ms,
                progress.metrics.content_chars, progress.metrics.reasoning_content_chars, *trace)
            raise error from exc
        except VisionRateLimited:
            raise
        except GatewayError:
            raise
        except (httpx.HTTPError, OSError) as exc:
            raise VisionUpstreamError(source="transport") from exc

    async def _complete_with_account_slot(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
        progress: _CallProgress | None = None,
    ) -> str:
        async with self._semaphore:
            if self._clock() < self._blocked_until:
                raise self._local_cooldown_error()
            if progress is not None:
                progress.stage = "upstream_headers"
            return await self._stream(
                question=question,
                image_base64=image_base64,
                context_images=context_images,
                progress=progress,
            )

    async def _stream(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
        progress: _CallProgress | None = None,
    ) -> str:
        started_at = self._clock()
        metrics = progress.metrics if progress is not None else _StreamMetrics()
        body = self._request_body(
            question=question,
            image_base64=image_base64,
            context_images=context_images,
        )
        headers = {"Authorization": f"Bearer {self._api_key}", "Accept": "text/event-stream"}
        async with self._client.stream("POST", self._request_url(), headers=headers, json=body) as response:
            if progress is not None:
                progress.upstream_http_status = response.status_code
                progress.stage = "provider_error_body" if response.status_code >= 400 else "upstream_stream"
            if response.status_code >= 400:
                provider_code = _parse_provider_code((await response.aread())[:16_384])
                if response.status_code in {401, 403}:
                    raise VisionProviderAuthError(upstream_http_status=response.status_code,
                                                  provider_code=provider_code)
                if response.status_code == 429 or provider_code in {"1302", "1305", "429"}:
                    retry_after_seconds, retry_after_source = self._retry_after_policy(
                        response.headers, provider_code
                    )
                    self._block_provider(retry_after_seconds, retry_after_source)
                    raise VisionRateLimited(
                        retry_after_seconds=retry_after_seconds,
                        retry_after_source=retry_after_source,
                        source="provider",
                        upstream_http_status=response.status_code,
                        provider_code=provider_code,
                    )
                raise VisionUpstreamError(
                    source="provider",
                    upstream_http_status=response.status_code,
                    provider_code=provider_code,
                )
            output: list[str] = []
            data_lines: list[str] = []
            event_type = "message"
            outcome = "interrupted"
            try:
                async for line in response.aiter_lines():
                    if not line:
                        if data_lines or event_type != "message":
                            metrics.event_count += 1
                            event_data = "\n".join(data_lines)
                            data_lines.clear()
                            should_stop = self._consume_event(
                                event_data,
                                output,
                                response.status_code,
                                response.headers,
                                metrics,
                                started_at=started_at,
                                event_type=event_type,
                            )
                            event_type = "message"
                            if should_stop:
                                break
                        continue
                    if line.startswith(":"):
                        continue
                    field, separator, value = line.partition(":")
                    if field == "data":
                        data_lines.append(value.lstrip() if separator else "")
                    elif field == "event":
                        event_type = value.strip() if separator else ""
                    elif field not in {"id", "retry"}:
                        metrics.unexpected_framing += 1
                else:
                    if data_lines or event_type != "message":
                        metrics.event_count += 1
                        self._consume_event(
                            "\n".join(data_lines),
                            output,
                            response.status_code,
                            response.headers,
                            metrics,
                            started_at=started_at,
                            event_type=event_type,
                        )
                result = "".join(output).strip()
                if not result:
                    outcome = metrics.outcome()
                    if outcome == "success":
                        # Whitespace-only deltas are not usable provider content.
                        outcome = "no_content"
                    raise VisionUpstreamError(
                        source="provider",
                        upstream_http_status=response.status_code,
                    )
                outcome = "success"
                return result
            except (VisionRateLimited, VisionUpstreamError):
                if outcome == "interrupted":
                    outcome = metrics.outcome()
                raise
            finally:
                self._log_stream_metrics(
                    metrics,
                    outcome=outcome,
                    upstream_http_status=response.status_code,
                    started_at=started_at,
                )

    def _request_body(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
    ) -> dict[str, Any]:
        user_content: list[dict[str, Any]] = [{"type": "text", "text": question}]
        if context_images:
            for index, context_image in enumerate(context_images, start=1):
                user_content.extend(
                    [
                        {"type": "text", "text": f"较早的背景截图{index}，按时间从早到晚排列。"},
                        {"type": "image_url", "image_url": {"url": self._image_url(context_image)}},
                    ]
                )
            user_content.extend(
                [
                    {"type": "text", "text": "当前主截图。"},
                    {"type": "image_url", "image_url": {"url": self._image_url(image_base64)}},
                ]
            )
        else:
            user_content.append(
                {"type": "image_url", "image_url": {"url": self._image_url(image_base64)}}
            )
        return {
            "model": self._model,
            "messages": [
                {"role": "system", "content": self._system_prompt},
                {
                    "role": "user",
                    "content": user_content,
                },
            ],
            "thinking": {"type": "disabled"},
            "stream": True,
            "max_tokens": 256,
        }

    def _consume_event(
        self,
        data_line: str,
        output: list[str],
        upstream_http_status: int,
        response_headers: Mapping[str, str],
        metrics: _StreamMetrics,
        *,
        started_at: float,
        event_type: str,
    ) -> bool:
        if data_line.strip() == "[DONE]":
            return True
        if event_type == "error":
            metrics.provider_error_event = True
        if not data_line and event_type not in {"", "message", "error"}:
            metrics.unexpected_events += 1
            return False
        try:
            payload = json.loads(data_line)
        except json.JSONDecodeError:
            metrics.malformed_events += 1
            if event_type == "error":
                raise VisionUpstreamError(
                    source="provider",
                    upstream_http_status=upstream_http_status,
                )
            return False
        if event_type not in {"", "message", "error"}:
            metrics.unexpected_events += 1
        if event_type == "error" or (isinstance(payload, dict) and "error" in payload):
            metrics.provider_error_event = True
            provider_code = _extract_provider_code(payload)
            if provider_code in {"1302", "1305", "429"}:
                retry_after_seconds, retry_after_source = self._retry_after_policy(
                    response_headers, provider_code
                )
                self._block_provider(retry_after_seconds, retry_after_source)
                raise VisionRateLimited(
                    retry_after_seconds=retry_after_seconds,
                    retry_after_source=retry_after_source,
                    source="provider",
                    upstream_http_status=upstream_http_status,
                    provider_code=provider_code,
                )
            raise VisionUpstreamError(
                source="provider",
                upstream_http_status=upstream_http_status,
                provider_code=provider_code,
            )
        if not isinstance(payload, dict) or not isinstance(payload.get("choices"), list):
            metrics.unexpected_events += 1
            return False
        choices = payload["choices"]
        metrics.choice_count += len(choices)
        if not choices:
            return False
        # Preserve the existing output contract: choices after index zero are
        # counted for diagnostics but never appended or allowed to end the stream.
        choice = choices[0]
        if not isinstance(choice, dict):
            metrics.unexpected_events += 1
            return False
        delta = choice.get("delta", {})
        if not isinstance(delta, dict):
            metrics.unexpected_events += 1
            return False

        if "reasoning_content" in delta:
            metrics.reasoning_content_present = True
            reasoning_content = delta["reasoning_content"]
            if isinstance(reasoning_content, str):
                metrics.reasoning_content_chars += len(reasoning_content)

        content = delta.get("content")
        content_parts: list[str] = []
        if isinstance(content, str):
            content_parts.append(content)
        elif isinstance(content, list):
            for item in content:
                if isinstance(item, dict) and isinstance(item.get("text"), str):
                    content_parts.append(item["text"])
        elif content is not None:
            metrics.unexpected_events += 1
        for part in content_parts:
            if part and metrics.first_content_ms is None:
                metrics.first_content_ms = max(0, int((self._clock() - started_at) * 1000))
            metrics.content_chars += len(part)
            output.append(part)

        finish_reason = choice.get("finish_reason")
        # Some compatible providers send an empty-string marker on every continuing
        # delta. It is not a terminal reason: stopping here discards the final answer.
        if finish_reason is None or finish_reason == "":
            return False
        metrics.finish_reason = (
            finish_reason
            if isinstance(finish_reason, str) and finish_reason in _ALLOWED_FINISH_REASONS
            else "other"
        )
        return True

    def _log_stream_metrics(
        self,
        metrics: _StreamMetrics,
        *,
        outcome: str,
        upstream_http_status: int,
        started_at: float,
    ) -> None:
        elapsed_ms = max(0, int((self._clock() - started_at) * 1000))
        trace = visual_request_context.get() or ("none", -1, "none", "none")
        log = _AUDIT_LOGGER.info if outcome == "success" else _AUDIT_LOGGER.warning
        log(
            "assistant_gateway_vision_stream outcome=%s upstream_http_status=%d elapsed_ms=%d "
            "first_content_ms=%s event_count=%d choice_count=%d reasoning_content_present=%s "
            "reasoning_content_chars=%d content_chars=%d finish_reason=%s malformed_events=%d "
            "unexpected_events=%d unexpected_framing=%d session_id=%s generation=%s turn_id=%s frame_id=%s",
            outcome,
            upstream_http_status,
            elapsed_ms,
            metrics.first_content_ms,
            metrics.event_count,
            metrics.choice_count,
            str(metrics.reasoning_content_present).lower(),
            metrics.reasoning_content_chars,
            metrics.content_chars,
            metrics.finish_reason,
            metrics.malformed_events,
            metrics.unexpected_events,
            metrics.unexpected_framing,
            *trace,
        )

    def _request_url(self) -> str:
        return GLM_URL

    def _image_url(self, image_base64: str) -> str:
        # Zhipu's GLM endpoint accepts raw base64 in image_url.url.
        return image_base64

    def _retry_after_policy(
        self,
        response_headers: Mapping[str, str],
        provider_code: str | None,
    ) -> tuple[int, str]:
        provider_header = _parse_retry_after_seconds(
            response_headers.get("Retry-After"), now=self._wall_clock()
        )
        if provider_header is not None:
            return provider_header, "provider_header"
        default = (
            DEFAULT_PROVIDER_OVERLOAD_BACKOFF_SECONDS
            if provider_code == "1305"
            else DEFAULT_RATE_LIMIT_BACKOFF_SECONDS
        )
        return default, "gateway_backoff"

    def _block_provider(self, retry_after_seconds: int, retry_after_source: str) -> None:
        blocked_until = self._clock() + retry_after_seconds
        if blocked_until >= self._blocked_until:
            self._blocked_until = blocked_until
            self._blocked_retry_after_source = retry_after_source

    def _local_cooldown_error(self) -> VisionRateLimited:
        remaining = max(1, math.ceil(self._blocked_until - self._clock()))
        return VisionRateLimited(
            retry_after_seconds=remaining,
            retry_after_source=self._blocked_retry_after_source,
            source="cooldown",
        )
