"""Environment-backed configuration for the self-hosted gateway."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class GatewaySettings:
    device_tokens: tuple[str, ...] = field(default_factory=tuple, repr=False)
    zhipu_api_key: str = field(default="", repr=False)
    zhipu_model: str = "glm-4.6v-flash"
    vision_provider: str = "zhipu"
    vision_base_url: str = ""
    vision_model: str = ""
    vision_api_key: str = field(default="", repr=False)
    vision_max_tokens: int = 256
    model_cache_dir: Path | None = None
    account_concurrency: int = 1
    request_timeout_seconds: float = 8.0
    require_tls: bool = True
    mode: str = "production"

    def __post_init__(self) -> None:
        if not re.fullmatch(r"glm-[a-z0-9][a-z0-9._-]{0,120}", self.zhipu_model):
            raise ValueError("ASSISTANT_GATEWAY_GLM_MODEL must be a GLM model identifier")
        if self.vision_provider not in {"zhipu", "compatible"}:
            raise ValueError("ASSISTANT_GATEWAY_VISION_PROVIDER is unsupported")
        if (isinstance(self.vision_max_tokens, bool) or not isinstance(self.vision_max_tokens, int)
                or not 64 <= self.vision_max_tokens <= 1024):
            raise ValueError("ASSISTANT_GATEWAY_VISION_MAX_TOKENS must be between 64 and 1024")
        if self.vision_provider == "compatible":
            url = urlsplit(self.vision_base_url)
            if (url.scheme != "https" or not url.hostname or url.username or url.password
                    or url.query or url.fragment):
                raise ValueError("ASSISTANT_GATEWAY_VISION_BASE_URL must be a credential-free HTTPS URL")
            if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}", self.vision_model):
                raise ValueError("ASSISTANT_GATEWAY_VISION_MODEL must be a model identifier")

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
        try:
            vision_max_tokens = int(os.environ.get("ASSISTANT_GATEWAY_VISION_MAX_TOKENS", "256"))
        except ValueError as exc:
            raise ValueError("ASSISTANT_GATEWAY_VISION_MAX_TOKENS must be an integer") from exc
        return cls(
            device_tokens=tokens,
            zhipu_api_key=os.environ.get("ZHIPU_API_KEY", "").strip(),
            zhipu_model=os.environ.get("ASSISTANT_GATEWAY_GLM_MODEL", "glm-4.6v-flash").strip(),
            vision_provider=os.environ.get("ASSISTANT_GATEWAY_VISION_PROVIDER", "zhipu").strip(),
            vision_base_url=os.environ.get("ASSISTANT_GATEWAY_VISION_BASE_URL", "").strip(),
            vision_model=os.environ.get("ASSISTANT_GATEWAY_VISION_MODEL", "").strip(),
            vision_api_key=os.environ.get("ASSISTANT_GATEWAY_VISION_API_KEY", "").strip(),
            vision_max_tokens=vision_max_tokens,
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
        "vision_provider": settings.vision_provider,
        "vision_max_tokens": settings.vision_max_tokens if settings.vision_provider == "compatible" else 256,
        "vision_model": (settings.vision_model if settings.vision_api_key else None)
            if settings.vision_provider == "compatible" else (settings.zhipu_model if settings.zhipu_api_key else None),
    }
