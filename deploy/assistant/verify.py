#!/usr/bin/env python3
"""Check a self-deployed gateway. Paid synthetic vision runs only with --visual."""
from __future__ import annotations

import argparse
import base64
import io
import json
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.request
import uuid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://sf.888413.xyz")
    parser.add_argument("--ca", type=Path)
    parser.add_argument("--config", type=Path, default=Path("/run/secrets/assistant-gateway.json"))
    parser.add_argument("--health-only", action="store_true")
    parser.add_argument("--visual", action="store_true")
    args = parser.parse_args()
    if not args.base_url.startswith("https://"):
        parser.error("Use HTTPS.")
    context = ssl.create_default_context(cafile=str(args.ca) if args.ca else None)
    base = args.base_url.rstrip("/")
    with urllib.request.urlopen(base + "/health", context=context, timeout=4) as response:
        health = json.load(response)
    assert health.get("status") == "vision_only" and health.get("asr_ready") is False
    if args.health_only:
        return
    config = json.loads(args.config.read_text())
    token = config["ASSISTANT_GATEWAY_DEVICE_TOKENS"].split(",")[0]

    def post(body: bytes, authenticated: bool = True):
        headers = {"Content-Type": "application/json"}
        if authenticated:
            headers["Authorization"] = "Bearer " + token
        started = time.monotonic()
        try:
            with urllib.request.urlopen(urllib.request.Request(base + "/v1/visual", data=body, headers=headers),
                                        context=context, timeout=12) as response:
                return response.status, json.load(response), round((time.monotonic() - started) * 1000)
        except urllib.error.HTTPError as error:
            return error.code, None, round((time.monotonic() - started) * 1000)

    results = {"health": "vision_only", "tls_verified": True}
    status, _, _ = post(b"{}", False)
    assert status == 401, "Unauthenticated requests must be rejected"
    results["unauthenticated_status"] = status
    status, _, _ = post(b"{}")
    assert status == 422, "Invalid schemas must be rejected"
    results["invalid_schema_status"] = status
    status, _, _ = post(b"a" * (2300 * 1024))
    assert status == 413, "Oversized requests must be rejected"
    results["oversized_body_status"] = status
    if args.visual:
        from PIL import Image, ImageDraw, ImageFont
        picture = Image.new("RGB", (960, 320), "white")
        ImageDraw.Draw(picture).text((40, 120), "SYNTHETIC GATEWAY TEST", fill="black", font=ImageFont.load_default(size=44))
        stream = io.BytesIO()
        picture.save(stream, format="JPEG", quality=75)
        payload = {"session_id": str(uuid.uuid4()), "generation": 1, "turn_id": "synthetic-test",
                   "frame_id": "synthetic-frame", "question": "请只读出图中英文文字。",
                   "frame_age_ms": 0, "proactive": False,
                   "image_base64": base64.b64encode(stream.getvalue()).decode()}
        status, result, elapsed = post(json.dumps(payload).encode())
        assert status == 200 and result is not None, "Synthetic visual request did not complete"
        assert "SYNTHETIC GATEWAY TEST" in result.get("answer", "").upper(), "Synthetic text was not read correctly"
        results.update(synthetic_visual_passed=True, synthetic_roundtrip_ms=elapsed)
    print(json.dumps(results, ensure_ascii=False))


if __name__ == "__main__":
    main()
