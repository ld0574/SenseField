"""Explicit synthetic-frame smoke test for the configured GLM vision model."""

from __future__ import annotations

import asyncio
import base64
import io
import json
import os
import sys

from PIL import Image, ImageDraw

from .glm import GlmVisionClient
from .validation import parse_visual_answer


async def _run(api_key: str) -> dict[str, object]:
    image = Image.new("RGB", (640, 360), "#101820")
    draw = ImageDraw.Draw(image)
    draw.rectangle((16, 16, 624, 104), fill="#202830", outline="#91c7ff", width=3)
    draw.text((40, 42), "SYNTHETIC HUD TEST", fill="white")
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=85)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    client = GlmVisionClient(api_key)
    try:
        raw = await client.complete(
            question="Read only the large visible text in the HUD.",
            image_base64=encoded,
        )
        answer = parse_visual_answer(raw)
        return {"model": "glm-4.6v-flash", "kind": answer.kind, "answer": answer.answer, "uncertain": answer.uncertain}
    finally:
        await client.close()


def main() -> None:
    api_key = os.environ.get("ZHIPU_API_KEY", "").strip()
    if not api_key:
        print("Set ZHIPU_API_KEY before running the live synthetic smoke test.", file=sys.stderr)
        raise SystemExit(2)
    try:
        result = asyncio.run(_run(api_key))
    except Exception as exc:
        # Exception text from clients can contain server details. Report only a
        # stable failure shape; never echo keys or request bodies.
        print(json.dumps({"status": "failed", "error": type(exc).__name__}))
        raise SystemExit(1) from None
    print(json.dumps({"status": "ok", **result}, ensure_ascii=False))
