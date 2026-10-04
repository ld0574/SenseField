from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path

import pytest


_path = Path(__file__).resolve().parents[1] / "scripts/assistant_gateway_container_entrypoint.py"
_spec = importlib.util.spec_from_file_location("gateway_container_entrypoint", _path)
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)

_configure_path = _path.parents[1] / "deploy/assistant/configure.py"
_configure_spec = importlib.util.spec_from_file_location("configure_gateway", _configure_path)
_configure = importlib.util.module_from_spec(_configure_spec)
_configure_spec.loader.exec_module(_configure)


def test_owner_only_configuration_loads_without_logging_secrets(tmp_path, monkeypatch, capsys):
    key = "ASSISTANT_GATEWAY_VISION_API_KEY"
    monkeypatch.setenv(key, "old-test-value")
    path = tmp_path / "config.json"
    path.write_text(json.dumps({key: "private-test-value"}))
    path.chmod(0o400)
    _module.load_configuration(path)
    assert os.environ[key] == "private-test-value"
    assert capsys.readouterr() == ("", "")


def test_world_readable_configuration_is_rejected_before_loading(tmp_path, monkeypatch):
    key = "ASSISTANT_GATEWAY_VISION_API_KEY"
    monkeypatch.setenv(key, "old-test-value")
    path = tmp_path / "config.json"
    path.write_text(json.dumps({key: "private-test-value"}))
    path.chmod(0o644)
    with pytest.raises(ValueError, match="only by its owner"):
        _module.load_configuration(path)
    assert os.environ[key] == "old-test-value"


def test_configuration_cannot_override_python_startup_environment(tmp_path, monkeypatch):
    monkeypatch.delenv("PYTHONPATH", raising=False)
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"ASSISTANT_GATEWAY_PORT": "18765", "PYTHONPATH": "/untrusted"}))
    path.chmod(0o400)
    with pytest.raises(ValueError, match="unsupported entry"):
        _module.load_configuration(path)
    assert "PYTHONPATH" not in os.environ


def test_initializer_generates_private_distinct_player_codes_without_echoing_key(tmp_path, monkeypatch, capsys):
    import sys
    import stat
    values = iter(["https://vision.example/v1", ""])
    monkeypatch.setattr("builtins.input", lambda _prompt: next(values))
    monkeypatch.setattr(_configure.getpass, "getpass", lambda _prompt: "test-provider-key")
    config_path = tmp_path / "gateway.json"
    codes_path = tmp_path / "codes.txt"
    monkeypatch.setattr(sys, "argv", ["configure.py", "--output", str(config_path), "--codes-output", str(codes_path)])
    _configure.main()
    config = json.loads(config_path.read_text())
    tokens = config["ASSISTANT_GATEWAY_DEVICE_TOKENS"].split(",")
    assert len(set(tokens)) == 2 and all(len(token) >= 24 for token in tokens)
    assert config["ASSISTANT_GATEWAY_VISION_MODEL"] == "qwen/qwen3.8-27b"
    assert config["ASSISTANT_GATEWAY_ASR_BACKEND"] == "disabled"
    assert stat.S_IMODE(config_path.stat().st_mode) == 0o600
    assert stat.S_IMODE(codes_path.stat().st_mode) == 0o600
    output = capsys.readouterr().out
    assert "test-provider-key" not in output and all(token not in output for token in tokens)
