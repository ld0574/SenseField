#!/usr/bin/env python3
"""Explicit emulator-only live fixture; temporary CA/device token, synthetic audio, real models."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output/assistant/0.4.0/android-transport")
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This fixture supports Android emulator host-loopback only.")
    if not (1024 <= args.port <= 65535):
        parser.error("Use an unprivileged TCP port.")
    api_key = os.environ.get("ZHIPU_API_KEY", "").strip()
    if not api_key:
        parser.error("Configure ZHIPU_API_KEY in this server process environment; never pass it as an argument.")
    folder = args.output_dir.resolve()
    if not folder.is_relative_to(ROOT / "output"):
        parser.error("Keep generated credentials and audio inside ignored output/.")
    xml = ROOT / "android/app/src/debug/res/xml/network_security_config.xml"
    ca = ROOT / "android/app/src/debug/res/raw/assistant_transport_test_ca.crt"
    if xml.exists() or ca.exists():
        parser.error("An existing debug fixture must be removed deliberately before running this helper.")
    folder.mkdir(parents=True, exist_ok=True)
    folder.chmod(0o700)
    token = secrets.token_urlsafe(32)
    try:
        from cryptography import x509
        from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
        from cryptography.hazmat.primitives import serialization, hashes
        from cryptography.hazmat.primitives.asymmetric import rsa
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "sensefield-synthetic-test")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5))
                .not_valid_after(now + timedelta(days=1))
                .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
                .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                    x509.IPAddress(ipaddress.ip_address("10.0.2.2"))]), critical=False)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                .sign(key, hashes.SHA256()))
        cert_bytes = cert.public_bytes(serialization.Encoding.PEM)
        (folder / "cert.pem").write_bytes(cert_bytes)
        private = folder / "key.pem"
        private.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        private.chmod(0o600)
        config = folder / "assistant-transport-test.json"
        config.write_text(json.dumps({"endpoint": f"https://10.0.2.2:{args.port}", "token": token}))
        config.chmod(0o600)
        ca.parent.mkdir(parents=True, exist_ok=True); ca.write_bytes(cert_bytes)
        xml.parent.mkdir(parents=True, exist_ok=True)
        xml.write_text('''<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
 <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
 <domain-config cleartextTrafficPermitted="true">
  <domain includeSubdomains="false">localhost</domain>
  <domain includeSubdomains="false">127.0.0.1</domain>
  <domain includeSubdomains="false">10.0.2.2</domain>
 </domain-config>
 <debug-overrides><trust-anchors><certificates src="user" /><certificates src="@raw/assistant_transport_test_ca" /></trust-anchors></debug-overrides>
</network-security-config>
''')
        spec = importlib.util.spec_from_file_location("synthetic_asr", ROOT / "scripts/assistant_gateway_asr_smoke.py")
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        module.OUTPUT_DIR = folder
        module._prepare_audio()  # macOS offline Tingting, fixed phrase only.
        print("Synthetic fixture ready. Build/install the debug app, copy the runtime files, then run the transport tests.", flush=True)
        print("Provider key stays in this server process; fixture trust must be removed before a candidate build.", flush=True)
        import uvicorn
        from mapassist.assistant_gateway.app import create_app
        from mapassist.assistant_gateway.config import GatewaySettings
        settings = GatewaySettings(device_tokens=(token,), zhipu_api_key=api_key, require_tls=True)
        # Uvicorn re-raises its captured signal after shutdown. Make TERM unwind
        # Python so our outer finally removes credentials and the debug CA.
        def interrupted(_number, _frame):
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, interrupted)
        uvicorn.run(create_app(settings), host="127.0.0.1", port=args.port,
                    ssl_certfile=str(folder / "cert.pem"), ssl_keyfile=str(private),
                    access_log=False, log_level="critical", proxy_headers=False)
    finally:
        xml.unlink(missing_ok=True); ca.unlink(missing_ok=True)
        for item in ("key.pem", "assistant-transport-test.json"):
            (folder / item).unlink(missing_ok=True)
        if args.adb:
            # Never prints file contents or token values. The test APK may already be uninstalled.
            subprocess.run([str(args.adb), "-s", args.serial, "shell", "run-as", "com.openkhub.sensefield",
                    "rm", "-f", "files/assistant-transport-test.json", "files/asr-test.pcm"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
