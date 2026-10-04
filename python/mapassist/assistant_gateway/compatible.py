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
        **kwargs: Any,
    ):
        if not isinstance(api_key, str) or not api_key.strip():
            raise ValueError("Vision API key is required; compatible vision is disabled")
        if not isinstance(base_url, str) or not base_url.strip():
            raise ValueError("A compatible vision base URL is required")
        if not isinstance(model, str) or not model.strip():
            raise ValueError("A compatible vision model is required")
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

    def _request_body(self, *, question: str, image_base64: str) -> dict[str, Any]:
        body = super()._request_body(question=question, image_base64=image_base64)
        # `thinking` is a Zhipu-specific option and is not part of the compatible API contract.
        body.pop("thinking", None)
        return body

    def _image_url(self, image_base64: str) -> str:
        return f"data:image/jpeg;base64,{image_base64}"
