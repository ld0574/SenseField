"""Environment-backed configuration for the self-hosted gateway."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class GatewaySettings:
    device_tokens: tuple[str, ...] = field(default_factory=tuple, repr=False)
    zhipu_api_key: str = field(default="", repr=False)
    model_cache_dir: Path | None = None
    account_concurrency: int = 1
    request_timeout_seconds: float = 8.0
    require_tls: bool = True
    mode: str = "production"

    @classmethod
    def from_env(cls, *, require_tls: bool = True, mode: str = "production") -> "GatewaySettings":
        values: list[str] = []
        for key in ("ASSISTANT_GATEWAY_DEVICE_TOKENS", "ASSISTANT_GATEWAY_DEVICE_TOKEN"):
            raw = os.environ.get(key, "")
            values.extend(piece.strip() for piece in raw.split(",") if piece.strip())
        # Preserve order while discarding duplicate credentials.
        tokens = tuple(dict.fromkeys(values))
        cache = os.environ.get("ASSISTANT_GATEWAY_MODEL_CACHE", "").strip()
        concurrency_raw = os.environ.get("ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY", "1").strip()
        try:
            concurrency = int(concurrency_raw)
        except ValueError as exc:
            raise ValueError("ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY must be an integer") from exc
        if concurrency < 1 or concurrency > 32:
            raise ValueError("ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY must be between 1 and 32")
        return cls(
            device_tokens=tokens,
            zhipu_api_key=os.environ.get("ZHIPU_API_KEY", "").strip(),
            model_cache_dir=Path(cache).expanduser() if cache else None,
            account_concurrency=concurrency,
            require_tls=require_tls,
            mode=mode,
        )

    def validate_production(self) -> None:
        if self.mode != "production":
            raise ValueError("Production startup requires production mode")
        if not self.device_tokens:
            raise ValueError("Set ASSISTANT_GATEWAY_DEVICE_TOKEN or ASSISTANT_GATEWAY_DEVICE_TOKENS")
        if not self.require_tls:
            raise ValueError("Production startup requires TLS for HTTPS and WSS")


def safe_configuration_status(settings: GatewaySettings, *, asr_ready: bool, vision_ready: bool) -> dict[str, object]:
    authenticated_asr_ready = bool(settings.device_tokens) and asr_ready
    ready = authenticated_asr_ready and vision_ready
    if settings.mode == "development_mock":
        status = "development_mock"
    elif authenticated_asr_ready and not vision_ready:
        status = "asr_only"
    else:
        status = "ready" if ready else "unconfigured"
    return {
        "status": status,
        "asr_ready": asr_ready,
        "vision_ready": vision_ready,
        "authentication_configured": bool(settings.device_tokens),
        "mode": settings.mode,
    }
