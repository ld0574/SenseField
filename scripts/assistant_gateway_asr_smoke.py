#!/usr/bin/env python3
"""Offline Chinese TTS to pinned FunASR streaming smoke; writes asr-smoke.json."""

from __future__ import annotations

import asyncio
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCRIPT = Path(__file__).resolve()
OUTPUT_DIR = Path.cwd() / "output/assistant/0.4.0"
ROOT = next(parent for parent in SCRIPT.parents if (parent / "pyproject.toml").is_file())
sys.path.insert(0, str(ROOT / "python"))

from mapassist.assistant_gateway.asr import FunAsrStreamingRecognizer  # noqa: E402
from mapassist.assistant_gateway.audio import AudioWebSocketSession  # noqa: E402


PHRASE = "请读出当前比分"
VOICE = "Tingting"
SAMPLE_RATE = 16_000
CHUNK_MS = 600
CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS // 1000
TURN_ID = "synthetic-tts-turn"
SESSION_ID = "synthetic-tts-session"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _prepare_audio() -> tuple[bytes, dict[str, Any]]:
    if shutil.which("say") is None or shutil.which("afconvert") is None:
        raise RuntimeError("This smoke requires macOS say and afconvert for offline synthetic TTS.")
    aiff_path = OUTPUT_DIR / "asr-smoke-tingting.aiff"
    wav_path = OUTPUT_DIR / "asr-smoke-16khz.wav"
    pcm_path = OUTPUT_DIR / "asr-smoke-16khz-mono-s16le.pcm"
    subprocess.run(["say", "-v", VOICE, "-o", str(aiff_path), PHRASE], check=True)
    subprocess.run(
        ["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff_path), str(wav_path)],
        check=True,
    )
    with wave.open(str(wav_path), "rb") as source:
        params = source.getparams()
        pcm = source.readframes(source.getnframes())
    if (params.nchannels, params.sampwidth, params.framerate, params.comptype) != (1, 2, SAMPLE_RATE, "NONE"):
        raise RuntimeError(f"Unexpected PCM format: {params!r}")
    pcm_path.write_bytes(pcm)
    return pcm, {
        "phrase": PHRASE,
        "engine": "macOS say (offline)",
        "voice": VOICE,
        "locale": "zh_CN",
        "source_aiff": str(aiff_path.relative_to(ROOT)),
        "converted_wav": str(wav_path.relative_to(ROOT)),
        "pcm_s16le": str(pcm_path.relative_to(ROOT)),
        "sample_rate_hz": SAMPLE_RATE,
        "channels": 1,
        "sample_width_bytes": 2,
        "duration_ms": round(params.nframes * 1000 / SAMPLE_RATE),
        "pcm_sha256": _sha256(pcm),
    }


class QueueWebSocket:
    """Minimal socket adapter that feeds real protocol packets into the session."""

    def __init__(self) -> None:
        self.incoming: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self.messages: list[dict[str, Any]] = []
        self.final_received = asyncio.Event()

    async def receive(self) -> dict[str, Any]:
        return await self.incoming.get()

    async def send_json(self, message: dict[str, Any]) -> None:
        self.messages.append(message)
        if message.get("type") == "final":
            self.final_received.set()


async def _stream_through_session(
    recognizer: FunAsrStreamingRecognizer,
    pcm: bytes,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    websocket = QueueWebSocket()
    session = AudioWebSocketSession(SESSION_ID, 0, recognizer)
    runner = asyncio.create_task(session.run(websocket))
    await websocket.incoming.put({
        "text": json.dumps({"type": "speech_start", "turn_id": TURN_ID, "generation": 0}),
    })
    for offset in range(0, len(pcm), CHUNK_BYTES):
        block = pcm[offset : offset + CHUNK_BYTES]
        await asyncio.sleep(len(block) / (SAMPLE_RATE * 2))
        await websocket.incoming.put({"bytes": block})
    await websocket.incoming.put({
        "text": json.dumps({"type": "speech_end", "turn_id": TURN_ID, "generation": 0}),
    })
    try:
        await asyncio.wait_for(websocket.final_received.wait(), timeout=180.0)
    finally:
        await websocket.incoming.put({"type": "websocket.disconnect"})
        await asyncio.wait_for(runner, timeout=10.0)
    final = next(item for item in reversed(websocket.messages) if item.get("type") == "final")
    return final, websocket.messages


async def _run() -> dict[str, Any]:
    manifest_path = ROOT / "python/mapassist/assistant_gateway/model_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    pcm, audio = _prepare_audio()
    chunk_sizes = [len(pcm[offset : offset + CHUNK_BYTES]) for offset in range(0, len(pcm), CHUNK_BYTES)]

    recognizer = FunAsrStreamingRecognizer()
    load_started = time.perf_counter()
    await recognizer.initialize()
    load_ms = round((time.perf_counter() - load_started) * 1000)
    final, messages = await _stream_through_session(recognizer, pcm)

    text = str(final.get("text", "")).strip()
    has_chinese = any("\u4e00" <= character <= "\u9fff" for character in text)
    semantically_relevant = "当前比分" in text or "请读出" in text
    status = "ok" if text and has_chinese and semantically_relevant else "recognition_failed"
    finalization_ms = final.get("finalization_ms")
    queue_review = {
        "per_session_queue_capacity_chunks": 3,
        "per_session_backlog_guard_seconds": 2.0,
        "global_model_generate_in_flight_capacity": 1,
        "global_asr_thread_waiters": 0,
        "over_capacity_behavior": "fail fast with asr_busy before submitting work to the executor",
        "observed_finalization_exceeded_two_seconds": isinstance(finalization_ms, int) and finalization_ms > 2000,
        "cancellation_behavior": (
            "Cancelling the asyncio worker marks its utterance cancelled and suppresses stale output. "
            "asyncio.to_thread cannot stop an already-running Python thread, so the global capacity-one "
            "admission slot remains held until generate returns; additional sessions receive asr_busy "
            "instead of adding threads waiting on the model lock."
        ),
        "capacity_one_pilot": {
            "status": "pending_load_test",
            "meaning": "one generate call in flight globally; excess sessions are rejected while the slot is held",
            "required_follow_up": "load-test concurrent sessions, >2 s inference, disconnect/reset cancellation, busy responses, and bounded admission before increasing capacity",
        },
    }
    return {
        "schema_version": 1,
        "status": status,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "model": {
            "repo_id": manifest["repo_id"],
            "revision": manifest["revision"],
            "snapshot_checksum_verification": "passed during initialization",
            "device": "cpu",
        },
        "audio": {
            **audio,
            "chunk_duration_ms": CHUNK_MS,
            "chunk_bytes": CHUNK_BYTES,
            "chunk_count": len(chunk_sizes),
            "chunk_sizes_bytes": chunk_sizes,
            "streamed_through": "AudioWebSocketSession speech_start / PCM packets / speech_end",
            "packet_pacing": "packets arrive at the end of each block's 16 kHz capture interval",
        },
        "result": {
            "final_text": text,
            "has_chinese": has_chinese,
            "contains_expected_score_wording": semantically_relevant,
            "type": final.get("type"),
            "kind": "final ASR transcript",
            "partial_texts": [item.get("text", "") for item in messages if item.get("type") == "partial"],
            "status_messages": [item.get("reason") for item in messages if item.get("type") == "status"],
        },
        "timing_ms": {
            "model_load": load_ms,
            "inference_total": final.get("inference_ms"),
            "speech_end_to_finalization": finalization_ms,
            "legacy_asr_ms_speech_start_to_final_including_audio_duration": final.get("asr_ms"),
            "voice_sla_p95_claimed": False,
        },
        "host_context": {
            "cpu_isolated": False,
            "note": "Diagnostic point measurement with uncontrolled host load; no isolated-performance or SLA claim.",
        },
        "queue_review": queue_review,
        "artifacts": {
            "report": str((OUTPUT_DIR / "asr-smoke.json").relative_to(ROOT)),
            "reproduce": ".venv/bin/python scripts/assistant_gateway_asr_smoke.py --output-dir output/assistant/0.4.0",
        },
    }


def main() -> None:
    global OUTPUT_DIR
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR,
                        help="Ignored output directory for generated audio and report")
    args = parser.parse_args()
    OUTPUT_DIR = args.output_dir.resolve()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    result = asyncio.run(_run())
    report_path = OUTPUT_DIR / "asr-smoke.json"
    report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["status"] != "ok":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
