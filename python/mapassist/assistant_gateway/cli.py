"""Run the gateway with backend TLS, a local HTTPS proxy, or local mocks."""

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
    # Wall-clock timestamps align provider/ASR events with the phone's logs;
    # scalar audit fields still exclude transcripts and image contents.
    config["formatters"]["default"]["fmt"] = "%(asctime)s %(levelprefix)s %(message)s"
    config["formatters"]["default"]["datefmt"] = "%Y-%m-%dT%H:%M:%S%z"
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
    local_proxy = os.environ.get("ASSISTANT_GATEWAY_LOCAL_TLS_PROXY", "0")
    parser = argparse.ArgumentParser(
        description="Run the authenticated MapAssist assistant gateway.",
        epilog=(
            "For phone-local ASR set ASSISTANT_GATEWAY_ASR_BACKEND=disabled and install the "
            "assistant-vision-gateway extra. /v1/visual needs the selected provider's server-side "
            "credentials: ZHIPU_API_KEY for zhipu or ASSISTANT_GATEWAY_VISION_API_KEY for compatible. "
            "No provider credentials are needed for historical ASR-only operation."
        ),
    )
    parser.add_argument("--host", default=None, help="Bind address; production defaults to 0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("ASSISTANT_GATEWAY_PORT", "8765")))
    parser.add_argument("--tls-cert", default=os.environ.get("ASSISTANT_GATEWAY_TLS_CERT"))
    parser.add_argument("--tls-key", default=os.environ.get("ASSISTANT_GATEWAY_TLS_KEY"))
    parser.add_argument(
        "--behind-local-proxy", action="store_true", default=local_proxy == "1",
        help="Use the existing HTTPS proxy with an HTTP vision-only backend on 127.0.0.1.",
    )
    parser.add_argument(
        "--devtest-mock",
        action="store_true",
        help="Run deterministic mock adapters on loopback only; never use this mode for production.",
    )
    args = parser.parse_args()
    if local_proxy not in {"0", "1"}:
        parser.error("ASSISTANT_GATEWAY_LOCAL_TLS_PROXY must be 0 or 1")
    default_host = "127.0.0.1" if args.devtest_mock or args.behind_local_proxy else "0.0.0.0"
    host = args.host or ("127.0.0.1" if args.devtest_mock
                         else os.environ.get("ASSISTANT_GATEWAY_HOST", default_host))
    if args.port < 1 or args.port > 65535:
        parser.error("--port must be from 1 to 65535")
    if args.devtest_mock:
        if args.behind_local_proxy:
            parser.error("--devtest-mock cannot be combined with --behind-local-proxy")
        if not _is_loopback(host):
            parser.error("--devtest-mock can bind only to localhost or a loopback IP")
        settings = GatewaySettings.from_env(require_tls=False, mode="development_mock")
        if not settings.device_tokens:
            parser.error("Set ASSISTANT_GATEWAY_DEVICE_TOKEN before starting mock mode")
    else:
        if args.behind_local_proxy:
            if host != "127.0.0.1":
                parser.error("--behind-local-proxy can bind only to 127.0.0.1")
            if args.tls_cert or args.tls_key:
                parser.error("Local proxy mode must not set backend TLS certificate or key paths")
        elif not args.tls_cert or not args.tls_key:
            parser.error("production requires --tls-cert and --tls-key (or their environment variables)")
        try:
            settings = GatewaySettings.from_env(
                require_tls=not args.behind_local_proxy,
                local_tls_proxy=args.behind_local_proxy,
                mode="production",
            )
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
        ssl_certfile=None if args.devtest_mock or args.behind_local_proxy else args.tls_cert,
        ssl_keyfile=None if args.devtest_mock or args.behind_local_proxy else args.tls_key,
        proxy_headers=False,
        access_log=False,
        ws_max_size=128 * 1024,
        log_config=_uvicorn_logging_config(),
    )
