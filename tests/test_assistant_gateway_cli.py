from __future__ import annotations

import pytest

pytest.importorskip("uvicorn")

import uvicorn

from mapassist.assistant_gateway.cli import _uvicorn_logging_config


def test_cli_logging_enables_only_the_gateway_audit_logger() -> None:
    config = _uvicorn_logging_config()
    audit = config["loggers"]["mapassist.assistant_gateway.audit"]

    assert audit == {
        "handlers": ["default"],
        "level": "INFO",
        "propagate": False,
    }
    assert "httpx" not in config["loggers"]
    assert "mapassist.assistant_gateway.audit" not in uvicorn.config.LOGGING_CONFIG["loggers"]
    assert config["handlers"]["default"] == uvicorn.config.LOGGING_CONFIG["handlers"]["default"]
