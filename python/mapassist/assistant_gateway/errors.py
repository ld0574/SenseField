"""Safe, client-visible gateway errors."""

from __future__ import annotations


class GatewayError(Exception):
    def __init__(self, code: str, message: str, *, http_status: int = 503):
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status

    def safe_metadata(self) -> dict[str, str | int | None]:
        """Scalar-only diagnostics safe to expose and audit."""
        return {}


class AsrBusy(GatewayError):
    def __init__(self):
        super().__init__("asr_busy", "Speech recognition is busy. Try again shortly.", http_status=503)


class VisionRateLimited(GatewayError):
    MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60

    def __init__(
        self,
        retry_after_seconds: int = 60,
        *,
        retry_after_source: str = "gateway_backoff",
        source: str = "provider",
        upstream_http_status: int | None = None,
        provider_code: str | int | None = None,
    ):
        if source not in {"provider", "cooldown"}:
            source = "provider"
        if isinstance(retry_after_seconds, bool) or not isinstance(retry_after_seconds, int):
            retry_after_seconds = 60
            retry_after_source = "gateway_backoff"
        if retry_after_source not in {"provider_header", "gateway_backoff"}:
            retry_after_source = "gateway_backoff"
        retry_after_seconds = min(max(1, retry_after_seconds), self.MAX_RETRY_AFTER_SECONDS)
        super().__init__(
            "vision_rate_limited",
            "The vision service is temporarily unavailable. Try again later.",
            http_status=429,
        )
        self.retry_after_seconds = retry_after_seconds
        self.retry_after_source = retry_after_source
        self.source = source
        self.upstream_http_status = _safe_http_status(upstream_http_status)
        self.provider_code = _safe_provider_code(provider_code)

    def safe_metadata(self) -> dict[str, str | int | None]:
        return {
            "source": self.source,
            "provider_code": self.provider_code,
            "upstream_http_status": self.upstream_http_status,
            "retry_after_seconds": self.retry_after_seconds,
            "retry_after_source": self.retry_after_source,
        }


class VisionUpstreamError(GatewayError):
    def __init__(
        self,
        *,
        source: str = "upstream",
        upstream_http_status: int | None = None,
        provider_code: str | int | None = None,
    ):
        super().__init__(
            "vision_upstream_unavailable",
            "The vision service could not complete this request.",
            http_status=502,
        )
        self.source = source if source in {"provider", "transport", "upstream"} else "upstream"
        self.upstream_http_status = _safe_http_status(upstream_http_status)
        self.provider_code = _safe_provider_code(provider_code)

    def safe_metadata(self) -> dict[str, str | int | None]:
        return {
            "source": self.source,
            "provider_code": self.provider_code,
            "upstream_http_status": self.upstream_http_status,
        }


class VisionTimeout(GatewayError):
    def __init__(self):
        super().__init__("vision_timeout", "The vision request timed out.", http_status=504)


def _safe_http_status(value: int | None) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or not 100 <= value <= 599:
        return None
    return value


def _safe_provider_code(value: str | int | None) -> str | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return str(value) if 0 <= value <= 2**31 - 1 else None
    if isinstance(value, str) and value.isascii() and value.isdigit() and len(value) <= 10:
        return str(int(value))
    return None
