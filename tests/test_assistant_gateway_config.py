from __future__ import annotations

import pytest

from mapassist.assistant_gateway.config import GatewaySettings, safe_configuration_status


def test_explicit_sensevoice_backend_and_private_model_directory(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ASSISTANT_GATEWAY_ASR_BACKEND", "sensevoice_int8")
    monkeypatch.setenv("ASSISTANT_GATEWAY_SENSEVOICE_MODEL_DIR", "/private/asr-model")
    settings = GatewaySettings.from_env()
    status = safe_configuration_status(settings, asr_ready=True, vision_ready=True)
    assert status["asr_backend"] == "sensevoice_int8"
    assert str(settings.sensevoice_model_dir) not in repr(status)
    monkeypatch.setenv("ASSISTANT_GATEWAY_ASR_BACKEND", "automatic-paid-fallback")
    with pytest.raises(ValueError, match="ASR_BACKEND"):
        GatewaySettings.from_env()


@pytest.mark.parametrize("value", ["63", "1025", "not-integer"])
def test_invalid_token_budget_is_rejected(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_MAX_TOKENS", value)
    with pytest.raises(ValueError, match="VISION_MAX_TOKENS"):
        GatewaySettings.from_env()


def test_reasoning_budget_is_configurable_without_changing_zhipu_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_MAX_TOKENS", "1024")
    settings = GatewaySettings.from_env()
    assert settings.vision_max_tokens == 1024
    assert settings.zhipu_model == "glm-4.6v-flash"


def test_explicit_model_configuration_is_safe_and_does_not_expose_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ASSISTANT_GATEWAY_GLM_MODEL", "glm-4.6v")
    monkeypatch.setenv("ZHIPU_API_KEY", "test-server-key-not-a-secret")
    monkeypatch.setenv("ASSISTANT_GATEWAY_DEVICE_TOKEN", "test-device-token")
    settings = GatewaySettings.from_env()
    status = safe_configuration_status(settings, asr_ready=True, vision_ready=True)
    assert settings.zhipu_model == status["vision_model"] == "glm-4.6v"
    assert settings.zhipu_api_key not in repr(settings)
    assert settings.zhipu_api_key not in repr(status)
    assert settings.device_tokens[0] not in repr(status)


@pytest.mark.parametrize("model", ["", "https://example.invalid", "glm-4.6v\nother=value", "glm-" + "a" * 122])
def test_invalid_model_configuration_fails_before_requests(monkeypatch: pytest.MonkeyPatch, model: str) -> None:
    monkeypatch.setenv("ASSISTANT_GATEWAY_GLM_MODEL", model)
    with pytest.raises(ValueError, match="model identifier"):
        GatewaySettings.from_env()


def test_explicit_compatible_provider_does_not_fall_back_to_old_provider(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_PROVIDER", "compatible")
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_BASE_URL", "https://example.invalid/v1")
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_MODEL", "qwen3.8-27b")
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_API_KEY", "test-paid-service-key")
    monkeypatch.setenv("ZHIPU_API_KEY", "test-unused-old-service-key")
    settings = GatewaySettings.from_env()
    status = safe_configuration_status(settings, asr_ready=True, vision_ready=True)
    assert status["vision_provider"] == "compatible"
    assert status["vision_model"] == "qwen3.8-27b"
    for key in (settings.vision_api_key, settings.zhipu_api_key):
        assert key not in repr(settings)
        assert key not in repr(status)


@pytest.mark.parametrize("url", ["http://example.invalid/v1", "https://key@example.invalid/v1", "https://example.invalid/v1?key=test-key"])
def test_provider_endpoint_rejects_insecure_or_embedded_credentials(url: str) -> None:
    with pytest.raises(ValueError, match="HTTPS URL"):
        GatewaySettings(vision_provider="compatible", vision_base_url=url, vision_model="qwen3.8-27b")
