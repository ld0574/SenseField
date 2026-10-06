#!/usr/bin/env python3
"""Diagnose a self-deployed gateway. Paid synthetic vision runs only with --visual."""
from __future__ import annotations

import argparse
import base64
import http.client
import io
import json
from pathlib import Path
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, TextIO


class VerificationFailure(Exception):
    """A safe, user-facing diagnostic that contains no request or response data."""

    def __init__(
        self,
        stage: str,
        code: str,
        message: str,
        **details: int | str | bool,
    ) -> None:
        super().__init__(message)
        self.stage = stage
        self.code = code
        self.message = message
        self.details = details

    def as_dict(self) -> dict[str, Any]:
        error: dict[str, Any] = {
            "stage": self.stage,
            "code": self.code,
            "message": self.message,
        }
        if self.details:
            error["details"] = self.details
        return error


class RedirectRejected(Exception):
    """Raised when urllib tries to follow a redirect outside the checked origin."""


def _origin(url: str) -> tuple[str, str, int | None]:
    parsed = urllib.parse.urlsplit(url)
    return parsed.scheme.lower(), (parsed.hostname or "").lower(), parsed.port


def _validate_redirect_url(url: str, origin: tuple[str, str, int | None]) -> bool:
    try:
        parsed = urllib.parse.urlsplit(url)
        if parsed.username is not None or parsed.password is not None:
            return False
        if parsed.query or parsed.fragment or "?" in url or "#" in url:
            return False
        return _origin(url) == origin
    except ValueError:
        return False


class SameOriginRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Follow only same-origin redirects, preventing authorization header leaks."""

    def __init__(self, base_url: str) -> None:
        super().__init__()
        self._origin = _origin(base_url)

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> urllib.request.Request | None:
        if not _validate_redirect_url(newurl, self._origin):
            raise RedirectRejected("redirect target rejected")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://sf.888413.xyz")
    parser.add_argument("--ca", type=Path, help="Optional PEM CA to add to the system trust store.")
    parser.add_argument("--config", type=Path, default=Path("/run/secrets/assistant-gateway.json"))
    parser.add_argument("--health-only", action="store_true")
    parser.add_argument("--visual", action="store_true")
    return parser


def _validate_target(args: argparse.Namespace) -> str:
    raw = args.base_url
    if not isinstance(raw, str) or any(ord(char) <= 0x20 or ord(char) == 0x7F for char in raw):
        raise VerificationFailure("configuration", "invalid_base_url", "目标地址格式无效。")
    try:
        parsed = urllib.parse.urlsplit(raw)
        # Accessing .port also validates malformed ports.
        port = parsed.port
    except ValueError:
        raise VerificationFailure("configuration", "invalid_base_url", "目标地址格式无效。") from None

    if parsed.username is not None or parsed.password is not None:
        raise VerificationFailure("configuration", "url_credentials_rejected", "目标地址不能包含用户名或密码。")
    if parsed.query or parsed.fragment or "?" in raw or "#" in raw:
        raise VerificationFailure("configuration", "url_query_fragment_rejected", "目标地址不能包含查询参数或片段。")
    if parsed.path not in ("", "/"):
        raise VerificationFailure("configuration", "invalid_base_url", "目标地址只能指定服务器根路径。")
    if not parsed.hostname:
        raise VerificationFailure("configuration", "invalid_base_url", "目标地址缺少主机名。")

    scheme = parsed.scheme.lower()
    if scheme == "https":
        return f"https://{parsed.netloc}"
    if scheme != "http":
        raise VerificationFailure("configuration", "https_required", "公网检查必须使用 HTTPS。")

    if not args.health_only or args.visual:
        raise VerificationFailure(
            "configuration", "http_not_allowed", "明文 HTTP 仅允许用于 --health-only 本机健康检查。"
        )
    if parsed.hostname != "127.0.0.1":
        raise VerificationFailure(
            "configuration", "http_host_not_allowed", "明文 HTTP 仅允许目标主机为 127.0.0.1。"
        )
    if args.ca is not None:
        raise VerificationFailure("configuration", "ca_not_applicable", "本机 HTTP 健康检查不能使用 CA 文件。")
    # Canonicalize the permitted cleartext target. In particular, do not accept
    # localhost, IPv6 loopback, or a DNS name that could resolve to a remote host.
    authority = f"127.0.0.1:{port}" if port is not None else "127.0.0.1"
    return f"http://{authority}"


def _progress(stderr: TextIO, stage: str, message: str) -> None:
    print(f"[verify] {stage}: {message}", file=stderr, flush=True)


def _build_opener(context: ssl.SSLContext, base_url: str) -> urllib.request.OpenerDirector:
    handlers: list[Any] = []
    if base_url.startswith("http://"):
        # The explicitly allowed cleartext mode is loopback-only and must not
        # be sent to a proxy selected by ambient HTTP_PROXY variables.
        handlers.append(urllib.request.ProxyHandler({}))
    handlers.extend((
        urllib.request.HTTPSHandler(context=context),
        SameOriginRedirectHandler(base_url),
    ))
    return urllib.request.build_opener(*handlers)


def _read_json(response: Any, stage: str, maximum_bytes: int) -> Any:
    try:
        raw = response.read(maximum_bytes + 1)
    except Exception as error:
        raise _transport_failure(error, stage) from None
    if len(raw) > maximum_bytes:
        raise VerificationFailure(stage, "response_too_large", "服务响应超过允许大小。")
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise VerificationFailure(stage, "invalid_json_response", "服务返回的 JSON 无法解析。") from None


def _transport_failure(error: BaseException, stage: str) -> VerificationFailure:
    if isinstance(error, RedirectRejected):
        return VerificationFailure(stage, "unsafe_redirect", "服务尝试跳转到不同主机、协议或带参数的地址，已拒绝。")
    if isinstance(error, urllib.error.HTTPError):
        return VerificationFailure(
            stage,
            "http_error",
            f"服务返回 HTTP {error.code}。",
            http_status=error.code,
        )

    reason: BaseException = error
    if isinstance(error, urllib.error.URLError):
        nested = error.reason
        if isinstance(nested, BaseException):
            reason = nested

    if isinstance(reason, ssl.SSLCertVerificationError):
        verify_message = (getattr(reason, "verify_message", "") or "").lower()
        if any(term in verify_message for term in ("hostname", "subject name", "ip address mismatch", "doesn't match", "not valid for")):
            return VerificationFailure(stage, "tls_hostname_mismatch", "TLS 证书名称与目标地址不匹配。")
        return VerificationFailure(stage, "tls_trust_error", "TLS 证书信任链验证失败；请检查系统 CA 和服务器证书链。")
    if isinstance(reason, ssl.SSLError):
        return VerificationFailure(stage, "tls_error", "TLS 握手失败；请检查服务端 TLS 配置。")
    if isinstance(reason, socket.gaierror):
        return VerificationFailure(stage, "dns_error", "DNS 解析失败；请检查目标域名和 DNS 配置。")
    if isinstance(reason, (TimeoutError, socket.timeout, http.client.RemoteDisconnected)):
        return VerificationFailure(stage, "timeout", "连接或请求超时；请检查网络和服务状态。")
    if isinstance(reason, ConnectionRefusedError):
        return VerificationFailure(stage, "connection_refused", "连接被拒绝；请检查服务是否监听目标端口。")
    if isinstance(reason, (ConnectionError, OSError)):
        return VerificationFailure(stage, "connection_error", "网络连接失败；请检查路由、防火墙和服务状态。")
    return VerificationFailure(stage, "request_error", "请求未能完成；请检查目标服务和网络配置。")


def _request_json(
    opener: Any,
    url: str,
    stage: str,
    *,
    data: bytes | None = None,
    headers: dict[str, str] | None = None,
    expected_error_statuses: frozenset[int] = frozenset(),
    timeout: float = 4,
    maximum_bytes: int = 64 * 1024,
) -> tuple[int, Any | None, int]:
    request = urllib.request.Request(url, data=data, headers=headers or {})
    started = time.monotonic()
    try:
        try:
            response = opener.open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            if error.code not in expected_error_statuses:
                raise
            error.close()
            return error.code, None, round((time.monotonic() - started) * 1000)
        with response:
            status = response.getcode()
            result = _read_json(response, stage, maximum_bytes)
            return status, result, round((time.monotonic() - started) * 1000)
    except VerificationFailure:
        raise
    except Exception as error:
        # Do not use str(error): urllib exception text can include request URLs,
        # and response/request objects may contain device tokens or model data.
        raise _transport_failure(error, stage) from None


def _expect_status(stage: str, status: int, expected: int) -> None:
    if status != expected:
        raise VerificationFailure(
            stage,
            "unexpected_status",
            f"预期 HTTP {expected}，实际收到 HTTP {status}。",
            expected_status=expected,
            actual_status=status,
        )


def _load_device_token(path: Path) -> str:
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise VerificationFailure("config", "config_missing", "设备令牌配置文件不存在。") from None
    except PermissionError:
        raise VerificationFailure("config", "config_permission_denied", "没有权限读取设备令牌配置文件。") from None
    except OSError:
        raise VerificationFailure("config", "config_unreadable", "无法读取设备令牌配置文件。") from None
    try:
        config = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise VerificationFailure("config", "config_invalid", "设备令牌配置文件不是有效 JSON。") from None
    if not isinstance(config, dict):
        raise VerificationFailure("config", "config_invalid", "设备令牌配置文件格式不正确。")
    tokens = config.get("ASSISTANT_GATEWAY_DEVICE_TOKENS")
    if not isinstance(tokens, str) or not tokens.split(",", 1)[0].strip():
        raise VerificationFailure("config", "device_token_missing", "配置中没有可用的设备令牌。")
    return tokens.split(",", 1)[0].strip()


def _synthetic_visual_payload() -> bytes:
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        raise VerificationFailure("visual", "pillow_missing", "缺少 Pillow，无法生成合成视觉请求。") from None
    picture = Image.new("RGB", (960, 320), "white")
    ImageDraw.Draw(picture).text(
        (40, 120),
        "SYNTHETIC GATEWAY TEST",
        fill="black",
        font=ImageFont.load_default(size=44),
    )
    stream = io.BytesIO()
    picture.save(stream, format="JPEG", quality=75)
    payload = {
        "session_id": str(uuid.uuid4()),
        "generation": 1,
        "turn_id": "synthetic-test",
        "frame_id": "synthetic-frame",
        "question": "请只读出图中英文文字。",
        "frame_age_ms": 0,
        "proactive": False,
        "image_base64": base64.b64encode(stream.getvalue()).decode(),
    }
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def execute(
    args: argparse.Namespace,
    *,
    opener: Any | None = None,
    context_factory: Any = ssl.create_default_context,
    stderr: TextIO | None = None,
) -> tuple[int, dict[str, Any]]:
    """Run checks and return an exit code plus a safe, structured report."""
    stderr = stderr or sys.stderr
    results: dict[str, Any] = {}
    tls_verified: bool | None = None
    stage = "configuration"
    try:
        _progress(stderr, stage, "验证目标地址和传输限制")
        base = _validate_target(args)
        is_https = base.startswith("https://")
        try:
            context = context_factory()
            if args.ca is not None:
                context.load_verify_locations(cafile=str(args.ca))
        except FileNotFoundError:
            code = "ca_file_missing" if args.ca else "tls_configuration_error"
            message = "CA 文件不存在。" if args.ca else "无法初始化系统 CA 信任配置。"
            raise VerificationFailure(stage, code, message) from None
        except PermissionError:
            raise VerificationFailure(stage, "ca_file_permission_denied", "没有权限读取 CA 文件。") from None
        except (OSError, ssl.SSLError, ValueError):
            raise VerificationFailure(stage, "tls_configuration_error", "无法初始化 TLS 证书验证。") from None
        if opener is None:
            opener = _build_opener(context, base)

        stage = "health"
        _progress(stderr, stage, "请求 /health")
        status, health, _ = _request_json(opener, base + "/health", stage)
        _expect_status(stage, status, 200)
        if not isinstance(health, dict) or health.get("status") != "vision_only" or health.get("asr_ready") is not False:
            raise VerificationFailure(stage, "health_status", "健康检查状态不符合 vision_only 且 ASR 关闭的预期。")
        results["health"] = {"status": "vision_only", "asr_ready": False}
        tls_verified = is_https
        _progress(stderr, stage, "健康状态符合预期")

        if args.health_only:
            report = {"ok": True, "tls_verified": tls_verified, **results, "mode": "health_only"}
            return 0, report

        stage = "config"
        _progress(stderr, stage, "读取设备令牌配置")
        public = health.get("public_vision_access") is True
        token = "" if public else _load_device_token(args.config)
        results["config"] = "public_vision_no_code" if public else "device_token_loaded"
        installation = str(uuid.uuid4())

        visual_url = base + "/v1/visual"

        def post(
            body: bytes,
            *,
            authenticated: bool,
            expected_error: int | None = None,
            declared_content_length: int | None = None,
        ) -> tuple[int, Any | None, int]:
            headers = {"Content-Type": "application/json"}
            if declared_content_length is not None:
                headers["Content-Length"] = str(declared_content_length)
            if authenticated:
                if public:
                    headers["X-SenseField-Installation"] = installation
                else:
                    headers["Authorization"] = "Bearer " + token
            allowed = frozenset({expected_error}) if expected_error is not None else frozenset()
            return _request_json(
                opener,
                visual_url,
                stage,
                data=body,
                headers=headers,
                expected_error_statuses=allowed,
                timeout=12,
                maximum_bytes=1024 * 1024,
            )

        stage = "unauthenticated"
        _progress(stderr, stage, "确认未认证请求返回 HTTP 401")
        status, _, _ = post(b"{}", authenticated=False, expected_error=401)
        _expect_status(stage, status, 401)
        results["unauthenticated_status"] = status
        _progress(stderr, stage, "401 已确认")

        stage = "schema"
        _progress(stderr, stage, "确认无效请求体返回 HTTP 422")
        status, _, _ = post(b"{}", authenticated=True, expected_error=422)
        _expect_status(stage, status, 422)
        results["invalid_schema_status"] = status
        _progress(stderr, stage, "422 已确认")

        stage = "body_limit"
        _progress(stderr, stage, "确认超限请求体返回 HTTP 413")
        # Advertise a body beyond the gateway limit without sending its bytes.
        # Uvicorn can reject from Content-Length alone; sending the entire body
        # races that early 413 with a large socket write and can raise BrokenPipe.
        oversized_bytes = 2300 * 1024
        status, _, _ = post(
            b"",
            authenticated=True,
            expected_error=413,
            declared_content_length=oversized_bytes,
        )
        _expect_status(stage, status, 413)
        results["oversized_body_status"] = status
        _progress(stderr, stage, "413 已确认")

        if args.visual:
            stage = "visual"
            _progress(stderr, stage, "发送付费合成视觉请求")
            payload = _synthetic_visual_payload()
            status, visual_result, elapsed = post(payload, authenticated=True)
            _expect_status(stage, status, 200)
            if not isinstance(visual_result, dict) or "SYNTHETIC GATEWAY TEST" not in str(
                visual_result.get("answer", "")
            ).upper():
                raise VerificationFailure(stage, "visual_result_mismatch", "合成视觉响应未读出预期文字。")
            results.update(synthetic_visual_passed=True, synthetic_roundtrip_ms=elapsed)
            _progress(stderr, stage, "合成视觉请求通过")

        return 0, {"ok": True, "tls_verified": tls_verified, **results}
    except VerificationFailure as error:
        _progress(stderr, error.stage, f"失败（{error.code}）")
        report: dict[str, Any] = {
            "ok": False,
            "tls_verified": tls_verified,
            "error": error.as_dict(),
            "completed": results,
        }
        return 1, report
    except Exception:
        # Keep an unexpected runtime failure useful without echoing exception
        # text, which can contain request details or credentials.
        failure = VerificationFailure(stage, "internal_error", "检查过程中发生内部错误；请检查本地运行环境。")
        _progress(stderr, stage, f"失败（{failure.code}）")
        return 1, {
            "ok": False,
            "tls_verified": tls_verified,
            "error": failure.as_dict(),
            "completed": results,
        }


def main(argv: list[str] | None = None) -> int:
    args = _make_parser().parse_args(argv)
    exit_code, report = execute(args)
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
