#!/usr/bin/env python3
"""Run the production assistant gateway with a scripted local vision adapter for Android tests."""

from __future__ import annotations

import argparse
import asyncio
import hmac
import json
from pathlib import Path
import secrets
import signal
import subprocess
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

DEFAULT_OUTPUT = ROOT / "output/assistant/0.4.1/local-asr-vision"
CONFIG_NAME = "assistant-local-asr-vision-test.json"
TEST_CA_NAME = "assistant_local_asr_test_ca"
TEST_NETWORK_CONFIG_NAME = "assistant_local_asr_test_network_security_config"
MAX_CAPTURE_BODY = 4 * 1024 * 1024


class FixtureState:
    def __init__(self) -> None:
        self.visual_posts: list[dict[str, Any]] = []
        self.audio_requests: list[dict[str, str]] = []
        self.api_paths: list[dict[str, str]] = []
        self.provider_started = 0
        self.provider_completed = 0
        self.provider_cancelled = 0

    def reset(self) -> None:
        self.visual_posts.clear()
        self.audio_requests.clear()
        self.api_paths.clear()
        self.provider_started = 0
        self.provider_completed = 0
        self.provider_cancelled = 0

    def snapshot(self) -> dict[str, Any]:
        return {
            "visual_posts": list(self.visual_posts),
            "audio_requests": list(self.audio_requests),
            "api_paths": list(self.api_paths),
            "provider_started": self.provider_started,
            "provider_completed": self.provider_completed,
            "provider_cancelled": self.provider_cancelled,
        }


class ScriptedVisionClient:
    """Small deterministic adapter; no external provider or model is contacted."""

    def __init__(self, state: FixtureState, first_delay_ms: int) -> None:
        self.state = state
        self.first_delay_ms = first_delay_ms

    async def complete(
        self,
        *,
        question: str,
        image_base64: str,
        context_images: tuple[str, ...] = (),
    ) -> str:
        del image_base64, context_images
        self.state.provider_started += 1
        call_number = self.state.provider_started
        if call_number == 1 and self.first_delay_ms:
            try:
                await asyncio.sleep(self.first_delay_ms / 1000)
            except asyncio.CancelledError:
                self.state.provider_cancelled += 1
                raise
        self.state.provider_completed += 1
        if call_number == 1:
            answer = "过期建议一。过期建议二。"
        elif "装备" in question or "出装" in question:
            answer = "合成出装建议一。合成出装建议二。"
        elif "选" in question or "英雄" in question:
            answer = "合成选人建议一。合成选人建议二。"
        elif "对战" in question or "策略" in question or "打法" in question:
            answer = "合成策略建议一。合成策略建议二。"
        else:
            answer = "合成读屏建议一。合成读屏建议二。"
        return json.dumps(
            {"kind": "ui_text", "answer": answer, "uncertain": False},
            ensure_ascii=False,
            separators=(",", ":"),
        )

    async def close(self) -> None:
        return None


class FixtureMiddleware:
    """Record only bounded, non-secret request metadata around the real gateway app."""

    def __init__(self, app: Any, state: FixtureState, token: str) -> None:
        self.app = app
        self.state = state
        self.token = token

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        scope_type = scope.get("type")
        path = str(scope.get("path", ""))
        method = str(scope.get("method", ""))
        if scope_type == "websocket" and path == "/v1/audio":
            self.state.audio_requests.append({"type": "websocket", "path": path})
            self.state.api_paths.append({"type": "websocket", "path": path})
            await self.app(scope, receive, send)
            return
        if scope_type != "http":
            await self.app(scope, receive, send)
            return
        if path == "/__fixture__/evidence" and method == "GET":
            await self._evidence(scope, receive, send)
            return
        if path == "/__fixture__/reset" and method == "POST":
            if not self._authorized(scope):
                await self._json(send, 401, {"error": "unauthorized"})
            else:
                self.state.reset()
                await self._json(send, 200, {"ok": True})
            return

        if path.startswith("/v1/"):
            self.state.api_paths.append({"type": "http", "path": path, "method": method})
            if path == "/v1/audio":
                self.state.audio_requests.append({"type": "http", "path": path, "method": method})
        if scope_type == "http" and path == "/v1/visual" and method == "POST":
            body = await self._read_body(receive)
            self._record_visual(scope, body)
            delivered = False

            async def replay_receive() -> dict[str, Any]:
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": body, "more_body": False}
                return await receive()

            await self.app(scope, replay_receive, send)
            return
        await self.app(scope, receive, send)

    def _authorized(self, scope: dict[str, Any]) -> bool:
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        received = headers.get(b"authorization", b"").decode("latin-1")
        return hmac.compare_digest(received, "Bearer " + self.token)

    async def _read_body(self, receive: Any) -> bytes:
        chunks: list[bytes] = []
        total = 0
        while True:
            message = await receive()
            if message.get("type") == "http.disconnect":
                return b""
            if message.get("type") != "http.request":
                continue
            block = message.get("body", b"")
            total += len(block)
            if total > MAX_CAPTURE_BODY:
                return b""
            chunks.append(block)
            if not message.get("more_body", False):
                return b"".join(chunks)

    def _record_visual(self, scope: dict[str, Any], body: bytes) -> None:
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        try:
            payload = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            payload = {}
        context = payload.get("context_frames", []) if isinstance(payload, dict) else []
        if not isinstance(context, list):
            context = []
        primary_image = payload.get("image_base64") if isinstance(payload, dict) else None
        record = {
            "method": str(scope.get("method", "")),
            "path": str(scope.get("path", "")),
            "session_id": payload.get("session_id") if isinstance(payload, dict) else None,
            "generation": payload.get("generation") if isinstance(payload, dict) else None,
            "turn_id": payload.get("turn_id") if isinstance(payload, dict) else None,
            "frame_id": payload.get("frame_id") if isinstance(payload, dict) else None,
            "question": payload.get("question") if isinstance(payload, dict) else None,
            "image_count": (1 if isinstance(primary_image, str) and primary_image else 0) + len(context),
            "proactive": payload.get("proactive") if isinstance(payload, dict) else None,
            "authorized": hmac.compare_digest(
                headers.get(b"authorization", b"").decode("latin-1"), "Bearer " + self.token
            ),
        }
        self.state.visual_posts.append(record)

    async def _evidence(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        del receive
        if not self._authorized(scope):
            await self._json(send, 401, {"error": "unauthorized"})
            return
        await self._json(send, 200, self.state.snapshot())

    @staticmethod
    async def _json(send: Any, status: int, value: dict[str, Any]) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        await send({"type": "http.response.start", "status": status, "headers": [
            (b"content-type", b"application/json; charset=utf-8"),
            (b"content-length", str(len(body)).encode("ascii")),
            (b"cache-control", b"no-store"),
        ]})
        await send({"type": "http.response.body", "body": body})


def _run_openssl(arguments: list[str]) -> None:
    try:
        subprocess.run(
            ["openssl", *arguments],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("OpenSSL is required to create a temporary test CA.") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError("OpenSSL could not create the temporary test certificate.") from exc


def _create_tls_material(output_dir: Path) -> tuple[Path, Path, Path]:
    ca_key = output_dir / "fixture-ca.key"
    ca_cert = output_dir / "fixture-ca.pem"
    server_key = output_dir / "fixture-server.key"
    server_csr = output_dir / "fixture-server.csr"
    server_cert = output_dir / "fixture-server.pem"
    fullchain = output_dir / "fixture-fullchain.pem"
    extension_file = output_dir / "fixture-server.ext"
    _run_openssl(["genrsa", "-out", str(ca_key), "2048"])
    ca_key.chmod(0o600)
    _run_openssl([
        "req", "-x509", "-new", "-sha256", "-days", "2", "-key", str(ca_key),
        "-out", str(ca_cert), "-subj", "/CN=SenseField Local ASR Vision Test CA",
        "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
        "-addext", "keyUsage=critical,keyCertSign,cRLSign",
    ])
    _run_openssl([
        "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", str(server_key),
        "-out", str(server_csr), "-subj", "/CN=10.0.2.2",
    ])
    server_key.chmod(0o600)
    extension_file.write_text(
        "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2\n"
        "basicConstraints=critical,CA:FALSE\n"
        "keyUsage=critical,digitalSignature,keyEncipherment\n"
        "extendedKeyUsage=serverAuth\n"
        "subjectKeyIdentifier=hash\n"
        "authorityKeyIdentifier=keyid:always\n",
        encoding="ascii",
    )
    _run_openssl([
        "x509", "-req", "-in", str(server_csr), "-CA", str(ca_cert), "-CAkey", str(ca_key),
        "-CAcreateserial", "-out", str(server_cert), "-days", "2", "-sha256",
        "-extfile", str(extension_file),
    ])
    fullchain.write_bytes(server_cert.read_bytes() + ca_cert.read_bytes())
    for private_path in (ca_key, server_key):
        private_path.chmod(0o600)
    for public_path in (ca_cert, fullchain):
        public_path.chmod(0o644)
    return ca_cert, fullchain, server_key


def _load_gateway_modules() -> tuple[Any, Any]:
    try:
        import uvicorn
        from mapassist.assistant_gateway.app import create_app
        from mapassist.assistant_gateway.config import GatewaySettings
    except ImportError as exc:
        raise RuntimeError("Install the lightweight assistant-vision-gateway dependencies first.") from exc
    return uvicorn, (create_app, GatewaySettings)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18767)
    parser.add_argument("--first-delay-ms", type=int, default=2500,
                        help="Delay the first scripted vision reply so the Android test can supersede it.")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use an unprivileged test port.")
    if not 0 <= args.first_delay_ms <= 15_000:
        parser.error("The first scripted response delay must be between 0 and 15000 ms.")
    output_dir = args.output_dir.expanduser().resolve()
    if not output_dir.is_relative_to((ROOT / "output").resolve()):
        parser.error("Keep the fixture key and private Android config inside ignored output/.")

    raw_dir = ROOT / "android/app/src/debug/res/raw"
    xml_dir = ROOT / "android/app/src/debug/res/xml"
    debug_manifest = ROOT / "android/app/src/debug/AndroidManifest.xml"
    ca_resource = raw_dir / f"{TEST_CA_NAME}.pem"
    security_resource = xml_dir / f"{TEST_NETWORK_CONFIG_NAME}.xml"
    legacy_security_resource = xml_dir / "network_security_config.xml"
    config_path = output_dir / CONFIG_NAME
    generated_paths = [output_dir / name for name in (
        "fixture-ca.key", "fixture-ca.pem", "fixture-ca.srl", "fixture-server.key",
        "fixture-server.csr", "fixture-server.pem", "fixture-fullchain.pem", "fixture-server.ext",
    )]
    fixture_paths = [
        *generated_paths,
        config_path,
        debug_manifest,
        security_resource,
        ca_resource,
        legacy_security_resource,
    ]
    occupied_paths = [path for path in fixture_paths if path.exists() or path.is_symlink()]
    if debug_manifest in occupied_paths:
        parser.error("A debug AndroidManifest.xml already exists; refusing to overwrite it.")
    if occupied_paths:
        parser.error("A fixture output path already exists; stop and clean it deliberately first.")

    uvicorn, modules = _load_gateway_modules()
    create_app, GatewaySettings = modules
    output_dir.mkdir(parents=True, exist_ok=True)
    output_dir.chmod(0o700)
    raw_dir.mkdir(parents=True, exist_ok=True)
    xml_dir.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(32)
    state = FixtureState()
    client = ScriptedVisionClient(state, args.first_delay_ms)
    try:
        ca_cert, fullchain, server_key = _create_tls_material(output_dir)
        ca_resource.write_bytes(ca_cert.read_bytes())
        ca_resource.chmod(0o644)
        security_resource.write_text(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/assistant_local_asr_test_ca" />
        </trust-anchors>
    </base-config>
</network-security-config>
""",
            encoding="utf-8",
        )
        debug_manifest.write_text(
            """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:networkSecurityConfig="@xml/assistant_local_asr_test_network_security_config"
        tools:replace="android:networkSecurityConfig" />
</manifest>
""",
            encoding="utf-8",
        )
        config_path.write_text(json.dumps({
            "endpoint": f"https://10.0.2.2:{args.port}",
            "token": token,
        }), encoding="utf-8")
        config_path.chmod(0o600)

        settings = GatewaySettings(
            device_tokens=(token,),
            asr_backend="disabled",
            require_tls=True,
            mode="production",
        )
        gateway_app = create_app(settings, vision_client=client)
        app = FixtureMiddleware(gateway_app, state, token)
        print(
            f"Local scripted vision gateway ready at https://127.0.0.1:{args.port}; "
            "debug TLS trust is temporary and the random device token is private.",
            flush=True,
        )
        def interrupt(_number: int, _frame: Any) -> None:
            raise KeyboardInterrupt

        signal.signal(signal.SIGTERM, interrupt)
        uvicorn.run(
            app,
            host="127.0.0.1",
            port=args.port,
            ssl_certfile=str(fullchain),
            ssl_keyfile=str(server_key),
            access_log=False,
            log_level="critical",
            proxy_headers=False,
            ws="websockets",
        )
    finally:
        debug_manifest.unlink(missing_ok=True)
        security_resource.unlink(missing_ok=True)
        ca_resource.unlink(missing_ok=True)
        config_path.unlink(missing_ok=True)
        for generated in generated_paths:
            generated.unlink(missing_ok=True)
        for directory in (xml_dir, raw_dir, xml_dir.parent):
            try:
                directory.rmdir()
            except OSError:
                pass


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        print(f"ERROR: local vision fixture failed ({type(exc).__name__}).", file=sys.stderr)
        raise SystemExit(1) from exc
