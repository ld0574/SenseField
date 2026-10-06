from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys

import pytest

from mapassist.assistant_gateway import cli
from mapassist.assistant_gateway.config import GatewaySettings


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("assistant_direct_start", ROOT / "start.py")
start = importlib.util.module_from_spec(spec)
spec.loader.exec_module(start)


@pytest.fixture
def clean_environment(monkeypatch):
    for key in list(os.environ):
        if key.startswith("ASSISTANT_GATEWAY_") or key == "ZHIPU_API_KEY":
            monkeypatch.delenv(key)
    monkeypatch.setattr(sys, "argv", ["start.py"])
    monkeypatch.setattr(sys, "path", list(sys.path))


def old_configuration(tmp_path):
    config = {
        "ASSISTANT_GATEWAY_VISION_PROVIDER": "compatible",
        "ASSISTANT_GATEWAY_VISION_BASE_URL": "https://vision.example.invalid/v1",
        "ASSISTANT_GATEWAY_VISION_MODEL": "qwen/qwen3.8-27b",
        "ASSISTANT_GATEWAY_VISION_API_KEY": "test-preserved-provider-key",
        "ASSISTANT_GATEWAY_DEVICE_TOKENS": "test-existing-device-code",
        "ASSISTANT_GATEWAY_TLS_CERT": "/deleted/backend.crt",
        "ASSISTANT_GATEWAY_TLS_KEY": "/deleted/backend.key",
        "ASSISTANT_GATEWAY_HOST": "0.0.0.0",
        "ASSISTANT_GATEWAY_ASR_BACKEND": "paraformer_streaming",
    }
    path = tmp_path / "gateway.json"
    path.write_text(json.dumps(config, indent=3))
    path.chmod(0o400)
    return path, config


def test_direct_start_uses_original_credentials_and_only_process_local_transport_overrides(
        tmp_path, monkeypatch, clean_environment):
    path, original = old_configuration(tmp_path)
    before = path.read_bytes()
    captured = {}
    def serve():
        settings = GatewaySettings.from_env(require_tls=False, local_tls_proxy=True)
        settings.validate_production()
        captured.update(settings=settings, argv=list(sys.argv), env=dict(os.environ))
    monkeypatch.setattr(cli, "main", serve)
    start.main(["--config", str(path), "--port", "18765"])
    assert captured["settings"].vision_api_key == original["ASSISTANT_GATEWAY_VISION_API_KEY"]
    assert captured["settings"].device_tokens == (original["ASSISTANT_GATEWAY_DEVICE_TOKENS"],)
    assert captured["settings"].asr_backend == "disabled"
    assert captured["env"]["ASSISTANT_GATEWAY_HOST"] == "127.0.0.1"
    assert "ASSISTANT_GATEWAY_TLS_CERT" not in captured["env"]
    assert "ASSISTANT_GATEWAY_TLS_KEY" not in captured["env"]
    assert captured["argv"][-2:] == ["--port", "18765"]
    assert path.read_bytes() == before
    assert list(tmp_path.iterdir()) == [path]


@pytest.mark.parametrize("args", [["--port", "0"], ["--port", "65536"], ["--host", "0.0.0.0"]])
def test_direct_start_cannot_expose_plaintext_on_an_external_interface(args, clean_environment):
    with pytest.raises(SystemExit) as error:
        start.main(args)
    assert error.value.code == 2


@pytest.mark.parametrize("condition", ["missing", "readable_by_others", "invalid"])
def test_invalid_private_configuration_fails_without_starting_or_echoing_its_contents(
        tmp_path, monkeypatch, clean_environment, capsys, condition):
    path, original = old_configuration(tmp_path)
    if condition == "missing":
        path.unlink()
    elif condition == "readable_by_others":
        path.chmod(0o644)
    else:
        path.chmod(0o600)
        path.write_text('{"private-test-value":')
        path.chmod(0o400)
    monkeypatch.setattr(cli, "main", lambda: pytest.fail("Must not start with invalid configuration"))
    with pytest.raises(SystemExit) as error:
        start.main(["--config", str(path)])
    assert error.value.code == 2
    output = capsys.readouterr()
    assert original["ASSISTANT_GATEWAY_VISION_API_KEY"] not in output.err
    assert original["ASSISTANT_GATEWAY_DEVICE_TOKENS"] not in output.err
