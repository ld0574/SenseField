from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import socket
import ssl
import urllib.error
import urllib.request

import pytest


_VERIFY_PATH = Path(__file__).resolve().parents[1] / "deploy" / "assistant" / "verify.py"
_SPEC = importlib.util.spec_from_file_location("assistant_deploy_verify", _VERIFY_PATH)
assert _SPEC is not None and _SPEC.loader is not None
verify = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(verify)


class FakeResponse:
    def __init__(self, body: bytes, status: int = 200) -> None:
        self._body = body
        self._status = status

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def getcode(self) -> int:
        return self._status

    def read(self, size: int = -1) -> bytes:
        return self._body[:size]


class FakeOpener:
    def __init__(self, responses: list[object]) -> None:
        self.responses = list(responses)
        self.requests: list[tuple[urllib.request.Request, float]] = []

    def open(self, request: urllib.request.Request, timeout: float) -> FakeResponse:
        self.requests.append((request, timeout))
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        assert isinstance(item, FakeResponse)
        return item


def _health() -> FakeResponse:
    return FakeResponse(b'{"status":"vision_only","asr_ready":false}')


def _http_error(status: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError(
        "https://gateway.example.invalid/v1/visual",
        status,
        "expected test response",
        {},
        None,
    )


def _args(*argv: str):
    return verify._make_parser().parse_args(list(argv))


def test_default_checks_health_then_401_422_413_without_visual_call(tmp_path: Path) -> None:
    token = "DEVICE_TOKEN_SHOULD_NEVER_BE_PRINTED"
    config = tmp_path / "gateway.json"
    config.write_text(json.dumps({"ASSISTANT_GATEWAY_DEVICE_TOKENS": token}), encoding="utf-8")
    opener = FakeOpener([_health(), _http_error(401), _http_error(422), _http_error(413)])
    stderr = io.StringIO()

    exit_code, report = verify.execute(
        _args("--config", str(config)),
        opener=opener,
        context_factory=lambda **_kwargs: object(),
        stderr=stderr,
    )

    assert exit_code == 0
    assert report == {
        "ok": True,
        "tls_verified": True,
        "health": {"status": "vision_only", "asr_ready": False},
        "config": "device_token_loaded",
        "unauthenticated_status": 401,
        "invalid_schema_status": 422,
        "oversized_body_status": 413,
    }
    assert [request.full_url for request, _ in opener.requests] == [
        "https://sf.888413.xyz/health",
        "https://sf.888413.xyz/v1/visual",
        "https://sf.888413.xyz/v1/visual",
        "https://sf.888413.xyz/v1/visual",
    ]
    assert opener.requests[1][0].get_header("Authorization") is None
    assert opener.requests[2][0].get_header("Authorization") == f"Bearer {token}"
    assert opener.requests[3][0].get_header("Authorization") == f"Bearer {token}"
    oversized_request = opener.requests[3][0]
    assert oversized_request.data == b""
    assert oversized_request.get_header("Content-length") == str(2300 * 1024)
    assert "[verify] health:" in stderr.getvalue()
    assert token not in stderr.getvalue()
    assert token not in json.dumps(report)


def test_loopback_http_health_only_skips_config_and_tls(tmp_path: Path) -> None:
    opener = FakeOpener([_health()])
    exit_code, report = verify.execute(
        _args(
            "--health-only",
            "--base-url",
            "http://127.0.0.1:18765",
            "--config",
            str(tmp_path / "must-not-be-read.json"),
        ),
        opener=opener,
        context_factory=lambda **_kwargs: object(),
        stderr=io.StringIO(),
    )

    assert exit_code == 0
    assert report["mode"] == "health_only"
    assert report["tls_verified"] is False
    assert report["health"]["status"] == "vision_only"
    assert [request.full_url for request, _ in opener.requests] == ["http://127.0.0.1:18765/health"]


def test_default_https_context_keeps_system_certificate_and_hostname_verification() -> None:
    contexts: list[ssl.SSLContext] = []

    def make_system_context() -> ssl.SSLContext:
        context = ssl.create_default_context()
        contexts.append(context)
        return context

    exit_code, report = verify.execute(
        _args("--health-only"),
        opener=FakeOpener([_health()]),
        context_factory=make_system_context,
        stderr=io.StringIO(),
    )

    assert exit_code == 0
    assert report["tls_verified"] is True
    assert contexts[0].verify_mode == ssl.CERT_REQUIRED
    assert contexts[0].check_hostname is True


@pytest.mark.parametrize(
    ("argv", "code"),
    [
        (("--base-url", "http://localhost:18765", "--health-only"), "http_host_not_allowed"),
        (("--base-url", "http://127.0.0.2:18765", "--health-only"), "http_host_not_allowed"),
        (("--base-url", "http://example.invalid:18765", "--health-only"), "http_host_not_allowed"),
        (("--base-url", "http://127.0.0.1:18765"), "http_not_allowed"),
        (("--base-url", "http://127.0.0.1:18765", "--health-only", "--visual"), "http_not_allowed"),
        (("--base-url", "https://device:secret@gateway.example.invalid"), "url_credentials_rejected"),
        (("--base-url", "https://gateway.example.invalid/?token=secret"), "url_query_fragment_rejected"),
        (("--base-url", "https://gateway.example.invalid/#secret"), "url_query_fragment_rejected"),
    ],
)
def test_unsafe_base_urls_fail_before_any_request(argv: tuple[str, ...], code: str) -> None:
    opener = FakeOpener([])
    exit_code, report = verify.execute(
        _args(*argv),
        opener=opener,
        context_factory=lambda **_kwargs: object(),
        stderr=io.StringIO(),
    )

    assert exit_code == 1
    assert report["error"]["code"] == code
    assert opener.requests == []
    assert "secret" not in json.dumps(report)


def test_redirect_handler_rejects_cross_host_and_cross_scheme() -> None:
    handler = verify.SameOriginRedirectHandler("https://gateway.example.invalid")
    request = urllib.request.Request("https://gateway.example.invalid/v1/visual")

    for destination in (
        "https://other.example.invalid/v1/visual",
        "http://gateway.example.invalid/v1/visual",
        "https://user:pass@gateway.example.invalid/v1/visual",
        "https://gateway.example.invalid/v1/visual?token=secret",
    ):
        with pytest.raises(verify.RedirectRejected):
            handler.redirect_request(request, None, 307, "temporary", {}, destination)


@pytest.mark.parametrize(
    ("reason", "code"),
    [
        (socket.gaierror("private dns detail"), "dns_error"),
        (ConnectionRefusedError("private connection detail"), "connection_refused"),
        (TimeoutError("private timeout detail"), "timeout"),
        (
            ssl.SSLCertVerificationError(1, "certificate verify failed"),
            "tls_trust_error",
        ),
    ],
)
def test_transport_diagnostics_are_classified_without_echoing_exception_text(
    reason: BaseException, code: str
) -> None:
    # The fake exception includes sensitive-looking text to ensure it is not
    # copied into either the stage log or the machine-readable report.
    opener = FakeOpener([urllib.error.URLError(reason)])
    stderr = io.StringIO()
    exit_code, report = verify.execute(
        _args("--health-only"),
        opener=opener,
        context_factory=lambda **_kwargs: object(),
        stderr=stderr,
    )

    assert exit_code == 1
    assert report["error"]["code"] == code
    if code == "tls_trust_error":
        assert report["tls_verified"] is None
    assert "private" not in stderr.getvalue()
    assert "private" not in json.dumps(report)


def test_certificate_hostname_mismatch_has_a_separate_diagnostic() -> None:
    error = ssl.SSLCertVerificationError(62, "certificate verify failed")
    error.verify_message = "Hostname mismatch, certificate is not valid for target"
    failure = verify._transport_failure(error, "health")

    assert failure.code == "tls_hostname_mismatch"


def test_missing_config_is_distinguished_after_health_passes(tmp_path: Path) -> None:
    exit_code, report = verify.execute(
        _args("--config", str(tmp_path / "missing.json")),
        opener=FakeOpener([_health()]),
        context_factory=lambda **_kwargs: object(),
        stderr=io.StringIO(),
    )

    assert exit_code == 1
    assert report["error"]["stage"] == "config"
    assert report["error"]["code"] == "config_missing"
    assert report["completed"]["health"]["status"] == "vision_only"


def test_unreadable_config_is_distinguished_from_missing_config(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = tmp_path / "secret-config.json"
    original_read_text = Path.read_text

    def denied(path: Path, *args: object, **kwargs: object) -> str:
        if path == config:
            raise PermissionError("private permission detail")
        return original_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", denied)
    exit_code, report = verify.execute(
        _args("--config", str(config)),
        opener=FakeOpener([_health()]),
        context_factory=lambda: ssl.create_default_context(),
        stderr=io.StringIO(),
    )

    assert exit_code == 1
    assert report["error"]["code"] == "config_permission_denied"
    assert "private" not in json.dumps(report)


def test_health_status_mismatch_is_not_reported_as_a_network_error() -> None:
    exit_code, report = verify.execute(
        _args("--health-only"),
        opener=FakeOpener([FakeResponse(b'{"status":"degraded","asr_ready":false}')]),
        context_factory=lambda **_kwargs: object(),
        stderr=io.StringIO(),
    )

    assert exit_code == 1
    assert report["error"]["stage"] == "health"
    assert report["error"]["code"] == "health_status"


def test_unexpected_http_status_is_classified_without_reading_error_body() -> None:
    error = urllib.error.HTTPError(
        "https://gateway.example.invalid/health",
        503,
        "service unavailable",
        {},
        io.BytesIO(b"secret model key echoed by upstream"),
    )
    stderr = io.StringIO()
    exit_code, report = verify.execute(
        _args("--health-only"),
        opener=FakeOpener([error]),
        context_factory=lambda: ssl.create_default_context(),
        stderr=stderr,
    )

    assert exit_code == 1
    assert report["error"]["code"] == "http_error"
    assert report["error"]["details"]["http_status"] == 503
    assert "model key" not in stderr.getvalue()
    assert "model key" not in json.dumps(report)


def test_cli_writes_only_structured_json_to_stdout(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setattr(verify, "execute", lambda _args: (0, {"ok": True, "checks": 4}))

    assert verify.main([]) == 0
    captured = capsys.readouterr()
    assert json.loads(captured.out) == {"ok": True, "checks": 4}
    assert captured.err == ""


def test_public_health_uses_installation_header_without_reading_private_config(tmp_path):
    health = FakeResponse(b'{"status":"vision_only","asr_ready":false,"public_vision_access":true}')
    opener = FakeOpener([health, _http_error(401), _http_error(422), _http_error(413)])
    code, report = verify.execute(_args("--config", str(tmp_path / "does-not-exist")),
        opener=opener, context_factory=lambda **kwargs: object(), stderr=io.StringIO())
    assert code == 0
    assert report["config"] == "public_vision_no_code"
    assert opener.requests[1][0].get_header("X-sensefield-installation") is None
    assert opener.requests[2][0].get_header("X-sensefield-installation") is not None
    assert all(request.get_header("Authorization") is None for request, _ in opener.requests)
