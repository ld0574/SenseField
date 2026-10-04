"""OpenAI-compatible streaming vision client for third-party providers."""

from __future__ import annotations

from typing import Any
from urllib.parse import urlsplit

from .glm import GlmVisionClient


class CompatibleVisionClient(GlmVisionClient):
    """Use the gateway's bounded streaming transport with an OpenAI-compatible API."""

    def __init__(
        self,
        api_key: str,
        *,
        base_url: str,
        model: str,
        max_tokens: int = 256,
        **kwargs: Any,
    ):
        if not isinstance(api_key, str) or not api_key.strip():
            raise ValueError("Vision API key is required; compatible vision is disabled")
        if not isinstance(base_url, str) or not base_url.strip():
            raise ValueError("A compatible vision base URL is required")
        if not isinstance(model, str) or not model.strip():
            raise ValueError("A compatible vision model is required")
        if isinstance(max_tokens, bool) or not isinstance(max_tokens, int) or not 64 <= max_tokens <= 1024:
            raise ValueError("Compatible vision max_tokens must be between 64 and 1024")
        self._max_tokens = max_tokens
        parsed = urlsplit(base_url.strip())
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("Compatible vision base URL must be a credential-free HTTPS URL")
        self._base_url = base_url.strip().rstrip("/")
        super().__init__(api_key, model=model.strip(), **kwargs)

    def _request_url(self) -> str:
        return f"{self._base_url}/chat/completions"

    def _request_body(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
    ) -> dict[str, Any]:
        body = super()._request_body(
            question=question,
            image_base64=image_base64,
            context_images=context_images,
        )
        # Optional vendor extensions are sent only for the exact documented model.
        body.pop("thinking", None)
        if self._model.lower() == "minimax-m3":
            body["thinking"] = {"type": "disabled"}
        body["max_tokens"] = self._max_tokens
        return body

    def _image_url(self, image_base64: str) -> str:
        return f"data:image/jpeg;base64,{image_base64}"
