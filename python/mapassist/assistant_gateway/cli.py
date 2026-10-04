"""Run the self-hosted gateway with TLS or an explicit local mock mode."""

from __future__ import annotations

import argparse
from copy import deepcopy
import ipaddress
import os
import sys

from .config import GatewaySettings


def _is_loopback(host: str) -> bool:
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _uvicorn_logging_config() -> dict[str, object]:
    """Keep scalar gateway diagnostics visible without raising other logger levels."""
    import uvicorn

    config = deepcopy(uvicorn.config.LOGGING_CONFIG)
    loggers = config.setdefault("loggers", {})
    if not isinstance(loggers, dict):
        raise TypeError("uvicorn logging config has an unexpected shape")
    loggers["mapassist.assistant_gateway.audit"] = {
        "handlers": ["default"],
        "level": "INFO",
        "propagate": False,
    }
    return config


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the authenticated MapAssist assistant gateway.",
        epilog=(
            "ZHIPU_API_KEY is optional for authenticated ASR-only mode. It is required only for "
            "/v1/visual; without it, health reports asr_only and visual requests return 503."
        ),
    )
    parser.add_argument("--host", default=None, help="Bind address; production defaults to 0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("ASSISTANT_GATEWAY_PORT", "8765")))
    parser.add_argument("--tls-cert", default=os.environ.get("ASSISTANT_GATEWAY_TLS_CERT"))
    parser.add_argument("--tls-key", default=os.environ.get("ASSISTANT_GATEWAY_TLS_KEY"))
    parser.add_argument(
        "--devtest-mock",
        action="store_true",
        help="Run deterministic mock adapters on loopback only; never use this mode for production.",
    )
    args = parser.parse_args()
    host = args.host or ("127.0.0.1" if args.devtest_mock else os.environ.get("ASSISTANT_GATEWAY_HOST", "0.0.0.0"))
    if args.port < 1 or args.port > 65535:
        parser.error("--port must be from 1 to 65535")
    if args.devtest_mock:
        if not _is_loopback(host):
            parser.error("--devtest-mock can bind only to localhost or a loopback IP")
        settings = GatewaySettings.from_env(require_tls=False, mode="development_mock")
        if not settings.device_tokens:
            parser.error("Set ASSISTANT_GATEWAY_DEVICE_TOKEN before starting mock mode")
    else:
        if not args.tls_cert or not args.tls_key:
            parser.error("production requires --tls-cert and --tls-key (or their environment variables)")
        settings = GatewaySettings.from_env(require_tls=True, mode="production")
        try:
            settings.validate_production()
        except ValueError as exc:
            parser.error(str(exc))

    try:
        import uvicorn
        from .app import create_app
    except ImportError as exc:
        print("Install the optional assistant-gateway dependencies first.", file=sys.stderr)
        raise SystemExit(2) from exc

    uvicorn.run(
        create_app(settings),
        host=host,
        port=args.port,
        ssl_certfile=None if args.devtest_mock else args.tls_cert,
        ssl_keyfile=None if args.devtest_mock else args.tls_key,
        proxy_headers=False,
        access_log=False,
        ws_max_size=128 * 1024,
        log_config=_uvicorn_logging_config(),
    )
