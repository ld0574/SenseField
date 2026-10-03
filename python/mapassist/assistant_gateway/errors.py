"""Safe, client-visible gateway errors."""

from __future__ import annotations


class GatewayError(Exception):
    def __init__(self, code: str, message: str, *, http_status: int = 503):
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status


class AsrBusy(GatewayError):
    def __init__(self):
        super().__init__("asr_busy", "Speech recognition is busy. Try again shortly.", http_status=503)


class VisionRateLimited(GatewayError):
    def __init__(self, retry_after_seconds: int = 60):
        super().__init__(
            "vision_rate_limited",
            "The vision service is temporarily unavailable. Try again later.",
            http_status=429,
        )
        self.retry_after_seconds = retry_after_seconds


class VisionUpstreamError(GatewayError):
    def __init__(self):
        super().__init__(
            "vision_upstream_unavailable",
            "The vision service could not complete this request.",
            http_status=502,
        )


class VisionTimeout(GatewayError):
    def __init__(self):
        super().__init__("vision_timeout", "The vision request timed out.", http_status=504)
